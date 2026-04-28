package cn.com.omnimind.bot.activity

import android.app.Activity
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import cn.com.omnimind.baselib.util.OmniLog
import cn.com.omnimind.bot.quicklog.QuickLogWidgetActionRouter
import kotlin.math.abs

/**
 * 启动页 Activity
 *
 * OSS 版本：直接进入 MainActivity，不再依赖账号与在线协议流程。
 */
class LauncherActivity : Activity() {

    companion object {
        private const val TAG = "LauncherActivity"
        const val STARTUP_PREFS_NAME = "app_startup"
        const val KEY_EMBEDDED_TERMINAL_FIRST_LAUNCH_INIT_PENDING =
            "embedded_terminal_first_launch_init_pending"
        const val EXTRA_PREPARE_EMBEDDED_TERMINAL_ON_FIRST_LAUNCH =
            "prepare_embedded_terminal_on_first_launch"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(StartupThemeResolver.resolveSplashTheme(this))
        applyResponsiveOrientation()
        super.onCreate(savedInstanceState)
        OmniLog.d(TAG, "LauncherActivity onCreate")
        if (QuickLogWidgetActionRouter.consumeInto(this, intent)) {
            finish()
            return
        }
        showLoadingAndStartMain()
    }

    private fun applyResponsiveOrientation() {
        val isTablet = resources.configuration.smallestScreenWidthDp >= 600
        requestedOrientation = if (isTablet) {
            ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        } else {
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
    }

    /**
     * 展示 Loading 动画并启动 MainActivity
     * 使用主题的 windowBackground 作为等待背景，Flutter 渲染完成后自然覆盖
     */
    private fun showLoadingAndStartMain() {
        OmniLog.d(TAG, "showLoadingAndStartMain")
        // 主题已设置 windowBackground，无需额外布局
        // 启动 MainActivity（Flutter 引擎）
        startMainActivity()
    }


    private fun startMainActivity() {
        val shouldPrepareEmbeddedTerminal = shouldPrepareEmbeddedTerminalOnFirstLaunch()
        val intent = Intent(this, MainActivity::class.java).apply {
            // 传递原始 Intent 的数据（用于 Deep Link 处理）
            data = this@LauncherActivity.intent.data
            action = this@LauncherActivity.intent.action
            this@LauncherActivity.intent.extras?.let { putExtras(it) }
            putExtra(
                EXTRA_PREPARE_EMBEDDED_TERMINAL_ON_FIRST_LAUNCH,
                shouldPrepareEmbeddedTerminal
            )
        }
        OmniLog.d(
            TAG,
            "startMainActivity prepareEmbeddedTerminal=$shouldPrepareEmbeddedTerminal",
        )
        startActivity(intent)
        // 不调用 finish()，让 MainActivity 的 Flutter 页面自然覆盖 Loading
        // LauncherActivity 会在 MainActivity 渲染完成后被系统回收
    }

    private fun shouldPrepareEmbeddedTerminalOnFirstLaunch(): Boolean {
        val prefs = getSharedPreferences(STARTUP_PREFS_NAME, MODE_PRIVATE)
        val pending = prefs.getBoolean(
            KEY_EMBEDDED_TERMINAL_FIRST_LAUNCH_INIT_PENDING,
            true
        )
        if (!pending) {
            return false
        }

        val packageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.getPackageInfo(
                packageName,
                PackageManager.PackageInfoFlags.of(0)
            )
        } else {
            @Suppress("DEPRECATION")
            packageManager.getPackageInfo(packageName, 0)
        }
        val isFreshInstall =
            abs(packageInfo.lastUpdateTime - packageInfo.firstInstallTime) <= 5_000L
        if (isFreshInstall) {
            return true
        }

        prefs.edit()
            .putBoolean(KEY_EMBEDDED_TERMINAL_FIRST_LAUNCH_INIT_PENDING, false)
            .apply()
        OmniLog.d(
            TAG,
            "Skip embedded terminal first-launch preparation: app launch is from upgrade, not fresh install.",
        )
        return false
    }

    override fun onStop() {
        super.onStop()
        // 当 MainActivity 覆盖 LauncherActivity 后，finish 自己
        if (!isFinishing) {
            OmniLog.d(TAG, "LauncherActivity onStop, finishing")
            finish()
        }
    }
}
