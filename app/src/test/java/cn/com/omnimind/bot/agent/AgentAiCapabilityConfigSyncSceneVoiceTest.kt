package cn.com.omnimind.bot.agent

import cn.com.omnimind.baselib.llm.SceneVoiceConfigStore
import com.google.gson.JsonElement
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class AgentAiCapabilityConfigSyncSceneVoiceTest {

    @Test
    fun resolveSceneSettings_normalizesVoiceScenePayload() {
        val sync = newSync()
        val resolveSceneSettings = sync.javaClass.getDeclaredMethod(
            "resolveSceneSettings",
            JsonElement::class.java
        ).apply {
            isAccessible = true
        }

        val result = resolveSceneSettings.invoke(
            sync,
            JsonParser.parseString(
                """
                {
                  "scene.voice": {
                    "autoPlay": true,
                    "voiceId": "unknown_voice",
                    "stylePreset": "unknown_style",
                    "customStyle": "  更沉稳一点  "
                  },
                  "scene.dispatch.model": {
                    "ignored": true
                  }
                }
                """.trimIndent()
            )
        ) as Map<*, *>

        val snapshot = result[SceneVoiceConfigStore.SCENE_ID]
        assertNotNull(snapshot)
        assertEquals(1, result.size)
        assertEquals(true, readSnapshotField(snapshot, "autoPlay"))
        assertEquals(
            SceneVoiceConfigStore.VOICE_DEFAULT_ZH,
            readSnapshotField(snapshot, "voiceId")
        )
        assertEquals(
            SceneVoiceConfigStore.STYLE_DEFAULT,
            readSnapshotField(snapshot, "stylePreset")
        )
        assertEquals("更沉稳一点", readSnapshotField(snapshot, "customStyle"))
    }

    private fun newSync(): Any {
        val unsafeClass = Class.forName("sun.misc.Unsafe")
        val unsafeField = unsafeClass.getDeclaredField("theUnsafe").apply {
            isAccessible = true
        }
        val unsafe = unsafeField.get(null)
        return unsafeClass
            .getMethod("allocateInstance", Class::class.java)
            .invoke(unsafe, AgentAiCapabilityConfigSync::class.java)
    }

    private fun readSnapshotField(snapshot: Any?, name: String): Any? {
        requireNotNull(snapshot)
        return snapshot.javaClass.getDeclaredField(name).apply {
            isAccessible = true
        }.get(snapshot)
    }
}
