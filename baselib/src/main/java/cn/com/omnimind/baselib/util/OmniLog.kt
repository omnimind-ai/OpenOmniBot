package cn.com.omnimind.baselib.util

import android.util.Log

object OmniLog {
    enum class Level {
        VERBOSE,
        DEBUG,
        INFO,
        WARN,
        ERROR,
        ASSERT,
        DISABLE,
    }

    /**
     * log params
     */
    private var globalTag = "[Omni]"
    private var minLevel = Level.VERBOSE

    /**
     * Optional extra reporter to mirror logs elsewhere (e.g. testbot -> scheduler).
     * Default null to preserve existing behavior.
     */
    @Volatile
    var extraLogReporter: ((tag: String, message: String, level: String) -> Unit)? = null

    /**
     * Notify the extra reporter if set.
     */
    private fun notifyReporter(tag: String, message: String, level: String) {
        try {
            extraLogReporter?.invoke(tag, message, level)
        } catch (_: Exception) {
            // Never let reporting side effects impact business execution.
        }
    }

    /**
     * get Log level
     */
    fun getLogLevel(): Level = minLevel

    /**
     * set Log level
     * set to DISABLE means disable OmniLog
     */
    fun setLogLevel(level: Level) {
        this.minLevel = level
    }

    /**
     * Whether to mirror INFO and above logs to [RuntimeLogStore].
     */
    @Volatile
    var runtimeLogEnabled: Boolean = true

    private fun storeRuntimeLog(level: String, tag: String, message: String, throwable: Throwable?, isCrash: Boolean = false) {
        if (!runtimeLogEnabled) return
        if (tag == "RuntimeLogStore") return
        if (tag == "[AssistsCoreChannel]") return
        val stack = throwable?.let { buildStackTraceString(it) }
        RuntimeLogStore.append(
            RuntimeLogEntry(
                level = level,
                tag = tag,
                message = message,
                stackTrace = stack,
                isCrash = isCrash,
            )
        )
    }

    private fun buildStackTraceString(throwable: Throwable): String {
        return buildString {
            appendLine(throwable.toString())
            throwable.stackTrace.forEach { appendLine("    at $it") }
            var cause = throwable.cause
            while (cause != null) {
                appendLine("Caused by: $cause")
                cause.stackTrace.forEach { appendLine("    at $it") }
                cause = cause.cause
            }
        }
    }

    /**
     * log verbose
     * Place at the beginning of each function call to monitor function invocation
     */
    @JvmOverloads
    fun v(
        tag: String,
        message: String,
        throwable: Throwable? = null,
    ) {
        if (minLevel.ordinal > Level.VERBOSE.ordinal) return
        val actualTag = globalTag + tag
        Log.v(actualTag, message, throwable)
        notifyReporter(tag, message, "VERBOSE")
    }

    /**
     * log debug
     * Record event outcome details
     */
    @JvmOverloads
    fun d(
        tag: String,
        message: String,
        throwable: Throwable? = null,
    ) {
        if (minLevel.ordinal > Level.DEBUG.ordinal) return
        val actualTag = globalTag + tag
        Log.v(actualTag, message, throwable)
        notifyReporter(tag, message, "DEBUG")
    }

    /**
     * log info
     * Track user behavior and record critical data
     */
    @JvmOverloads
    fun i(
        tag: String,
        message: String,
        throwable: Throwable? = null,
    ) {
        if (minLevel.ordinal > Level.INFO.ordinal) return
        val actualTag = globalTag + tag
        Log.i(actualTag, message, throwable)
        notifyReporter(tag, message, "INFO")
        storeRuntimeLog("INFO", tag, message, throwable)
    }

    /**
     * log warn
     * Record handled exceptions that won't terminate the application
     */
    @JvmOverloads
    fun w(
        tag: String,
        message: String,
        throwable: Throwable? = null,
    ) {
        if (minLevel.ordinal > Level.WARN.ordinal) return
        val actualTag = globalTag + tag
        Log.w(actualTag, message, throwable)
        notifyReporter(tag, message, "WARN")
        storeRuntimeLog("WARN", tag, message, throwable)
    }

    /**
     * log error
     * Record serious failures that affect functionality
     */
    @JvmOverloads
    fun e(
        tag: String,
        message: String,
        throwable: Throwable? = null,
    ) {
        if (minLevel.ordinal > Level.ERROR.ordinal) return
        val actualTag = globalTag + tag
        Log.e(actualTag, message, throwable)
        notifyReporter(tag, message, "ERROR")
        storeRuntimeLog("ERROR", tag, message, throwable)
    }

    /**
     * log assert
     * Record critical errors that should never occur (what the fuck message)
     */
    @JvmOverloads
    fun wtf(
        tag: String,
        message: String,
        throwable: Throwable? = null,
    ) {
        if (minLevel.ordinal > Level.ASSERT.ordinal) return
        val actualTag = globalTag + tag
        Log.wtf(actualTag, message, throwable)
        notifyReporter(tag, message, "ASSERT")
        storeRuntimeLog("ASSERT", tag, message, throwable)
    }
}