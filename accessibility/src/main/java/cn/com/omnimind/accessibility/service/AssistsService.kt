package cn.com.omnimind.accessibility.service

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.hardware.display.DisplayManager
import android.view.MotionEvent
import android.view.accessibility.AccessibilityEvent
import android.view.autofill.AutofillManager
import androidx.annotation.CallSuper
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ServiceLifecycleDispatcher
import cn.com.omnimind.accessibility.action.ScreenStateListener
import cn.com.omnimind.accessibility.action.ScreenStateReceiver
import cn.com.omnimind.baselib.util.OmniLog
import java.util.Collections


open class AssistsService : AccessibilityService(), LifecycleOwner {
    private val dispatcher = ServiceLifecycleDispatcher(this)
    private val screenStateListeners =
        Collections.synchronizedList(arrayListOf<ScreenStateListener>())

    private var screenStateReceiver: ScreenStateReceiver? = null
    private var mAutofillManager: AutofillManager? = null
    private var lastOperableState: Boolean? = null // 记录上次可操作状态，避免重复通知

    companion object {

        private const val TAG = "AssistsService"

        var instance: AssistsService? = null
            private set
        fun isInit(): Boolean{
            return instance != null
        }

        val listeners: MutableList<AssistsServiceListener> =
            Collections.synchronizedList(arrayListOf<AssistsServiceListener>())

        fun addListener(listener: AssistsServiceListener) {
            listeners.add(listener)
        }

        fun removeListener(listener: AssistsServiceListener) {
            listeners.remove(listener)
        }

        // 添加屏幕状态监听器管理
        fun addScreenStateListener(listener: ScreenStateListener) {
            instance?.screenStateListeners?.add(listener)
        }

        fun removeScreenStateListener(listener: ScreenStateListener) {
            instance?.screenStateListeners?.remove(listener)
        }


    }

    override fun onCreate() {
        super.onCreate()
        dispatcher.onServicePreSuperOnCreate()
        mAutofillManager = getSystemService<AutofillManager?>(AutofillManager::class.java)
        OmniLog.i(TAG, "AssistsService is start")
        instance = this

        // 注册屏幕状态监听
        registerScreenStateReceiver()
    }

    /**
     * 注册屏幕状态广播接收器
     */
    private fun registerScreenStateReceiver() {

        screenStateReceiver = ScreenStateReceiver(object : ScreenStateListener {
            override fun onLocked() {
                OmniLog.d(TAG, "Device locked - notifying ${screenStateListeners.size} listeners")
                // 通知所有注册的监听器
                screenStateListeners.forEach { listener ->
                    try {
                        listener.onLocked()
                    } catch (e: Exception) {
                        OmniLog.e(TAG, "Error in lock state listener: ${e.message}", e)
                    }
                }
            }

            override fun onUnlocked() {
                OmniLog.d(TAG, "Device unlocked - notifying ${screenStateListeners.size} listeners")
                // 通知所有注册的监听器
                screenStateListeners.forEach { listener ->
                    try {
                        listener.onUnlocked()
                    } catch (e: Exception) {
                        OmniLog.e(TAG, "Error in unlock state listener: ${e.message}", e)
                    }
                }
            }

            override fun onOperableStateChanged(isOperable: Boolean) {
                // 只在状态变化时通知，避免重复通知
                if (lastOperableState != isOperable) {
                    lastOperableState = isOperable
                    OmniLog.d(
                        TAG,
                        "Operable state changed: ${if (isOperable) "Operable" else "Not Operable"} - notifying ${screenStateListeners.size} listeners"
                    )
                    // 通知所有注册的监听器
                    screenStateListeners.forEach { listener ->
                        try {
                            listener.onOperableStateChanged(isOperable)
                        } catch (e: Exception) {
                            OmniLog.e(TAG, "Error in operable state listener: ${e.message}", e)
                        }
                    }
                }
            }
        })
        try {

            ScreenStateReceiver.register(this, screenStateReceiver!!)

        } catch (e: Exception) {
            OmniLog.e(TAG, "Failed to register screen state receiver: ${e.message}", e)
        }
    }


    /**
     * 注销屏幕状态广播接收器
     */
    private fun unregisterScreenStateReceiver() {
        screenStateReceiver?.let { receiver ->
            try {
                ScreenStateReceiver.unregister(this, receiver)
                OmniLog.d(TAG, "Screen state receiver unregistered")
            } catch (e: Exception) {
                OmniLog.e(TAG, "Failed to unregister screen state receiver: ${e.message}", e)
            }
        }
        screenStateReceiver = null
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this

        runCatching { listeners.forEach { it.onServiceConnected(this) } }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        // 获取选中文字所在的视图节点
        instance = this
        runCatching { listeners.forEach { it.onAccessibilityEvent(event) } }
    }

    override fun onUnbind(intent: Intent?): Boolean {
        if (instance === this) {
            instance = null
        }
        runCatching { listeners.forEach { it.onUnbind() } }
        return super.onUnbind(intent)
    }

    override fun onInterrupt() {
        runCatching { listeners.forEach { it.onInterrupt() } }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (instance === this) {
            instance = null
        }
        dispatcher.onServicePreSuperOnDestroy()
        // 注销屏幕状态监听
        unregisterScreenStateReceiver()
    }


    @Deprecated("Deprecated in Java")
    @Suppress("DEPRECATION")
    @CallSuper
    override fun onStart(intent: Intent?, startId: Int) {
        dispatcher.onServicePreSuperOnStart()
        super.onStart(intent, startId)
    }

    // this method is added only to annotate it with @CallSuper.
    // In usual Service, super.onStartCommand is no-op, but in LifecycleService
    // it results in dispatcher.onServicePreSuperOnStart() call, because
    // super.onStartCommand calls onStart().
    @CallSuper
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return super.onStartCommand(intent, flags, startId)
    }

    override fun onMotionEvent(event: MotionEvent) {
    }

    fun hideKeyboard() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
            softKeyboardController.showMode = AccessibilityService.SHOW_MODE_HIDDEN
        }
    }

    fun restoreKeyboard() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
            softKeyboardController.showMode = AccessibilityService.SHOW_MODE_AUTO
        }
    }


    override val lifecycle: Lifecycle
        get() = dispatcher.lifecycle
}
