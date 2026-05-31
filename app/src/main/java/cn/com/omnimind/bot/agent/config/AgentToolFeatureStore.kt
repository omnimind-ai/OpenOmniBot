package cn.com.omnimind.bot.agent.config

import android.content.Context

object AgentToolFeatureStore {
    private const val PREFS_NAME = "agent_tool_features"
    private const val KEY_OOB_FUNCTION_AS_TOOL = "oob_function_as_tool_enabled"
    private const val KEY_OOB_FUNCTION_AS_TOOL_USER_SET = "oob_function_as_tool_user_set"

    fun isOobFunctionAsToolEnabled(context: Context): Boolean {
        val prefs = context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (!isOobFunctionAsToolUserSet(context)) {
            return true
        }
        return prefs.getBoolean(KEY_OOB_FUNCTION_AS_TOOL, true)
    }

    fun isOobFunctionAsToolUserSet(context: Context): Boolean {
        val prefs = context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getBoolean(KEY_OOB_FUNCTION_AS_TOOL_USER_SET, false)
    }

    fun setOobFunctionAsToolEnabled(context: Context, enabled: Boolean) {
        context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_OOB_FUNCTION_AS_TOOL, enabled)
            .putBoolean(KEY_OOB_FUNCTION_AS_TOOL_USER_SET, true)
            .apply()
    }

    fun clearOobFunctionAsToolEnabled(context: Context) {
        context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY_OOB_FUNCTION_AS_TOOL)
            .remove(KEY_OOB_FUNCTION_AS_TOOL_USER_SET)
            .apply()
    }

    fun getFeatures(context: Context): Map<String, Any?> = linkedMapOf(
        "oobFunctionAsToolEnabled" to isOobFunctionAsToolEnabled(context),
        "oobFunctionAsToolDefaultEnabled" to true,
        "oobFunctionAsToolUserSet" to isOobFunctionAsToolUserSet(context),
    )
}
