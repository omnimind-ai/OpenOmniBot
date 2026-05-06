package cn.com.omnimind.bot.quicklog

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.graphics.Paint
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
        remoteViews.setTextViewText(R.id.quick_log_widget_item_check, if (record.isCompleted) "✓" else "")
        remoteViews.setTextViewText(R.id.quick_log_widget_item_star, if (record.isImportant) "★" else "☆")
        remoteViews.setTextViewText(
            R.id.quick_log_widget_item_detail,
            buildDetailText(record)
        )
        remoteViews.setViewVisibility(
            R.id.quick_log_widget_item_detail,
            if (settings.showDetails && buildDetailText(record).isNotBlank()) {
                android.view.View.VISIBLE
            } else {
                android.view.View.GONE
            }
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
        val secondaryText = if (isLightSurface) {
            0xFF64748B.toInt()
        } else {
            0xFFB9C2D0.toInt()
        }
        val accentText = if (isLightSurface) {
            0xFF5B6FBE.toInt()
        } else {
            0xFF6AE7C8.toInt()
        }
        val importantText = 0xFFB83B68.toInt()
        val textSize = when (settings.fontSize) {
            QuickLogService.FONT_SMALL -> 13f
            QuickLogService.FONT_LARGE -> 17f
            else -> 15f
        }
        remoteViews.setInt(R.id.quick_log_widget_item_root, "setBackgroundResource", itemBackground)
        remoteViews.setTextColor(R.id.quick_log_widget_item_text, primaryText)
        remoteViews.setTextColor(R.id.quick_log_widget_item_detail, secondaryText)
        remoteViews.setTextColor(R.id.quick_log_widget_item_check, accentText)
        remoteViews.setTextColor(
            R.id.quick_log_widget_item_star,
            if (record.isImportant) importantText else accentText
        )
        remoteViews.setTextViewTextSize(R.id.quick_log_widget_item_text, android.util.TypedValue.COMPLEX_UNIT_SP, textSize)
        remoteViews.setTextViewTextSize(R.id.quick_log_widget_item_detail, android.util.TypedValue.COMPLEX_UNIT_SP, textSize - 3f)
        remoteViews.setInt(
            R.id.quick_log_widget_item_text,
            "setPaintFlags",
            if (record.isCompleted) Paint.STRIKE_THRU_TEXT_FLAG else 0
        )

        val editIntent = Intent().apply {
            action = QuickLogWidgetProvider.ACTION_EDIT_LOG
            putExtra(QuickLogWidgetProvider.EXTRA_LOG_ID, record.id)
            putExtra(QuickLogWidgetProvider.EXTRA_LOG_CONTENT, record.content)
            putExtra(QuickLogWidgetProvider.EXTRA_APP_WIDGET_ID, appWidgetId)
        }
        val completedIntent = Intent().apply {
            action = QuickLogWidgetProvider.ACTION_TOGGLE_COMPLETED
            putExtra(QuickLogWidgetProvider.EXTRA_LOG_ID, record.id)
            putExtra(QuickLogWidgetProvider.EXTRA_APP_WIDGET_ID, appWidgetId)
        }
        val importantIntent = Intent().apply {
            action = QuickLogWidgetProvider.ACTION_TOGGLE_IMPORTANT
            putExtra(QuickLogWidgetProvider.EXTRA_LOG_ID, record.id)
            putExtra(QuickLogWidgetProvider.EXTRA_APP_WIDGET_ID, appWidgetId)
        }

        remoteViews.setOnClickFillInIntent(R.id.quick_log_widget_item_root, editIntent)
        remoteViews.setOnClickFillInIntent(R.id.quick_log_widget_item_text, editIntent)
        remoteViews.setOnClickFillInIntent(R.id.quick_log_widget_item_detail, editIntent)
        remoteViews.setOnClickFillInIntent(R.id.quick_log_widget_item_check_container, completedIntent)
        remoteViews.setOnClickFillInIntent(R.id.quick_log_widget_item_check, completedIntent)
        remoteViews.setOnClickFillInIntent(R.id.quick_log_widget_item_star_container, importantIntent)
        remoteViews.setOnClickFillInIntent(R.id.quick_log_widget_item_star, importantIntent)

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

    private fun buildDetailText(record: QuickLogRecord): String {
        val parts = mutableListOf<String>()
        val listLabel = listLabel(record.listId ?: QuickLogService.LIST_TASKS)
        if (settings.selectedListId == QuickLogService.LIST_TASKS) {
            parts += listLabel
        }
        record.dueAtMillis?.let {
            parts += localizedContext.getString(
                R.string.quick_log_widget_due_detail,
                timeFormat.format(Date(it))
            )
        }
        record.reminderAtMillis?.let {
            parts += localizedContext.getString(
                R.string.quick_log_widget_reminder_detail,
                timeFormat.format(Date(it))
            )
        }
        repeatLabel(record.repeatRule)?.let {
            parts += it
        }
        return parts.joinToString(" · ")
    }

    private fun listLabel(listId: String): String {
        return when (listId) {
            QuickLogService.LIST_MY_DAY -> localizedContext.getString(R.string.quick_log_list_my_day)
            QuickLogService.LIST_IMPORTANT -> localizedContext.getString(R.string.quick_log_list_important)
            QuickLogService.LIST_PLANNED -> localizedContext.getString(R.string.quick_log_list_planned)
            else -> localizedContext.getString(R.string.quick_log_list_tasks)
        }
    }

    private fun repeatLabel(repeatRule: String?): String? {
        QuickLogService.customRepeatParts(repeatRule)?.let { (interval, unit) ->
            return customRepeatLabel(interval, unit)
        }
        return when (repeatRule) {
            QuickLogService.REPEAT_DAILY -> localizedContext.getString(R.string.quick_log_repeat_daily)
            QuickLogService.REPEAT_WEEKDAYS -> localizedContext.getString(R.string.quick_log_repeat_weekdays)
            QuickLogService.REPEAT_WEEKLY -> localizedContext.getString(R.string.quick_log_repeat_weekly)
            QuickLogService.REPEAT_MONTHLY -> localizedContext.getString(R.string.quick_log_repeat_monthly)
            QuickLogService.REPEAT_YEARLY -> localizedContext.getString(R.string.quick_log_repeat_yearly)
            QuickLogService.REPEAT_CUSTOM -> localizedContext.getString(R.string.quick_log_repeat_custom)
            else -> null
        }
    }

    private fun customRepeatLabel(interval: Int, unit: String): String {
        val pluralRes = when (unit) {
            QuickLogService.REPEAT_UNIT_WEEK -> R.plurals.quick_log_repeat_custom_weeks
            QuickLogService.REPEAT_UNIT_MONTH -> R.plurals.quick_log_repeat_custom_months
            QuickLogService.REPEAT_UNIT_YEAR -> R.plurals.quick_log_repeat_custom_years
            else -> R.plurals.quick_log_repeat_custom_days
        }
        return localizedContext.resources.getQuantityString(pluralRes, interval, interval)
    }
}
