package cn.com.omnimind.baselib.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SceneVoiceConfigStoreTest {

    @Test
    fun normalize_fallsBackToDefaultsForUnknownVoiceAndStyle() {
        val normalized = SceneVoiceConfigStore.normalize(
            SceneVoiceConfig(
                autoPlay = true,
                voiceId = "unknown_voice",
                stylePreset = "unknown_style",
                customStyle = "  更柔和一点  "
            )
        )

        assertTrue(normalized.autoPlay)
        assertEquals(SceneVoiceConfigStore.VOICE_DEFAULT_ZH, normalized.voiceId)
        assertEquals(SceneVoiceConfigStore.STYLE_DEFAULT, normalized.stylePreset)
        assertEquals("更柔和一点", normalized.customStyle)
    }

    @Test
    fun parse_returnsNormalizedVoiceConfig() {
        val parsed = SceneVoiceConfigStore.parse(
            """
            {
              "autoPlay": true,
              "voiceId": "default_en",
              "stylePreset": "专业播报",
              "customStyle": "  节奏慢一点  "
            }
            """.trimIndent()
        )

        requireNotNull(parsed)
        assertTrue(parsed.autoPlay)
        assertEquals(SceneVoiceConfigStore.VOICE_DEFAULT_EN, parsed.voiceId)
        assertEquals(SceneVoiceConfigStore.STYLE_PROFESSIONAL_BROADCAST, parsed.stylePreset)
        assertEquals("节奏慢一点", parsed.customStyle)
    }
}
