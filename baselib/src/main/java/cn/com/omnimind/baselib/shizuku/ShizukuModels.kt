package cn.com.omnimind.baselib.shizuku

import kotlinx.serialization.Serializable

@Serializable
enum class ShizukuBackend {
    NONE,
    ADB,
    ROOT
}

@Serializable
enum class ShizukuStatusCode {
    NOT_INSTALLED,
    NOT_RUNNING,
    PERMISSION_DENIED,
    GRANTED_ADB,
    GRANTED_ROOT,
    BINDER_DEAD
}

@Serializable
data class ShizukuStatus(
    val code: ShizukuStatusCode,
    val backend: ShizukuBackend = ShizukuBackend.NONE,
    val installed: Boolean = false,
    val running: Boolean = false,
    val permissionGranted: Boolean = false,
    val binderReady: Boolean = false,
    val serviceBound: Boolean = false,
    val uid: Int? = null,
    val version: Int? = null,
    val availableActions: List<String> = emptyList(),
    val message: String = "",
) {
    fun isGranted(): Boolean {
        return code == ShizukuStatusCode.GRANTED_ADB || code == ShizukuStatusCode.GRANTED_ROOT
    }

    fun toMap(): Map<String, Any?> {
        return linkedMapOf(
            "status" to code.name,
            "backend" to backend.name,
            "installed" to installed,
            "running" to running,
            "permissionGranted" to permissionGranted,
            "binderReady" to binderReady,
            "serviceBound" to serviceBound,
            "uid" to uid,
            "version" to version,
            "availableActions" to availableActions,
            "message" to message
        )
    }
}

@Serializable
data class PrivilegedRequest(
    val requestId: String,
    val action: String,
    val arguments: Map<String, String> = emptyMap(),
    val requiresConfirmation: Boolean = false,
    val command: String? = null,
    val timeoutSeconds: Int? = null,
    val workingDirectory: String? = null,
    val environment: Map<String, String> = emptyMap(),
    val sessionId: String? = null,
)

@Serializable
data class PrivilegedResult(
    val requestId: String,
    val action: String,
    val success: Boolean,
    val code: String,
    val message: String,
    val backend: ShizukuBackend = ShizukuBackend.NONE,
    val output: String = "",
    val stdout: String = "",
    val stderr: String = "",
    val transcript: String = "",
    val exitCode: Int? = null,
    val requiresConfirmation: Boolean = false,
    val availableActions: List<String> = emptyList(),
    val data: Map<String, String> = emptyMap(),
    val command: String? = null,
    val timeoutSeconds: Int? = null,
    val workingDirectory: String? = null,
    val environment: Map<String, String> = emptyMap(),
    val sessionId: String? = null,
    val blockedByPolicy: Boolean = false,
) {
    fun toMap(): Map<String, Any?> {
        return linkedMapOf(
            "requestId" to requestId,
            "action" to action,
            "success" to success,
            "code" to code,
            "message" to message,
            "backend" to backend.name,
            "output" to output,
            "stdout" to stdout,
            "stderr" to stderr,
            "transcript" to transcript,
            "exitCode" to exitCode,
            "requiresConfirmation" to requiresConfirmation,
            "availableActions" to availableActions,
            "data" to data,
            "command" to command,
            "timeoutSeconds" to timeoutSeconds,
            "workingDirectory" to workingDirectory,
            "environment" to environment,
            "sessionId" to sessionId,
            "blockedByPolicy" to blockedByPolicy
        )
    }
}
