package cn.com.omnimind.bot.runlog

import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cn.com.omnimind.accessibility.service.AssistsService
import cn.com.omnimind.baselib.runlog.InternalRunLogStore
import cn.com.omnimind.bot.mcp.VlmTaskRequest
import cn.com.omnimind.bot.vlm.VlmToolCoordinator
import cn.com.omnimind.bot.vlm.VlmToolOutcomeStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.FileInputStream

@RunWith(AndroidJUnit4::class)
class VlmRunLogConversionInstrumentedTest {
    @Test
    fun executeVlmThenConvertItsRunLog() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val goal = "打开 Settings"
        val testScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        var functionId = ""

        try {
            prepareDeviceForVlm(context.packageName)
            waitForAccessibilityService()

            val outcome = VlmToolCoordinator.executeNewTask(
                context = context,
                request = VlmTaskRequest(
                    goal = goal,
                    packageName = "com.android.settings",
                    maxSteps = 1,
                    needSummary = false,
                ),
                scope = testScope,
            )

            println("VLM_RUNLOG_TEST outcome=${outcome.toPayload()}")
            assertEquals(outcome.message, VlmToolOutcomeStatus.FINISHED, outcome.status)

            val runId = outcome.taskId
            functionId = "oob_${runId.replace('-', '_')}"
            val record = InternalRunLogStore.getRun(context, runId)
            println("VLM_RUNLOG_TEST runlog=$record")
            assertNotNull("VLM task should persist InternalRunLog with taskId", record)
            assertEquals(true, record!!.success)
            assertTrue("VLM runlog should contain at least one card", record.cards.isNotEmpty())

            val convert = OobOmniFlowToolkitService(context).convertRunLog(
                mapOf(
                    "run_id" to runId,
                    "register" to true,
                    "function_id" to functionId,
                    "name" to "Open Settings via VLM runlog",
                    "description" to goal,
                )
            )
            println("VLM_RUNLOG_TEST convert=$convert")
            assertEquals(convert["error_message"]?.toString().orEmpty(), true, convert["success"])
            assertEquals(true, convert["registered"])
            assertEquals(runId, convert["run_id"])
            assertEquals(functionId, convert["function_id"])
        } finally {
            if (functionId.isNotBlank()) {
                OobRunLogReplayService(context).deleteFunction(functionId)
            }
            testScope.cancel()
        }
    }

    private suspend fun prepareDeviceForVlm(packageName: String) {
        val component = "$packageName/com.google.android.accessibility.selecttospeak.SelectToSpeakService"
        shell("settings put secure enabled_accessibility_services $component")
        shell("settings put secure accessibility_enabled 1")
        shell("appops set $packageName SYSTEM_ALERT_WINDOW allow")
    }

    private suspend fun waitForAccessibilityService() {
        repeat(50) {
            if (AssistsService.instance != null) return
            delay(200)
        }
        assertNotNull("OOB accessibility service should bind before VLM starts", AssistsService.instance)
    }

    private fun shell(command: String): String {
        val fd: ParcelFileDescriptor = InstrumentationRegistry.getInstrumentation()
            .uiAutomation
            .executeShellCommand(command)
        return try {
            FileInputStream(fd.fileDescriptor).bufferedReader().use { it.readText() }
        } finally {
            fd.close()
        }
    }
}
