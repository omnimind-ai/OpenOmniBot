package cn.com.omnimind.bot.runlog

import BaseApplication
import android.app.Activity
import android.content.Intent
import cn.com.omnimind.assists.controller.accessibility.AccessibilityController
import cn.com.omnimind.baselib.util.APPPackageUtil
import cn.com.omnimind.baselib.util.OmniLog
import cn.com.omnimind.baselib.util.exception.PrivacyBlockedException
import cn.com.omnimind.omniintelligence.models.ScrollDirection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

interface OmniflowActionBackend {
    fun isReady(): Boolean

    suspend fun click(x: Float, y: Float)

    suspend fun longPress(x: Float, y: Float, durationMs: Long)

    suspend fun scroll(
        x: Float,
        y: Float,
        direction: ScrollDirection,
        distance: Float,
        durationMs: Long,
    )

    suspend fun inputTextToFocusedNode(text: String)

    suspend fun launchApplication(packageName: String)

    suspend fun pressHotKey(key: String)

    fun currentXml(): String?

    fun currentPackageName(): String?

    fun currentActivityName(): String?
}

object OmniflowActionRuntime {
    @Volatile
    private var backendOverride: OmniflowActionBackend? = null

    val backend: OmniflowActionBackend
        get() = backendOverride ?: AccessibilityOmniflowActionBackend

    fun useBackendForTesting(backend: OmniflowActionBackend): AutoCloseable {
        backendOverride = backend
        return AutoCloseable {
            if (backendOverride === backend) {
                backendOverride = null
            }
        }
    }
}

private object AccessibilityOmniflowActionBackend : OmniflowActionBackend {
    private const val TAG = "OmniflowActionBackend"

    override fun isReady(): Boolean = AccessibilityController.initController()

    override suspend fun click(x: Float, y: Float) {
        AccessibilityController.clickCoordinate(x, y)
    }

    override suspend fun longPress(x: Float, y: Float, durationMs: Long) {
        AccessibilityController.longClickCoordinate(x, y, durationMs)
    }

    override suspend fun scroll(
        x: Float,
        y: Float,
        direction: ScrollDirection,
        distance: Float,
        durationMs: Long,
    ) {
        AccessibilityController.scrollCoordinate(x, y, direction, distance, durationMs)
    }

    override suspend fun inputTextToFocusedNode(text: String) {
        AccessibilityController.inputTextToFocusedNode(text)
    }

    override suspend fun launchApplication(packageName: String) {
        if (AccessibilityController.initController()) {
            runCatching {
                AccessibilityController.launchApplication(packageName) { x, y ->
                    AccessibilityController.clickCoordinate(x, y)
                }
            }.onSuccess {
                return
            }.onFailure { error ->
                if (error is PrivacyBlockedException) throw error
                OmniLog.w(TAG, "accessibility launchApplication failed: ${error.message}")
            }
        }
        launchApplicationByForegroundIntent(packageName)
    }

    override suspend fun pressHotKey(key: String) {
        AccessibilityController.pressHotKey(key)
    }

    override fun currentXml(): String? =
        if (AccessibilityController.initController()) {
            AccessibilityController.getCaptureScreenShotXml(true)
        } else {
            null
        }

    override fun currentPackageName(): String? =
        if (AccessibilityController.initController()) {
            AccessibilityController.getPackageName()
        } else {
            null
        }

    override fun currentActivityName(): String? =
        if (AccessibilityController.initController()) {
            AccessibilityController.getCurrentActivity()
        } else {
            null
        }

    private suspend fun launchApplicationByForegroundIntent(packageName: String) {
        if (!APPPackageUtil.isPackageAuthorized(packageName)) {
            val appName = APPPackageUtil.getAppName(BaseApplication.instance, packageName)
                .takeIf { it.isNotBlank() }
                ?: packageName
            throw PrivacyBlockedException("应用 $appName 未授权，已被隐私设置限制")
        }
        val started = withContext(Dispatchers.Main) {
            val appContext = BaseApplication.instance
            val startContext = BaseApplication.foregroundActivity ?: appContext
            val launchIntent = appContext.packageManager.getLaunchIntentForPackage(packageName)
                ?: throw IllegalArgumentException(
                    "Application with package name $packageName not found"
                )
            launchIntent.addFlags(
                Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED or
                    Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
            )
            if (startContext !is Activity) {
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startContext.startActivity(launchIntent)
            true
        }
        if (started) {
            delay(800)
        }
    }
}
