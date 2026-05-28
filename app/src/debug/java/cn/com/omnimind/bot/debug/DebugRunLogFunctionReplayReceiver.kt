package cn.com.omnimind.bot.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Base64
import com.google.gson.reflect.TypeToken
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
        val name = intent.decodeBase64Extra("nameBase64")
            ?: intent?.getStringExtra("name").orEmpty()
        val description = intent.decodeBase64Extra("descriptionBase64")
            ?: intent?.getStringExtra("description").orEmpty()
        val goal = intent.decodeBase64Extra("goalBase64")
            ?: intent?.getStringExtra("goal").orEmpty()
        val shouldRun = intent?.getBooleanExtra("run", true) ?: true
        val rawRunLog = intent.decodeBase64Extra("runLogBase64")
            ?.let(::decodeRunLog)
            ?: emptyMap()

        scope.launch {
            val result = runCatching {
                val service = OobOmniFlowToolkitService(appContext)
                val convertArgs = linkedMapOf<String, Any?>(
                    "run_id" to runId,
                    "register" to true,
                )
                if (rawRunLog.isNotEmpty()) {
                    convertArgs.remove("run_id")
                    convertArgs["run_log"] = rawRunLog
                }
                functionId.takeIf { it.isNotBlank() }?.let {
                    convertArgs["function_id"] = it
                }
                name.takeIf { it.isNotBlank() }?.let {
                    convertArgs["name"] = it
                }
                description.takeIf { it.isNotBlank() }?.let {
                    convertArgs["description"] = it
                }

                val convert = if (rawRunLog.isNotEmpty()) {
                    service.ingestRunLog(linkedMapOf("run_log" to rawRunLog))
                } else {
                    service.convertRunLog(convertArgs)
                }
                val createdFunctionId = convert["function_id"]?.toString()
                    ?: convert["created_function_id"]?.toString()
                    ?: ""
                val convertResult = (convert["result"] as? Map<*, *>)
                    ?.mapKeys { it.key?.toString().orEmpty() }
                    ?: emptyMap<String, Any?>()
                val functionSpec = convert["function_spec"] ?: convertResult["function_spec"]
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
                    "run_id" to (
                        convert["run_id"] ?: runId.ifBlank {
                            rawRunLog["run_id"] ?: rawRunLog["runId"]
                        }
                    ),
                    "function_id" to createdFunctionId,
                    "convert" to convert,
                    "function_spec" to functionSpec,
                    "replay" to replay,
                    "run_requested" to shouldRun,
                )
            }.getOrElse { error ->
                OmniLog.e(TAG, "debug runlog function replay failed: ${error.fullMessage()}", error)
                linkedMapOf<String, Any?>(
                    "success" to false,
                    "phase" to "exception",
                    "run_id" to runId,
                    "function_id" to functionId,
                    "error_message" to error.fullMessage(),
                    "error_type" to error.javaClass.name,
                    "error_cause_chain" to error.causeChain(),
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
        private val mapType = object : TypeToken<Map<String, Any?>>() {}.type
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }

    private fun Intent?.decodeBase64Extra(name: String): String? {
        val raw = this?.getStringExtra(name)?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return runCatching {
            String(Base64.decode(raw, Base64.DEFAULT), Charsets.UTF_8).trim()
                .takeIf { it.isNotEmpty() }
        }.getOrNull()
    }

    private fun decodeRunLog(rawJson: String): Map<String, Any?> {
        return runCatching {
            val decoded = gson.fromJson<Map<String, Any?>>(rawJson, mapType)
            decoded ?: emptyMap()
        }.getOrDefault(emptyMap())
    }

    private fun Throwable.fullMessage(): String {
        val parts = mutableListOf<String>()
        var current: Throwable? = this
        val seen = mutableSetOf<Throwable>()
        while (current != null && seen.add(current)) {
            parts += current.message?.takeIf(String::isNotBlank)
                ?.let { "${current.javaClass.name}: $it" }
                ?: current.javaClass.name
            current = current.cause
        }
        return parts.joinToString(" <- ")
    }

    private fun Throwable.causeChain(): List<Map<String, String>> {
        val output = mutableListOf<Map<String, String>>()
        var current: Throwable? = this
        val seen = mutableSetOf<Throwable>()
        while (current != null && seen.add(current)) {
            output += linkedMapOf(
                "type" to current.javaClass.name,
                "message" to current.message.orEmpty(),
            )
            current = current.cause
        }
        return output
    }
}
