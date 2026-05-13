package cn.com.omnimind.bot.agent.config

import android.content.Context

object AgentToolFeatureStore {
    private const val PREFS_NAME = "agent_tool_features"
    private const val KEY_OOB_FUNCTION_AS_TOOL = "oob_function_as_tool_enabled"

    fun isOobFunctionAsToolEnabled(context: Context): Boolean {
        return context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_OOB_FUNCTION_AS_TOOL, false)
    }

    fun setOobFunctionAsToolEnabled(context: Context, enabled: Boolean) {
        context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_OOB_FUNCTION_AS_TOOL, enabled)
            .apply()
    }

    fun getFeatures(context: Context): Map<String, Any?> = linkedMapOf(
        "oobFunctionAsToolEnabled" to isOobFunctionAsToolEnabled(context)
    )
}
