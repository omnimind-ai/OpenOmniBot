package cn.com.omnimind.bot.agent.tool.handlers

import cn.com.omnimind.baselib.util.OmniLog
import cn.com.omnimind.bot.omniflow.OobFunctionJson.firstNonBlank
import cn.com.omnimind.bot.omniflow.OobFunctionJson.mapArg
import cn.com.omnimind.bot.runlog.OmniflowActionRuntime
import cn.com.omnimind.bot.runlog.OmniflowStepExecutor
import cn.com.omnimind.bot.runlog.RunLogReplayPolicy

/**
 * Runtime guard that restores the Function's entry package before replay when
 * the user has navigated away and the recorded Function does not already start
 * with an explicit open_app step.
 */
class OobFunctionEntryPackageGuard {
    suspend fun ensureForeground(steps: List<Map<String, Any?>>) {
        val entryPackage = entryPackageForSteps(steps).takeIf { it.isNotBlank() } ?: return
        val currentPackage = runCatching {
            OmniflowActionRuntime.backend.currentPackageName()?.trim().orEmpty()
        }.getOrDefault("")
        if (currentPackage.isBlank() || currentPackage == entryPackage) return
        val firstAction = OmniflowStepExecutor.actionNameForStep(steps.first())
        if (firstAction == "open_app") return

        OmniLog.d(TAG, "global open_app: current=$currentPackage expected=$entryPackage")
        runCatching {
            OmniflowStepExecutor.execute(
                step = openAppStep(entryPackage),
                stepId = "global_open_app",
                stepTitle = "open_app: $entryPackage",
            )
        }.onFailure {
            OmniLog.w(TAG, "global open_app failed for $entryPackage: ${it.message}")
        }
    }

    private fun entryPackageForSteps(steps: List<Map<String, Any?>>): String {
        for (step in steps) {
            if (OmniflowStepExecutor.actionNameForStep(step) == "open_app") {
                val args = mapArg(step["args"])
                val pkg = firstNonBlank(args["package_name"], args["packageName"])
                if (pkg.isNotBlank()) return pkg
            }
        }
        for (step in steps) {
            val srcCtx = mapArg(mapArg(step["source_context"])["src_ctx"])
            val pkg = firstNonBlank(srcCtx["package_name"], srcCtx["packageName"])
            if (pkg.isNotBlank() && pkg !in IGNORED_ENTRY_PACKAGES && !pkg.startsWith("cn.com.omnimind")) {
                return pkg
            }
        }
        return ""
    }

    private fun openAppStep(packageName: String): Map<String, Any?> =
        linkedMapOf(
            "id" to "global_open_app",
            "title" to "open_app: $packageName",
            "kind" to "omniflow_action",
            "executor" to RunLogReplayPolicy.EXECUTOR_OMNIFLOW,
            "omniflow_action" to "open_app",
            "local_action" to "open_app",
            "model_free" to true,
            "tool" to "open_app",
            "callable_tool" to "open_app",
            "args" to linkedMapOf("package_name" to packageName, "reset_task" to false),
        )

    private companion object {
        const val TAG = "OobFunctionEntryPackageGuard"
        val IGNORED_ENTRY_PACKAGES = setOf("android", "com.android.systemui")
    }
}
