package cn.com.omnimind.assists.controller.accessibility

import BaseApplication
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import cn.com.omnimind.accessibility.action.AccessibilityScrollDirection
import cn.com.omnimind.accessibility.action.OmniAction
import cn.com.omnimind.accessibility.action.OmniCaptureAction
import cn.com.omnimind.accessibility.action.OmniScreenshotAction
import cn.com.omnimind.accessibility.action.ScreenCaptureManager
import cn.com.omnimind.accessibility.service.AssistsService
import cn.com.omnimind.accessibility.service.AssistsServiceListener
import cn.com.omnimind.assists.AssistsCore
import cn.com.omnimind.assists.api.bean.CaptureData
import cn.com.omnimind.assists.detection.scenarios.stability.PageStabilityDetector
import cn.com.omnimind.assists.detection.state.SystemNotificationStateManager
import cn.com.omnimind.baselib.util.ImageCompressor
import cn.com.omnimind.baselib.util.ImageQuality
import cn.com.omnimind.baselib.util.ImageUtils
import cn.com.omnimind.baselib.util.OmniLog
import cn.com.omnimind.baselib.util.exception.PermissionException
import cn.com.omnimind.omniintelligence.models.HostResponse
import cn.com.omnimind.omniintelligence.models.ScrollDirection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.future.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/**
 * 控制器辅助类
 */
class AccessibilityController() {

