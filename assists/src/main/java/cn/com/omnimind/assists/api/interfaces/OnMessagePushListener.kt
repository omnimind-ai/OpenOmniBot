package cn.com.omnimind.assists.api.interfaces

import cn.com.omnimind.assists.api.bean.VlmTaskTerminalResult

interface OnMessagePushListener {
    /**
     * 大模型消息
     * @param taskID 任务ID
     * @param content 消息内容
     * @param type 消息类型（来自EventSource的type字段）
     */
    suspend fun onChatMessage(taskID:String,content: String, type: String?)

    /**
     * 大模型消息结束事件
     */
    suspend fun onChatMessageEnd(taskID:String)

    /**
     * 任务结束回调
     */
    fun onTaskFinish()

    /**
     * vlmTask执行结束
     */
    fun onVLMTaskFinish()

    /**
     * VLM任务请求用户输入（INFO动作）
     */
    fun onVLMRequestUserInput(question: String)

    /**
     * VLM任务终态/交互态结果
     */
    fun onVlmTaskResult(result: VlmTaskTerminalResult) {}

    /**
     * VLM任务单步工具事件。payload 使用 canonical 字段，展示层负责本地化。
     */
    fun onVlmToolEvent(event: Map<String, Any?>) {}

}
