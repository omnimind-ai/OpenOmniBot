package cn.com.omnimind.bot.util

import cn.com.omnimind.accessibility.action.ScreenCaptureManager
import android.app.AppOpsManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Process
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import androidx.core.net.toUri
import cn.com.omnimind.assists.AssistsCore
import cn.com.omnimind.assists.api.bean.TaskParams
import cn.com.omnimind.assists.api.interfaces.OnMessagePushListener
import cn.com.omnimind.assists.task.scheduled.worker.ScheduledParams
import cn.com.omnimind.assists.task.scheduled.worker.ScheduledStates
import cn.com.omnimind.baselib.util.APPPackageUtil
import cn.com.omnimind.baselib.util.MobileManufacturerUtil
import cn.com.omnimind.baselib.util.exception.PermissionException
import cn.com.omnimind.bot.App
import cn.com.omnimind.bot.manager.OmniForegroundService
import cn.com.omnimind.bot.util.AssistsUtil.Core.createCompanionTask
import cn.com.omnimind.uikit.UIKit
import cn.com.omnimind.uikit.api.callback.HalfScreenApi
import java.util.concurrent.TimeUnit


private val TAG = "AssistsUtil"

class AssistsUtil {
    object Core {
        /**
         * 初始化
         */
        fun initCore(context: Context) =
            AssistsCore.initCore(context)

        fun initCore(context: Context, halfScreenApi: HalfScreenApi) =
            UIKit.init(context, halfScreenApi)

        /**
         * 状态机是否初始化
         */
        fun isInitialized() = AssistsCore.isStateMachineInitialized();

        /**
         * 辅助服务知否已经执行
         */
        fun isAccessibilityServiceEnabled() = AssistsCore.isAccessibilityServiceEnabled()


        /**
         * 创建陪伴任务

         * @param onMessagePushListener 消息推送监听器
         * @sample createCompanionTask("创建陪伴任务", "[{"你好"},{"你好,我能为你做什么?"}]", object : OnMessagePushListener {})
         */
        suspend fun createCompanionTask(
            context: Context,
            onMessagePushListener: OnMessagePushListener
        ) {
            if (!Settings.canDrawOverlays(context)) {
                throw PermissionException("请先开启悬浮窗权限!")
            }
            AssistsCore.startTask(TaskParams.CompanionTaskParams {
                // startForegroundService(context)
                onMessagePushListener.onTaskFinish()
            })
        }

        private fun jumpToHonorAutoStartSettings() {
            try {
                val intent = Intent().apply {
                    component = ComponentName(
                        "com.hihonor.systemmanager",
                        "com.hihonor.systemmanager.startupmgr.ui.StartupNormalAppListActivity"
                    )
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                App.instance.startActivity(intent)
            } catch (e: Exception) {
                // 页面不存在或其他异常，可以跳转到通用设置页面
                jumpToGeneralSettings()
            }
        }

        private fun jumpToGeneralSettings() {
            // 跳转到应用详情页面作为备选方案
            val intent = Intent().apply {
                action = Settings.ACTION_APPLICATION_DETAILS_SETTINGS
                data = Uri.parse("package:${App.instance.packageName}")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            App.instance.startActivity(intent)
        }

        private fun checkAutoStartPermission(): Boolean {
            // 注意：此方法仅适用于部分华为设备
            return try {
                val pm = App.instance.packageManager
                val intent = Intent()
                intent.component = ComponentName(
                    "com.hihonor.systemmanager",  // 荣耀系统管理器包名
                    "com.hihonor.systemmanager.startupmgr.ui.StartupNormalAppListActivity"
                )
                val list = pm.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
                list.isNotEmpty()
            } catch (e: Exception) {
                false
            }
        }

        private fun stopForegroundService(context: Context) {
            val serviceIntent = Intent(context, OmniForegroundService::class.java)
            context.stopService(serviceIntent)
        }

        private fun startForegroundService(context: Context) {
            val serviceIntent = Intent(context, OmniForegroundService::class.java)
            serviceIntent.putExtra("inputExtra", "服务正在运行...")
            context.startForegroundService(serviceIntent)
        }

        fun isCompanionTaskRunning(): Boolean = AssistsCore.isCompanionTaskRunning()

        /**
         * 取消陪伴任务的回到桌面操作
         */
        fun cancelCompanionGoHome() = AssistsCore.cancelCompanionGoHome()

        /**
         * 结束任务
         */
        fun finishTask(context: Context) {
            AssistsCore.finishCompanionTask()
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
                if (ScreenCaptureManager.getInstance().hasPermission()) {
                    ScreenCaptureManager.getInstance().release()
                }
            }
        }

        /**
         * 取消正在运行或等待中的任务，不影响陪伴模式
         * 可在预执行 delay 期间取消任务
         */
        fun cancelRunningTask(taskId: String? = null): Boolean {
            return AssistsCore.cancelPendingTask(taskId)
        }

        /**
         * 取消聊天任务
         */
        fun cancelChatTask(taskId: String? = null) = AssistsCore.cancelChatTask(taskId)
        fun createChatTask(
            taskId: String,
            content: List<Map<String, Any>>,
            onMessagePush: OnMessagePushListener,
            provider: String? = null,
            openClawConfig: TaskParams.OpenClawConfig? = null,
            modelOverride: TaskParams.ChatModelOverride? = null,
            reasoningEffort: String? = null
        ) {
            AssistsCore.startTask(
                TaskParams.ChatTaskParams(
                    taskId,
                    content,
                    onMessagePush,
                    provider,
                    openClawConfig,
                    modelOverride,
                    reasoningEffort
                )
            )
        }

        suspend fun createVLMOperationTask(
            context: Context,
            goal: String,
            model: String?,
            maxSteps: Int?,
            packageName: String?,
            onMessagePushListener: OnMessagePushListener,
            needSummary: Boolean = false,
            skipGoHome: Boolean = false,  // 是否跳过回到主页，从当前页面开始执行
            stepSkillGuidance: String = "",
            taskId: String? = null
        ) {

            if (!AssistsCore.isAccessibilityServiceEnabled()) {
                throw PermissionException("请先开无障碍服务!")
            }
            if (!Settings.canDrawOverlays(context)) {
                throw PermissionException("请先开启悬浮窗权限!")
            }
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
                if (!ScreenCaptureManager.getInstance().hasPermission()) {
                    val hasPermission =
                        ScreenCaptureManager.getInstance().requestScreenCapturePermission()
                    if (!hasPermission) {
                        throw PermissionException("请先授予屏幕截图权限!")
                    }
                }
            }
            AssistsCore.startTask(
                TaskParams.VLMOperationTaskParams(
                    goal,
                    model,
                    maxSteps,
                    packageName,
                    {
                        onMessagePushListener.onVLMTaskFinish()
                    },
                    needSummary,
                    onMessagePushListener,
                    skipGoHome,
                    stepSkillGuidance,
                    taskId
                )
            )
        }

