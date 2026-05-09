package cn.com.omnimind.bot.omniinfer

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.Locale
import java.util.concurrent.TimeUnit

object OmniInferMnnMarketRepository {
    private const val MARKET_URL = "https://omnimind-model.oss-cn-beijing.aliyuncs.com/mnn/model_market.json"
    private const val ASSET_NAME = "omniinfer_mnn_model_market.json"
    private const val CACHE_DIR = "omniinfer"
    private const val CACHE_FILE_NAME = "mnn_model_market_cache.json"

    @Serializable
    data class MarketPayload(
        val version: String? = null,
        val models: List<MarketItem> = emptyList(),
    )

    @Serializable
    data class MarketItem(
        @SerialName("modelName") val modelName: String = "",
        val tags: List<String> = emptyList(),
        @SerialName("extra_tags") val extraTags: List<String> = emptyList(),
        val categories: List<String> = emptyList(),
        val sources: Map<String, String> = emptyMap(),
        @SerialName("min_app_version") val minAppVersion: String? = null,
        @SerialName("size_gb") val sizeGb: Double? = null,
        val vendor: String? = null,
        @SerialName("file_size") val fileSize: Long = 0L,
    )

    data class ResolvedMarketModel(
        val modelId: String,
        val downloadId: String,
        val source: String,
        val repoPath: String,
        val item: MarketItem,
    )

    private var appContext: Context? = null
    private val json = Json { ignoreUnknownKeys = true }
    private val client = OkHttpClient.Builder()
        .followRedirects(true)
        .followSslRedirects(true)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    @Volatile
    private var cachedPayload: MarketPayload? = null

    fun setContext(context: Context) {
        appContext = context.applicationContext
    }

    suspend fun listModels(
        source: String,
        refresh: Boolean = false,
    ): List<ResolvedMarketModel> {
        return resolveModels(loadPayload(refresh), source)
    }

    fun findModel(
        modelId: String,
        preferredSource: String? = null,
    ): ResolvedMarketModel? {
        val normalizedModelId = modelId.trim()
        if (normalizedModelId.isEmpty()) {
            return null
        }
        val models = allModels()
        models.firstOrNull { it.downloadId == normalizedModelId }?.let { return it }
        preferredSource
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.let { source ->
                models.firstOrNull { it.source == source && it.modelId == normalizedModelId }?.let { return it }
            }
        return models.firstOrNull { it.modelId == normalizedModelId }
    }

    fun normalizeModelId(modelId: String?): String {
        val normalizedModelId = modelId?.trim().orEmpty()
        if (normalizedModelId.isEmpty()) {
            return ""
        }
        return findModel(normalizedModelId)?.modelId ?: normalizedModelId
    }

    fun allModels(): List<ResolvedMarketModel> {
        val payload = cachedPayload ?: loadLocalPayload().also { cachedPayload = it }
        return buildList {
            addAll(resolveModels(payload, "HuggingFace"))
            addAll(resolveModels(payload, "ModelScope"))
            addAll(resolveModels(payload, "Modelers"))
        }
    }

    private suspend fun loadPayload(refresh: Boolean): MarketPayload {
        if (!refresh) {
            cachedPayload?.let { return it }
            return loadLocalPayload().also { cachedPayload = it }
        }
        return runCatching { fetchNetworkPayload() }
            .getOrElse {
                cachedPayload ?: loadLocalPayload()
            }
            .also { cachedPayload = it }
    }

    private fun loadLocalPayload(): MarketPayload {
        val context = appContext ?: error("MNN market context is not initialized")
        val cacheFile = cacheFile(context)
        val cachedText = runCatching {
            if (cacheFile.exists()) cacheFile.readText() else null
        }.getOrNull()
        if (!cachedText.isNullOrBlank()) {
            return decodePayload(cachedText)
        }
        val assetText = context.assets.open(ASSET_NAME).bufferedReader().use { it.readText() }
        return decodePayload(assetText)
    }

    private suspend fun fetchNetworkPayload(): MarketPayload {
        val context = appContext ?: error("MNN market context is not initialized")
        val body = withContext(Dispatchers.IO) {
            client.newCall(Request.Builder().url(MARKET_URL).get().build()).execute().use { response ->
                if (!response.isSuccessful) {
                    error("Failed to fetch MNN market: HTTP ${response.code}")
                }
                response.body?.string().orEmpty()
            }
        }
        val payload = decodePayload(body)
        withContext(Dispatchers.IO) {
            val target = cacheFile(context)
            target.parentFile?.mkdirs()
            target.writeText(body)
        }
        return payload
    }

    private fun decodePayload(raw: String): MarketPayload {
        return json.decodeFromString(raw)
    }

    private fun resolveModels(
        payload: MarketPayload,
        source: String,
    ): List<ResolvedMarketModel> {
        return payload.models.asSequence()
            .filter(::isServiceable)
            .mapNotNull { item ->
                val repoPath = item.sources[source]?.trim().orEmpty()
                if (repoPath.isEmpty()) {
                    null
                } else {
                    ResolvedMarketModel(
                        modelId = item.modelName.trim(),
                        downloadId = "$source/$repoPath",
                        source = source,
                        repoPath = repoPath,
                        item = item,
                    )
                }
            }
            .distinctBy { it.modelId }
            .sortedWith(
                compareBy<ResolvedMarketModel> {
                    it.item.vendor.orEmpty().lowercase(Locale.getDefault())
                }.thenBy {
                    it.item.modelName.lowercase(Locale.getDefault())
                }
            )
            .toList()
    }

    private fun isServiceable(item: MarketItem): Boolean {
        val modelName = item.modelName.trim().lowercase(Locale.getDefault())
        if (modelName.isEmpty()) {
            return false
        }
        val tags = item.tags.map { it.trim().lowercase(Locale.getDefault()) }
        if (tags.any { it == "imagegen" || it == "audiogen" || it == "tts" || it == "asr" }) {
            return false
        }
        if (modelName.contains("stable-diffusion") || modelName.contains("sana")) {
            return false
        }
        return item.sources.isNotEmpty()
    }

    private fun cacheFile(context: Context): File {
        return File(File(context.filesDir, CACHE_DIR), CACHE_FILE_NAME)
    }
}


