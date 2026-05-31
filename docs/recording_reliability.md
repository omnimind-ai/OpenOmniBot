# 手动录制可靠性文档

本文档描述 OOB 手动录制链路的已知问题、根本原因和修复规范，供长期维护参考。

---

## 架构概览

```
用户触摸
  └─ ManualTouchRecordLoader (overlay, main thread)
       └─ pendingGestures 队列
            └─ processGestureQueue (IO coroutine)
                 └─ processQueuedGesture
                      ├─ unlockTouchLocked()         ← 解锁 overlay，main thread
                      ├─ HumanTrajectoryLearningSession.recordOverlayGesture()
                      │    ├─ performOverlayGesture() ← GestureDescription，IO
                      │    ├─ onGestureDispatched()   ← 回调，withContext(Main)
                      │    └─ appendOverlayClickGesture / appendOverlaySwipeGesture
                      └─ lockTouchLocked()            ← 重锁 overlay，main thread

用户按暂停/完成
  └─ ManualRecordingControlOverlay
       ├─ pauseActive()   ← BUG: 主线程直接调用
       └─ completeActive() ← OK: recordingControlScope (IO)
```

---

## 已知 Bug

### BUG-1（严重）：handleCaptureClick 在主线程调用 pauseActive → 死锁 → ANR → 闪退

**文件**：`ManualRecordingControlOverlay.kt`，`handleCaptureClick()`

**触发条件**：用户在一次 overlay 手势正在处理时（`overlayGestureActiveCount > 0`），点击截图/暂停按钮。

**死锁路径**：
```
主线程:
  handleCaptureClick()
    HumanTrajectoryLearningSession.pauseActive()
      ManualVlmTraceRecorder.pause()
        awaitOverlayRecordJobs()                  ← 主线程 Object.wait()
          synchronized(recordingLock)
            while (overlayGestureActiveCount > 0)
              recordingLock.wait(100ms)            ← 阻塞主线程

IO 线程 (recordScope):
  processQueuedGesture → recordOverlayGesture
    performOverlayGesture() ← 完成
    finally: onGestureDispatched()
      withContext(Dispatchers.Main)               ← 等主线程，主线程被 wait() 占用
    ← 永远无法继续
    synchronized(recordingLock) {
      decrementOverlayGestureActiveLocked()       ← 永远不会执行
      recordingLock.notifyAll()                   ← 永远不会执行
    }
```

两端互相等待：主线程等 `count=0`，IO 线程等主线程，`count` 永远不会归零。5 秒后 Android 触发 ANR，用户关闭应用。

**修复**：`handleCaptureClick()` 中的 `pauseActive()` 必须移入 `recordingControlScope.launch {}` 块，与 `resumeActive()` 一起在 IO 线程执行：

```kotlin
// ManualRecordingControlOverlay.kt handleCaptureClick() 修复
private fun handleCaptureClick() {
    val callback = synchronized(this) { captureStateCallback } ?: return
    val previousState = synchronized(this) { state }
    val context = overlayView?.context ?: UIKit.appContext ?: return

    recordingControlScope.launch {                          // IO 线程
        val wasPaused = HumanTrajectoryLearningSession.isPaused()
        val shouldResume = HumanTrajectoryLearningSession.isActive() &&
            !wasPaused &&
            HumanTrajectoryLearningSession.pauseActive()   // 在 IO 线程阻塞，安全
        withContext(Dispatchers.Main) {
            if (shouldResume) markPaused()
            hideTemporarily()
        }
        val result = runCatching { callback() }.getOrElse { ... }
        val resumed = if (shouldResume) HumanTrajectoryLearningSession.resumeActive() else false
        // ... 后续逻辑不变
    }
}
```

---

### BUG-2（中）：awaitOverlayRecordJobs 无超时上限

**文件**：`ManualVlmTraceRecorder.kt`，`awaitOverlayRecordJobs()`

**问题**：每次循环等 100ms，但没有总超时。若 `overlayGestureActiveCount` 因意外未归零（如 `decrementOverlayGestureActiveLocked` 未执行），调用方永久阻塞。

