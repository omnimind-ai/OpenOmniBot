package cn.com.omnimind.uikit.settings

import android.content.Context
import android.os.Handler
import android.os.Looper
import cn.com.omnimind.baselib.util.OmniLog
import cn.com.omnimind.uikit.loader.CancelClickLoader
import cn.com.omnimind.uikit.loader.FloatingHalfScreenLoader
import cn.com.omnimind.uikit.loader.ScreenMaskLoader
import cn.com.omnimind.uikit.loader.cat.DraggableBallInstance

object CompanionOverlaySettings {
    private const val TAG = "CompanionOverlaySettings"
    private const val FLUTTER_SHARED_PREFS_NAME = "FlutterSharedPreferences"
    private const val KEY_FLOATING_OVERLAY_ENABLED = "flutter.floating_overlay_enabled"

    @Volatile
    private var appContext: Context? = null

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    fun isEnabled(context: Context? = appContext): Boolean {
        val resolvedContext = context ?: return true
        return resolvedContext.applicationContext
            .getSharedPreferences(FLUTTER_SHARED_PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_FLOATING_OVERLAY_ENABLED, true)
    }

    fun setEnabled(context: Context, enabled: Boolean) {
        init(context)
        context.applicationContext
            .getSharedPreferences(FLUTTER_SHARED_PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_FLOATING_OVERLAY_ENABLED, enabled)
            .apply()
        if (!enabled) {
            dismissFloatingUi()
        }
    }

    fun dismissFloatingUi() {
        Handler(Looper.getMainLooper()).post {
            try {
                FloatingHalfScreenLoader.destroyInstance()
                DraggableBallInstance.destroy()
                ScreenMaskLoader.destroyInstance()
                CancelClickLoader.destroyInstance()
            } catch (error: Exception) {
                OmniLog.w(TAG, "dismiss floating UI failed: ${error.message}", error)
            }
        }
    }
}
