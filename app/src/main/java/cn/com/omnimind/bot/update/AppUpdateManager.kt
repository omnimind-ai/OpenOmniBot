package cn.com.omnimind.bot.update

import android.content.Context
import androidx.annotation.VisibleForTesting
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import cn.com.omnimind.baselib.service.DeviceInfoService
import cn.com.omnimind.baselib.util.OmniLog
import cn.com.omnimind.bot.manager.ExternalApkInstallResult
import cn.com.omnimind.bot.manager.ExternalApkInstaller
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import java.io.IOException
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.Locale
import java.util.concurrent.TimeUnit

data class AppUpdateState(
    val currentVersion: String,
    val latestVersion: String,
    val hasUpdate: Boolean,
    val checkedAt: Long,
    val publishedAt: Long,
    val releaseUrl: String,
    val releaseNotes: String,
    val apkName: String,
    val apkDownloadUrl: String
) {
    fun toMap(): Map<String, Any> = mapOf(
        "currentVersion" to currentVersion,
        "latestVersion" to latestVersion,
        "hasUpdate" to hasUpdate,
        "checkedAt" to checkedAt,
        "publishedAt" to publishedAt,
        "releaseUrl" to releaseUrl,
        "releaseNotes" to releaseNotes,
        "apkName" to apkName,
        "apkDownloadUrl" to apkDownloadUrl
    )
}

@VisibleForTesting
internal data class ReleaseAsset(
    val name: String,
    val downloadUrl: String
)

@VisibleForTesting
internal enum class ApkDownloadSource(val value: String) {
    CNB("cnb"),
    GITHUB("github");

    companion object {
        fun fromValue(raw: String?): ApkDownloadSource {
            return entries.firstOrNull { it.value.equals(raw?.trim(), ignoreCase = true) } ?: CNB
        }
    }
}

@VisibleForTesting
internal enum class ReleaseTrack {
    STABLE,
    BETA,
    UNSUPPORTED
}

@VisibleForTesting
internal data class ReleaseCandidate(
    val version: String,
    val track: ReleaseTrack,
    val publishedAt: Long,
    val releaseUrl: String,
    val releaseNotes: String,
    val assets: List<ReleaseAsset>
)

object AppUpdateManager {
    private const val TAG = "AppUpdateManager"
    private const val PREFS_NAME = "app_update_state"
    private const val KEY_BETA_OPT_IN = "beta_opt_in"
    private const val KEY_LATEST_VERSION = "latest_version"
    private const val KEY_HAS_UPDATE = "has_update"
    private const val KEY_CHECKED_AT = "checked_at"
    private const val KEY_PUBLISHED_AT = "published_at"
    private const val KEY_RELEASE_URL = "release_url"
    private const val KEY_RELEASE_NOTES = "release_notes"
    private const val KEY_APK_NAME = "apk_name"
    private const val KEY_APK_DOWNLOAD_URL = "apk_download_url"
    private const val KEY_APK_DOWNLOAD_SOURCE = "apk_download_source"

