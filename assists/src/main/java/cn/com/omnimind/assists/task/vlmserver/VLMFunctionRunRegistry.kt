package cn.com.omnimind.assists.task.vlmserver

import kotlinx.serialization.json.JsonObject

data class VLMFunctionRunRequest(
    val functionId: String,
    val arguments: JsonObject,
)

interface VLMFunctionRunHandler {
    suspend fun runFunction(request: VLMFunctionRunRequest): OperationResult
}

object VLMFunctionRunRegistry {
    @Volatile
    private var handler: VLMFunctionRunHandler? = null

    fun register(handler: VLMFunctionRunHandler?) {
        this.handler = handler
    }

    suspend fun run(request: VLMFunctionRunRequest): OperationResult {
        val currentHandler = handler
            ?: return OperationResult(
                success = false,
                message = "复用指令执行器未注册",
                data = null,
            )
        return currentHandler.runFunction(request)
    }
}
