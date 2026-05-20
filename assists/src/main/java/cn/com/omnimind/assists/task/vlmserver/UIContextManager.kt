package cn.com.omnimind.assists.task.vlmserver

/**
 * UI上下文管理器 - 负责管理操作历史和关键记忆
 */

class UIContextManager {
    /**
    * 初始化上下文
    */
    fun initializeContext(
        overallTask: String,
        installedApplications: Map<String, String> = emptyMap(),
        targetPackageName: String = "",
        maxSteps: Int? = null,
        currentStepGoal: String = overallTask,
        stepSkillGuidance: String = ""
    ): UIContext {
        return UIContext(
            overallTask = overallTask,
            currentStepGoal = currentStepGoal,
            stepSkillGuidance = stepSkillGuidance,
            installedApplications = installedApplications,
            targetPackageName = targetPackageName,
            trace = emptyList(),
            keyMemory = emptyList(),
            maxSteps = maxSteps,
            stepsUsed = 0,
            stepsRemaining = maxSteps
        )
    }

    /**
     * 更新上下文 - 添加新的执行步骤并检测重复操作
     * 对应Python中的 update_context 方法
     */
    fun updateContext(context: UIContext, step: UIStep): UIContext {
        val newTrace = context.trace + step
        val cleanedTrace = removeRepeatedOperations(newTrace)
        val stepsUsed = cleanedTrace.size
        val stepsRemaining = context.maxSteps?.let { maxSteps ->
            val remaining = maxSteps - stepsUsed
            if (remaining < 0) 0 else remaining
        }

        return context.copy(
            trace = cleanedTrace,
            stepsUsed = stepsUsed,
            stepsRemaining = stepsRemaining
        )
    }

    /**
     * 处理记录动作 - 添加关键记忆
     * 对应Python中的 RecordAction 处理逻辑
     */
    fun addKeyMemory(context: UIContext, memory: String): UIContext {
        return context.copy(
            keyMemory = context.keyMemory + memory
        )
    }

    /**
     * 检测并移除重复操作
     */
    private fun removeRepeatedOperations(trace: List<UIStep>): List<UIStep> {
        if (trace.size < 2) return trace

        // 检测循环模式
        val cycleInfo = detectCyclePattern(trace)
        println("出现循环")
        return if (cycleInfo != null) {
            val (patternLength, repeatCount) = cycleInfo
            val removeCount = patternLength * repeatCount
            trace.dropLast(removeCount)
        } else {
            trace
        }
    }

    /**
     * 检测循环模式
     * @return Pair(模式长度, 重复次数) 如果检测到循环；否则返回null
     */
    private fun detectCyclePattern(trace: List<UIStep>): Pair<Int, Int>? {
        val maxPatternLength = trace.size / 2 // 最大模式长度不超过总长度的一半

        // 从小到大尝试不同的模式长度
        for (patternLength in 1..maxPatternLength) {
            val pattern = createStepPattern(trace.takeLast(patternLength))
            var repeatCount = 0
            var position = trace.size

            // 向前查找相同的模式
            while (position >= patternLength) {
                val currentSegment = trace.subList(position - patternLength, position)
                val currentPattern = createStepPattern(currentSegment)

                if (currentPattern == pattern) {
                    repeatCount++
                    position -= patternLength
                } else {
                    break
                }
            }

            // 如果找到至少2次重复（即实际重复了1次以上），则认为是循环
            if (repeatCount >= 2) {
                return Pair(patternLength, repeatCount)
            }
        }

        return null
    }

    /**
     * 创建步骤模式标识
     * 将UIStep转换为可比较的模式字符串
     */
    private fun createStepPattern(steps: List<UIStep>): List<String> {
        return steps.map { step ->
            "${step.action.name}|${step.thought}|${step.observation}"
        }
    }

}
