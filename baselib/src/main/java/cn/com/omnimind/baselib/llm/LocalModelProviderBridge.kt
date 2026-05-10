package cn.com.omnimind.baselib.llm

object LocalModelProviderBridge {
    interface Delegate {
        suspend fun prepareForRequest(
            profileId: String?,
            apiBase: String?,
            modelId: String
        ): Boolean
    }

    @Volatile
    private var delegate: Delegate? = null

    fun setDelegate(value: Delegate?) {
        delegate = value
    }

    fun isBuiltinLocalProvider(profileId: String?, apiBase: String?): Boolean {
        if (!MnnLocalProviderStateStore.isEnabled()) {
            return false
        }
        if (MnnLocalProviderStateStore.isBuiltinProfileId(profileId)) {
            return true
        }
        val normalizedBase = ModelProviderConfigStore.normalizeBaseUrl(apiBase ?: "") ?: return false
        val builtinBase = ModelProviderConfigStore.normalizeBaseUrl(
            MnnLocalProviderStateStore.getProfile().baseUrl
        ) ?: return false
        return normalizedBase == builtinBase
    }

    suspend fun prepareIfNeeded(
        profileId: String?,
        apiBase: String?,
        modelId: String
    ): Boolean {
        val normalizedModelId = modelId.trim()
        if (normalizedModelId.isEmpty() || !isBuiltinLocalProvider(profileId, apiBase)) {
            return false
        }
        return delegate?.prepareForRequest(profileId, apiBase, normalizedModelId) == true
    }
}