**修复**：加最大等待时间：

```kotlin
private fun awaitOverlayRecordJobs() {
    val deadline = System.currentTimeMillis() + OVERLAY_RECORD_DRAIN_TIMEOUT_MS
    synchronized(recordingLock) {
        while (overlayGestureActiveCount > 0 &&
               System.currentTimeMillis() < deadline) {
            try {
                recordingLock.wait(OVERLAY_RECORD_DRAIN_POLL_MS)
            } catch (_: InterruptedException) { }
        }
        if (overlayGestureActiveCount > 0) {
            OmniLog.w(TAG, "awaitOverlayRecordJobs timed out, count=$overlayGestureActiveCount; resetting")
            overlayGestureActiveCount = 0
        }
    }
}
// 常量建议: OVERLAY_RECORD_DRAIN_TIMEOUT_MS = 3000L
```

---

### BUG-3（严重）：captureCurrentXml 无超时 → 第一个手势永久卡死

**文件**：`ManualVlmTraceRecorder.kt`，`recordOverlayGesture()`

**触发条件**：目标 App 在 UI 切换（页面跳转、弹窗出现）时，`captureCurrentXml()` 被调用。

**根因**：`captureCurrentXml()` → `OmniCaptureAction.captureScreenshotXml()` → `service.windows` → `window.root` 是 binder 调用，当目标 App 未响应时无限阻塞。没有任何超时保护。整个 `recordOverlayGesture` 协程挂死，所有后续手势无法处理。

**修复（已生效）**：
```kotlin
val beforeXml = withTimeoutOrNull(BEFORE_XML_CAPTURE_TIMEOUT_MS) {  // 2000ms
    withContext(Dispatchers.IO) { captureCurrentXml() }  // 独立 IO 线程
} ?: synchronized(recordingLock) { lastXmlSnapshot }  // 超时回退到上一次快照
```

- `withTimeoutOrNull`：超时后协程继续，不再等待
- `withContext(Dispatchers.IO)`：binder 在独立线程，超时后该线程后台释放，不阻塞当前协程
- 超时回退 `lastXmlSnapshot`：beforeXml 可能略旧，但手势仍然正确录制

**不变式**：凡是调用 `captureCurrentXml()`（或任何 `window.root` / `getCaptureScreenShotXml`）的地方，都必须有超时保护。

---

### BUG-4（轻）：点击被识别为滑动

**文件**：`ManualTouchRecordLoader.kt`，`handleTouchEvent()`

**问题（已修复）**：原实现在 ACTION_MOVE 期间只要距离超阈值就置 `isSwipe = true`，导致手指轻微漂移的点击被识别为滑动。

**修复（已生效）**：分类只用 ACTION_UP 时的 start→end 净位移，不用中间过程的峰值位移。

---

### BUG-4（轻）：Thread.sleep 在协程中不可取消

**文件**：`ManualVlmTraceRecorder.kt`，`settleAndRecordOverlayGesture()`

**问题（已修复）**：原用 `Thread.sleep(OVERLAY_TOUCH_SETTLE_MS)` 阻塞 IO 线程，超时机制无法中断。

**修复（已生效）**：改为 `suspend fun` + `delay(OVERLAY_TOUCH_SETTLE_MS)`，协程取消可正常传播。

---

## 不变式（所有修改必须保证）

1. **`overlayGestureActiveCount` 必须对称**：每次 `overlayGestureActiveCount += 1` 之后，无论成功/失败/超时，`decrementOverlayGestureActiveLocked()` 必须在 `finally` 块中执行。
2. **`awaitOverlayRecordJobs()` 只能在非主线程调用**：`pause()` / `stop()` 调用链不得出现在主线程上。
3. **`withContext(Dispatchers.Main)` 不得在主线程持有 `recordingLock` 时被等待**：否则形成 monitor-dispatcher 双向等待死锁。
4. **`updateViewLayout` 只在主线程调用**：`lockTouchLocked()` / `unlockTouchLocked()` 调用方必须在主线程或 `withContext(Main)` 中。
5. **`pendingGestures` 写入只在主线程**：`enqueueGesture` 从 touch listener 调用（主线程），`pollFirst` 从 IO 线程调用但持有锁。两者均在 `synchronized(this)` 内，安全。

