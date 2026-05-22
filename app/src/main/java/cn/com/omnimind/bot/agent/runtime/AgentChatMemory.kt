package cn.com.omnimind.bot.agent

import cn.com.omnimind.baselib.llm.ChatCompletionMessage

interface AgentChatMemory {
    fun snapshot(): List<ChatCompletionMessage>
    fun add(message: ChatCompletionMessage)
    fun addAll(messages: List<ChatCompletionMessage>)
    fun replaceAll(messages: List<ChatCompletionMessage>)
    fun lastRole(): String?
    fun size(): Int
}

class MutableListChatMemory(
    initial: List<ChatCompletionMessage> = emptyList()
) : AgentChatMemory {
    private val backing: MutableList<ChatCompletionMessage> = initial.toMutableList()

    override fun snapshot(): List<ChatCompletionMessage> = backing.toList()

    override fun add(message: ChatCompletionMessage) {
        backing.add(message)
    }

    override fun addAll(messages: List<ChatCompletionMessage>) {
        backing.addAll(messages)
    }

    override fun replaceAll(messages: List<ChatCompletionMessage>) {
        backing.clear()
        backing.addAll(messages)
    }

    override fun lastRole(): String? = backing.lastOrNull()?.role

    override fun size(): Int = backing.size
}
