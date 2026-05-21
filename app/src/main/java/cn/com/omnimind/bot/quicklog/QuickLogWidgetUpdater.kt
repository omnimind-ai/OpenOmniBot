package cn.com.omnimind.bot.quicklog

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import cn.com.omnimind.bot.R

object QuickLogWidgetUpdater {
    fun updateAll(context: Context) {
        val appWidgetManager = AppWidgetManager.getInstance(context)
        val provider = ComponentName(context, QuickLogWidgetProvider::class.java)
        val widgetIds = appWidgetManager.getAppWidgetIds(provider)
        if (widgetIds.isEmpty()) {
            return
        }
        appWidgetManager.notifyAppWidgetViewDataChanged(widgetIds, R.id.quick_log_widget_list)
        QuickLogWidgetProvider.updateWidgets(context, appWidgetManager, widgetIds)
    }
}
