package cn.com.omnimind.assists.task.vlmserver

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VLMScrollCoordinateSanitizerTest {
    @Test
    fun `clamps scroll away from bottom gesture area`() {
        val safe = sanitizeScrollGestureCoordinates(
            x1 = 360,
            y1 = 1280,
            x2 = 360,
            y2 = 384,
            displayWidth = 720,
            displayHeight = 1280
        )

        assertTrue(safe.adjusted)
        assertEquals(360, safe.x1)
        assertEquals(1178, safe.y1)
        assertEquals(360, safe.x2)
        assertEquals(384, safe.y2)
    }

    @Test
    fun `clamps scroll away from top and horizontal edges`() {
        val safe = sanitizeScrollGestureCoordinates(
            x1 = 0,
            y1 = 0,
            x2 = 720,
            y2 = 1280,
            displayWidth = 720,
            displayHeight = 1280
        )

        assertTrue(safe.adjusted)
        assertEquals(18, safe.x1)
        assertEquals(51, safe.y1)
        assertEquals(702, safe.x2)
        assertEquals(1178, safe.y2)
    }

    @Test
    fun `does not modify scroll already inside safe area`() {
        val safe = sanitizeScrollGestureCoordinates(
            x1 = 360,
            y1 = 1100,
            x2 = 360,
            y2 = 400,
            displayWidth = 720,
            displayHeight = 1280
        )

        assertFalse(safe.adjusted)
        assertEquals(360, safe.x1)
        assertEquals(1100, safe.y1)
        assertEquals(360, safe.x2)
        assertEquals(400, safe.y2)
    }
}
