package cn.com.omnimind.assists.api.bean

enum class VlmTaskTerminalStatus {
    WAITING_INPUT,
    FINISHED,
    ERROR,
    CANCELLED
}

data class VlmTaskTerminalResult(
    val status: VlmTaskTerminalStatus,
    val message: String = "",
    val finishedContent: String? = null,
    val summaryText: String? = null,
    val errorMessage: String? = null,
    val needSummary: Boolean = false,
    val waitingQuestion: String? = null,
    val feedback: String? = null,
    val summaryUnavailable: Boolean = false,
    val simpleRunLogJson: String? = null
)
