package cn.com.omnimind.bot.runlog

import cn.com.omnimind.bot.runlog.OobActionCodec.firstNonBlank

/**
 * Removes deterministic noise from compiled replay steps.
 *
 * This operates after RunLog cards have already been lowered into canonical
 * execution steps. Card filtering, parameter inference, and launch-bridge
 * handling stay in the compiler.
 */
object RunLogReplayStepNoiseNormalizer {
    private val inputTextActions = setOf(OobActionCodec.ACTION_INPUT_TEXT)

    fun normalize(steps: List<Map<String, Any?>>): List<Map<String, Any?>> =
        dropDuplicateTextInputSteps(steps)
            .let(::collapseConsecutiveTextInputSteps)
            .let(::dropRedundantClickBeforeInputText)

    /**
     * Collapses consecutive input_text steps on the same target into the last one.
     *
     * Accessibility text-change events fire on every keystroke, producing one
     * input_text card per character. Only the final value matters for replay.
     */
    private fun collapseConsecutiveTextInputSteps(
        steps: List<Map<String, Any?>>,
    ): List<Map<String, Any?>> {
        if (steps.size < 2) return steps
        val output = mutableListOf<Map<String, Any?>>()
        var i = 0
        while (i < steps.size) {
            val step = steps[i]
            if (replayActionForStep(step) !in inputTextActions) {
                output += step
                i++
                continue
            }
            var last = step
            val lastSig = textInputTargetSignature(last)
            if (lastSig.isBlank()) {
                output += last
                i++
                continue
            }
            while (i + 1 < steps.size) {
                val next = steps[i + 1]
                if (replayActionForStep(next) !in inputTextActions) break
                val nextSig = textInputTargetSignature(next)
                if (nextSig.isBlank() || nextSig != lastSig) break
                last = next
                i++
            }
            output += last
            i++
        }
        return output
    }

    /**
     * Drops a click step that immediately precedes input_text on the same target.
     */
    private fun dropRedundantClickBeforeInputText(
        steps: List<Map<String, Any?>>,
    ): List<Map<String, Any?>> {
        if (steps.size < 2) return steps
        val output = mutableListOf<Map<String, Any?>>()
        var i = 0
        while (i < steps.size) {
            val step = steps[i]
            val next = steps.getOrNull(i + 1)
            if (next != null &&
                replayActionForStep(step) == OobActionCodec.ACTION_CLICK &&
                replayActionForStep(next) in inputTextActions &&
                clickAndInputShareTarget(step, next)
            ) {
                i++
                continue
            }
            output += step
            i++
        }
        return output
    }

    private fun dropDuplicateTextInputSteps(
        steps: List<Map<String, Any?>>,
    ): List<Map<String, Any?>> {
        if (steps.size < 2) return steps
        val output = mutableListOf<Map<String, Any?>>()
        steps.forEach { step ->
            val previous = output.lastOrNull()
            if (previous != null && isDuplicateTextInputStep(previous, step)) {
                return@forEach
            }
            output += step
        }
        return output
    }

    private fun isDuplicateTextInputStep(
        previous: Map<String, Any?>,
        current: Map<String, Any?>,
    ): Boolean {
        if (replayActionForStep(previous) !in inputTextActions) return false
        if (replayActionForStep(current) !in inputTextActions) return false
        val previousText = textInputValue(previous)
        val currentText = textInputValue(current)
        if (previousText.isBlank() || previousText != currentText) return false
        val previousTarget = textInputTargetSignature(previous)
        val currentTarget = textInputTargetSignature(current)
        return previousTarget.isNotBlank() && previousTarget == currentTarget
    }

    private fun clickAndInputShareTarget(
        clickStep: Map<String, Any?>,
        inputStep: Map<String, Any?>,
    ): Boolean {
        val clickArgs = OobActionCodec.argsForStep(clickStep)
        val inputArgs = OobActionCodec.argsForStep(inputStep)

        val clickResId = firstNonBlank(
            clickArgs["node_resource_id"],
            clickArgs["nodeResourceId"],
            clickArgs["resource_id"],
        )
        val inputResId = firstNonBlank(
            inputArgs["node_resource_id"],
            inputArgs["nodeResourceId"],
            inputArgs["resource_id"],
        )
        if (clickResId.isNotBlank() && clickResId == inputResId) return true

        val clickBounds = firstNonBlank(clickArgs["bounds"])
        val inputBounds = firstNonBlank(inputArgs["bounds"])
        if (clickBounds.isNotBlank() && clickBounds == inputBounds) return true

        val cx = firstNonBlank(clickArgs["x"], clickArgs["center_x"], clickArgs["centerX"]).toFloatOrNull()
        val cy = firstNonBlank(clickArgs["y"], clickArgs["center_y"], clickArgs["centerY"]).toFloatOrNull()
        val ix = firstNonBlank(inputArgs["x"], inputArgs["center_x"], inputArgs["centerX"]).toFloatOrNull()
        val iy = firstNonBlank(inputArgs["y"], inputArgs["center_y"], inputArgs["centerY"]).toFloatOrNull()
        if (cx != null && cy != null && ix != null && iy != null) {
            return kotlin.math.abs(cx - ix) < 80f && kotlin.math.abs(cy - iy) < 80f
        }

        return false
    }

    private fun replayActionForStep(step: Map<String, Any?>): String =
        OobActionCodec.actionNameForStep(step)

    private fun textInputValue(step: Map<String, Any?>): String {
        val args = OobActionCodec.argsForStep(step)
        return firstNonBlank(args["text"], args["content"], args["value"])
    }

    private fun textInputTargetSignature(step: Map<String, Any?>): String {
        val args = OobActionCodec.argsForStep(step)
        val action = OobActionCodec.sourceActionForStep(step)
        return firstNonBlank(
            args["node_resource_id"],
            action["node_resource_id"],
            args["selector"],
            action["selector"],
            args["bounds"],
            action["bounds"],
            args["target_description"],
            args["targetDescription"],
            action["target_description"],
            action["targetDescription"],
        )
    }

}
