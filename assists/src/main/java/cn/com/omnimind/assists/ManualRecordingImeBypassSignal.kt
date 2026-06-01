package cn.com.omnimind.assists

import cn.com.omnimind.baselib.util.OmniLog

/**
 * Lightweight one-way signal from the assists recorder to the UIKit overlay.
 *
 * Text-change accessibility events are the earliest reliable indication that
 * the IME is active. The callback must never do blocking work on the
 * accessibility event thread.
 */
object ManualRecordingImeBypassSignal {
    private const val TAG = "ManualRecordingImeBypassSignal"

    private val lock = Any()
    private var listener: (() -> Unit)? = null

    fun setListener(callback: (() -> Unit)?) {
        synchronized(lock) {
            listener = callback
        }
    }

    fun clearListener() {
        synchronized(lock) {
            listener = null
        }
    }

    fun notifyTextInputObserved() {
        val callback = synchronized(lock) { listener }
        runCatching { callback?.invoke() }
            .onFailure { OmniLog.w(TAG, "IME bypass callback failed: ${it.message}") }
    }
}
