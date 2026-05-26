package cn.com.omnimind.uikit.loader

import android.annotation.SuppressLint
import android.app.Service
import android.graphics.PixelFormat
import android.graphics.Point
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import cn.com.omnimind.accessibility.service.AssistsService
import cn.com.omnimind.baselib.util.OmniLog
import cn.com.omnimind.uikit.view.data.WindowFlag
import cn.com.omnimind.uikit.view.mask.BlockUserTouchMask


class ScreenMaskLoader(override val context: Service) :
    OverlayLoader<BlockUserTouchMask>(context, BlockUserTouchMask(context)) {
    val TAG = "[ScreenMaskLoader]"


    companion object {
        @SuppressLint("StaticFieldLeak")
        @Volatile
        private var INSTANCE: ScreenMaskLoader? = null
        private var lockFlag = WindowFlag.SCREEN_UNLOCK_FLAG
        private var visibility = View.GONE

        fun getInstance(): ScreenMaskLoader? {
            if (AssistsService.instance != null) {
                return INSTANCE ?: synchronized(this) {
                    INSTANCE ?: ScreenMaskLoader(
                        AssistsService.instance!!
                    ).also { INSTANCE = it }
                }
            }
            return null
        }

        fun gone() {
            getInstance()?.load(WindowFlag.SCREEN_UNLOCK_FLAG)
            getInstance()?.view?.visibility = View.GONE
        }

        fun visiable() {
            getInstance()?.toLoad()
        }

        fun loadLockScreenMask() {
            getInstance()?.loadLockScreenMask()
        }

        fun loadUnlockScreenMask() {
            getInstance()?.loadUnlockScreenMask()
        }

        fun loadGoneViewScreenMask() {
            getInstance()?.loadGoneViewScreenMask()
        }
        fun loadLockScreenMask(x: Int, y: Int) {
            getInstance()?.loadLockScreenMask(x,y)
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
    private var externalActivityLockFlag = WindowFlag.SCREEN_UNLOCK_FLAG
    private var externalActivityVisibility = View.GONE

    override fun getParams(flagsValue: Int): WindowManager.LayoutParams {
        return WindowManager.LayoutParams().apply {
            // 窗口类型：8.0+ 必须用 TYPE_APPLICATION_OVERLAY
            type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
            } else {
                @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE
            }
            val screenSize = Point()
            getWindowManager().defaultDisplay.getRealSize(screenSize)
            flags = flagsValue
            format = PixelFormat.TRANSLUCENT // 透明背景（可选，根据蒙层样式调整）
            width = WindowManager.LayoutParams.MATCH_PARENT
            height = screenSize.y
            gravity = Gravity.TOP or Gravity.START
            alpha = 0.8f


        }
    }


    fun loadLockScreenMask() {
        lockFlag = WindowFlag.SCREEN_LOCK_FLAG
        visibility = View.VISIBLE
        toLoad()
    }

    fun loadLockScreenMask(x: Int, y: Int) {
        loadLockScreenMask()
        view.startCircleAnimation(x, y)
    }

    fun loadUnlockScreenMask() {
        lockFlag = WindowFlag.SCREEN_UNLOCK_FLAG
        visibility = View.VISIBLE
        toLoad()
    }

    fun loadGoneViewScreenMask() {
        lockFlag = WindowFlag.SCREEN_UNLOCK_FLAG
        visibility = View.GONE
        view.visibility = View.VISIBLE
        toLoad()
    }

    fun toLoad() {
        load(lockFlag)
        view.visibility = visibility
    }

    fun hideForExternalActivity(): Boolean {
        if (hiddenForExternalActivity) {
            return true
        }
        if (!isAttachedToWindow || view.windowToken == null) {
            return false
        }

        externalActivityLockFlag = lockFlag
        externalActivityVisibility = visibility
        lockFlag = WindowFlag.SCREEN_UNLOCK_FLAG
        visibility = View.GONE

        return try {
            toLoad()
            hiddenForExternalActivity = true
            OmniLog.d(TAG, "Screen mask hidden for external activity")
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

        lockFlag = externalActivityLockFlag
        visibility = externalActivityVisibility

        return try {
            toLoad()
            hiddenForExternalActivity = false
            OmniLog.d(TAG, "Screen mask restored after external activity")
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