    private const val RELEASES_URL =
        "https://api.github.com/repos/omnimind-ai/OpenOmniBot/releases?per_page=30"
    private const val GITHUB_RELEASE_DOWNLOAD_PREFIX =
        "https://github.com/omnimind-ai/OpenOmniBot/releases/download"
    private const val CNB_RELEASE_DOWNLOAD_PREFIX =
        "https://cnb.cool/o.a/OpenOmniBot/-/releases/download"
    private const val WORK_NAME = "app_update_periodic_check"
    private const val PERIODIC_CHECK_HOURS = 12L
    private const val SILENT_CHECK_INTERVAL_MS = 6 * 60 * 60 * 1000L
    private const val USER_AGENT = "OpenOmniBot-App"

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .writeTimeout(20, TimeUnit.SECONDS)
            .build()
    }

    fun schedulePeriodicChecks(context: Context) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val request = PeriodicWorkRequestBuilder<AppUpdateWorker>(
            PERIODIC_CHECK_HOURS,
            TimeUnit.HOURS
        )
            .setConstraints(constraints)
            .build()

        WorkManager.getInstance(context.applicationContext)
            .enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
    }

    fun requestSilentCheckIfDue(context: Context) {
        schedulePeriodicChecks(context)
        CoroutineScope(Dispatchers.IO).launch {
            runCatching {
                checkNow(context.applicationContext, force = false)
            }.onFailure {
                OmniLog.w(TAG, "Silent app update check failed: ${it.message}")
            }
        }
    }

    fun getCachedStatus(context: Context): AppUpdateState {
        val appContext = context.applicationContext
        return readState(
            context = appContext,
            currentVersion = currentVersion(appContext),
            includeBeta = isBetaOptIn(appContext)
        )
    }

    fun isBetaOptIn(context: Context): Boolean {
        return context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_BETA_OPT_IN, false)
    }

    internal fun getApkDownloadSource(context: Context): ApkDownloadSource {
        val rawValue = context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_APK_DOWNLOAD_SOURCE, null)
        return ApkDownloadSource.fromValue(rawValue)
    }

    fun setBetaOptIn(context: Context, enabled: Boolean): Boolean {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val changed = prefs.getBoolean(KEY_BETA_OPT_IN, false) != enabled
        prefs.edit().apply {
            putBoolean(KEY_BETA_OPT_IN, enabled)
            if (changed) {
                putLong(KEY_CHECKED_AT, 0L)
            }
        }.apply()
        return enabled
    }

    internal fun setApkDownloadSource(context: Context, rawValue: String?): ApkDownloadSource {
        val source = ApkDownloadSource.fromValue(rawValue)
        context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_APK_DOWNLOAD_SOURCE, source.value)
            .apply()
        return source
    }

    suspend fun checkNow(context: Context, force: Boolean): AppUpdateState {
        val appContext = context.applicationContext
        val now = System.currentTimeMillis()
        val currentVersion = currentVersion(appContext)
        val includeBeta = isBetaOptIn(appContext)
        val downloadSource = getApkDownloadSource(appContext)
        val cached = readState(appContext, currentVersion, includeBeta)
        if (!force && now - cached.checkedAt < SILENT_CHECK_INTERVAL_MS) {
            return cached
        }

        val fetched = fetchLatestReleaseState(currentVersion, includeBeta, downloadSource)
            .copy(checkedAt = now)
        saveState(appContext, fetched)
        return fetched
    }

    suspend fun installLatestApk(context: Context): ExternalApkInstallResult {
        val installState = resolveInstallState(context)
        if (!installState.hasUpdate || installState.apkDownloadUrl.isBlank()) {
            return ExternalApkInstallResult(
                success = false,
                status = ExternalApkInstaller.STATUS_INSTALL_FAILED,
                message = "当前没有可安装的新版本。"
            )
        }

        val safeFileName = installState.apkName.ifBlank {
            "OpenOmniBot-v${installState.latestVersion}.apk"
        }
        return ExternalApkInstaller.downloadAndInstall(
            context = context,
            downloadUrl = installState.apkDownloadUrl,
            apkFileName = safeFileName,
            displayName = "OpenOmniBot"
        )
    }

    @VisibleForTesting
    internal fun normalizeVersion(raw: String?): String {
        return raw
            ?.trim()
            ?.removePrefix("v")
            ?.removePrefix("V")
            ?.substringBefore('+')
            ?.trim()
            .orEmpty()
    }

    @VisibleForTesting
    internal fun compareVersions(leftRaw: String?, rightRaw: String?): Int {
        val left = normalizeVersion(leftRaw)
        val right = normalizeVersion(rightRaw)
        if (left == right) return 0

        val leftParts = parseNumericVersionParts(left)
        val rightParts = parseNumericVersionParts(right)
        if (leftParts != null && rightParts != null) {
            val maxLength = maxOf(leftParts.size, rightParts.size)
            for (index in 0 until maxLength) {
                val leftValue = leftParts.getOrElse(index) { 0 }
                val rightValue = rightParts.getOrElse(index) { 0 }
                if (leftValue != rightValue) {
                    return leftValue.compareTo(rightValue)
                }
            }
            return 0
        }

        return left.compareTo(right)
    }

    @VisibleForTesting
    internal fun versionSegmentCount(raw: String?): Int {
        val normalized = normalizeVersion(raw)
        if (normalized.isBlank()) return 0
        val parts = normalized.split('.')
        if (parts.any { part -> part.isBlank() || part.any { !it.isDigit() } }) {
            return 0
        }
        return parts.size
    }

    @VisibleForTesting
    internal fun classifyReleaseTrack(rawVersion: String?, prerelease: Boolean = false): ReleaseTrack {
        if (prerelease) {
            return ReleaseTrack.BETA
        }
        return when (versionSegmentCount(rawVersion)) {
            3 -> ReleaseTrack.STABLE
            4 -> ReleaseTrack.BETA
            else -> ReleaseTrack.UNSUPPORTED
        }
    }

    @VisibleForTesting
    internal fun selectLatestRelease(
        candidates: List<ReleaseCandidate>,
        includeBeta: Boolean
    ): ReleaseCandidate? {
        var selected: ReleaseCandidate? = null
        for (candidate in candidates) {
            if (!shouldIncludeTrack(candidate.track, includeBeta)) continue
            val currentSelected = selected
            if (
                currentSelected == null ||
                compareVersions(candidate.version, currentSelected.version) > 0 ||
                (
                    compareVersions(candidate.version, currentSelected.version) == 0 &&
                        candidate.publishedAt > currentSelected.publishedAt
                    )
            ) {
                selected = candidate
            }
        }
        return selected
    }

    @VisibleForTesting
    internal fun selectPreferredApkAsset(assets: List<ReleaseAsset>): ReleaseAsset? {
        if (assets.isEmpty()) return null
        val preferred = assets.firstOrNull {
            it.name.startsWith("OpenOmniBot-v", ignoreCase = true) &&
                it.name.lowercase(Locale.ROOT).endsWith(".apk")
        }
        if (preferred != null) return preferred
        return assets.firstOrNull { it.name.lowercase(Locale.ROOT).endsWith(".apk") }
    }

    private suspend fun resolveInstallState(context: Context): AppUpdateState {
        val cached = getCachedStatus(context)
        if (cached.hasUpdate && cached.apkDownloadUrl.isNotBlank()) {
            return cached
        }
        return checkNow(context, force = true)
    }

    private fun readState(context: Context, currentVersion: String, includeBeta: Boolean): AppUpdateState {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val storedState = AppUpdateState(
            currentVersion = currentVersion,
            latestVersion = prefs.getString(KEY_LATEST_VERSION, currentVersion).orEmpty().ifBlank {
                currentVersion
            },
            hasUpdate = prefs.getBoolean(KEY_HAS_UPDATE, false),
            checkedAt = prefs.getLong(KEY_CHECKED_AT, 0L),
            publishedAt = prefs.getLong(KEY_PUBLISHED_AT, 0L),
            releaseUrl = prefs.getString(KEY_RELEASE_URL, "").orEmpty(),
            releaseNotes = prefs.getString(KEY_RELEASE_NOTES, "").orEmpty(),
            apkName = prefs.getString(KEY_APK_NAME, "").orEmpty(),
            apkDownloadUrl = prefs.getString(KEY_APK_DOWNLOAD_URL, "").orEmpty()
        )
        val stateWithPreferredSource = applyPreferredDownloadSource(
            storedState,
            getApkDownloadSource(context)
        )
        if (!shouldIncludeTrack(classifyReleaseTrack(stateWithPreferredSource.latestVersion), includeBeta)) {
            return emptyState(currentVersion = currentVersion, checkedAt = stateWithPreferredSource.checkedAt)
        }
        return stateWithPreferredSource.copy(
            hasUpdate = stateWithPreferredSource.hasUpdate &&
                compareVersions(stateWithPreferredSource.latestVersion, currentVersion) > 0
        )
    }

    private fun saveState(context: Context, state: AppUpdateState) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_LATEST_VERSION, state.latestVersion)
            .putBoolean(KEY_HAS_UPDATE, state.hasUpdate)
            .putLong(KEY_CHECKED_AT, state.checkedAt)
            .putLong(KEY_PUBLISHED_AT, state.publishedAt)
            .putString(KEY_RELEASE_URL, state.releaseUrl)
            .putString(KEY_RELEASE_NOTES, state.releaseNotes)
            .putString(KEY_APK_NAME, state.apkName)
            .putString(KEY_APK_DOWNLOAD_URL, state.apkDownloadUrl)
            .apply()
    }

    private fun currentVersion(context: Context): String {
        return DeviceInfoService.getAppVersion(context)["versionName"]?.toString()
            ?.trim()
            ?.ifBlank { "0.0.0" }
            ?: "0.0.0"
    }

    private fun fetchLatestReleaseState(
        currentVersion: String,
        includeBeta: Boolean,
        downloadSource: ApkDownloadSource
    ): AppUpdateState {
        val request = Request.Builder()
            .url(RELEASES_URL)
            .addHeader("Accept", "application/vnd.github+json")
            .addHeader("X-GitHub-Api-Version", "2022-11-28")
            .addHeader("User-Agent", USER_AGENT)
            .get()
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("GitHub release request failed with code ${response.code}")
            }

            val body = response.body?.string().orEmpty()
            if (body.isBlank()) {
                throw IOException("GitHub release response body is empty")
            }

            val selectedRelease = selectLatestRelease(
                candidates = parseReleaseCandidates(JSONArray(body)),
                includeBeta = includeBeta
            ) ?: return emptyState(currentVersion, checkedAt = System.currentTimeMillis())
            val preferredAsset = selectPreferredApkAsset(selectedRelease.assets)

            return AppUpdateState(
                currentVersion = currentVersion,
                latestVersion = selectedRelease.version,
                hasUpdate = compareVersions(selectedRelease.version, currentVersion) > 0,
                checkedAt = System.currentTimeMillis(),
                publishedAt = selectedRelease.publishedAt,
                releaseUrl = selectedRelease.releaseUrl,
                releaseNotes = selectedRelease.releaseNotes,
                apkName = preferredAsset?.name.orEmpty(),
                apkDownloadUrl = preferredAsset?.let {
                    resolveApkDownloadUrl(downloadSource, selectedRelease.version, it)
                }.orEmpty()
            )
        }
    }

    private fun applyPreferredDownloadSource(
        state: AppUpdateState,
        downloadSource: ApkDownloadSource
    ): AppUpdateState {
        if (state.latestVersion.isBlank() || state.apkName.isBlank()) {
            return state
        }
        return state.copy(
            apkDownloadUrl = resolveApkDownloadUrl(
                downloadSource = downloadSource,
                version = state.latestVersion,
                asset = ReleaseAsset(
                    name = state.apkName,
                    downloadUrl = state.apkDownloadUrl
                )
            )
        )
    }

    @VisibleForTesting
    internal fun resolveApkDownloadUrl(
        downloadSource: ApkDownloadSource,
        version: String,
        asset: ReleaseAsset
    ): String {
        if (asset.name.isBlank()) {
            return asset.downloadUrl
        }
        val normalizedVersion = normalizeVersion(version)
        if (normalizedVersion.isBlank()) {
            return asset.downloadUrl
        }
        val releaseTag = "v${encodePathSegment(normalizedVersion)}"
        val fileName = encodePathSegment(asset.name)
        val prefix = when (downloadSource) {
            ApkDownloadSource.CNB -> CNB_RELEASE_DOWNLOAD_PREFIX
            ApkDownloadSource.GITHUB -> GITHUB_RELEASE_DOWNLOAD_PREFIX
        }
        return "$prefix/$releaseTag/$fileName"
    }

    private fun parseReleaseCandidates(array: JSONArray?): List<ReleaseCandidate> {
        if (array == null) return emptyList()
        val candidates = mutableListOf<ReleaseCandidate>()
        for (index in 0 until array.length()) {
            val raw = array.optJSONObject(index) ?: continue
            if (raw.optBoolean("draft")) continue
            val version = normalizeVersion(raw.optString("tag_name"))
            val track = classifyReleaseTrack(version, prerelease = raw.optBoolean("prerelease"))
            if (track == ReleaseTrack.UNSUPPORTED || version.isBlank()) continue
            candidates += ReleaseCandidate(
                version = version,
                track = track,
                publishedAt = parseGithubTimeToMillis(raw.optString("published_at")),
                releaseUrl = raw.optString("html_url"),
                releaseNotes = raw.optString("body"),
                assets = parseAssets(raw.optJSONArray("assets"))
            )
        }
        return candidates
    }

    private fun parseAssets(array: JSONArray?): List<ReleaseAsset> {
        if (array == null) return emptyList()
        val assets = mutableListOf<ReleaseAsset>()
        for (index in 0 until array.length()) {
            val raw = array.optJSONObject(index) ?: continue
            val name = raw.optString("name")
            if (!name.lowercase(Locale.ROOT).endsWith(".apk")) continue
            val downloadUrl = raw.optString("browser_download_url")
            if (downloadUrl.isBlank()) continue
            assets += ReleaseAsset(name = name, downloadUrl = downloadUrl)
        }
        return assets
    }

    private fun parseGithubTimeToMillis(raw: String?): Long {
        if (raw.isNullOrBlank()) return 0L
        return runCatching { Instant.parse(raw).toEpochMilli() }.getOrDefault(0L)
    }

    private fun encodePathSegment(raw: String): String {
        return URLEncoder.encode(raw, StandardCharsets.UTF_8.toString())
            .replace("+", "%20")
    }

    private fun emptyState(currentVersion: String, checkedAt: Long = 0L): AppUpdateState {
        return AppUpdateState(
            currentVersion = currentVersion,
            latestVersion = currentVersion,
            hasUpdate = false,
            checkedAt = checkedAt,
            publishedAt = 0L,
            releaseUrl = "",
            releaseNotes = "",
            apkName = "",
            apkDownloadUrl = ""
        )
    }

    private fun parseNumericVersionParts(raw: String): List<Int>? {
        if (raw.isBlank()) return null
        val parts = raw.split('.')
        if (parts.any { part -> part.isBlank() || part.any { !it.isDigit() } }) {
            return null
        }
        return parts.map { it.toInt() }
    }

    private fun shouldIncludeTrack(track: ReleaseTrack, includeBeta: Boolean): Boolean {
        return when (track) {
            ReleaseTrack.STABLE -> true
            ReleaseTrack.BETA -> includeBeta
            ReleaseTrack.UNSUPPORTED -> false
        }
    }
}

class AppUpdateWorker(
    appContext: Context,
    workerParams: androidx.work.WorkerParameters
) : CoroutineWorker(appContext, workerParams) {
    override suspend fun doWork(): androidx.work.ListenableWorker.Result {
        return runCatching {
            AppUpdateManager.checkNow(applicationContext, force = true)
            androidx.work.ListenableWorker.Result.success()
        }.getOrElse {
            OmniLog.w("AppUpdateWorker", "Periodic app update check failed: ${it.message}")
            androidx.work.ListenableWorker.Result.retry()
        }
    }
}
