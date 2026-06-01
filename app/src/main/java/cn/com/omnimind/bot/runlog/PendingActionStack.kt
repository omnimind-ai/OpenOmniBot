package cn.com.omnimind.bot.runlog

import java.util.ArrayDeque

/**
 * Runtime state for call_function replay.
 *
 * The stack owns the list of actions that have not been executed yet. Source
 * alignment can pop already-satisfied non-key frames before the executor acts.
 */
class PendingActionStack private constructor(
    frames: List<ActionFrame>,
    val sourceAlignmentEnabled: Boolean,
) {
    private val remaining = ArrayDeque<ActionFrame>(frames)

    data class ActionFrame(
        val originalIndex: Int,
        val step: Map<String, Any?>,
        val stepId: String,
        val tool: String,
        val sourceContext: Map<String, Any?>,
        val sourcePageXml: String,
        val sourcePackage: String,
        val sourceVector: OobPageVectorSet.PageVector?,
        val isKeyAction: Boolean,
    ) {
        val hasSourcePage: Boolean get() = sourceVector != null
    }

    fun isEmpty(): Boolean = remaining.isEmpty()

    fun peek(): ActionFrame? = remaining.peekFirst()

    fun popExecuted(): ActionFrame? = remaining.pollFirst()

    fun windowUntilNextKey(): List<ActionFrame> {
        val window = mutableListOf<ActionFrame>()
        for (frame in remaining) {
            window += frame
            if (frame.isKeyAction) break
        }
        return window
    }

    fun popSkippedUntil(target: ActionFrame): List<ActionFrame> {
        val skipped = mutableListOf<ActionFrame>()
        while (remaining.isNotEmpty() && remaining.peekFirst() != target) {
            remaining.pollFirst()?.let { skipped += it } ?: break
        }
        return skipped
    }

    companion object {
        fun fromSteps(
            steps: List<Map<String, Any?>>,
            functionSpec: Map<String, Any?>,
            originalSpec: Map<String, Any?> = emptyMap(),
            startIndex: Int = 0,
        ): PendingActionStack {
            val frames = steps.mapIndexed { index, step ->
                val originalIndex = startIndex + index
                val actionName = OobActionCodec.actionNameForStep(step)
                val sourceContext = OobActionCodec.sourceContextForStep(step)
                val srcCtx = OobActionCodec.mapArg(sourceContext["src_ctx"])
                val srcXml = OobActionCodec.pageXmlFromContext(srcCtx)
                val srcPackage = OobActionCodec.firstNonBlank(srcCtx["package_name"], srcCtx["packageName"])
                val sourceVector = srcXml.takeIf { it.isNotBlank() }?.let {
                    OobPageVectorSet.encode(xml = it, packageName = srcPackage)
                }
                ActionFrame(
                    originalIndex = originalIndex,
                    step = step,
                    stepId = OobActionCodec.firstNonBlank(step["id"], step["step_id"], step["stepId"])
                        .ifBlank { "step_${originalIndex + 1}" },
                    tool = actionName,
                    sourceContext = sourceContext,
                    sourcePageXml = srcXml,
                    sourcePackage = srcPackage,
                    sourceVector = sourceVector,
                    isKeyAction = OobActionCodec.isUserFacingAction(actionName),
                )
            }
            return PendingActionStack(
                frames = frames,
                sourceAlignmentEnabled = ENABLE_SOURCE_ALIGNMENT && frames.any { it.isKeyAction },
            )
        }

        private const val ENABLE_SOURCE_ALIGNMENT = false
    }
}
