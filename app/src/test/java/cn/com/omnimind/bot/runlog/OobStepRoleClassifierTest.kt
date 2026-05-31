package cn.com.omnimind.bot.runlog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OobStepRoleClassifierTest {
    @Test
    fun `classifies agent reuse references consistently`() {
        val functionSpec = mapOf(
            "agent_reuse" to mapOf(
                "key_actions" to listOf(mapOf("step_index" to 1)),
                "checker_assets" to listOf(mapOf("step_id" to "step_3")),
                "noise_actions" to listOf(4),
            )
        )

        assertEquals(
            OobStepRoleClassifier.ROLE_SEMANTIC,
            OobStepRoleClassifier.classify(functionSpec, mapOf("id" to "step_2"), 1).role,
        )
        assertEquals(
            OobStepRoleClassifier.ROLE_CHECKER_CANDIDATE,
            OobStepRoleClassifier.classify(functionSpec, mapOf("id" to "step_3"), 2).role,
        )
        assertEquals(
            OobStepRoleClassifier.ROLE_NOISE,
            OobStepRoleClassifier.classify(functionSpec, mapOf("id" to "step_5"), 4).role,
        )
    }

    @Test
    fun `normalizes cleanup annotations into role classes`() {
        val classification = OobStepRoleClassifier.classify(
            functionSpec = emptyMap(),
            step = mapOf(
                "cleanup_annotation" to mapOf("cleanup_action" to "optional_checker")
            ),
            stepIndex = 0,
        )

        assertEquals(OobStepRoleClassifier.ROLE_CHECKER_CANDIDATE, classification.role)
        assertTrue(classification.explicit)
    }

    @Test
    fun `defaults navigation role for canonical back key action`() {
        val classification = OobStepRoleClassifier.classify(
            functionSpec = emptyMap(),
            step = mapOf("tool" to "press_back", "args" to emptyMap<String, Any?>()),
            stepIndex = 0,
        )

        assertEquals(OobStepRoleClassifier.ROLE_NAVIGATION, classification.role)
        assertFalse(classification.explicit)
    }
}
