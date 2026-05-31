package cn.com.omnimind.bot.omniflow

import cn.com.omnimind.bot.omniflow.OobFunctionJson.firstNonBlank
import cn.com.omnimind.bot.omniflow.OobFunctionJson.listArg
import cn.com.omnimind.bot.omniflow.OobFunctionJson.mapArg

/**
 * Normalizes update_function patch and natural-language repair intent into
 * explicit operations. The update service owns applying those operations.
 */
class OobFunctionUpdateIntentParser {
    fun operationsFromPatch(patch: Map<String, Any?>): List<Map<String, Any?>> {
        val direct = listArg(patch["ops"])
            .ifEmpty { listArg(patch["operations"]) }
            .ifEmpty { listArg(patch["repairs"]) }
            .mapNotNull { mapArg(it).takeIf { op -> op.isNotEmpty() } }
        if (direct.isNotEmpty()) return direct
        return mapArg(patch["replace_target"])
            .ifEmpty { mapArg(patch["replaceTarget"]) }
            .takeIf { it.isNotEmpty() }
            ?.let { listOf(linkedMapOf<String, Any?>("op" to "replace_target").apply { putAll(it) }) }
            .orEmpty()
    }

    fun operationsFromInstruction(instruction: String): List<Map<String, Any?>> {
        if (instruction.isBlank()) return emptyList()
        val quoted = Regex("[「“\\\"']([^」”\\\"']{1,80})[」”\\\"']")
            .findAll(instruction)
            .map { it.groupValues[1].trim() }
            .filter { it.isNotEmpty() }
            .toList()
        if (quoted.size >= 2) {
            return quotedTargetReplacement(instruction, quoted[0], quoted[1])
        }

        val patterns = listOf(
            Regex("(?:应该|应当|要|请)?(?:点击|点|选择|选|打开)\\s*(.{1,40}?)\\s*(?:而不是|而非|不是|不要)\\s*(?:点击|点|选择|选|打开)?\\s*(.{1,40})"),
            Regex("(?:不要|别|不该)(?:点击|点|选择|选|打开)?\\s*(.{1,40}?)\\s*(?:，|,|；|;|\\s)+(?:应该|应当|要|改成|改为|而是)(?:点击|点|选择|选|打开)?\\s*(.{1,40})"),
            Regex("把\\s*(?:点击|点|选择|选|打开)?\\s*(.{1,40}?)\\s*(?:改成|改为)\\s*(?:点击|点|选择|选|打开)?\\s*(.{1,40})"),
        )
        patterns.forEachIndexed { index, regex ->
            val match = regex.find(instruction) ?: return@forEachIndexed
            val first = cleanupInstructionTarget(match.groupValues[1])
            val second = cleanupInstructionTarget(match.groupValues[2])
            if (first.isBlank() || second.isBlank()) return@forEachIndexed
            val desired = if (index == 0) first else second
            val wrong = if (index == 0) second else first
            return listOf(replaceTargetOperation(instruction, wrong, desired))
        }
        return emptyList()
    }

    fun isReplaceTargetOperation(op: Map<String, Any?>): Boolean =
        firstNonBlank(op["op"], op["type"], op["operation"])
            .lowercase() in setOf("replace_target", "replace_click_target", "retarget_action")

    fun isStructuralOperation(op: Map<String, Any?>): Boolean =
        firstNonBlank(op["op"], op["type"], op["operation"])
            .lowercase() in setOf(
                "insert_step",
                "add_step",
                "insert_action",
                "add_action",
                "delete_step",
                "remove_step",
                "delete_action",
                "remove_action",
            )

    private fun quotedTargetReplacement(
        instruction: String,
        first: String,
        second: String,
    ): List<Map<String, Any?>> {
        val firstIndex = instruction.indexOf(first)
        val secondIndex = instruction.indexOf(
            second,
            startIndex = (firstIndex + first.length).coerceAtLeast(0),
        )
        val between = if (firstIndex >= 0 && secondIndex > firstIndex) {
            instruction.substring(firstIndex + first.length, secondIndex)
        } else {
            instruction
        }
        val prefix = if (firstIndex > 0) instruction.substring(0, firstIndex) else ""
        val desiredFirst = between.contains("而不是") ||
            between.contains("而非") ||
            between.contains("instead of", ignoreCase = true) ||
            (prefix.contains("应该") && between.contains("不是"))
        val wrongFirst = prefix.contains("不要") ||
            prefix.contains("别") ||
            prefix.contains("不该") ||
            between.contains("改成") ||
            between.contains("改为") ||
            between.contains("rather") && prefix.contains("not", ignoreCase = true)
        val desired = if (wrongFirst && !desiredFirst) second else first
        val wrong = if (wrongFirst && !desiredFirst) first else second
        return listOf(replaceTargetOperation(instruction, wrong, desired))
    }

    private fun replaceTargetOperation(
        instruction: String,
        wrong: String,
        desired: String,
    ): Map<String, Any?> =
        linkedMapOf(
            "op" to "replace_target",
            "action" to inferredActionFromInstruction(instruction),
            "wrong_text" to wrong,
            "desired_text" to desired,
            "source" to "instruction_inference",
        )

    private fun cleanupInstructionTarget(value: String): String =
        value.trim()
            .trim('「', '」', '“', '”', '"', '\'', '，', ',', '。', '.', '；', ';', ' ')
            .replace(Regex("\\s+"), " ")

    private fun inferredActionFromInstruction(instruction: String): String =
        when {
            instruction.contains("长按") -> "long_press"
            instruction.contains("输入") || instruction.contains("填写") -> "input_text"
            instruction.contains("滑") || instruction.contains("滚") -> "swipe"
            else -> "click"
        }

}