        /**
         * 提供用户输入给正在运行的VLM任务
         * 用于响应INFO动作的用户回复
         */
        fun provideUserInputToVLMTask(userInput: String): Boolean {
            return AssistsCore.provideUserInputToVLMTask(userInput)
        }

        fun pauseVLMTask(): Boolean {
            return AssistsCore.pauseVLMTask()
        }

        fun resumeVLMTask(): Boolean {
            return AssistsCore.resumeVLMTask()
        }

        fun appendVlmExternalMemory(memory: String): Boolean {
            return AssistsCore.appendVlmExternalMemory(memory)
        }

        /**
         * Append a priority event to the VLM task
         * @param memory The event message
         * @param eventType The event type (e.g., "file_received")
         * @param suggestCompletion Whether to suggest VLM complete the task
         */
        fun appendVlmPriorityEvent(memory: String, eventType: String, suggestCompletion: Boolean = false): Boolean {
            return AssistsCore.appendVlmPriorityEvent(memory, eventType, suggestCompletion)
        }

        /**
         * 通知VLM任务总结Sheet已准备就绪
         * ChatBotSheet加载完成后调用此方法，VLM任务会开始推送总结消息
         */
        fun notifySummarySheetReady(): Boolean {
            return AssistsCore.notifySummarySheetReady()
        }

        suspend fun scheduleVLMOperationTask(
            context: Context,
            goal: String,
            model: String?,
            maxSteps: Int?,
            packageName: String?,
            times: Long,
            title: String,
            subTitle: String?,
            extraJson: String?,
            onMessagePushListener: OnMessagePushListener,
            needSummary: Boolean = false
        ) {
            if (!AssistsCore.isAccessibilityServiceEnabled()) {
                throw PermissionException("请先开无障碍服务!")
            }
            if (!Settings.canDrawOverlays(context)) {
                throw PermissionException("请先开启悬浮窗权限!")
            }
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
                if (!ScreenCaptureManager.getInstance().hasPermission()) {
                    val hasPermission =
                        ScreenCaptureManager.getInstance().requestScreenCapturePermission()
                    if (!hasPermission) {
                        throw PermissionException("请先授予屏幕截图权限!")
                    }
                }
            }
            val taskParams =
                TaskParams.ScheduledVLMOperationTaskParams(
                    title,
                    subTitle,
                    extraJson,
                    goal,
                    model,
                    maxSteps,
                    packageName,
                    "",
                    needSummary = needSummary,
                    onMessagePushListener = onMessagePushListener
                )
            AssistsCore.startTask(
                TaskParams.ScheduledTaskParams(taskParams, times, TimeUnit.SECONDS) {
                    onMessagePushListener.onTaskFinish()
                }
            )
        }

