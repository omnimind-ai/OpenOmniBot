package cn.com.omnimind.bot.activity

import android.app.Activity
import android.content.Intent
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import cn.com.omnimind.bot.R
import cn.com.omnimind.bot.quicklog.QuickLogService

object QuickLogEditorScreen {
    fun bind(activity: Activity, sourceIntent: Intent) {
        activity.setContentView(R.layout.activity_quick_log_entry)

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

        val titleView = activity.findViewById<TextView>(R.id.quick_log_title)
        val subtitleView = activity.findViewById<TextView>(R.id.quick_log_subtitle)
        val editor = activity.findViewById<EditText>(R.id.quick_log_editor)
        val cancelButton = activity.findViewById<Button>(R.id.quick_log_cancel_button)
        val deleteButton = activity.findViewById<Button>(R.id.quick_log_delete_button)
        val saveButton = activity.findViewById<Button>(R.id.quick_log_save_button)

        if (isEditMode) {
            titleView.setText(R.string.quick_log_entry_edit_title)
            subtitleView.setText(R.string.quick_log_entry_edit_subtitle)
            saveButton.setText(R.string.quick_log_update)
            deleteButton.visibility = View.VISIBLE
            editor.setText(
                sourceIntent.getStringExtra(QuickLogEntryActivity.EXTRA_LOG_CONTENT)?.takeIf { it.isNotBlank() }
                    ?: existingLog?.content.orEmpty()
            )
            editor.setSelection(editor.text?.length ?: 0)
        }

        cancelButton.setOnClickListener {
            activity.finish()
        }

        deleteButton.setOnClickListener {
            runCatching {
                quickLogService.deleteLog(logId)
            }.onSuccess { deleted ->
                val messageRes = if (deleted) {
                    R.string.quick_log_delete_success
                } else {
                    R.string.quick_log_delete_failed
                }
                Toast.makeText(activity, activity.getString(messageRes), Toast.LENGTH_SHORT).show()
                if (deleted) {
                    activity.finish()
                }
            }.onFailure {
                Toast.makeText(
                    activity,
                    activity.getString(R.string.quick_log_delete_failed),
                    Toast.LENGTH_SHORT
                ).show()
            }
        }

        saveButton.setOnClickListener {
            val content = editor.text?.toString().orEmpty().trim()
            if (content.isEmpty()) {
                editor.error = activity.getString(R.string.quick_log_content_required)
                return@setOnClickListener
            }
            runCatching {
                if (isEditMode) {
                    quickLogService.updateLog(logId, content)
                } else {
                    quickLogService.addLog(
                        content = content,
                        source = QuickLogService.SOURCE_WIDGET
                    )
                }
            }.onSuccess {
                Toast.makeText(
                    activity,
                    activity.getString(
                        if (isEditMode) {
                            R.string.quick_log_update_success
                        } else {
                            R.string.quick_log_save_success
                        }
                    ),
                    Toast.LENGTH_SHORT
                ).show()
                activity.finish()
            }.onFailure {
                Toast.makeText(
                    activity,
                    activity.getString(
                        if (isEditMode) {
                            R.string.quick_log_update_failed
                        } else {
                            R.string.quick_log_save_failed
                        }
                    ),
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }
}
