package cn.com.omnimind.bot.quicklog

import android.app.Activity
import android.content.Intent
import cn.com.omnimind.baselib.util.OmniLog
import cn.com.omnimind.bot.activity.QuickLogEntryActivity

object QuickLogWidgetActionRouter {
    private const val TAG = "QuickLogWidgetAction"

    fun consumeInto(activity: Activity, intent: Intent?): Boolean {
        val action = intent?.action ?: return false
        return when {
            action == QuickLogWidgetProvider.ACTION_ADD_LOG -> {
                OmniLog.d(TAG, "Consuming widget add action in ${activity.javaClass.simpleName}")
                activity.startActivity(buildEntryIntent(activity, intent))
                true
            }
            action.startsWith(QuickLogWidgetProvider.ACTION_EDIT_LOG) -> {
                OmniLog.d(TAG, "Consuming widget edit action in ${activity.javaClass.simpleName}")
                activity.startActivity(buildEntryIntent(activity, intent))
                true
            }
            else -> false
        }
    }

    private fun buildEntryIntent(activity: Activity, sourceIntent: Intent): Intent {
        return Intent(activity, QuickLogEntryActivity::class.java).apply {
            action = sourceIntent.action
            sourceIntent.extras?.let { putExtras(it) }
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP
            )
        }
    }
}
