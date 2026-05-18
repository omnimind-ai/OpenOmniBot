package cn.com.omnimind.bot.agent

import cn.com.omnimind.baselib.llm.contentText as sharedContentText

typealias ChatCompletionRequest = cn.com.omnimind.baselib.llm.ChatCompletionRequest
typealias ChatCompletionStreamOptions = cn.com.omnimind.baselib.llm.ChatCompletionStreamOptions
typealias ChatCompletionMessage = cn.com.omnimind.baselib.llm.ChatCompletionMessage
typealias ChatCompletionThinkingContentBlock = cn.com.omnimind.baselib.llm.ChatCompletionThinkingContentBlock
typealias ChatCompletionTool = cn.com.omnimind.baselib.llm.ChatCompletionTool
typealias ChatCompletionFunction = cn.com.omnimind.baselib.llm.ChatCompletionFunction
typealias AssistantToolCall = cn.com.omnimind.baselib.llm.AssistantToolCall
typealias AssistantToolCallFunction = cn.com.omnimind.baselib.llm.AssistantToolCallFunction
typealias ChatCompletionResponse = cn.com.omnimind.baselib.llm.ChatCompletionResponse
typealias ChatCompletionChoice = cn.com.omnimind.baselib.llm.ChatCompletionChoice
typealias ChatCompletionAssistantMessage = cn.com.omnimind.baselib.llm.ChatCompletionAssistantMessage
typealias ChatCompletionStreamChunk = cn.com.omnimind.baselib.llm.ChatCompletionStreamChunk
typealias ChatCompletionStreamChoice = cn.com.omnimind.baselib.llm.ChatCompletionStreamChoice
typealias ChatCompletionDelta = cn.com.omnimind.baselib.llm.ChatCompletionDelta
typealias ChatCompletionLegacyFunctionCall = cn.com.omnimind.baselib.llm.ChatCompletionLegacyFunctionCall
typealias ChatCompletionToolCallDelta = cn.com.omnimind.baselib.llm.ChatCompletionToolCallDelta
typealias ChatCompletionToolCallFunctionDelta = cn.com.omnimind.baselib.llm.ChatCompletionToolCallFunctionDelta
typealias ChatCompletionUsage = cn.com.omnimind.baselib.llm.ChatCompletionUsage
typealias ChatCompletionTurn = cn.com.omnimind.baselib.llm.ChatCompletionTurn

fun ChatCompletionMessage.contentText(): String = this.sharedContentText()
