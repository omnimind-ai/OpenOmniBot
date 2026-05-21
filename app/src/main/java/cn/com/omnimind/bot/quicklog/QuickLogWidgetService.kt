package cn.com.omnimind.bot.quicklog

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import android.widget.RemoteViewsService
import cn.com.omnimind.bot.R

class QuickLogWidgetService : RemoteViewsService() {
    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory {
        return QuickLogWidgetFactory(applicationContext, intent)
    }
}

private class QuickLogWidgetFactory(
    private val context: Context,
    intent: Intent
) : RemoteViewsService.RemoteViewsFactory {

    private val appWidgetId = intent.getIntExtra(
        AppWidgetManager.EXTRA_APPWIDGET_ID,
        AppWidgetManager.INVALID_APPWIDGET_ID
    )

    private var records: List<QuickLogRecord> = emptyList()
    private var settings: QuickLogWidgetSettings = QuickLogWidgetSettings()

    override fun onCreate() {
        loadRecords()
    }

    override fun onDataSetChanged() {
        loadRecords()
    }

    override fun onDestroy() {
        records = emptyList()
    }

    override fun getCount(): Int = records.size

    override fun getViewAt(position: Int): RemoteViews? {
        val record = records.getOrNull(position) ?: return null
        val remoteViews = RemoteViews(context.packageName, R.layout.widget_quick_log_item)
        val preview = record.content.replace(Regex("\\s+"), " ").trim()
        remoteViews.setTextViewText(
            R.id.quick_log_widget_item_text,
            preview
        )
        val isLightSurface = settings.colorTheme == QuickLogService.COLOR_LIGHT ||
            settings.colorTheme == QuickLogService.COLOR_BLUE ||
            settings.colorTheme == QuickLogService.COLOR_PINK
        val itemBackground = if (isLightSurface) {
            R.drawable.quick_log_widget_row_card_light
        } else {
            R.drawable.quick_log_widget_row_card_dark
        }
        val primaryText = if (isLightSurface) {
            0xFF1F2937.toInt()
        } else {
            0xFFF8FAFC.toInt()
        }
        val textSize = when (settings.fontSize) {
            QuickLogService.FONT_SMALL -> 13f
            QuickLogService.FONT_LARGE -> 17f
            else -> 15f
        }
        remoteViews.setInt(R.id.quick_log_widget_item_root, "setBackgroundResource", itemBackground)
        remoteViews.setTextColor(R.id.quick_log_widget_item_text, primaryText)
        remoteViews.setTextViewTextSize(R.id.quick_log_widget_item_text, android.util.TypedValue.COMPLEX_UNIT_SP, textSize)

        val editIntent = Intent().apply {
            action = QuickLogWidgetProvider.ACTION_EDIT_LOG
            putExtra(QuickLogWidgetProvider.EXTRA_LOG_ID, record.id)
            putExtra(QuickLogWidgetProvider.EXTRA_LOG_CONTENT, record.content)
            putExtra(QuickLogWidgetProvider.EXTRA_APP_WIDGET_ID, appWidgetId)
        }

        remoteViews.setOnClickFillInIntent(R.id.quick_log_widget_item_root, editIntent)
        remoteViews.setOnClickFillInIntent(R.id.quick_log_widget_item_text, editIntent)

        return remoteViews
    }

    override fun getLoadingView(): RemoteViews? = null

    override fun getViewTypeCount(): Int = 1

    override fun getItemId(position: Int): Long {
        return records.getOrNull(position)?.id?.hashCode()?.toLong() ?: position.toLong()
    }

    override fun hasStableIds(): Boolean = true

    private fun loadRecords() {
        val service = QuickLogService(context)
        settings = service.getWidgetSettings()
        records = service.latestLogsForWidget(limit = 200)
    }

}
