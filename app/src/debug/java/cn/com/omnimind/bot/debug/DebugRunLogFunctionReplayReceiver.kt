package cn.com.omnimind.bot.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import cn.com.omnimind.baselib.util.OmniLog
import cn.com.omnimind.bot.runlog.OobOmniFlowToolkitService
import com.google.gson.GsonBuilder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File

class DebugRunLogFunctionReplayReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val appContext = context.applicationContext
        val runId = intent?.getStringExtra("runId")
            ?: intent?.getStringExtra("run_id")
            ?: ""
        val functionId = intent?.getStringExtra("functionId")
            ?: intent?.getStringExtra("function_id")
            ?: ""
        val name = intent?.getStringExtra("name").orEmpty()
        val description = intent?.getStringExtra("description").orEmpty()
        val goal = intent?.getStringExtra("goal").orEmpty()
        val shouldRun = intent?.getBooleanExtra("run", true) ?: true

        scope.launch {
            val result = runCatching {
                val service = OobOmniFlowToolkitService(appContext)
                val convertArgs = linkedMapOf<String, Any?>(
                    "run_id" to runId,
                    "register" to true,
                )
                functionId.takeIf { it.isNotBlank() }?.let {
                    convertArgs["function_id"] = it
                }
                name.takeIf { it.isNotBlank() }?.let {
                    convertArgs["name"] = it
                }
                description.takeIf { it.isNotBlank() }?.let {
                    convertArgs["description"] = it
                }

                val convert = service.convertRunLog(convertArgs)
                val createdFunctionId = convert["function_id"]?.toString()
                    ?: convert["created_function_id"]?.toString()
                    ?: ""
                val replay = if (shouldRun && convert["success"] == true && createdFunctionId.isNotBlank()) {
                    service.callFunction(
                        linkedMapOf(
                            "function_id" to createdFunctionId,
                            "goal" to goal,
                            "arguments" to emptyMap<String, Any?>(),
                        )
                    )
                } else {
                    null
                }

                linkedMapOf<String, Any?>(
                    "success" to (convert["success"] == true && (replay?.get("success") != false)),
                    "run_id" to runId,
                    "function_id" to createdFunctionId,
                    "convert" to convert,
                    "replay" to replay,
                    "run_requested" to shouldRun,
                )
            }.getOrElse { error ->
                linkedMapOf<String, Any?>(
                    "success" to false,
                    "phase" to "exception",
                    "run_id" to runId,
                    "function_id" to functionId,
                    "error_message" to error.message.orEmpty(),
                    "error_type" to error.javaClass.name,
                )
            }
            val json = gson.toJson(result)
            File(appContext.filesDir, "debug-runlog-function-replay-result.json").writeText(json)
            OmniLog.i(TAG, json)
        }
    }

    companion object {
        private const val TAG = "DebugRunLogFunctionReplayReceiver"
        private val gson = GsonBuilder().disableHtmlEscaping().create()
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}
