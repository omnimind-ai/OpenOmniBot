package cn.com.omnimind.bot.quicklog

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import android.widget.RemoteViewsService
import cn.com.omnimind.baselib.i18n.AppLocaleManager
import cn.com.omnimind.bot.R
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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

    private val localizedContext: Context
        get() = AppLocaleManager.localizedContext(context)

    private val timeFormat: SimpleDateFormat
        get() = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())

    private var records: List<QuickLogRecord> = emptyList()

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
        val timestamp = timeFormat.format(Date(record.updatedAtMillis))
        val preview = record.content.replace(Regex("\\s+"), " ").trim()
        remoteViews.setTextViewText(
            R.id.quick_log_widget_item_text,
            "$timestamp  $preview"
        )
        remoteViews.setTextViewText(
            R.id.quick_log_widget_item_edit,
            localizedContext.getString(R.string.quick_log_widget_edit)
        )
        remoteViews.setTextViewText(
            R.id.quick_log_widget_item_delete,
            localizedContext.getString(R.string.quick_log_widget_delete)
        )

        val editIntent = Intent().apply {
            action = QuickLogWidgetProvider.ACTION_EDIT_LOG
            putExtra(QuickLogWidgetProvider.EXTRA_LOG_ID, record.id)
            putExtra(QuickLogWidgetProvider.EXTRA_LOG_CONTENT, record.content)
            putExtra(QuickLogWidgetProvider.EXTRA_APP_WIDGET_ID, appWidgetId)
        }
        val deleteIntent = Intent().apply {
            action = QuickLogWidgetProvider.ACTION_DELETE_LOG
            putExtra(QuickLogWidgetProvider.EXTRA_LOG_ID, record.id)
            putExtra(QuickLogWidgetProvider.EXTRA_APP_WIDGET_ID, appWidgetId)
        }

        remoteViews.setOnClickFillInIntent(R.id.quick_log_widget_item_root, editIntent)
        remoteViews.setOnClickFillInIntent(R.id.quick_log_widget_item_text, editIntent)
        remoteViews.setOnClickFillInIntent(R.id.quick_log_widget_item_edit_action, editIntent)
        remoteViews.setOnClickFillInIntent(R.id.quick_log_widget_item_edit, editIntent)
        remoteViews.setOnClickFillInIntent(R.id.quick_log_widget_item_delete_action, deleteIntent)
        remoteViews.setOnClickFillInIntent(R.id.quick_log_widget_item_delete, deleteIntent)

        return remoteViews
    }

    override fun getLoadingView(): RemoteViews? = null

    override fun getViewTypeCount(): Int = 1

    override fun getItemId(position: Int): Long {
        return records.getOrNull(position)?.id?.hashCode()?.toLong() ?: position.toLong()
    }

    override fun hasStableIds(): Boolean = true

    private fun loadRecords() {
        records = QuickLogService(context).listLogs(limit = 200)
    }
}
