package cn.com.omnimind.assists.task.vlmserver

import cn.com.omnimind.baselib.util.OmniLog

data class VLMPageContextRequest(
    val context: UIContext,
    val currentXml: String?,
    val currentPackageName: String?,
    val screenshotBase64: String?,
    val stepIndex: Int,
    val snapshot: VLMCurrentPageSnapshot? = null,
)

data class VLMCurrentPageSnapshot(
    val packageName: String?,
    val xml: String?,
    val screenshotBase64: String?,
    val displayWidth: Int,
    val displayHeight: Int,
    val capturedAtMs: Long,
)

interface VLMPageContextProvider {
    suspend fun enrich(request: VLMPageContextRequest): UIContext
}

object VLMPageContextProviderRegistry {
    private const val TAG = "VLMPageContextProvider"

    @Volatile
    private var provider: VLMPageContextProvider? = null

    fun register(provider: VLMPageContextProvider?) {
        this.provider = provider
    }

    fun clear() {
        provider = null
    }

    suspend fun enrich(request: VLMPageContextRequest): UIContext {
        val activeProvider = provider ?: return request.context
        return runCatching { activeProvider.enrich(request) }
            .onFailure { error ->
                OmniLog.w(TAG, "page context provider failed: ${error.message}")
            }
            .getOrDefault(request.context)
    }
}
