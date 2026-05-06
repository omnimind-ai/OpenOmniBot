package cn.com.omnimind.bot.activity

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.PopupProperties
import cn.com.omnimind.bot.R
import cn.com.omnimind.bot.agent.AgentAlarmToolService
import cn.com.omnimind.bot.quicklog.QuickLogRecord
import cn.com.omnimind.bot.quicklog.QuickLogService
import cn.com.omnimind.bot.quicklog.QuickLogWidgetProvider
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

object QuickLogEditorScreen {
    fun bind(activity: ComponentActivity, sourceIntent: Intent) {
        val quickLogService = QuickLogService(activity)
        val logId = sourceIntent.getStringExtra(QuickLogEntryActivity.EXTRA_LOG_ID)?.trim().orEmpty()
        val isEditMode = logId.isNotEmpty()
        val existingLog = if (isEditMode) quickLogService.getLog(logId) else null

        if (isEditMode && existingLog == null) {
            Toast.makeText(
                activity,
                activity.getString(R.string.quick_log_not_found),
                Toast.LENGTH_SHORT
            ).show()
            activity.finish()
            return
        }

        val initialListId = sourceIntent.getStringExtra(QuickLogWidgetProvider.EXTRA_LIST_ID)
            ?: quickLogService.getWidgetSettings().selectedListId

        activity.setContent {
            MaterialTheme {
                QuickLogEditorContent(
                    activity = activity,
                    quickLogService = quickLogService,
                    logId = logId,
                    isEditMode = isEditMode,
                    existingLog = existingLog,
                    initialContent = sourceIntent
                        .getStringExtra(QuickLogEntryActivity.EXTRA_LOG_CONTENT)
                        .orEmpty(),
                    initialListId = initialListId
                )
            }
        }
    }