        fun getScheduleStatus(): ScheduledStates? {
            return AssistsCore.getScheduleStatus()
        }

        fun getScheduleParams(): ScheduledParams? {
            return AssistsCore.getScheduleParams()
        }

        fun clearScheduleTask() {
            AssistsCore.clearScheduleTask()
        }

        fun doScheduleNow() {
            AssistsCore.doScheduleNow()
        }

        fun cancelScheduleTask() {
            AssistsCore.cancelScheduleTask()
        }

        /**
         * 检查指定包名是否已授权
         * 从 MMKV 读取 Flutter 层存储的黑名单应用列表
         * 黑名单机制：不在黑名单中的应用默认授权
         */
        fun isPackageAuthorized(packageName: String): Boolean {
            return APPPackageUtil.isPackageAuthorized(packageName)
        }


        suspend fun startFirstUse(
            context: Context,
            onMessagePushListener: OnMessagePushListener,
            packageName: String
        ) {
            if (!AssistsCore.isAccessibilityServiceEnabled()) {
                throw PermissionException("请先开无障碍服务!")
            }
            if (!Settings.canDrawOverlays(context)) {
                throw PermissionException("请先开启悬浮窗权限!")
            }
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
                if (!ScreenCaptureManager.getInstance().hasPermission()) {
                    val hasPermission =
                        ScreenCaptureManager.getInstance().requestScreenCapturePermission()
                    if (!hasPermission) {
                        throw PermissionException("请先授予屏幕截图权限!")
                    }
                }
            }
            AssistsCore.startFirstUse({
                onMessagePushListener.onTaskFinish()
            }, packageName)
        }
    }

    object Setting {
        /**
         * 打开辅助功能设置页面
         * @param context 上下文
         * @param result 方法调用结果
         */
        fun openAccessibilitySettings(context: Context) {
            val intent =
                Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
            context.startActivity(intent)
        }


        /**
         * 检查是否忽略电池优化
         * @param result 方法调用结果
         * @param context 上下文
         */
        fun isIgnoringBatteryOptimizations(context: Context): Boolean {
            val powerManager =
                context.getSystemService(Context.POWER_SERVICE) as PowerManager
            return powerManager.isIgnoringBatteryOptimizations(context.packageName);

        }

        fun isBackgroundRunAllowed(context: Context): Boolean {
            if (isIgnoringBatteryOptimizations(context)) {
                return true
            }
            if (!MobileManufacturerUtil.isXiaomiSeries()) {
                return false
            }
            val compatibilityAllowed = isXiaomiBackgroundRunAllowed(context)
            Log.d(TAG, "Xiaomi background run compatibility result=$compatibilityAllowed")
            return compatibilityAllowed
        }

        private fun isXiaomiBackgroundRunAllowed(context: Context): Boolean {
            val appOpsManager =
                context.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager
                    ?: return false
            val uid = context.applicationInfo.uid.takeIf { it > 0 } ?: Process.myUid()
            val packageName = context.packageName
            val runAnyMode = readAppOpMode(
                appOpsManager = appOpsManager,
                operation = OPSTR_RUN_ANY_IN_BACKGROUND,
                uid = uid,
                packageName = packageName
            )
            val runMode = readAppOpMode(
                appOpsManager = appOpsManager,
                operation = OPSTR_RUN_IN_BACKGROUND,
                uid = uid,
                packageName = packageName
            )
            Log.d(TAG, "Xiaomi background run app ops: runAny=$runAnyMode, run=$runMode")
            val knownModes = listOf(runAnyMode, runMode).filterNotNull()
            if (knownModes.isEmpty()) {
                return false
            }
            val explicitlyBlocked = knownModes.any { mode ->
                mode == AppOpsManager.MODE_IGNORED ||
                        mode == AppOpsManager.MODE_ERRORED ||
                        mode == AppOpsManager.MODE_FOREGROUND
            }
            if (explicitlyBlocked) {
                return false
            }
            return knownModes.all { mode ->
                mode == AppOpsManager.MODE_ALLOWED || mode == AppOpsManager.MODE_DEFAULT
            }
        }

        private fun readAppOpMode(
            appOpsManager: AppOpsManager,
            operation: String,
            uid: Int,
            packageName: String
        ): Int? {
            return runCatching {
                appOpsManager.unsafeCheckOpNoThrow(operation, uid, packageName)
            }.getOrElse { error ->
                Log.d(TAG, "readAppOpMode failed for $operation: ${error.message}")
                null
            }
        }

        /**
         * 打开电池优化设置页面
         * @param context 上下文
         * @param result 方法调用结果
         */
        private const val OPSTR_RUN_ANY_IN_BACKGROUND = "android:run_any_in_background"
        private const val OPSTR_RUN_IN_BACKGROUND = "android:run_in_background"

        fun openBatteryOptimizationSettings(context: Context) {
            val intent =
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = "package:${context.packageName}".toUri()
                }
            context.startActivity(intent)
        }

        fun isOverlayPermission(context: Context): Boolean {
            return Settings.canDrawOverlays(context)
        }

        fun openOverlaySettings(context: Context) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                ("package:" + context.packageName).toUri()
            )
            context.startActivity(intent)
        }

        /**
         * 检查是否有获取已安装应用列表的权限
         * @param context 上下文
         * @return 是否已授权
         */
        fun isInstalledAppsPermissionGranted(context: Context): Boolean {
            return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                // Android 11 (API 30) 及以上版本
                // 尝试获取已安装应用列表，如果能获取到较多应用，说明有权限
                try {
                    val packages = context.packageManager.getInstalledApplications(0)
                    Log.d(TAG, "packages.size ${packages.size}")
                    return packages.size > 1
                } catch (e: Exception) {
                    Log.d(TAG, "isInstalledAppsPermissionGranted error:${e}")
                    false
                }
            } else {
                // Android 11 以下版本默认有权限
                true
            }
        }

        /**
         * 打开获取已安装应用列表权限设置页面
         * @param context 上下文
         */
        fun openInstalledAppsSettings(context: Context) {
            val intent = Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                ("package:" + context.packageName).toUri()
            ).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
        }

        /**
         * 打开应用启动管理设置页面（华为和荣耀设备）
         * @param context 上下文
         */
        fun openAutoStartSettings(context: Context) {
            val brand = android.os.Build.BRAND?.lowercase() ?: ""

            when {
                brand == "honor" || brand == "荣耀" -> {
                    // 荣耀设备
                    openHonorAutoStartSettings(context)
                }

                brand == "huawei" || brand == "华为" -> {
                    // 华为设备
                    openHuaweiAutoStartSettings(context)
                }

                else -> {
                    // 其他设备，跳转到应用详情页
                    openAppDetailSettings(context)
                }
            }
        }

        /**
         * 打开荣耀自启动设置页面
         */
        private fun openHonorAutoStartSettings(context: Context) {
            try {
                val intent = Intent().apply {
                    component = ComponentName(
                        "com.hihonor.systemmanager",
                        "com.hihonor.systemmanager.startupmgr.ui.StartupNormalAppListActivity"
                    )
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                context.startActivity(intent)
            } catch (e: Exception) {
                // 尝试备用页面
                try {
                    val intent = Intent().apply {
                        component = ComponentName(
                            "com.hihonor.systemmanager",
                            "com.hihonor.systemmanager.startupmgr.ui.StartupAppListActivity"
                        )
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                    context.startActivity(intent)
                } catch (e2: Exception) {
                    // 跳转到应用详情页作为备选方案
                    openAppDetailSettings(context)
                }
            }
        }

        /**
         * 打开华为自启动设置页面
         */
        private fun openHuaweiAutoStartSettings(context: Context) {
            try {
                val intent = Intent().apply {
                    component = ComponentName(
                        "com.huawei.systemmanager",
                        "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"
                    )
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                context.startActivity(intent)
            } catch (e: Exception) {
                // 尝试备用页面
                try {
                    val intent = Intent().apply {
                        component = ComponentName(
                            "com.huawei.systemmanager",
                            "com.huawei.systemmanager/.appcontrol.activity.StartupAppControlActivity"
                        )
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                    context.startActivity(intent)
                } catch (e2: Exception) {
                    // 跳转到应用详情页作为备选方案
                    openAppDetailSettings(context)
                }
            }
        }

        /**
         * 打开应用详情页面（备选方案）
         */
        private fun openAppDetailSettings(context: Context) {
            val intent = Intent().apply {
                action = Settings.ACTION_APPLICATION_DETAILS_SETTINGS
                data = ("package:${context.packageName}").toUri()
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
        }

    }

    object UI {
        suspend fun closeScreenDialog() {
            UIKit.uiChatEvent?.dismissHalfScreen()
//            AssistsCore.closeScreenDialog()
        }

        suspend fun closeChatBotDialog() {
            UIKit.uiChatEvent?.closeChatBotBg()

//            AssistsCore.closeChatBotDialog()
        }

        fun isChatBotDialogShowing(): Boolean {
            return UIKit.uiChatEvent?.isChatBotHalfScreenShowing() == true
        }

    }
}
