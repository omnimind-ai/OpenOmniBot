package cn.com.omnimind.uikit.loader

import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.graphics.PixelFormat
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import cn.com.omnimind.accessibility.service.AssistsService
import cn.com.omnimind.baselib.util.OmniLog
import cn.com.omnimind.uikit.view.data.WindowFlag
import cn.com.omnimind.uikit.view.mask.CancelMask

class CancelClickLoader(override val context: AccessibilityService) :
    OverlayLoader<CancelMask>(context, CancelMask(context)) {
    private val TAG = "[CancelClickLoader]"

    companion object {
        @SuppressLint("StaticFieldLeak")
        @Volatile
        private var INSTANCE: CancelClickLoader? = null

        fun getInstance(): CancelClickLoader? {
            if (AssistsService.instance != null) {
                return INSTANCE ?: synchronized(this) {
                    INSTANCE ?: CancelClickLoader(
                        AssistsService.instance!!
                    ).also { INSTANCE = it }
                }
            }
            return null
        }
        fun interceptingOtherViewClick(clickListener: () -> Unit){
            getInstance()?.interceptingOtherViewClick(clickListener)
        }
        fun cancelIntercepting() {
            getInstance()?.cancelIntercepting()
        }
        fun destroyInstance() {
            INSTANCE?.destroy()
            INSTANCE = null
        }

        fun hideForExternalActivity(): Boolean {
            return getInstance()?.hideForExternalActivity() ?: false
        }

        fun restoreAfterExternalActivity(): Boolean {
            return getInstance()?.restoreAfterExternalActivity() ?: false
        }
    }

    private var hiddenForExternalActivity = false
    private var externalActivityVisibility = View.GONE
    private var externalActivityWasIntercepting = false

    override fun getParams(flagsValue: Int): WindowManager.LayoutParams {
        return WindowManager.LayoutParams().apply {
            // 窗口类型：8.0+ 必须用 TYPE_APPLICATION_OVERLAY
            type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            }
            flags = flagsValue
            format = PixelFormat.TRANSLUCENT // 透明背景（可选，根据蒙层样式调整）
            width = WindowManager.LayoutParams.MATCH_PARENT
            height = WindowManager.LayoutParams.MATCH_PARENT
            gravity = Gravity.TOP or Gravity.START
        }
    }

    fun interceptingOtherViewClick(clickListener: () -> Unit) {
        load(WindowFlag.SCREEN_LOCK_FLAG)
        view.visibility = View.VISIBLE
        view.setCancelListener(clickListener)
    }

    fun cancelIntercepting() {
        try {
            // 如果 view 还没有附加到窗口，说明还没有调用过 interceptingOtherViewClick()
            // 这种情况下不需要取消拦截，因为本来就没有在拦截状态
            // 静默返回，不打印警告（这是正常情况，不是错误）
            if (!isAttachedToWindow) {
                // 使用 debug 级别而不是 warning，因为这是正常的初始化状态
                OmniLog.d(
                    TAG,
                    "View not attached to window, skip cancelIntercepting (view not initialized yet)"
                )
                return
            }

            // 检查 view 的实际附加状态（双重检查，更安全）
            if (view.windowToken == null) {
                // view 已经被移除，更新状态
                isAttachedToWindow = false
                OmniLog.d(TAG, "View windowToken is null, update isAttachedToWindow to false")
                return
            }

            load(WindowFlag.SCREEN_UNLOCK_FLAG)
            view.visibility = View.GONE
        } catch (e: Exception) {
            OmniLog.e(TAG, "cancelIntercepting failed: ${e.message}", e)
            // 如果出现异常，可能是 view 已经被移除，更新状态
            if (e is IllegalStateException || e.message?.contains("not attached") == true) {
                isAttachedToWindow = false
            }
        }
    }

    fun hideForExternalActivity(): Boolean {
        if (hiddenForExternalActivity) {
            return true
        }
        if (!isAttachedToWindow || view.windowToken == null) {
            return false
        }

        externalActivityVisibility = view.visibility
        externalActivityWasIntercepting = view.visibility == View.VISIBLE

        return try {
            load(WindowFlag.SCREEN_UNLOCK_FLAG)
            view.visibility = View.GONE
            hiddenForExternalActivity = true
            OmniLog.d(TAG, "Cancel click mask hidden for external activity")
            true
        } catch (e: Exception) {
            OmniLog.e(TAG, "hideForExternalActivity failed: ${e.message}", e)
            false
        }
    }

    fun restoreAfterExternalActivity(): Boolean {
        if (!hiddenForExternalActivity) {
            return false
        }

        return try {
            load(
                if (externalActivityWasIntercepting) {
                    WindowFlag.SCREEN_LOCK_FLAG
                } else {
                    WindowFlag.SCREEN_UNLOCK_FLAG
                }
            )
            view.visibility = externalActivityVisibility
            hiddenForExternalActivity = false
            OmniLog.d(TAG, "Cancel click mask restored after external activity")
            true
        } catch (e: Exception) {
            OmniLog.e(TAG, "restoreAfterExternalActivity failed: ${e.message}", e)
            false
        }
    }

    override fun destroy() {
        hiddenForExternalActivity = false
        super.destroy()
    }

}
