package cn.com.omnimind.assists.task.vlmserver

import cn.com.omnimind.baselib.util.OmniLog

/**
 * Loading Sprite Agent - 赛博精灵加载状态生成器
 * 在任务开始时从本地候选中准备多个加载提示词。
 * Compactor 工作期间按顺序取用
 */
class LoadingSpriteAgent {
    private val Tag = "LoadingSpriteAgent"

    // 当前任务的加载词列表
    private var loadingPhrases: MutableList<String> = mutableListOf()
    private var currentIndex = 0

    // 预设的本地候选词库（当 LLM 请求失败时使用）
    private val fallbackPhrases = listOf(
        "正在给比特流打蝴蝶结",
        "把乱码梳理成麻花辫",
        "炼化逻辑金丹中",
        "正在把 0 和 1 熬成浓汤",
        "翻箱倒柜找灵感",
        "正在听硬盘窃窃私语",
        "脑回路打结中",
        "正在安抚暴躁的晶体管",
        "神游太虚收集碎片",
        "絮絮叨叨整理思绪",
        "正在编织数据蛛网",
        "叽里咕噜念咒语",
        "抓耳挠腮想对策",
        "正在给像素点排队",
        "左顾右盼找出路"
    )

    /**
     * 根据任务目标预生成加载提示词列表
     * 应在任务开始时调用
     * @param taskGoal 用户的任务目标
     */
    suspend fun prepareForTask(taskGoal: String) {
        currentIndex = 0
        loadingPhrases.clear()
        loadingPhrases.addAll(fallbackPhrases.shuffled().take(5))
        OmniLog.i(Tag, "使用本地加载提示词，跳过远程生成: $taskGoal")
    }

    /**
     * 获取下一个加载提示词
     * 按顺序返回，用完后循环
     * @return 加载提示词
     */
    fun getNextPhrase(): String {
        if (loadingPhrases.isEmpty()) {
            return fallbackPhrases.random()
        }

        val phrase = loadingPhrases[currentIndex]
        currentIndex = (currentIndex + 1) % loadingPhrases.size
        return phrase
    }

    /**
     * 重置索引（用于新一轮循环）
     */
    fun reset() {
        currentIndex = 0
    }
}
