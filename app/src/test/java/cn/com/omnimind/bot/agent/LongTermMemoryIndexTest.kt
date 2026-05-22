package cn.com.omnimind.bot.agent

import cn.com.omnimind.bot.agent.workspace.memory.LongTermMemoryIndex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LongTermMemoryIndexTest {

    @Test
    fun `slug is deterministic for same text`() {
        val a = LongTermMemoryIndex.makeSlug("用户喜欢使用 Kotlin 协程")
        val b = LongTermMemoryIndex.makeSlug("用户喜欢使用 Kotlin 协程")
        assertEquals(a, b)
    }

    @Test
    fun `slug differs across distinct text`() {
        val a = LongTermMemoryIndex.makeSlug("用户偏好 Vim")
        val b = LongTermMemoryIndex.makeSlug("用户偏好 Emacs")
        assertNotEquals(a, b)
    }

    @Test
    fun `slug ends with eight hex chars`() {
        val slug = LongTermMemoryIndex.makeSlug("This is a sample entry")
        val tail = slug.substringAfterLast("-")
        assertEquals(8, tail.length)
        assertTrue(tail.all { it.isDigit() || it in 'a'..'f' })
    }

    @Test
    fun `slug strips punctuation in title prefix`() {
        val slug = LongTermMemoryIndex.makeSlug("!@#\$%   hello, world!!!")
        // Title prefix should be alphanumeric + dashes only.
        val head = slug.substringBeforeLast("-")
        assertTrue(head.matches(Regex("[a-z0-9-]+")))
    }
}
