package cn.com.omnimind.bot.omniinfer

/**
 * Download state constants – local replacement for com.alibaba.mls.api.download.DownloadState.
 * Numeric values are kept identical so the Flutter UI "download.state" field remains compatible.
 */
object MnnDownloadState {
    const val NOT_START = 0
    const val DOWNLOADING = 1
    const val DOWNLOAD_SUCCESS = 2
    const val DOWNLOAD_FAILED = 3
    const val DOWNLOAD_PAUSED = 4
    const val DOWNLOAD_CANCELLED = 5
    const val PREPARING = 6
}

/**
 * Download progress snapshot – local replacement for com.alibaba.mls.api.download.DownloadInfo.
 */
data class MnnDownloadInfo(
    var downloadState: Int = MnnDownloadState.NOT_START,
    var progress: Double = 0.0,
    var savedSize: Long = 0L,
    var totalSize: Long = 0L,
    var speedInfo: String = "",
    var errorMessage: String? = null,
    var progressStage: String = "",
    var currentFile: String? = null,
    var downloadedTime: Long = 0L,
    var hasUpdate: Boolean = false,
)

/**
 * Available model download sources – local replacement for com.alibaba.mls.api.source.ModelSources.
 */
object MnnModelSources {
    const val sourceHuggingFace = "HuggingFace"
    const val sourceModelScope = "ModelScope"
    const val sourceModelers = "Modelers"
    val sourceList: List<String> = listOf(sourceHuggingFace, sourceModelScope, sourceModelers)
}