---

## 操作分类和线程归属

| 操作 | 线程 | 说明 |
|------|------|------|
| 触摸捕获（ACTION_DOWN/MOVE/UP） | 主线程 | touch listener 回调 |
| 手势入队 `enqueueGesture` | 主线程 | 在 `synchronized(this)` 内 |
| 队列处理 `processGestureQueue` | IO 线程 | `recordScope.launch` |
| `performOverlayGesture`（GestureDescription）| IO 线程 | suspend，accessibility service callback 线程完成 |
| `onGestureDispatched` 回调 | IO → Main | `withContext(Main)` 中执行 overlay re-lock |
| `lockTouchLocked / unlockTouchLocked` | 主线程 | `updateViewLayout` 强制要求 |
| `awaitOverlayRecordJobs` | IO 线程 | 阻塞调用，**严禁在主线程** |
| `pauseActive / completeActive / cancelActive` | IO 线程 | 必须从 `recordingControlScope.launch {}` 发出 |
| Accessibility 事件处理 | Accessibility binder 线程 | `synchronized(recordingLock)` 保护 |

---

## 超时参数说明

| 常量 | 位置 | 值 | 含义 |
|------|------|----|------|
| `OVERLAY_UNLOCK_REPLAY_DELAY_MS` | ManualTouchRecordLoader | 32ms | overlay NOT_TOUCHABLE 生效等待 |
| `OVERLAY_CLICK_REPLAY_TIMEOUT_MS` | ManualVlmTraceRecorder | 650ms | click GestureDescription 最大等待 |
| `OVERLAY_TOUCH_SETTLE_MS` | ManualVlmTraceRecorder | 350ms | 已废弃（beforeXml-only 模式下不等待） |
| `IME_WAIT_TIMEOUT_MS` | ManualTouchRecordLoader | 600ms | 点击后 IME 弹出等待上限 |
| `IME_VISIBILITY_GRACE_MS` | ManualTouchRecordLoader | 450ms | IME 未出现时的额外宽限 |
| `OVERLAY_RECORD_DRAIN_POLL_MS` | ManualVlmTraceRecorder | 100ms | `awaitOverlayRecordJobs` 每轮等待 |
| `OVERLAY_RECORD_DRAIN_TIMEOUT_MS` | ManualVlmTraceRecorder | **待添加** 3000ms | drain 总超时，防永久阻塞 |

---

## NOT_TOUCHABLE 窗口说明

GestureDescription 必须在 overlay NOT_TOUCHABLE 时分发（否则被 overlay 自身拦截）。这是 Android WindowManager 的硬性约束，无法绕过。

当前最小化方案：
- NOT_TOUCHABLE 期间：`unlockTouchLocked()` → `delay(32ms)` → `performOverlayGesture()`
- `onGestureDispatched` 回调后立即 `lockTouchLocked()`（TOUCHABLE）
- 新触摸在 TOUCHABLE 期间进入 `pendingGestures` 队列
- NOT_TOUCHABLE 窗口约 32ms + GestureDescription 执行时间（≤650ms）≈ 700ms
- 此窗口内的用户触摸直接到 App，无法被 overlay 捕获——这是不可消除的物理约束

---

## 诊断字段（RunLog action.eventContext）

每个录制的 action 携带以下诊断：

| 字段 | 含义 |
|------|------|
| `operation_id` | 每次操作的唯一 id，格式 `overlay_{startMs}_{sequence}` |
| `dispatch_status` | `dispatch_completed` / `dispatch_timeout` / `dispatch_failed` / `dispatch_cancelled` |
| `before_xml_present` | beforeXml 是否有效 |
| `error_code` | 失败时的错误代码 |
| `error_message` | 失败时的错误消息 |
| `recording_backend` | `overlay_touch` |

`dispatch_timeout` 不等于操作丢失——RunLog 仍记录坐标、时间、动作类型和失败原因。
