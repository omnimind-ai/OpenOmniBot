package cn.com.omnimind.bot.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Base64
import cn.com.omnimind.assists.controller.accessibility.AccessibilityController
import cn.com.omnimind.baselib.util.OmniLog
import cn.com.omnimind.bot.runlog.OobOmniFlowToolkitService
import com.google.gson.GsonBuilder
import java.io.File

class DebugOobRecallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val appContext = context.applicationContext
        val goal = intent.decodeBase64Extra("goalBase64")
            ?: intent?.getStringExtra("goal").orEmpty()
        val requestedPackage = intent?.getStringExtra("currentPackage")
            ?: intent?.getStringExtra("current_package")
        val currentPackage = requestedPackage?.trim()?.takeIf { it.isNotEmpty() }
            ?: runCatching { AccessibilityController.getPackageName() }.getOrNull().orEmpty()
        val currentXml = intent.decodeBase64Extra("currentXmlBase64")
            ?: intent?.getStringExtra("currentXml")?.trim()?.takeIf { it.isNotEmpty() }
            ?: runCatching { AccessibilityController.getCaptureScreenShotXml(true) }.getOrNull().orEmpty()
        val topK = intent?.getIntExtra("k", 8)?.coerceIn(1, 50) ?: 8

        val result = runCatching {
            OobOmniFlowToolkitService(appContext).recall(
                linkedMapOf(
                    "goal" to goal,
                    "current_package" to currentPackage,
                    "current_xml" to currentXml,
                    "k" to topK,
                )
            )
        }.getOrElse { error ->
            linkedMapOf<String, Any?>(
                "success" to false,
                "phase" to "exception",
                "error_message" to error.message.orEmpty(),
                "error_type" to error.javaClass.name,
            )
        }
        val payload = linkedMapOf<String, Any?>(
            "goal" to goal,
            "current_package" to currentPackage,
            "current_xml_present" to currentXml.isNotBlank(),
            "current_xml_chars" to currentXml.length,
            "recall" to result,
        )
        val json = gson.toJson(payload)
        File(appContext.filesDir, "debug-oob-recall-result.json").writeText(json)
        OmniLog.i(TAG, json)
    }

    private fun Intent?.decodeBase64Extra(name: String): String? {
        val raw = this?.getStringExtra(name)?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return runCatching {
            String(Base64.decode(raw, Base64.DEFAULT), Charsets.UTF_8).trim()
                .takeIf { it.isNotEmpty() }
        }.getOrNull()
    }

    companion object {
        private const val TAG = "DebugOobRecallReceiver"
        private val gson = GsonBuilder().disableHtmlEscaping().create()
    }
}
