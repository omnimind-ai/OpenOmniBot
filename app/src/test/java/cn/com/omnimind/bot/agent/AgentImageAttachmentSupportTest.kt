package cn.com.omnimind.bot.agent

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.io.File

class AgentImageAttachmentSupportTest {
    @After
    fun tearDown() {
        AgentImageAttachmentSupport.resetBackendForTests()
    }

    @Test
    fun `prepareAttachments keeps model image and history preview separate`() {
        AgentImageAttachmentSupport.backend = object : AgentImageAttachmentSupport.Backend {
            override fun readFileAsDataUrl(file: File, mimeTypeHint: String?): String {
                return "data:image/png;base64,ORIGINAL"
            }

            override fun compressDataUrl(
                dataUrl: String,
                scale: Float,
                quality: Int
            ): AgentImageAttachmentSupport.ResolvedImageData {
                val encoded = if (scale >= 0.7f) {
                    "data:image/jpeg;base64,MODEL"
                } else {
                    "data:image/jpeg;base64,PREVIEW"
                }
                return AgentImageAttachmentSupport.ResolvedImageData(
                    dataUrl = encoded,
                    mimeType = "image/jpeg",
                    originalWidth = 1440,
                    originalHeight = 900,
                    compressedWidth = if (scale >= 0.7f) 1080 else 504,
                    compressedHeight = if (scale >= 0.7f) 675 else 315
                )
            }
        }

        val prepared = AgentImageAttachmentSupport.prepareAttachments(
            listOf(
                mapOf(
                    "path" to "/tmp/screenshot.png",
                    "name" to "screenshot.png",
                    "mimeType" to "image/png",
                    "isImage" to true
                )
            )
        )

        assertEquals(1, prepared.modelAttachments.size)
        assertEquals(1, prepared.historyAttachments.size)
        assertEquals(
            "data:image/jpeg;base64,MODEL",
            prepared.modelAttachments.single()["dataUrl"]
        )
        assertEquals(
            "data:image/jpeg;base64,PREVIEW",
            prepared.historyAttachments.single()["dataUrl"]
        )
        assertEquals("/tmp/screenshot.png", prepared.historyAttachments.single()["path"])
    }

    @Test
    fun `buildFileReadImageResult returns image preview outside payload json`() {
        AgentImageAttachmentSupport.backend = object : AgentImageAttachmentSupport.Backend {
            override fun readFileAsDataUrl(file: File, mimeTypeHint: String?): String {
                return "data:image/png;base64,ORIGINAL"
            }

            override fun compressDataUrl(
                dataUrl: String,
                scale: Float,
                quality: Int
            ): AgentImageAttachmentSupport.ResolvedImageData {
                return AgentImageAttachmentSupport.ResolvedImageData(
                    dataUrl = "data:image/jpeg;base64,MODEL",
                    mimeType = "image/jpeg",
                    originalWidth = 1179,
                    originalHeight = 2556,
                    compressedWidth = 884,
                    compressedHeight = 1917
                )
            }
        }

        val result = AgentImageAttachmentSupport.buildFileReadImageResult(
            file = File("/tmp/photo.png"),
            shellPath = "/workspace/photo.png",
            mimeTypeHint = "image/png",
            uri = "omnibot://workspace/photo.png",
            sizeBytes = 4096L
        )

        assertNotNull(result)
        assertEquals("data:image/jpeg;base64,MODEL", result?.imageDataUrl)
        assertFalse(result?.payload.toString().orEmpty().contains("base64"))
        assertEquals(1179, result?.payload?.get("width"))
        assertEquals(2556, result?.payload?.get("height"))
    }
}
