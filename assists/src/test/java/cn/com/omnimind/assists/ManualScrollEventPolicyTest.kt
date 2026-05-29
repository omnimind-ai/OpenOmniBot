package cn.com.omnimind.assists

import cn.com.omnimind.assists.task.vlmserver.ManualScrollDirection
import cn.com.omnimind.assists.task.vlmserver.ManualScrollEventPolicy
import cn.com.omnimind.assists.task.vlmserver.ManualScrollSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ManualScrollEventPolicyTest {
    @Test
    fun `viewport-only scroll event initializes state without becoming swipe`() {
        val current = ManualScrollSnapshot(
            scrollX = 0,
            scrollY = 0,
            scrollDeltaX = 0,
            scrollDeltaY = 0,
            fromIndex = 0,
            toIndex = 6,
            itemCount = 8
        )

        assertTrue(current.hasViewportSignal())
        assertNull(ManualScrollEventPolicy.inferDirection(previous = null, current = current))
    }

    @Test
    fun `index movement after previous viewport becomes swipe direction`() {
        val previous = ManualScrollSnapshot(
            scrollX = 0,
            scrollY = 0,
            scrollDeltaX = 0,
            scrollDeltaY = 0,
            fromIndex = 0,
            toIndex = 6,
            itemCount = 8
        )
        val current = previous.copy(fromIndex = 2, toIndex = 8)

        assertEquals(
            ManualScrollDirection.UP,
            ManualScrollEventPolicy.inferDirection(previous = previous, current = current)
        )
    }

    @Test
    fun `delta movement directly becomes swipe direction`() {
        val current = ManualScrollSnapshot(
            scrollX = 0,
            scrollY = 0,
            scrollDeltaX = 0,
            scrollDeltaY = 24,
            fromIndex = -1,
            toIndex = -1,
            itemCount = -1
        )

        assertEquals(
            ManualScrollDirection.UP,
            ManualScrollEventPolicy.inferDirection(previous = null, current = current)
        )
    }
}
