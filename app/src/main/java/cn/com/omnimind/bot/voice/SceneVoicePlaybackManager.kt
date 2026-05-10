package cn.com.omnimind.bot.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import cn.com.omnimind.baselib.http.OkHttpManager
import cn.com.omnimind.baselib.llm.ChatCompletionRequest
import cn.com.omnimind.baselib.llm.ModelProviderConfigStore
import cn.com.omnimind.baselib.llm.SceneModelBindingStore
import cn.com.omnimind.baselib.llm.SceneVoiceConfig
import cn.com.omnimind.baselib.llm.SceneVoiceConfigStore
import cn.com.omnimind.baselib.util.OmniLog
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import java.util.Base64

private data class VoicePlaybackQueueItem(
    val messageId: String,
    val text: String,
    val binding: SceneVoiceResolvedBinding,
    val config: SceneVoiceConfig,
    val cacheKey: String,
    val preferStreaming: Boolean
)

private data class VoiceCacheEntry(
    val key: String,
    val format: String,
    val pcmBytes: ByteArray? = null,
    val wavFile: File? = null
)

class SceneVoicePlaybackManager(
    context: Context
) {
    companion object {
        private const val TAG = "SceneVoicePlayback"
        private const val STREAM_PCM_FORMAT = "pcm16"
        private const val FALLBACK_WAV_FORMAT = "wav"
        private const val SAMPLE_RATE = 24_000
        private const val CHANNEL_COUNT = 1
        private const val BYTES_PER_SAMPLE = 2
        private const val MAX_CACHE_SIZE = 48
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }

    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
        explicitNulls = false
    }
    private val lock = Any()
    private val queue = ArrayDeque<VoicePlaybackQueueItem>()
    private val replayableKeysByMessageId = mutableMapOf<String, MutableSet<String>>()
    private val cache = object : LinkedHashMap<String, VoiceCacheEntry>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, VoiceCacheEntry>?): Boolean {
            val shouldRemove = size > MAX_CACHE_SIZE
            if (shouldRemove) {
                eldest?.value?.wavFile?.takeIf { it.exists() }?.delete()
                replayableKeysByMessageId.values.forEach { keys ->
                    eldest?.key?.let(keys::remove)
                }
            }
            return shouldRemove
        }
    }

    @Volatile
    private var eventEmitter: ((Map<String, Any?>) -> Unit)? = null

    @Volatile
    private var processorJob: Job? = null

    @Volatile
    private var currentEventSource: EventSource? = null

    @Volatile
    private var currentAudioTrack: AudioTrack? = null

    @Volatile
    private var currentMediaPlayer: MediaPlayer? = null

    @Volatile
    private var currentMessageId: String? = null

    @Volatile
    private var currentStopRequested = false

    fun setEventEmitter(emitter: ((Map<String, Any?>) -> Unit)?) {
        eventEmitter = emitter
    }

    fun speakText(
        messageId: String,
        text: String,
        enqueue: Boolean,
        preferStreaming: Boolean
    ): Boolean {
        val item = runCatching { buildQueueItem(messageId, text, preferStreaming) }
            .onFailure {
                emitState(
                    messageId = messageId,
                    status = "error",
                    error = it.message ?: "voice scene is not configured"
                )
            }
            .getOrNull()
            ?: return false

        synchronized(lock) {
            if (!enqueue) {
                queue.clear()
                stopCurrentPlaybackLocked()
            }
            queue.addLast(item)
            ensureProcessorLocked()
        }
        return true
    }

    fun replayText(
        messageId: String,
        text: String
    ): Boolean {
        return speakText(
            messageId = messageId,
            text = text,
            enqueue = false,
            preferStreaming = true
        )
    }

    fun pause(messageId: String?): Boolean {
        val currentId = currentMessageId ?: return false
        if (!messageId.isNullOrBlank() && messageId.trim() != currentId) {
            return false
        }
        currentAudioTrack?.pause()
        currentMediaPlayer?.takeIf { it.isPlaying }?.pause()
        emitState(currentId, "paused")
        return true
    }

    fun resume(messageId: String?): Boolean {
        val currentId = currentMessageId ?: return false
        if (!messageId.isNullOrBlank() && messageId.trim() != currentId) {
            return false
        }
        currentAudioTrack?.play()
        currentMediaPlayer?.start()
        emitState(currentId, "playing", canReplay = hasReplayableCache(currentId))
        return true
    }

    fun stop(messageId: String?): Boolean {
        val normalizedId = messageId?.trim().orEmpty()
        synchronized(lock) {
            if (normalizedId.isNotEmpty()) {
                queue.removeAll { it.messageId == normalizedId }
            } else {
                queue.clear()
            }
            if (normalizedId.isEmpty() || currentMessageId == normalizedId) {
                stopCurrentPlaybackLocked()
            }
        }
        if (normalizedId.isNotEmpty()) {
            emitState(normalizedId, "idle", canReplay = hasReplayableCache(normalizedId))
        }
        return true
    }

    fun release() {
        synchronized(lock) {
            queue.clear()
            stopCurrentPlaybackLocked()
            processorJob?.cancel()
            processorJob = null
            cache.values.forEach { entry ->
                entry.wavFile?.takeIf { it.exists() }?.delete()
            }
            cache.clear()
            replayableKeysByMessageId.clear()
        }
    }

    private fun buildQueueItem(
        messageId: String,
        text: String,
        preferStreaming: Boolean
    ): VoicePlaybackQueueItem {
        val normalizedMessageId = messageId.trim().ifEmpty {
            throw IllegalArgumentException("messageId is empty")
        }
        val normalizedText = text.trim()
        if (normalizedText.isEmpty()) {
            throw IllegalArgumentException("text is empty")
        }
        val binding = resolveVoiceBinding()
        val config = SceneVoiceConfigStore.getConfig()
        val stylePayload = SceneVoiceTtsProtocol.composeStylePayload(config)
        val cacheKey = SceneVoiceTtsProtocol.buildCacheKey(
            messageId = normalizedMessageId,
            text = normalizedText,
            providerProfileId = binding.providerProfileId,
            modelId = binding.modelId,
            voiceId = config.voiceId,
            stylePayload = stylePayload
        )
        return VoicePlaybackQueueItem(
            messageId = normalizedMessageId,
            text = normalizedText,
            binding = binding,
            config = config,
            cacheKey = cacheKey,
            preferStreaming = preferStreaming
        )
    }

    private fun resolveVoiceBinding(): SceneVoiceResolvedBinding {
        val binding = SceneModelBindingStore.getBinding(SceneVoiceConfigStore.SCENE_ID)
            ?: throw IllegalStateException("Voice 场景未绑定模型")
        val profile = ModelProviderConfigStore.getProfile(binding.providerProfileId)
            ?: throw IllegalStateException("Voice Provider 不存在")
        if (!profile.isConfigured()) {
            throw IllegalStateException("Voice Provider 未配置")
        }
        if (profile.protocolType.trim().ifEmpty { "openai_compatible" } != "openai_compatible") {
            throw IllegalStateException("Voice 场景仅支持 OpenAI-Compatible Provider")
        }
        return SceneVoiceResolvedBinding(
            providerProfileId = profile.id,
            apiBase = profile.baseUrl,
            apiKey = profile.apiKey,
            modelId = binding.modelId
        )
    }

    private fun ensureProcessorLocked() {
        if (processorJob?.isActive == true) {
            return
        }
        processorJob = scope.launch {
            processLoop()
        }
    }

    private suspend fun processLoop() {
        while (true) {
            val item = synchronized(lock) {
                queue.removeFirstOrNull()?.also {
                    currentMessageId = it.messageId
                    currentStopRequested = false
                }
            } ?: break
            try {
                processItem(item)
            } catch (t: Throwable) {
                OmniLog.e(TAG, "voice playback failed: ${t.message}", t)
                emitState(item.messageId, "error", error = t.message ?: "voice playback failed")
            } finally {
                releaseCurrentPlaybackResources()
                synchronized(lock) {
                    currentMessageId = null
                    currentStopRequested = false
                }
            }
        }
        synchronized(lock) {
            processorJob = null
        }
    }

    private suspend fun processItem(item: VoicePlaybackQueueItem) {
        synchronized(lock) {
            cache[item.cacheKey]
        }?.let { entry ->
            playCachedEntry(item, entry)
            return
        }

        emitState(item.messageId, "synthesizing")
        if (item.preferStreaming) {
            val streamed = synthesizeStreaming(item)
            if (streamed != null) {
                synchronized(lock) {
                    cache[item.cacheKey] = streamed
                    replayableKeysByMessageId
                        .getOrPut(item.messageId) { linkedSetOf() }
                        .add(item.cacheKey)
                }
                return
            }
        }
        val cached = synthesizeNonStreaming(item)
        if (cached == null) {
            throw IllegalStateException("语音合成失败")
        }
        synchronized(lock) {
            cache[item.cacheKey] = cached
            replayableKeysByMessageId
                .getOrPut(item.messageId) { linkedSetOf() }
                .add(item.cacheKey)
        }
        playCachedEntry(item, cached)
    }

    private suspend fun synthesizeStreaming(item: VoicePlaybackQueueItem): VoiceCacheEntry? {
        val request = buildRequest(
            binding = item.binding,
            request = SceneVoiceTtsProtocol.buildChatCompletionRequest(
                text = item.text,
                modelId = item.binding.modelId,
                config = item.config,
                format = STREAM_PCM_FORMAT,
                stream = true
            ),
            stream = true
        )
        val output = ByteArrayOutputStream()
        val latch = CountDownLatch(1)
        val minBufferSize = AudioTrack.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(4096)
        var parseFailed = false
        var sawAudio = false
        var errorMessage: String? = null

        val listener = object : EventSourceListener() {
            override fun onEvent(
                eventSource: EventSource,
                id: String?,
                type: String?,
                data: String
            ) {
                if (data.trim() == "[DONE]") {
                    latch.countDown()
                    return
                }
                val base64Audio = SceneVoiceTtsProtocol.extractStreamAudioBase64(data)
                if (base64Audio.isNullOrBlank()) {
                    return
                }
                val bytes = runCatching { Base64.getDecoder().decode(base64Audio) }.getOrNull()
                if (bytes == null || bytes.isEmpty()) {
                    parseFailed = true
                    errorMessage = "stream audio payload is invalid"
                    latch.countDown()
                    return
                }
                sawAudio = true
                val track = currentAudioTrack ?: createAudioTrack(minBufferSize).also {
                    currentAudioTrack = it
                    emitState(item.messageId, "playing", canReplay = true)
                    it.play()
                }
                output.write(bytes)
                track.write(bytes, 0, bytes.size)
            }

            override fun onClosed(eventSource: EventSource) {
                latch.countDown()
            }

            override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) {
                errorMessage = response?.message ?: t?.message
                latch.countDown()
            }
        }

        val source = OkHttpManager.enqueueWithStream(request, listener)
        currentEventSource = source
        withContext(Dispatchers.IO) {
            latch.await(90, TimeUnit.SECONDS)
        }
        currentEventSource = null
        if (currentStopRequested) {
            return null
        }
        if (!sawAudio || parseFailed) {
            OmniLog.w(TAG, "streaming tts fallback: ${errorMessage.orEmpty()}")
            releaseCurrentPlaybackResources()
            return null
        }
        waitForPcmPlayback(output.size())
        emitState(item.messageId, "completed", canReplay = true)
        return VoiceCacheEntry(
            key = item.cacheKey,
            format = STREAM_PCM_FORMAT,
            pcmBytes = output.toByteArray()
        )
    }

    private suspend fun synthesizeNonStreaming(item: VoicePlaybackQueueItem): VoiceCacheEntry? {
        val request = buildRequest(
            binding = item.binding,
            request = SceneVoiceTtsProtocol.buildChatCompletionRequest(
                text = item.text,
                modelId = item.binding.modelId,
                config = item.config,
                format = FALLBACK_WAV_FORMAT,
                stream = false
            ),
            stream = false
        )
        val response = OkHttpManager.enqueue(request)
        val body = response.body?.string().orEmpty()
        val parsed = SceneVoiceTtsProtocol.parseNonStreamingAudio(body) ?: return null
        val bytes = runCatching { Base64.getDecoder().decode(parsed.base64Data) }.getOrNull()
            ?: return null
        if (bytes.isEmpty()) {
            return null
        }
        val file = cacheFileFor(item.cacheKey, parsed.format ?: FALLBACK_WAV_FORMAT)
        file.parentFile?.mkdirs()
        file.writeBytes(bytes)
        return VoiceCacheEntry(
            key = item.cacheKey,
            format = parsed.format ?: FALLBACK_WAV_FORMAT,
            wavFile = file
        )
    }

    private suspend fun playCachedEntry(item: VoicePlaybackQueueItem, entry: VoiceCacheEntry) {
        when {
            entry.pcmBytes != null -> playPcmBytes(item.messageId, entry.pcmBytes)
            entry.wavFile != null -> playWavFile(item.messageId, entry.wavFile)
            else -> throw IllegalStateException("voice cache is empty")
        }
    }

    private suspend fun playPcmBytes(messageId: String, bytes: ByteArray) {
        if (bytes.isEmpty()) {
            throw IllegalStateException("pcm bytes are empty")
        }
        val minBufferSize = AudioTrack.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(4096)
        val track = createAudioTrack(minBufferSize)
        currentAudioTrack = track
        emitState(messageId, "playing", canReplay = true)
        track.play()
        track.write(bytes, 0, bytes.size)
        waitForPcmPlayback(bytes.size)
        emitState(messageId, "completed", canReplay = true)
    }

    private suspend fun playWavFile(messageId: String, file: File) {
        if (!file.exists()) {
            throw IllegalStateException("wav file does not exist")
        }
        val latch = CountDownLatch(1)
        val mediaPlayer = MediaPlayer()
        currentMediaPlayer = mediaPlayer
        mediaPlayer.setAudioStreamType(AudioManager.STREAM_MUSIC)
        mediaPlayer.setDataSource(file.absolutePath)
        mediaPlayer.setOnCompletionListener {
            latch.countDown()
        }
        mediaPlayer.setOnErrorListener { _, _, _ ->
            latch.countDown()
            true
        }
        mediaPlayer.prepare()
        emitState(messageId, "playing", canReplay = true)
        mediaPlayer.start()
        withContext(Dispatchers.IO) {
            latch.await(120, TimeUnit.SECONDS)
        }
        emitState(messageId, "completed", canReplay = true)
    }

    private suspend fun waitForPcmPlayback(totalBytes: Int) {
        val totalFrames = totalBytes / (CHANNEL_COUNT * BYTES_PER_SAMPLE)
        while (true) {
            if (currentStopRequested) {
                return
            }
            val track = currentAudioTrack ?: return
            val playedFrames = track.playbackHeadPosition
            if (playedFrames >= totalFrames - 128) {
                delay(40)
                return
            }
            delay(40)
        }
    }

    private fun createAudioTrack(bufferSize: Int): AudioTrack {
        return AudioTrack(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build(),
            AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(SAMPLE_RATE)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .build(),
            bufferSize,
            AudioTrack.MODE_STREAM,
            AudioManager.AUDIO_SESSION_ID_GENERATE
        )
    }

    private fun buildRequest(
        binding: SceneVoiceResolvedBinding,
        request: ChatCompletionRequest,
        stream: Boolean
    ): Request {
        val body = json.encodeToString(ChatCompletionRequest.serializer(), request)
        val requestBuilder = Request.Builder()
            .url(buildChatCompletionsUrl(binding.apiBase))
            .post(body.toRequestBody(JSON_MEDIA_TYPE))
            .addHeader("Content-Type", "application/json")
        if (stream) {
            requestBuilder.addHeader("Accept", "text/event-stream")
        } else {
            requestBuilder.addHeader("Accept", "application/json")
        }
        if (binding.apiKey.isNotBlank()) {
            requestBuilder.addHeader("Authorization", "Bearer ${binding.apiKey}")
        }
        return requestBuilder.build()
    }

    private fun buildChatCompletionsUrl(apiBase: String): String {
        val base = ModelProviderConfigStore.stripDirectRequestUrlMarker(apiBase)
        if (ModelProviderConfigStore.hasDirectRequestUrlMarker(apiBase)) {
            return base
        }
        return if (base.endsWith("/v1", ignoreCase = true)) {
            "$base/chat/completions"
        } else {
            "$base/v1/chat/completions"
        }
    }

    private fun cacheFileFor(cacheKey: String, format: String): File {
        val extension = when (format.lowercase()) {
            STREAM_PCM_FORMAT -> "pcm"
            else -> "wav"
        }
        return File(File(appContext.cacheDir, "scene_voice"), "$cacheKey.$extension")
    }

    private fun hasReplayableCache(messageId: String): Boolean {
        return synchronized(lock) {
            replayableKeysByMessageId[messageId]?.isNotEmpty() == true
        }
    }

    private fun stopCurrentPlaybackLocked() {
        currentStopRequested = true
        currentEventSource?.cancel()
        currentEventSource = null
        releaseCurrentPlaybackResources()
    }

    private fun releaseCurrentPlaybackResources() {
        currentAudioTrack?.runCatching {
            pause()
            flush()
            stop()
            release()
        }
        currentAudioTrack = null
        currentMediaPlayer?.runCatching {
            if (isPlaying) {
                stop()
            }
            reset()
            release()
        }
        currentMediaPlayer = null
    }

    private fun emitState(
        messageId: String,
        status: String,
        error: String? = null,
        canReplay: Boolean = false
    ) {
        val payload = linkedMapOf<String, Any?>(
            "messageId" to messageId,
            "status" to status,
            "error" to error,
            "canReplay" to canReplay
        )
        mainHandler.post {
            eventEmitter?.invoke(Collections.unmodifiableMap(payload))
        }
    }
}
