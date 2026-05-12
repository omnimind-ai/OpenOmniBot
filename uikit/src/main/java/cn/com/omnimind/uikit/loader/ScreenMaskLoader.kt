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
    }

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


}
