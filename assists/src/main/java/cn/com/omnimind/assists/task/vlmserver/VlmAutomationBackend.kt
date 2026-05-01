package cn.com.omnimind.assists.task.vlmserver

import android.content.Context
import cn.com.omnimind.assists.AssistsCore
import cn.com.omnimind.baselib.shizuku.ShizukuCapabilityManager

enum class VlmAutomationBackend {
    SHIZUKU,
    ACCESSIBILITY;

    companion object {
        fun resolve(context: Context): VlmAutomationBackend? {
            val appContext = context.applicationContext
            return when {
                ShizukuCapabilityManager.get(appContext).isGranted() -> SHIZUKU
                AssistsCore.isAccessibilityServiceEnabled() -> ACCESSIBILITY
                else -> null
            }
        }
    }
}

enum class VlmActionProtocol {
    OPENAI_TOOL_CALLS,
    DO_TEXT
}