    companion object {
        const val TAG = "[ControllerHelper]"
        private var actionController: OmniAction? = null;
        private var captureAction: OmniCaptureAction? = null;
        private var service: AssistsService? = null;


        private var screenshotAction: OmniScreenshotAction? = null

        /**
         * 初始化控制器
         * 需注意初始化时机,保证AssistsService以运行后初始化
         */
        fun initController(): Boolean {
            if (service != null || AssistsService.instance == null) {
                return false
            }
            this.service = AssistsService.instance!!;
            actionController = OmniAction(service!!)
            captureAction = OmniCaptureAction(service!!)
            screenshotAction = OmniScreenshotAction(service!!)
            AssistsService.addListener(object : AssistsServiceListener {
                override fun onAccessibilityEvent(event: AccessibilityEvent) {
                    captureAction!!.onAccessibilityEvent(event)

                    // 将事件传递给系统通知状态管理器处理
                    SystemNotificationStateManager.handleAccessibilityEvent(event)

                }
            })

            return true
        }

        fun hideKeyboard() {
            service?.hideKeyboard()
        }

        fun restoreKeyboard() {
            service?.restoreKeyboard()
        }

        private fun checkAccessibilityPermissions() {
            if (!AssistsCore.isAccessibilityServiceEnabled()) {
                throw PermissionException("无障碍服务未启用或权限未授予!")
            }
        }

        //
        suspend fun inputText(
            nodeId: String, text: String
        ) {
            val node = captureAction?.getNodeMap()?.get(nodeId)?.info
                ?: throw IllegalArgumentException("Node with ID '$nodeId' not found.")
            actionController?.inputText(node, text)
        }

        suspend fun inputTextToFocusedNode(text: String) {
            val focusedNode =
                captureAction?.findFocusedNode()
                    ?: throw NoFocusedNodeException()
            actionController?.inputText(focusedNode, text)
        }

        suspend fun pressHotKey(key: String) {
            when (key.trim().uppercase()) {
                "ENTER" -> {
                    val focusedNode =
                        captureAction?.findFocusedNode()
                            ?: throw NoFocusedNodeException()
                    actionController?.performImeEnter(focusedNode)
                        ?: throw IllegalStateException("Accessibility action controller is not ready")
                }

                "BACK" -> goBack()
                "HOME" -> goHome()
                else -> throw IllegalArgumentException("Unsupported hot key: $key")
            }
        }

        // 剪贴板回调
        private var clipboardCopyCallback: ((Boolean) -> Unit)? = null

        /**
         * 供 ClipboardHelperActivity 调用，通知复制结果
         */
        @JvmStatic
        fun notifyClipboardCopyResult(success: Boolean) {
            clipboardCopyCallback?.invoke(success)
            clipboardCopyCallback = null
        }

        /**
         * 复制文本到剪贴板
         * 使用 ClipboardHelperActivity 确保 Android 10+ 上的稳定性
         */
        suspend fun copyToClipboard(text: String) {
            val success = kotlinx.coroutines.withTimeoutOrNull(5000L) {
                kotlinx.coroutines.suspendCancellableCoroutine { continuation ->
                    clipboardCopyCallback = { result ->
                        if (continuation.isActive) continuation.resume(result, null)
                    }
                    try {
                        val intent = android.content.Intent().apply {
                            setClassName(
                                BaseApplication.instance.packageName,
                                "cn.com.omnimind.bot.activity.ClipboardHelperActivity"
                            )
                            putExtra("clipboard_text", text)
                            putExtra("clipboard_operation", "copy")
                            putExtra("callback_target", "AccessibilityController")
                            addFlags(
                                android.content.Intent.FLAG_ACTIVITY_NEW_TASK or
                                android.content.Intent.FLAG_ACTIVITY_NO_ANIMATION or
                                android.content.Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
                            )
                        }
                        BaseApplication.instance.startActivity(intent)
                    } catch (e: Exception) {
                        clipboardCopyCallback = null
                        OmniLog.e(TAG, "copyToClipboard failed to start activity: ${e.message}")
                        if (continuation.isActive) continuation.resume(false, null)
                    }
                }
            } ?: false

            if (!success) {
                throw IllegalStateException("Failed to copy to clipboard")
            }
        }


        suspend fun clickCoordinate(
            x: Float, y: Float
        ) {
            checkAccessibilityPermissions()
            withTimeout(2000) {
                actionController?.clickCoordinate(x, y)?.await()

            }
        }

        suspend fun longClickCoordinate(
            x: Float, y: Float, duration: Long = 1000L
        ) {
            checkAccessibilityPermissions()
            withTimeout(2000 + duration) {
                actionController?.longClickCoordinate(x, y, duration)?.await()
            }
        }

        suspend fun scrollCoordinate(
            x: Float, y: Float, direction: ScrollDirection, distance: Float, duration: Long = 500L
        ) {
            var mDirection = when (direction) {
                ScrollDirection.UP -> {
                    AccessibilityScrollDirection.UP
                }

                ScrollDirection.DOWN -> {
                    AccessibilityScrollDirection.DOWN
                }

                ScrollDirection.LEFT -> {
                    AccessibilityScrollDirection.LEFT
                }

                ScrollDirection.RIGHT -> {
                    AccessibilityScrollDirection.RIGHT
                }
            }
            actionController?.scrollCoordinate(x, y, mDirection, distance, duration)?.await()
        }

        //
        suspend fun goHome() {
            if (actionController == null) {
                OmniLog.w(TAG, "goHome: actionController is null, skip")
                return
            }
            try {
                actionController?.goHome()
            } catch (e: Exception) {
                OmniLog.e(TAG, "goHome failed: ${e.message}", e)
            }
        }

        suspend fun goBack() {
            actionController?.goBack()
        }

        fun getPackageName(): String? {
            return captureAction?.getCurrentPackageName()
        }

        fun getCaptureScreenShotXml(withOld: Boolean = true): String? {
            return captureAction?.captureScreenshotXml(withOld)
        }

        fun getCurrentActivity(): String? {
            return captureAction?.getCurrentActivity()
        }

        suspend fun launchApplication(
            packageName: String, doClickInvoke: suspend (x: Float, y: Float) -> Unit
        ) {
            checkAccessibilityPermissions()
            actionController?.launchApplication(packageName)
            OmniLog.d("[Omni] Running", "before awaitStability")
            PageStabilityDetector.awaitStability()
            OmniLog.d("[Omni] Running", "after awaitStability")

            if (getPackageName() == packageName) {
                return
            }
            OmniLog.w(
                TAG,
                "launchApplication did not reach target package after stability wait: $packageName"
            )
        }

        suspend fun captureScreenshotImage(
            isBitmap: Boolean = true,
            isBase64: Boolean = true,
            isFile: Boolean = false,
            isFilterOverlay: Boolean = true,
            isCheckSingleColor: Boolean = false,
            isCheckMostlyLightBackground: Boolean = false,
            isCheckSideRegionMostlySingleColor: Boolean = false,
            compressQuality: ImageQuality? = null    // null = 不压缩
        ): CaptureData {
            val image = if (isFilterOverlay) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    screenshotAction?.captureExcludingOverlaysV14()

                } else {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        screenshotAction?.captureDefaultScreenshot({
                            AssistsCore.screenshotImageEventApi?.onScreenShotHideOverlay()
                        }, {
                            AssistsCore.screenshotImageEventApi?.onScreenShotShowOverlay()
                        })
                    } else {
                        withContext(Dispatchers.Main) {
                            AssistsCore.screenshotImageEventApi?.onScreenShotHideOverlay()

                        }
                        delay(100)
                        val bitmap = ScreenCaptureManager.getInstance().captureOnce()
                        withContext(Dispatchers.Main) {
                            AssistsCore.screenshotImageEventApi?.onScreenShotShowOverlay()

                        }
                        bitmap
                    }
                }
            } else {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    screenshotAction?.captureDefaultScreenshot()
                } else {
                    ScreenCaptureManager.getInstance().captureOnce()
                }
            }
            if (image == null) {
                return CaptureData(
                    isSuccess = false,
                    isFilterOverlay = false,
                    isLotOfSingleColor = false,
                    isMostlyLightBackground = false,
                    imageFilePath = null,
                    imageBase64 = null,
                    imageBitmap = null
                )
            }

            // 获取原始图片尺寸
            val originalWidth = image.width
            val originalHeight = image.height

            val isSingleColor = if (isCheckSingleColor) {
                ImageUtils.isMostlySingleColor(image)
            } else false

            val imageFile = if (isFile) {
                ImageUtils.bitmapToFile(service!!, image)
            } else null

            val isMostlyLightBackground = if (isCheckMostlyLightBackground) {
                ImageUtils.isMostlyLightBackground(image)
            } else false

            val isSideRegionMostlySingleColor = if (isCheckSideRegionMostlySingleColor) {
                ImageUtils.isSideRegionMostlySingleColor(image)
            } else false

            // 处理压缩：直接基于 Bitmap 压缩，避免 Base64 中间转换
            var imageBase64Str: String? = null
            var appliedScale = 1f
            var compressedWidth = originalWidth
            var compressedHeight = originalHeight
            var bitmapToReturn: Bitmap? = null

            if (compressQuality != null) {
                if (isBitmap) {
                    // 需要返回 Bitmap：使用 scaleBitmap 缩放
                    val scaleResult = ImageCompressor.scaleBitmap(image, compressQuality)
                    bitmapToReturn = scaleResult.bitmap
                    appliedScale = scaleResult.appliedScale
                    compressedWidth = scaleResult.scaledWidth
                    compressedHeight = scaleResult.scaledHeight

                    // 如果需要 Base64，从缩放后的 bitmap 生成
                    if (isBase64) {
                        imageBase64Str = ImageUtils.bitmapToJpegBase64(bitmapToReturn!!)
                    }

                    // 如果缩放产生了新的 bitmap，回收原始 image
                    if (bitmapToReturn != image && !image.isRecycled) {
                        image.recycle()
                    }
                } else {
                    // 不需要返回 Bitmap：直接使用 compressBitmapImage 生成 Base64
                    val compressResult = ImageCompressor.compressBitmapImage(image, compressQuality)
                    if (isBase64) {
                        imageBase64Str = compressResult.base64
                    }
                    appliedScale = compressResult.appliedScale
                    compressedWidth = compressResult.compressedWidth
                    compressedHeight = compressResult.compressedHeight

                    // 回收原始 image（compressBitmapImage 不会回收它）
                    if (!image.isRecycled) {
                        image.recycle()
                    }
                }
            } else {
                // 不压缩：按原有逻辑处理
                if (isBase64) {
                    imageBase64Str = ImageUtils.bitmapToJpegBase64(image)
                }
                if (isBitmap) {
                    bitmapToReturn = image
                } else {
                    image.recycle()
                }
            }

            return CaptureData(
                isSuccess = true,
                isFilterOverlay = isFilterOverlay,
                isLotOfSingleColor = isSingleColor,
                isMostlyLightBackground = isMostlyLightBackground,
                isSideRegionMostlySingleColor = isSideRegionMostlySingleColor,
                imageFilePath = imageFile,
                imageBase64 = imageBase64Str,
                imageBitmap = bitmapToReturn,
                originalWidth = originalWidth,
                originalHeight = originalHeight,
                compressedWidth = compressedWidth,
                compressedHeight = compressedHeight,
                appliedScale = appliedScale
            )
        }

        //
        suspend fun listInstalledApplications(): HostResponse.Payload.ListInstalledApplicationsPayload {
            val (packageNames, applicationNames) = actionController!!.listInstalledApplications()
            return HostResponse.Payload.ListInstalledApplicationsPayload(
                packageNames, applicationNames
            )
        }

        suspend fun mapInstalledApplications(): Map<String, String> {
            val (packageNames, applicationNames) = actionController!!.listInstalledApplications()
            return packageNames.zip(applicationNames).toMap()
        }

        fun destroy() {
            service = null;
            actionController = null;
            captureAction = null
            screenshotAction = null
        }
    }
}
