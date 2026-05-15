package cn.com.omnimind.bot.share

import android.content.Context

object SharedOpenPreferenceStore {
    const val MODE_DEFAULT = "default"
    const val MODE_WORKSPACE = "workspace"

    private const val PREFS_NAME = "shared_open_preferences"
    private const val KEY_OPEN_MODE = "open_mode"
    private const val KEY_IMAGE_OPEN_MODE = "image_open_mode"
    private const val KEY_FILE_OPEN_MODE = "file_open_mode"

    fun getImageOpenMode(context: Context): String {
        return getTypedOpenMode(context, KEY_IMAGE_OPEN_MODE)
    }

    fun setImageOpenMode(context: Context, mode: String): String {
        return setTypedOpenMode(context, KEY_IMAGE_OPEN_MODE, mode)
    }

    fun getFileOpenMode(context: Context): String {
        return getTypedOpenMode(context, KEY_FILE_OPEN_MODE)
    }

    fun setFileOpenMode(context: Context, mode: String): String {
        return setTypedOpenMode(context, KEY_FILE_OPEN_MODE, mode)
    }

    fun getOpenModes(context: Context): Map<String, String> = mapOf(
        "imageMode" to getImageOpenMode(context),
        "fileMode" to getFileOpenMode(context),
    )

    fun getOpenMode(context: Context): String {
        return getImageOpenMode(context)
    }

    fun setOpenMode(context: Context, mode: String): String {
        val normalized = normalizeOpenMode(mode)
        prefs(context)
            .edit()
            .putString(KEY_OPEN_MODE, normalized)
            .putString(KEY_IMAGE_OPEN_MODE, normalized)
            .putString(KEY_FILE_OPEN_MODE, normalized)
            .apply()
        return normalized
    }

    fun normalizeOpenMode(mode: String?): String {
        return when (mode?.trim()?.lowercase()) {
            MODE_WORKSPACE -> MODE_WORKSPACE
            else -> MODE_DEFAULT
        }
    }

    private fun getTypedOpenMode(context: Context, key: String): String {
        val preferences = prefs(context)
        val saved = preferences.getString(key, null)
            ?: preferences.getString(KEY_OPEN_MODE, MODE_DEFAULT)
        return normalizeOpenMode(saved)
    }

    private fun setTypedOpenMode(context: Context, key: String, mode: String): String {
        val normalized = normalizeOpenMode(mode)
        prefs(context).edit().putString(key, normalized).apply()
        return normalized
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
