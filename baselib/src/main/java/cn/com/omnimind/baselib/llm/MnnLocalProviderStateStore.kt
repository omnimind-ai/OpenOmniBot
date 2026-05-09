package cn.com.omnimind.baselib.llm

import com.tencent.mmkv.MMKV

object MnnLocalProviderStateStore {
    const val BUILTIN_PROFILE_ID = "omniinfer-local"
    const val LEGACY_BUILTIN_PROFILE_ID = "mnn-local"
    const val BUILTIN_PROFILE_NAME = "OmniInfer"

    private const val KEY_PORT = "mnn_local_provider_port"
    private const val KEY_API_KEY = "mnn_local_provider_api_key"
    private const val KEY_READY = "mnn_local_provider_ready"

    @Volatile
    private var enabled: Boolean = false

    fun setEnabled(value: Boolean) {
        enabled = value
    }

    fun isEnabled(): Boolean = enabled

    fun isBuiltinProfileId(profileId: String?): Boolean {
        return when (profileId?.trim()) {
            BUILTIN_PROFILE_ID, LEGACY_BUILTIN_PROFILE_ID -> true
            else -> false
        }
    }

    fun update(port: Int, apiKey: String, ready: Boolean) {
        val mmkv = MMKV.defaultMMKV() ?: return
        mmkv.encode(KEY_PORT, port)
        mmkv.encode(KEY_API_KEY, apiKey.trim())
        mmkv.encode(KEY_READY, ready)
    }

    fun getProfile(): ModelProviderProfile {
        val mmkv = MMKV.defaultMMKV()
        val port = mmkv?.decodeInt(KEY_PORT, 8080)?.takeIf { it > 0 } ?: 8080
        val apiKey = mmkv?.decodeString(KEY_API_KEY)?.trim().orEmpty()
        val ready = mmkv?.decodeBool(KEY_READY, false) ?: false
        return ModelProviderProfile(
            id = BUILTIN_PROFILE_ID,
            name = BUILTIN_PROFILE_NAME,
            baseUrl = "http://127.0.0.1:$port",
            apiKey = apiKey,
            sourceType = "omniinfer",
            readOnly = true,
            ready = ready,
            statusText = if (ready) "已就绪" else "未就绪"
        )
    }

    fun getConfig(): ModelProviderConfig {
        val profile = getProfile()
        return ModelProviderConfig(
            id = profile.id,
            name = profile.name,
            baseUrl = profile.baseUrl,
            apiKey = profile.apiKey,
            source = "omniinfer",
            providerType = profile.sourceType,
            readOnly = profile.readOnly,
            ready = profile.ready,
            statusText = profile.statusText
        )
    }
}
