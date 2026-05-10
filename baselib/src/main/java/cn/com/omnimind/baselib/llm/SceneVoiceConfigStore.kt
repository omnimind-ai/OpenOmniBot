package cn.com.omnimind.baselib.llm

import cn.com.omnimind.baselib.util.OmniLog
import com.google.gson.Gson
import com.tencent.mmkv.MMKV

object SceneVoiceConfigStore {
    private const val TAG = "SceneVoiceConfigStore"
    private const val KEY_SCENE_VOICE_CONFIG = "scene_voice_config_v1"

    const val SCENE_ID = "scene.voice"

    const val VOICE_MIMO_DEFAULT = "mimo_default"
    const val VOICE_DEFAULT_ZH = "default_zh"
    const val VOICE_DEFAULT_EN = "default_en"

    const val STYLE_DEFAULT = "默认"
    const val STYLE_NATURAL_DIALOG = "自然对话"
    const val STYLE_GENTLE_COMPANION = "温柔陪伴"
    const val STYLE_PROFESSIONAL_BROADCAST = "专业播报"
    const val STYLE_LIVELY = "活泼元气"
    const val STYLE_BEDTIME = "睡前轻声"
    const val STYLE_SING = "唱歌"

    private val gson = Gson()
    private val defaultConfig = SceneVoiceConfig()
    private val allowedVoices = setOf(
        VOICE_MIMO_DEFAULT,
        VOICE_DEFAULT_ZH,
        VOICE_DEFAULT_EN
    )
    private val allowedStylePresets = setOf(
        STYLE_DEFAULT,
        STYLE_NATURAL_DIALOG,
        STYLE_GENTLE_COMPANION,
        STYLE_PROFESSIONAL_BROADCAST,
        STYLE_LIVELY,
        STYLE_BEDTIME,
        STYLE_SING
    )

    fun getConfig(): SceneVoiceConfig {
        val mmkv = MMKV.defaultMMKV() ?: return defaultConfig
        val raw = mmkv.decodeString(KEY_SCENE_VOICE_CONFIG)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: return defaultConfig
        return parse(raw) ?: defaultConfig
    }

    fun saveConfig(config: SceneVoiceConfig): SceneVoiceConfig {
        val normalized = normalize(config)
        MMKV.defaultMMKV()?.encode(KEY_SCENE_VOICE_CONFIG, gson.toJson(normalized))
        return normalized
    }

    fun reset() {
        MMKV.defaultMMKV()?.removeValueForKey(KEY_SCENE_VOICE_CONFIG)
    }

    fun normalize(config: SceneVoiceConfig): SceneVoiceConfig {
        val normalizedVoiceId = config.voiceId.trim()
            .takeIf { allowedVoices.contains(it) }
            ?: defaultConfig.voiceId
        val normalizedStylePreset = config.stylePreset.trim()
            .takeIf { allowedStylePresets.contains(it) }
            ?: defaultConfig.stylePreset
        return SceneVoiceConfig(
            autoPlay = config.autoPlay,
            voiceId = normalizedVoiceId,
            stylePreset = normalizedStylePreset,
            customStyle = config.customStyle.trim()
        )
    }

    internal fun parse(raw: String): SceneVoiceConfig? {
        return runCatching {
            gson.fromJson(raw, SceneVoiceConfig::class.java)
        }.onFailure {
            OmniLog.w(TAG, "parse voice config failed: ${it.message}")
        }.getOrNull()?.let(::normalize)
    }
}