    @Composable
    private fun QuickLogEditorContent(
        activity: ComponentActivity,
        quickLogService: QuickLogService,
        logId: String,
        isEditMode: Boolean,
        existingLog: QuickLogRecord?,
        initialContent: String,
        initialListId: String
    ) {
        var content by remember {
            val initialText = initialContent.ifBlank { existingLog?.content.orEmpty() }
            mutableStateOf(
                TextFieldValue(
                    text = initialText,
                    selection = TextRange(initialText.length)
                )
            )
        }
        var listId by remember {
            mutableStateOf(existingLog?.listId ?: initialListId.takeIf { it in QuickLogService.listIds }
                ?: QuickLogService.LIST_TASKS)
        }
        var important by remember {
            mutableStateOf(existingLog?.isImportant ?: (listId == QuickLogService.LIST_IMPORTANT))
        }
        var dueAt by remember { mutableStateOf(existingLog?.dueAtMillis) }
        var reminderAt by remember { mutableStateOf(existingLog?.reminderAtMillis) }
        var repeatRule by remember { mutableStateOf(existingLog?.repeatRule) }
        var listMenuExpanded by remember { mutableStateOf(false) }
        var reminderMenuExpanded by remember { mutableStateOf(false) }
        var repeatMenuExpanded by remember { mutableStateOf(false) }
        var customRepeatDialogVisible by remember { mutableStateOf(false) }
        var customRepeatInterval by remember {
            mutableStateOf(
                QuickLogService.customRepeatParts(existingLog?.repeatRule)
                    ?.first
                    ?.toString()
                    ?: "2"
            )
        }
        var customRepeatUnit by remember {
            mutableStateOf(
                QuickLogService.customRepeatParts(existingLog?.repeatRule)
                    ?.second
                    ?: QuickLogService.REPEAT_UNIT_DAY
            )
        }

        val context = LocalContext.current
        val focusRequester = remember { FocusRequester() }
        val keyboardController = LocalSoftwareKeyboardController.current
        val coroutineScope = rememberCoroutineScope()

        fun keepKeyboardOpen() {
            focusRequester.requestFocus()
            keyboardController?.show()
        }

        LaunchedEffect(Unit) {
            delay(250)
            keepKeyboardOpen()
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0x99000000))
                .clickable { activity.finish() },
            contentAlignment = Alignment.BottomCenter
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {}
                    .imePadding()
                    .navigationBarsPadding(),
                color = Color(0xEE1F2327),
                shape = RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 18.dp, vertical = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = context.getString(
                                if (isEditMode) {
                                    R.string.quick_log_edit_task
                                } else {
                                    R.string.quick_log_add_task
                                }
                            ),
                            color = Color.White,
                            fontSize = 19.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(onClick = { activity.finish() }) {
                            Text(
                                activity.getString(R.string.quick_log_cancel),
                                color = Color(0xFFB9C2D0)
                            )
                        }
                    }

                    Row(
                        modifier = Modifier.height(64.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "○",
                            color = Color(0xFFB9C2D0),
                            fontSize = 32.sp,
                            modifier = Modifier.size(44.dp)
                        )
                        BasicTextField(
                            value = content,
                            onValueChange = { content = it },
                            textStyle = TextStyle(
                                color = Color.White,
                                fontSize = 20.sp,
                                lineHeight = 25.sp
                            ),
                            modifier = Modifier
                                .weight(1f)
                                .height(56.dp)
                                .focusRequester(focusRequester)
                                .background(Color(0x22111111), RoundedCornerShape(8.dp))
                                .padding(12.dp),
                            decorationBox = { innerTextField ->
                                if (content.text.isBlank()) {
                                    Text(
                                        text = context.getString(R.string.quick_log_add_task),
                                        color = Color(0xFF7E8793),
                                        fontSize = 20.sp
                                    )
                                }
                                innerTextField()
                            }
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Button(
                            modifier = Modifier.size(44.dp),
                            onClick = {
                                coroutineScope.launch {
                                    val normalized = content.text.trim()
                                    if (normalized.isBlank()) {
                                        Toast.makeText(
                                            context,
                                            context.getString(R.string.quick_log_content_required),
                                            Toast.LENGTH_SHORT
                                        ).show()
                                        return@launch
                                    }
                                    val reminderNeedsPermission =
                                        reminderAt != null && reminderAt!! > System.currentTimeMillis()
                                    if (reminderAt != null && !reminderNeedsPermission) {
                                        Toast.makeText(
                                            context,
                                            context.getString(R.string.quick_log_reminder_time_past),
                                            Toast.LENGTH_SHORT
                                        ).show()
                                        return@launch
                                    }
                                    if (reminderNeedsPermission) {
                                        val alarmService = AgentAlarmToolService(context)
                                        val notificationGranted =
                                            alarmService.hasNotificationPermission() ||
                                                alarmService.requestNotificationPermission()
                                        if (!notificationGranted) {
                                            Toast.makeText(
                                                context,
                                                context.getString(R.string.quick_log_reminder_permission_needed),
                                                Toast.LENGTH_SHORT
                                            ).show()
                                            return@launch
                                        }
                                    }
                                    val nextImportant =
                                        important || listId == QuickLogService.LIST_IMPORTANT
                                    val savedRecord = runCatching {
                                        if (isEditMode) {
                                            quickLogService.updateLog(
                                                id = logId,
                                                content = normalized,
                                                listId = listId,
                                                isImportant = nextImportant,
                                                dueAtMillis = dueAt,
                                                reminderAtMillis = reminderAt,
                                                repeatRule = repeatRule,
                                                updateTaskMetadata = true
                                            )
                                        } else {
                                            quickLogService.addLog(
                                                content = normalized,
                                                source = QuickLogService.SOURCE_WIDGET,
                                                listId = listId,
                                                isImportant = nextImportant,
                                                dueAtMillis = dueAt,
                                                reminderAtMillis = reminderAt,
                                                repeatRule = repeatRule
                                            )
                                        }
                                    }.onFailure {
                                        Toast.makeText(
                                            context,
                                            context.getString(
                                                if (isEditMode) {
                                                    R.string.quick_log_update_failed
                                                } else {
                                                    R.string.quick_log_save_failed
                                                }
                                            ),
                                            Toast.LENGTH_SHORT
                                        ).show()
                                    }.getOrNull()
                                    if (savedRecord == null) {
                                        return@launch
                                    }
                                    if (reminderNeedsPermission && savedRecord.reminderAlarmId.isNullOrBlank()) {
                                        Toast.makeText(
                                            context,
                                            context.getString(R.string.quick_log_reminder_schedule_failed),
                                            Toast.LENGTH_LONG
                                        ).show()
                                        return@launch
                                    }
                                    Toast.makeText(
                                        context,
                                        context.getString(
                                            if (isEditMode) {
                                                R.string.quick_log_update_success
                                            } else {
                                                R.string.quick_log_save_success
                                            }
                                        ),
                                        Toast.LENGTH_SHORT
                                    ).show()
                                    activity.finish()
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF6AE7C8)),
                            shape = RoundedCornerShape(10.dp),
                            contentPadding = PaddingValues(0.dp)
                        ) {
                            Text("↑", color = Color(0xFF10201D), fontSize = 24.sp)
                        }
                    }

                    Row(
                        modifier = Modifier
                            .horizontalScroll(rememberScrollState())
                            .fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Box {
                            QuickChip(
                                text = context.getString(
                                    R.string.quick_log_task_type,
                                    listLabel(context, listId)
                                )
                            ) {
                                keepKeyboardOpen()
                                listMenuExpanded = true
                            }
                            DropdownMenu(
                                expanded = listMenuExpanded,
                                onDismissRequest = { listMenuExpanded = false },
                                properties = PopupProperties(focusable = false)
                            ) {
                                QuickLogService.listIds.forEach { option ->
                                    DropdownMenuItem(
                                        text = { Text(listLabel(context, option)) },
                                        onClick = {
                                            listId = option
                                            if (option == QuickLogService.LIST_IMPORTANT) {
                                                important = true
                                            }
                                            keepKeyboardOpen()
                                            listMenuExpanded = false
                                        }
                                    )
                                }
                            }
                        }
                        QuickChip(
                            text = context.getString(
                                R.string.quick_log_due_chip,
                                dateLabel(dueAt) ?: context.getString(R.string.quick_log_due_unset)
                            )
                        ) {
                            showDateTimePicker(activity, dueAt) { dueAt = it }
                        }
                        Box {
                            QuickChip(
                                text = context.getString(
                                    R.string.quick_log_remind_chip,
                                    dateLabel(reminderAt)
                                        ?: context.getString(R.string.quick_log_reminder_unset)
                                )
                            ) {
                                keepKeyboardOpen()
                                reminderMenuExpanded = true
                            }
                            DropdownMenu(
                                expanded = reminderMenuExpanded,
                                onDismissRequest = { reminderMenuExpanded = false },
                                properties = PopupProperties(focusable = false)
                            ) {
                                reminderPresetItems(context).forEach { item ->
                                    DropdownMenuItem(
                                        text = { Text(item.label) },
                                        onClick = {
                                            reminderAt = item.timeMillis
                                            keepKeyboardOpen()
                                            reminderMenuExpanded = false
                                        }
                                    )
                                }
                                DropdownMenuItem(
                                    text = { Text(context.getString(R.string.quick_log_pick_date_time)) },
                                    onClick = {
                                        reminderMenuExpanded = false
                                        showDateTimePicker(activity, reminderAt) { reminderAt = it }
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text(context.getString(R.string.quick_log_clear_reminder)) },
                                    onClick = {
                                        reminderAt = null
                                        keepKeyboardOpen()
                                        reminderMenuExpanded = false
                                    }
                                )
                            }
                        }
                        Box {
                            QuickChip(
                                text = context.getString(
                                    R.string.quick_log_repeat_chip,
                                    repeatLabel(context, repeatRule)
                                )
                            ) {
                                keepKeyboardOpen()
                                repeatMenuExpanded = true
                            }
                            DropdownMenu(
                                expanded = repeatMenuExpanded,
                                onDismissRequest = { repeatMenuExpanded = false },
                                properties = PopupProperties(focusable = false)
                            ) {
                                repeatOptions(context).forEach { option ->
                                    DropdownMenuItem(
                                        text = { Text(option.second) },
                                        onClick = {
                                            repeatMenuExpanded = false
                                            if (option.first == QuickLogService.REPEAT_CUSTOM) {
                                                customRepeatDialogVisible = true
                                            } else {
                                                repeatRule = option.first
                                                keepKeyboardOpen()
                                            }
                                        }
                                    )
                                }
                            }
                        }
                    }

                    if (customRepeatDialogVisible) {
                        CustomRepeatDialog(
                            intervalText = customRepeatInterval,
                            selectedUnit = customRepeatUnit,
                            onIntervalChange = { value ->
                                customRepeatInterval = value.filter { it.isDigit() }.take(3)
                            },
                            onUnitChange = { customRepeatUnit = it },
                            onDismiss = {
                                customRepeatDialogVisible = false
                                keepKeyboardOpen()
                            },
                            onConfirm = {
                                val interval = customRepeatInterval.toIntOrNull()?.coerceIn(1, 999)
                                if (interval == null) {
                                    Toast.makeText(
                                        context,
                                        context.getString(R.string.quick_log_repeat_custom_invalid),
                                        Toast.LENGTH_SHORT
                                    ).show()
                                    return@CustomRepeatDialog
                                }
                                repeatRule = QuickLogService.customRepeatRule(
                                    interval = interval,
                                    unit = customRepeatUnit
                                )
                                customRepeatInterval = interval.toString()
                                customRepeatDialogVisible = false
                                keepKeyboardOpen()
                            }
                        )
                    }

                    if (isEditMode) {
                        TextButton(
                            onClick = {
                                runCatching { quickLogService.deleteLog(logId) }
                                    .onSuccess { deleted ->
                                        Toast.makeText(
                                            context,
                                            context.getString(
                                                if (deleted) {
                                                    R.string.quick_log_delete_success
                                                } else {
                                                    R.string.quick_log_delete_failed
                                                }
                                            ),
                                            Toast.LENGTH_SHORT
                                        ).show()
                                        if (deleted) activity.finish()
                                    }
                                    .onFailure {
                                        Toast.makeText(
                                            context,
                                            context.getString(R.string.quick_log_delete_failed),
                                            Toast.LENGTH_SHORT
                                        ).show()
                                    }
                            }
                        ) {
                            Text(
                                context.getString(R.string.quick_log_delete_task),
                                color = Color(0xFFE05A8A)
                            )
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun CustomRepeatDialog(
        intervalText: String,
        selectedUnit: String,
        onIntervalChange: (String) -> Unit,
        onUnitChange: (String) -> Unit,
        onDismiss: () -> Unit,
        onConfirm: () -> Unit
    ) {
        val context = LocalContext.current
        AlertDialog(
            onDismissRequest = onDismiss,
            containerColor = Color(0xFF252A30),
            title = {
                Text(
                    text = context.getString(R.string.quick_log_repeat_custom_title),
                    color = Color.White
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = intervalText,
                        onValueChange = onIntervalChange,
                        label = {
                            Text(context.getString(R.string.quick_log_repeat_custom_interval))
                        },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        repeatUnitOptions(context).forEach { option ->
                            CustomRepeatUnitChip(
                                text = option.second,
                                selected = selectedUnit == option.first,
                                modifier = Modifier.weight(1f)
                            ) {
                                onUnitChange(option.first)
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = onConfirm) {
                    Text(context.getString(R.string.quick_log_repeat_custom_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = onDismiss) {
                    Text(context.getString(R.string.quick_log_cancel))
                }
            }
        )
    }

    @Composable
    private fun CustomRepeatUnitChip(
        text: String,
        selected: Boolean,
        modifier: Modifier = Modifier,
        onClick: () -> Unit
    ) {
        Surface(
            color = if (selected) Color(0xFF6AE7C8) else Color(0x22FFFFFF),
            shape = RoundedCornerShape(10.dp),
            modifier = modifier.clickable(onClick = onClick)
        ) {
            Text(
                text = text,
                color = if (selected) Color(0xFF10201D) else Color(0xFFE6EDF3),
                fontSize = 14.sp,
                modifier = Modifier.padding(vertical = 10.dp),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
        }
    }

    @Composable
    private fun QuickChip(text: String, onClick: () -> Unit) {
        Surface(
            color = Color(0x22FFFFFF),
            shape = RoundedCornerShape(10.dp),
            modifier = Modifier.clickable(onClick = onClick)
        ) {
            Text(
                text = text,
                color = Color(0xFFE6EDF3),
                fontSize = 15.sp,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp)
            )
        }
    }

    private data class ReminderPreset(val label: String, val timeMillis: Long)

    private fun reminderPresetItems(context: Context): List<ReminderPreset> {
        val locale = context.resources.configuration.locales[0] ?: Locale.getDefault()
        val formatter = SimpleDateFormat("E HH:mm", locale)
        val nowMillis = System.currentTimeMillis()
        val todayLater = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 18)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val todayLaterLabel = if (todayLater.timeInMillis <= nowMillis) {
            todayLater.add(Calendar.DAY_OF_YEAR, 1)
            context.getString(R.string.quick_log_later_tomorrow, formatter.format(todayLater.time))
        } else {
            context.getString(R.string.quick_log_later_today)
        }
        val tomorrow = Calendar.getInstance().apply {
            add(Calendar.DAY_OF_YEAR, 1)
            set(Calendar.HOUR_OF_DAY, 9)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val nextWeek = Calendar.getInstance().apply {
            add(Calendar.WEEK_OF_YEAR, 1)
            set(Calendar.DAY_OF_WEEK, Calendar.MONDAY)
            set(Calendar.HOUR_OF_DAY, 9)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        return listOf(
            ReminderPreset(
                todayLaterLabel,
                todayLater.timeInMillis
            ),
            ReminderPreset(
                context.getString(R.string.quick_log_tomorrow, formatter.format(tomorrow.time)),
                tomorrow.timeInMillis
            ),
            ReminderPreset(
                context.getString(R.string.quick_log_next_week, formatter.format(nextWeek.time)),
                nextWeek.timeInMillis
            )
        )
    }

    private fun repeatOptions(context: Context): List<Pair<String?, String>> = listOf(
        null to context.getString(R.string.quick_log_repeat_none),
        QuickLogService.REPEAT_DAILY to context.getString(R.string.quick_log_repeat_daily),
        QuickLogService.REPEAT_WEEKDAYS to context.getString(R.string.quick_log_repeat_weekdays),
        QuickLogService.REPEAT_WEEKLY to context.getString(R.string.quick_log_repeat_weekly),
        QuickLogService.REPEAT_MONTHLY to context.getString(R.string.quick_log_repeat_monthly),
        QuickLogService.REPEAT_YEARLY to context.getString(R.string.quick_log_repeat_yearly),
        QuickLogService.REPEAT_CUSTOM to context.getString(R.string.quick_log_repeat_custom)
    )

    private fun repeatUnitOptions(context: Context): List<Pair<String, String>> = listOf(
        QuickLogService.REPEAT_UNIT_DAY to context.getString(R.string.quick_log_repeat_unit_day),
        QuickLogService.REPEAT_UNIT_WEEK to context.getString(R.string.quick_log_repeat_unit_week),
        QuickLogService.REPEAT_UNIT_MONTH to context.getString(R.string.quick_log_repeat_unit_month),
        QuickLogService.REPEAT_UNIT_YEAR to context.getString(R.string.quick_log_repeat_unit_year)
    )

    private fun listLabel(context: Context, listId: String): String {
        return when (listId) {
            QuickLogService.LIST_MY_DAY -> context.getString(R.string.quick_log_list_my_day)
            QuickLogService.LIST_IMPORTANT -> context.getString(R.string.quick_log_list_important)
            QuickLogService.LIST_PLANNED -> context.getString(R.string.quick_log_list_planned)
            else -> context.getString(R.string.quick_log_list_tasks)
        }
    }

    private fun repeatLabel(context: Context, repeatRule: String?): String {
        QuickLogService.customRepeatParts(repeatRule)?.let { (interval, unit) ->
            return customRepeatLabel(context, interval, unit)
        }
        return when (repeatRule) {
            QuickLogService.REPEAT_DAILY -> context.getString(R.string.quick_log_repeat_daily)
            QuickLogService.REPEAT_WEEKDAYS -> context.getString(R.string.quick_log_repeat_weekdays)
            QuickLogService.REPEAT_WEEKLY -> context.getString(R.string.quick_log_repeat_weekly)
            QuickLogService.REPEAT_MONTHLY -> context.getString(R.string.quick_log_repeat_monthly)
            QuickLogService.REPEAT_YEARLY -> context.getString(R.string.quick_log_repeat_yearly)
            QuickLogService.REPEAT_CUSTOM -> context.getString(R.string.quick_log_repeat_custom)
            else -> context.getString(R.string.quick_log_repeat_none)
        }
    }

    private fun customRepeatLabel(context: Context, interval: Int, unit: String): String {
        val pluralRes = when (unit) {
            QuickLogService.REPEAT_UNIT_WEEK -> R.plurals.quick_log_repeat_custom_weeks
            QuickLogService.REPEAT_UNIT_MONTH -> R.plurals.quick_log_repeat_custom_months
            QuickLogService.REPEAT_UNIT_YEAR -> R.plurals.quick_log_repeat_custom_years
            else -> R.plurals.quick_log_repeat_custom_days
        }
        return context.resources.getQuantityString(pluralRes, interval, interval)
    }

    private fun dateLabel(timeMillis: Long?): String? {
        if (timeMillis == null) return null
        return SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(timeMillis))
    }

    private fun showDateTimePicker(
        activity: ComponentActivity,
        initialMillis: Long?,
        onSelected: (Long) -> Unit
    ) {
        val initial = Calendar.getInstance().apply {
            if (initialMillis != null) timeInMillis = initialMillis
        }
        DatePickerDialog(
            activity,
            { _, year, month, day ->
                val selected = Calendar.getInstance().apply {
                    set(Calendar.YEAR, year)
                    set(Calendar.MONTH, month)
                    set(Calendar.DAY_OF_MONTH, day)
                    set(Calendar.HOUR_OF_DAY, initial.get(Calendar.HOUR_OF_DAY))
                    set(Calendar.MINUTE, initial.get(Calendar.MINUTE))
                    set(Calendar.SECOND, 0)
                    set(Calendar.MILLISECOND, 0)
                }
                TimePickerDialog(
                    activity,
                    { _, hour, minute ->
                        selected.set(Calendar.HOUR_OF_DAY, hour)
                        selected.set(Calendar.MINUTE, minute)
                        onSelected(selected.timeInMillis)
                    },
                    selected.get(Calendar.HOUR_OF_DAY),
                    selected.get(Calendar.MINUTE),
                    true
                ).show()
            },
            initial.get(Calendar.YEAR),
            initial.get(Calendar.MONTH),
            initial.get(Calendar.DAY_OF_MONTH)
        ).show()
    }
}
