package cn.com.omnimind.bot.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Base64
import cn.com.omnimind.baselib.util.OmniLog
import cn.com.omnimind.bot.runlog.OobOmniFlowToolkitService
import com.google.gson.GsonBuilder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File

class DebugOobFunctionRunReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val appContext = context.applicationContext
        val functionId = intent?.getStringExtra("functionId")
            ?: intent?.getStringExtra("function_id")
            ?: ""
        val goal = intent.decodeBase64Extra("goalBase64")
            ?: intent?.getStringExtra("goal").orEmpty()

        scope.launch {
            val result = runCatching {
                OobOmniFlowToolkitService(appContext).callFunction(
                    mapOf(
                        "function_id" to functionId,
                        "goal" to goal,
                        "arguments" to emptyMap<String, Any?>(),
                    )
                )
            }.getOrElse { error ->
                linkedMapOf<String, Any?>(
                    "success" to false,
                    "phase" to "exception",
                    "function_id" to functionId,
                    "error_message" to error.message.orEmpty(),
                    "error_type" to error.javaClass.name,
                )
            }
            val json = gson.toJson(result)
            File(appContext.filesDir, "debug-oob-function-run-result.json").writeText(json)
            OmniLog.i(TAG, json)
        }
    }

    companion object {
        private const val TAG = "DebugOobFunctionRunReceiver"
        private val gson = GsonBuilder().disableHtmlEscaping().create()
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }

    private fun Intent?.decodeBase64Extra(name: String): String? {
        val raw = this?.getStringExtra(name)?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return runCatching {
            String(Base64.decode(raw, Base64.DEFAULT), Charsets.UTF_8).trim()
                .takeIf { it.isNotEmpty() }
        }.getOrNull()
    }
}
