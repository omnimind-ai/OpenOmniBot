package cn.com.omnimind.bot.runlog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OmniflowCheckerRuleTest {
    @Test
    fun `normalizes checker condition and action aliases`() {
        val condition = OmniflowCheckerRule.normalizeCondition("skip-ad")
        val action = OmniflowCheckerRule.normalizeAction("click_dismiss", condition)

        assertEquals(OmniflowCheckerRule.COND_AD_BLOCKING, condition)
        assertEquals(OmniflowCheckerRule.ACTION_DISMISS, action)
        assertTrue(OmniflowCheckerRule.isSupportedPair(condition, action))
    }

    @Test
    fun `derives default checker action and phase from condition`() {
        assertEquals(
            OmniflowCheckerRule.ACTION_HIDE_KEYBOARD,
            OmniflowCheckerRule.actionForCondition(OmniflowCheckerRule.COND_KEYBOARD_OBSCURING),
        )
        assertEquals(
            OmniflowCheckerRule.PHASE_PRE_ACTION,
            OmniflowCheckerRule.phaseForCondition(OmniflowCheckerRule.COND_KEYBOARD_OBSCURING),
        )
        assertEquals(
            OmniflowCheckerRule.PHASE_POST_ACTION,
            OmniflowCheckerRule.phaseForCondition(OmniflowCheckerRule.COND_RESOLVER_DIALOG),
        )
    }

    @Test
    fun `from map canonicalizes aliases and rejects unsupported pairs`() {
        val rule = OmniflowCheckerRule.fromMap(
            mapOf(
                "id" to "allow_permission",
                "condition" to "permission_prompt",
                "action" to "click_allow",
            )
        )

        assertNotNull(rule)
        assertEquals(OmniflowCheckerRule.COND_PERMISSION_DIALOG, rule?.condition)
        assertEquals(OmniflowCheckerRule.ACTION_ALLOW, rule?.action)
        assertEquals(OmniflowCheckerRule.PHASE_PRE_TRANSFER, rule?.phase)

        assertFalse(
            OmniflowCheckerRule.isSupportedPair(
                OmniflowCheckerRule.COND_PERMISSION_DIALOG,
                OmniflowCheckerRule.ACTION_DISMISS,
            )
        )
    }
}
