package cn.com.omnimind.bot.agent.tool.handlers

import cn.com.omnimind.baselib.llm.ModelProviderConfigStore
import cn.com.omnimind.bot.agent.AgentCallback
import cn.com.omnimind.bot.agent.AgentExecutionEnvironment
import cn.com.omnimind.bot.agent.AgentToolExecutionHandle
import cn.com.omnimind.bot.agent.AgentToolRegistry
import cn.com.omnimind.bot.agent.AgentWorkspaceManager
import cn.com.omnimind.bot.agent.ToolExecutionResult
import cn.com.omnimind.bot.BuildConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.Base64
import java.util.concurrent.TimeUnit

class ImageGenerationToolHandler(
    private val helper: SharedHelper,
    private val workspaceManager: AgentWorkspaceManager
) : ToolHandler {
    override val toolNames: Set<String> = setOf("image_generate")

    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    override suspend fun execute(
        toolCall: cn.com.omnimind.baselib.llm.AssistantToolCall,
        args: JsonObject,
        runtimeDescriptor: AgentToolRegistry.RuntimeToolDescriptor,
        env: AgentExecutionEnvironment,
        callback: AgentCallback,
        toolHandle: AgentToolExecutionHandle
    ): ToolExecutionResult {
        return executeImageGenerate(args, env, callback, toolHandle)
    }

    private suspend fun executeImageGenerate(
        args: JsonObject,
        env: AgentExecutionEnvironment,
        callback: AgentCallback,
        toolHandle: AgentToolExecutionHandle
    ): ToolExecutionResult {
        val toolName = "image_generate"
        return try {
            val workspace = env.workspaceDescriptor
            helper.requireWorkspaceStorageAccess(callback)?.let { return it }
            helper.requirePublicStorageAccessIfNeeded(
                callback,
                args["outputPath"]?.jsonPrimitive?.contentOrNull
            )?.let { return it }

            val prompt = args["prompt"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            require(prompt.isNotEmpty()) { "prompt cannot be empty" }
            val outputPath = args["outputPath"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            require(outputPath.isNotEmpty()) { "outputPath cannot be empty" }

            val profileId = args["providerProfileId"]?.jsonPrimitive?.contentOrNull?.trim()
                ?.takeIf { it.isNotEmpty() }
            val profile = profileId?.let { ModelProviderConfigStore.getProfile(it) }
                ?: ModelProviderConfigStore.getEditingProfile()
            val bundledImageConfig = bundledImageProviderConfig()
            val profileApiKey = profile.apiKey.trim()
            val hatchPetImageRequest = env.resolvedSkills.any { it.skillId == HATCH_PET_SKILL_ID }
            require(!hatchPetImageRequest || bundledImageConfig.apiKey.isNotEmpty()) {
                "Hatch-pet image provider is not bundled. Build the app with OMNIBOT_IMAGE_API_KEY so hatch-pet can generate images independently of the user's model provider."
            }
            val useBundledImageProvider = shouldUseBundledImageProvider(
                activeSkillIds = env.resolvedSkills.mapTo(mutableSetOf()) { it.skillId },
                profileApiKey = profileApiKey,
                bundledApiKey = bundledImageConfig.apiKey
            )
            val apiKey = if (useBundledImageProvider) bundledImageConfig.apiKey else profileApiKey
            require(apiKey.isNotEmpty()) {
                "Image provider apiKey is empty. Configure an OpenAI-compatible provider profile or build with OMNIBOT_IMAGE_API_KEY."
            }
            require(!profile.readOnly || useBundledImageProvider) {
                "The current provider is local/read-only and cannot generate images. Select an OpenAI provider profile."
            }

            val requestedModel = normalizeImageModelId(args["model"]?.jsonPrimitive?.contentOrNull)
                ?.takeIf { it.isNotEmpty() }
            val model = when {
                hatchPetImageRequest -> bundledImageConfig.model
                requestedModel != null -> requestedModel
                useBundledImageProvider -> bundledImageConfig.model
                else -> DEFAULT_IMAGE_MODEL
            }
            val size = args["size"]?.jsonPrimitive?.contentOrNull?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?: "1024x1024"
            val quality = args["quality"]?.jsonPrimitive?.contentOrNull?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?: "auto"
            val requestedFormat = args["format"]?.jsonPrimitive?.contentOrNull?.trim()
                ?.lowercase()
                ?.takeIf { it in SUPPORTED_OUTPUT_FORMATS }
                ?: outputPath.substringAfterLast('.', missingDelimiterValue = "")
                    .lowercase()
                    .takeIf { it in SUPPORTED_OUTPUT_FORMATS }
                ?: "png"
            val background = args["background"]?.jsonPrimitive?.contentOrNull?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?: "auto"

            val file = workspaceManager.resolvePath(
                inputPath = outputPath,
                workspace = workspace,
                allowPublicStorage = true
            )

            helper.reportToolProgress(
                callback,
                toolName,
                "Generating image",
                mapOf("model" to model, "outputPath" to outputPath),
                toolHandle
            )

            val endpoint = resolveImageGenerationEndpoint(
                if (useBundledImageProvider) bundledImageConfig.baseUrl else profile.baseUrl,
                apiKey
            )

            val imageBytes = withContext(Dispatchers.IO) {
                requestGeneratedImage(
                    endpoint = endpoint,
                    apiKey = apiKey,
                    model = model,
                    prompt = prompt,
                    size = size,
                    quality = quality,
                    outputFormat = requestedFormat,
                    background = background
                )
            }
            require(imageBytes.isNotEmpty()) { "image generation returned empty image data" }

            file.parentFile?.mkdirs()
            file.writeBytes(imageBytes)

            val artifact = workspaceManager.buildArtifactForFile(file, toolName)
            val payload = linkedMapOf<String, Any?>(
                "path" to (workspaceManager.shellPathForAndroid(file) ?: file.absolutePath),
                "androidPath" to file.absolutePath,
                "uri" to artifact.uri,
                "size" to file.length(),
                "mimeType" to workspaceManager.guessMimeType(file),
                "model" to model,
                "providerProfileId" to if (useBundledImageProvider) BUNDLED_IMAGE_PROVIDER_ID else profile.id,
                "providerProfileName" to if (useBundledImageProvider) BUNDLED_IMAGE_PROVIDER_NAME else profile.name
            )
            val payloadJson = helper.encodeLocalizedPayload(payload)
            ToolExecutionResult.ContextResult(
                toolName = toolName,
                summaryText = helper.localized("Generated image: ${file.name}"),
                previewJson = payloadJson,
                rawResultJson = payloadJson,
                success = true,
                artifacts = listOf(artifact),
                workspaceId = workspace.id
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            helper.workspacePermissionResult(e, callback)?.let { return it }
            helper.errorResult(toolName, e.message, "Image generation failed")
        }
    }

    private fun requestGeneratedImage(
        endpoint: String,
        apiKey: String,
        model: String,
        prompt: String,
        size: String,
        quality: String,
        outputFormat: String,
        background: String
    ): ByteArray {
        val requestJson = JSONObject().apply {
            put("model", model)
            put("prompt", prompt)
            put("n", 1)
            put("size", size)
            put("quality", quality)
            put("output_format", outputFormat)
            put("background", background)
        }
        val request = Request.Builder()
            .url(endpoint)
            .addHeader("Content-Type", "application/json")
            .addHeader("Authorization", "Bearer $apiKey")
            .post(requestJson.toString().toRequestBody("application/json".toMediaType()))
            .build()

        httpClient.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw IllegalStateException(
                    "image generation request failed(${response.code}) endpoint=$endpoint model=$model: ${body.take(500)}"
                )
            }
            val payload = JSONObject(body)
            val data = payload.optJSONArray("data")
            if (data != null && data.length() > 0) {
                val first = data.optJSONObject(0) ?: JSONObject()
                first.optString("b64_json").takeIf { it.isNotBlank() }?.let(::decodeBase64Image)
                    ?.let { return it }
                first.optString("url").takeIf { it.isNotBlank() }?.let(::downloadImage)
                    ?.let { return it }
            }
            val responseFormat = payload.optString("format").takeIf { it.isNotBlank() }
            val output = payload.optJSONArray("output")
            if (output != null) {
                for (i in 0 until output.length()) {
                    extractImageFromOutputItem(output.optJSONObject(i), responseFormat)?.let { return it }
                }
            }
            throw IllegalStateException("image generation response did not contain b64_json or image url")
        }
    }

    private fun extractImageFromOutputItem(item: JSONObject?, responseFormat: String?): ByteArray? {
        if (item == null) return null
        item.optString("b64_json").takeIf { it.isNotBlank() }?.let(::decodeBase64Image)?.let { return it }
        item.optString("url").takeIf { it.isNotBlank() }?.let(::downloadImage)?.let { return it }
        item.optString("result").takeIf { it.isNotBlank() }?.let(::decodeBase64Image)?.let { return it }
        item.optString("image_base64").takeIf { it.isNotBlank() }?.let(::decodeBase64Image)?.let { return it }

        val content = item.optJSONArray("content") ?: return null
        for (i in 0 until content.length()) {
            val contentItem = content.optJSONObject(i) ?: continue
            contentItem.optString("b64_json").takeIf { it.isNotBlank() }?.let(::decodeBase64Image)?.let { return it }
            contentItem.optString("image_base64").takeIf { it.isNotBlank() }?.let(::decodeBase64Image)?.let { return it }
            val dataUrl = contentItem.optString("image_url").takeIf { it.startsWith("data:", ignoreCase = true) }
            dataUrl?.substringAfter(',')?.let(::decodeBase64Image)?.let { return it }
            responseFormat?.takeIf { it == "b64_json" }
            contentItem.optString("result").takeIf { it.isNotBlank() }?.let(::decodeBase64Image)?.let { return it }
        }
        return null
    }

    private fun decodeBase64Image(encoded: String): ByteArray? {
        val normalized = encoded.substringAfter(',', encoded)
            .filterNot { it.isWhitespace() }
        if (normalized.isBlank()) return null
        val padded = normalized + "=".repeat((4 - normalized.length % 4) % 4)
        return runCatching { Base64.getDecoder().decode(padded) }
            .recoverCatching { Base64.getUrlDecoder().decode(padded) }
            .getOrNull()
    }

    private fun downloadImage(url: String): ByteArray? {
        val request = Request.Builder().url(url).get().build()
        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IllegalStateException("image download failed(${response.code})")
            }
            return response.body?.bytes()
        }
    }

    private fun normalizeImageModelId(rawModel: String?): String? {
        val trimmed = rawModel?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return trimmed.replace(Regex("\\s+"), "")
    }

    private data class BundledImageProviderConfig(
        val baseUrl: String,
        val model: String,
        val apiKey: String
    )

    private fun bundledImageProviderConfig(): BundledImageProviderConfig {
        return BundledImageProviderConfig(
            baseUrl = BuildConfig.IMAGE_BASE_URL.trim().ifBlank { DEFAULT_IMAGE_BASE_URL },
            model = BuildConfig.IMAGE_MODEL.trim().ifBlank { DEFAULT_IMAGE_MODEL },
            apiKey = BuildConfig.IMAGE_API_KEY.trim()
        )
    }

    companion object {
        private const val HATCH_PET_SKILL_ID = "hatch-pet"
        private const val BUNDLED_IMAGE_PROVIDER_ID = "bundled-image-provider"
        private const val BUNDLED_IMAGE_PROVIDER_NAME = "Xiaowan Image Provider"
        internal const val DEFAULT_IMAGE_BASE_URL = "https://cloud.omnimind.com.cn"
        internal const val DEFAULT_IMAGE_MODEL = "gpt-image-2"
        private val SUPPORTED_OUTPUT_FORMATS = setOf("png", "webp", "jpeg")
        private val IMAGE_GENERATION_ENDPOINT_SUFFIXES = listOf(
            "/v1/images/generations",
            "/images/generations"
        )

        internal fun shouldUseBundledImageProvider(
            activeSkillIds: Set<String>,
            profileApiKey: String,
            bundledApiKey: String
        ): Boolean {
            return bundledApiKey.isNotBlank() &&
                (HATCH_PET_SKILL_ID in activeSkillIds || profileApiKey.isBlank())
        }

        internal fun resolveImageGenerationEndpoint(baseUrl: String, apiKey: String): String {
            val raw = baseUrl.trim()
            val resolved = raw.takeIf { it.isNotEmpty() } ?: DEFAULT_IMAGE_BASE_URL.also {
                require(apiKey.isNotBlank()) {
                    "Image provider baseUrl is empty. Configure https://cloud.omnimind.com.cn or another OpenAI-compatible endpoint."
                }
            }
            val stripped = ModelProviderConfigStore.stripDirectRequestUrlMarker(resolved).trimEnd('/')
            if (ModelProviderConfigStore.hasDirectRequestUrlMarker(resolved)) {
                return stripped
            }
            if (IMAGE_GENERATION_ENDPOINT_SUFFIXES.any { stripped.endsWith(it, ignoreCase = true) }) {
                return stripped
            }
            return if (stripped.endsWith("/v1", ignoreCase = true)) {
                "$stripped/images/generations"
            } else {
                "$stripped/v1/images/generations"
            }
        }
    }
}
