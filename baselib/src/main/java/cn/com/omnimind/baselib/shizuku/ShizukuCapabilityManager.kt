package cn.com.omnimind.baselib.shizuku

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.RemoteException
import android.util.Base64
import cn.com.omnimind.baselib.util.OmniLog
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import rikka.shizuku.Shizuku
import java.util.UUID

class ShizukuCapabilityManager private constructor(
    context: Context
) {

    private val appContext = context.applicationContext
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
    private val bindMutex = Mutex()
    private val permissionMutex = Mutex()

    @Volatile
    private var remoteService: IOmnibotPrivilegedUserService? = null

    @Volatile
    private var screenCaptureService: IOmnibotScreenCaptureService? = null

    @Volatile
    private var pendingBind: CompletableDeferred<IOmnibotPrivilegedUserService?>? = null

    @Volatile
    private var pendingScreenBind: CompletableDeferred<IOmnibotScreenCaptureService?>? = null

    @Volatile
    private var lastBinderDead = false

    @Volatile
    private var listenersRegistered = false

    private val userServiceArgs = Shizuku.UserServiceArgs(
        ComponentName(appContext, OmnibotPrivilegedUserService::class.java)
    )
        .daemon(false)
        .processNameSuffix("omnibot_privileged")
        .tag(USER_SERVICE_TAG)
        .version(USER_SERVICE_VERSION)

    private val screenCaptureServiceArgs = Shizuku.UserServiceArgs(
        ComponentName(appContext, OmnibotScreenCaptureUserService::class.java)
    )
        .daemon(false)
        .processNameSuffix("omnibot_screen_capture")
        .tag(SCREEN_CAPTURE_SERVICE_TAG)
        .version(SCREEN_CAPTURE_SERVICE_VERSION)

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val privilegedService = if (service != null) {
                IOmnibotPrivilegedUserService.Stub.asInterface(service)
            } else {
                null
            }
            remoteService = privilegedService
            pendingBind?.takeIf { !it.isCompleted }?.complete(privilegedService)
            OmniLog.i(TAG, "Shizuku user service connected: ${name?.className}")
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            remoteService = null
            pendingBind?.takeIf { !it.isCompleted }?.complete(null)
            OmniLog.w(TAG, "Shizuku user service disconnected")
        }

        override fun onBindingDied(name: ComponentName?) {
            remoteService = null
            pendingBind?.takeIf { !it.isCompleted }?.complete(null)
            OmniLog.w(TAG, "Shizuku user service binding died")
        }

        override fun onNullBinding(name: ComponentName?) {
            remoteService = null
            pendingBind?.takeIf { !it.isCompleted }?.complete(null)
            OmniLog.w(TAG, "Shizuku user service null binding")
        }
    }

    private val screenCaptureServiceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val captureService = if (service != null) {
                IOmnibotScreenCaptureService.Stub.asInterface(service)
            } else {
                null
            }
            screenCaptureService = captureService
            pendingScreenBind?.takeIf { !it.isCompleted }?.complete(captureService)
            OmniLog.i(TAG, "Shizuku screen capture service connected: ${name?.className}")
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            screenCaptureService = null
            pendingScreenBind?.takeIf { !it.isCompleted }?.complete(null)
            OmniLog.w(TAG, "Shizuku screen capture service disconnected")
        }

        override fun onBindingDied(name: ComponentName?) {
            screenCaptureService = null
            pendingScreenBind?.takeIf { !it.isCompleted }?.complete(null)
            OmniLog.w(TAG, "Shizuku screen capture service binding died")
        }

        override fun onNullBinding(name: ComponentName?) {
            screenCaptureService = null
            pendingScreenBind?.takeIf { !it.isCompleted }?.complete(null)
            OmniLog.w(TAG, "Shizuku screen capture service null binding")
        }
    }

    init {
        registerListenersIfNeeded()
    }

    fun getStatus(): ShizukuStatus {
        registerListenersIfNeeded()
        val installed = isShizukuInstalled() || runCatching { Shizuku.pingBinder() }.getOrDefault(false)
        val binderReady = runCatching { Shizuku.pingBinder() }.getOrDefault(false)
        val running = binderReady
        val permissionGranted = if (binderReady) {
            runCatching { Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED }
                .getOrDefault(false)
        } else {
            false
        }
        val uid = if (binderReady) runCatching { Shizuku.getUid() }.getOrNull() else null
        val version = if (binderReady) runCatching { Shizuku.getVersion() }.getOrNull() else null
        val backend = when {
            !permissionGranted -> ShizukuBackend.NONE
            uid == 0 -> ShizukuBackend.ROOT
            uid == 2000 -> ShizukuBackend.ADB
            else -> ShizukuBackend.ADB
        }
        val code = when {
            !installed -> ShizukuStatusCode.NOT_INSTALLED
            !binderReady && lastBinderDead -> ShizukuStatusCode.BINDER_DEAD
            !binderReady -> ShizukuStatusCode.NOT_RUNNING
            !permissionGranted -> ShizukuStatusCode.PERMISSION_DENIED
            backend == ShizukuBackend.ROOT -> ShizukuStatusCode.GRANTED_ROOT
            else -> ShizukuStatusCode.GRANTED_ADB
        }
        return ShizukuStatus(
            code = code,
            backend = backend,
            installed = installed,
            running = running,
            permissionGranted = permissionGranted,
            binderReady = binderReady,
            serviceBound = remoteService != null,
            uid = uid,
            version = version,
            availableActions = suggestedAgentActions(backend),
            message = statusMessage(code)
        )
    }

    fun suggestedAgentActions(backend: ShizukuBackend = getStatus().backend): List<String> {
        return PrivilegedActionPolicy.visibleAgentActions(
            if (backend == ShizukuBackend.ROOT) ShizukuBackend.ROOT else ShizukuBackend.ADB
        )
    }

    fun isGranted(): Boolean = getStatus().isGranted()

    fun isShizukuInstalled(): Boolean {
        return runCatching {
            appContext.packageManager.getPackageInfo(SHIZUKU_PACKAGE_NAME, 0)
            true
        }.getOrDefault(false)
    }

    fun openShizukuDownloadOrApp(): Boolean {
        return runCatching {
            val launchIntent = appContext.packageManager.getLaunchIntentForPackage(SHIZUKU_PACKAGE_NAME)
            val intent = launchIntent ?: Intent(
                Intent.ACTION_VIEW,
                android.net.Uri.parse("https://shizuku.rikka.app/download/")
            )
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            appContext.startActivity(intent)
            true
        }.getOrElse {
            OmniLog.e(TAG, "Failed to open Shizuku app or website", it)
            false
        }
    }

    suspend fun requestPermission(): ShizukuStatus {
        registerListenersIfNeeded()
        return permissionMutex.withLock {
            val current = getStatus()
            if (!current.installed || !current.running) {
                return@withLock current
            }
            if (current.permissionGranted) {
                return@withLock current
            }
            val deferred = CompletableDeferred<Int>()
            val requestCode = (System.currentTimeMillis() % 100000).toInt()
            val listener = Shizuku.OnRequestPermissionResultListener { callbackCode, grantResult ->
                if (callbackCode == requestCode && !deferred.isCompleted) {
                    deferred.complete(grantResult)
                }
            }
            withContext(Dispatchers.Main) {
                Shizuku.addRequestPermissionResultListener(listener)
                Shizuku.requestPermission(requestCode)
            }
            withTimeoutOrNull(10_000) {
                deferred.await()
            }
            withContext(Dispatchers.Main) {
                Shizuku.removeRequestPermissionResultListener(listener)
            }
            getStatus()
        }
    }

    suspend fun runHealthCheck(): Map<String, Any?> {
        val status = getStatus()
        val payload = linkedMapOf<String, Any?>()
        payload.putAll(status.toMap())
        if (!status.isGranted()) {
            payload["probe"] = null
            payload["rawShellProbe"] = null
            payload["sessionProbe"] = null
            return payload
        }
        val probe = execute(
            PrivilegedRequest(
                requestId = UUID.randomUUID().toString(),
                action = PrivilegedActionPolicy.ACTION_DIAGNOSTICS_GETPROP,
                arguments = mapOf("name" to "ro.build.version.release")
            )
        )
        payload["probe"] = probe.toMap()
        val rawShellProbe = execute(
            PrivilegedRequest(
                requestId = UUID.randomUUID().toString(),
                action = PrivilegedActionPolicy.ACTION_SHELL_EXEC,
                arguments = mapOf("confirmed" to "true"),
                command = "getprop ro.build.version.release",
                timeoutSeconds = 10
            )
        )
        payload["rawShellProbe"] = rawShellProbe.toMap()

        val sessionStart = startPrivilegedSession(
            confirmed = true
        )
        val sessionProbe = linkedMapOf<String, Any?>(
            "start" to sessionStart.toMap()
        )
        val sessionId = sessionStart.sessionId
        if (sessionStart.success && !sessionId.isNullOrBlank()) {
            val sessionExec = execPrivilegedSession(
                sessionId = sessionId,
                command = "pwd",
                timeoutSeconds = 10,
                confirmed = true
            )
            sessionProbe["exec"] = sessionExec.toMap()
            val sessionRead = readPrivilegedSession(
                sessionId = sessionId,
                maxChars = 2048
            )
            sessionProbe["read"] = sessionRead.toMap()
            val sessionStop = stopPrivilegedSession(sessionId)
            sessionProbe["stop"] = sessionStop.toMap()
        }
        payload["sessionProbe"] = sessionProbe
        return payload
    }

    suspend fun executeAgentAction(
        action: String,
        arguments: Map<String, String>,
        requiresConfirmation: Boolean = false,
    ): PrivilegedResult {
        val request = PrivilegedRequest(
            requestId = UUID.randomUUID().toString(),
            action = PrivilegedActionPolicy.normalizeAction(action),
            arguments = arguments,
            requiresConfirmation = requiresConfirmation
        )
        return execute(request)
    }

    suspend fun executeRawShell(
        command: String,
        timeoutSeconds: Int? = null,
        workingDirectory: String? = null,
        environment: Map<String, String> = emptyMap(),
        confirmed: Boolean = false,
    ): PrivilegedResult {
        return execute(
            PrivilegedRequest(
                requestId = UUID.randomUUID().toString(),
                action = PrivilegedActionPolicy.ACTION_SHELL_EXEC,
                arguments = confirmationArguments(confirmed),
                command = command,
                timeoutSeconds = timeoutSeconds,
                workingDirectory = workingDirectory,
                environment = environment
            )
        )
    }

    suspend fun startPrivilegedSession(
        sessionId: String? = null,
        sessionName: String? = null,
        workingDirectory: String? = null,
        environment: Map<String, String> = emptyMap(),
        confirmed: Boolean = false,
    ): PrivilegedResult {
        val arguments = confirmationArguments(confirmed).toMutableMap()
        sessionName?.trim()?.takeIf { it.isNotEmpty() }?.let { arguments["sessionName"] = it }
        return execute(
            PrivilegedRequest(
                requestId = UUID.randomUUID().toString(),
                action = PrivilegedActionPolicy.ACTION_SESSION_START,
                arguments = arguments,
                workingDirectory = workingDirectory,
                environment = environment,
                sessionId = sessionId
            )
        )
    }

    suspend fun execPrivilegedSession(
        sessionId: String,
        command: String,
        timeoutSeconds: Int? = null,
        workingDirectory: String? = null,
        environment: Map<String, String> = emptyMap(),
        confirmed: Boolean = false,
    ): PrivilegedResult {
        return execute(
            PrivilegedRequest(
                requestId = UUID.randomUUID().toString(),
                action = PrivilegedActionPolicy.ACTION_SESSION_EXEC,
                arguments = confirmationArguments(confirmed),
                command = command,
                timeoutSeconds = timeoutSeconds,
                workingDirectory = workingDirectory,
                environment = environment,
                sessionId = sessionId
            )
        )
    }

    suspend fun readPrivilegedSession(
        sessionId: String,
        maxChars: Int? = null,
    ): PrivilegedResult {
        val arguments = buildMap {
            maxChars?.let { put("maxChars", it.toString()) }
        }
        return execute(
            PrivilegedRequest(
                requestId = UUID.randomUUID().toString(),
                action = PrivilegedActionPolicy.ACTION_SESSION_READ,
                arguments = arguments,
                sessionId = sessionId
            )
        )
    }

    suspend fun stopPrivilegedSession(sessionId: String): PrivilegedResult {
        return execute(
            PrivilegedRequest(
                requestId = UUID.randomUUID().toString(),
                action = PrivilegedActionPolicy.ACTION_SESSION_STOP,
                sessionId = sessionId
            )
        )
    }

    suspend fun pressKeyEvent(key: String): PrivilegedResult {
        return executeAgentAction(
            action = PrivilegedActionPolicy.ACTION_DEVICE_KEYEVENT,
            arguments = mapOf("key" to key)
        )
    }

    suspend fun inputText(
        text: String,
        mode: ShizukuInputTextMode = ShizukuInputTextMode.AUTO
    ): PrivilegedResult {
        return executeAgentAction(
            action = PrivilegedActionPolicy.ACTION_DEVICE_INPUT_TEXT,
            arguments = mapOf(
                "text" to text,
                "mode" to mode.name
            )
        )
    }

    suspend fun tap(x: Int, y: Int): PrivilegedResult {
        return executeAgentAction(
            action = PrivilegedActionPolicy.ACTION_DEVICE_TAP,
            arguments = mapOf(
                "x" to x.toString(),
                "y" to y.toString()
            )
        )
    }

    suspend fun swipe(
        x1: Int,
        y1: Int,
        x2: Int,
        y2: Int,
        durationMs: Long,
    ): PrivilegedResult {
        return executeAgentAction(
            action = PrivilegedActionPolicy.ACTION_DEVICE_SWIPE,
            arguments = mapOf(
                "x1" to x1.toString(),
                "y1" to y1.toString(),
                "x2" to x2.toString(),
                "y2" to y2.toString(),
                "durationMs" to durationMs.toString()
            )
        )
    }

    suspend fun captureScreenshotBase64Png(): PrivilegedResult {
        val requestId = UUID.randomUUID().toString()
        val action = PrivilegedActionPolicy.ACTION_DEVICE_SCREENSHOT
        val status = getStatus()
        if (!status.isGranted()) {
            return PrivilegedResult(
                requestId = requestId,
                action = action,
                success = false,
                code = status.code.name.lowercase(),
                message = status.message,
                backend = status.backend,
                availableActions = suggestedAgentActions(status.backend)
            )
        }
        val service = ensureUserServiceBound() ?: return PrivilegedResult(
            requestId = requestId,
            action = action,
            success = false,
            code = "service_bind_failed",
            message = "Failed to bind Shizuku user service.",
            backend = status.backend,
            availableActions = suggestedAgentActions(status.backend)
        )

        val bytes = runCatching {
            readScreenshotPngBytes(service)
        }.recoverCatching { error ->
            if (error is RemoteException) {
                remoteService = null
            }
            val reboundService = ensureUserServiceBound()
                ?: error("Failed to rebind Shizuku user service.")
            readScreenshotPngBytes(reboundService)
        }.getOrElse { error ->
            return PrivilegedResult(
                requestId = requestId,
                action = action,
                success = false,
                code = "service_call_failed",
                message = error.message ?: "Failed to read Shizuku screenshot stream.",
                backend = status.backend,
                availableActions = suggestedAgentActions(status.backend)
            )
        }

        if (bytes.isEmpty()) {
            return PrivilegedResult(
                requestId = requestId,
                action = action,
                success = false,
                code = "empty_screenshot",
                message = "Shizuku screenshot stream returned no data.",
                backend = status.backend,
                availableActions = suggestedAgentActions(status.backend),
                command = "/system/bin/screencap -p",
                timeoutSeconds = (SCREENSHOT_STREAM_TIMEOUT_MS / 1000L).toInt()
            )
        }

        return PrivilegedResult(
            requestId = requestId,
            action = action,
            success = true,
            code = "ok",
            message = "Screenshot captured successfully.",
            backend = status.backend,
            availableActions = suggestedAgentActions(status.backend),
            data = mapOf("base64Png" to Base64.encodeToString(bytes, Base64.NO_WRAP)),
            command = "/system/bin/screencap -p",
            timeoutSeconds = (SCREENSHOT_STREAM_TIMEOUT_MS / 1000L).toInt()
        )
    }

    suspend fun captureScreen(
        maxWidth: Int,
        maxHeight: Int,
        quality: Int
    ): ShizukuScreenCaptureResult {
        val status = getStatus()
        if (!status.isGranted()) {
            return ShizukuScreenCaptureResult.error(status.message, elapsedMs = 0L)
        }
        val service = ensureScreenCaptureServiceBound() ?: return ShizukuScreenCaptureResult.error(
            message = "Failed to bind Shizuku screen capture service.",
            elapsedMs = 0L
        )

        return runCatching {
            callCaptureScreen(service, maxWidth, maxHeight, quality)
        }.recoverCatching { error ->
            if (error is RemoteException) {
                screenCaptureService = null
            }
            val reboundService = ensureScreenCaptureServiceBound()
                ?: error("Failed to rebind Shizuku screen capture service.")
            callCaptureScreen(reboundService, maxWidth, maxHeight, quality)
        }.getOrElse { error ->
            ShizukuScreenCaptureResult.error(
                message = error.message ?: "Failed to call Shizuku screen capture service.",
                elapsedMs = 0L
            )
        }
    }

    suspend fun launchApp(packageName: String): PrivilegedResult {
        return executeAgentAction(
            action = PrivilegedActionPolicy.ACTION_PACKAGE_LAUNCH,
            arguments = mapOf("packageName" to packageName)
        )
    }

    private suspend fun execute(request: PrivilegedRequest): PrivilegedResult {
        val status = getStatus()
        if (!status.isGranted()) {
            return PrivilegedResult(
                requestId = request.requestId,
                action = request.action,
                success = false,
                code = status.code.name.lowercase(),
                message = status.message,
                backend = status.backend,
                availableActions = suggestedAgentActions(status.backend)
            )
        }
        val service = ensureUserServiceBound() ?: return PrivilegedResult(
            requestId = request.requestId,
            action = request.action,
            success = false,
            code = "service_bind_failed",
            message = "Failed to bind Shizuku user service.",
            backend = status.backend,
            availableActions = suggestedAgentActions(status.backend)
        )
        return runCatching {
            executeRemoteRequest(request, service, status.backend)
        }.recoverCatching {
            remoteService = null
            val reboundService = ensureUserServiceBound()
                ?: error("Failed to rebind Shizuku user service.")
            executeRemoteRequest(request, reboundService, status.backend)
        }.getOrElse { error ->
            PrivilegedResult(
                requestId = request.requestId,
                action = request.action,
                success = false,
                code = "service_call_failed",
                message = error.message ?: "Failed to call Shizuku user service.",
                backend = status.backend,
                availableActions = suggestedAgentActions(status.backend)
            )
        }
    }

    private suspend fun executeRemoteRequest(
        request: PrivilegedRequest,
        service: IOmnibotPrivilegedUserService,
        backend: ShizukuBackend,
    ): PrivilegedResult {
        val requestJson = json.encodeToString(PrivilegedRequest.serializer(), request)
        val resultJson = withTimeoutOrNull(remoteTimeoutMillisFor(request)) {
            withContext(Dispatchers.IO) {
                runCatching {
                    service.execute(requestJson)
                }.getOrElse { error ->
                    if (error is RemoteException) {
                        remoteService = null
                    }
                    throw error
                }
            }
        } ?: return PrivilegedResult(
            requestId = request.requestId,
            action = request.action,
            success = false,
            code = "service_timeout",
            message = "Timed out waiting for privileged service result.",
            backend = backend,
            availableActions = suggestedAgentActions(backend)
        )

        return runCatching {
            json.decodeFromString(PrivilegedResult.serializer(), resultJson)
        }.getOrElse { error ->
            PrivilegedResult(
                requestId = request.requestId,
                action = request.action,
                success = false,
                code = "service_decode_failed",
                message = error.message ?: "Failed to decode privileged service result.",
                backend = backend,
                availableActions = suggestedAgentActions(backend)
            )
        }
    }

    private suspend fun readScreenshotPngBytes(
        service: IOmnibotPrivilegedUserService
    ): ByteArray {
        return withTimeoutOrNull(SCREENSHOT_STREAM_TIMEOUT_MS) {
            withContext(Dispatchers.IO) {
                val descriptor = service.captureScreenshotPng()
                    ?: error("Failed to open Shizuku screenshot stream.")
                ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { input ->
                    input.readBytes()
                }
            }
        } ?: error("Timed out waiting for Shizuku screenshot stream.")
    }

    private suspend fun callCaptureScreen(
        service: IOmnibotScreenCaptureService,
        maxWidth: Int,
        maxHeight: Int,
        quality: Int
    ): ShizukuScreenCaptureResult {
        return withTimeoutOrNull(SCREEN_CAPTURE_TIMEOUT_MS) {
            withContext(Dispatchers.IO) {
                service.captureScreen(maxWidth, maxHeight, quality)
            }
        } ?: ShizukuScreenCaptureResult.error(
            message = "Timed out waiting for Shizuku screen capture service.",
            elapsedMs = SCREEN_CAPTURE_TIMEOUT_MS
        )
    }

    private suspend fun ensureUserServiceBound(): IOmnibotPrivilegedUserService? {
        remoteService?.let { return it }
        return bindMutex.withLock {
            remoteService?.let { return@withLock it }
            if (!getStatus().isGranted()) {
                return@withLock null
            }
            val connected = pendingBind?.takeIf { !it.isCompleted }
                ?: CompletableDeferred<IOmnibotPrivilegedUserService?>().also {
                    pendingBind = it
                }
            withContext(Dispatchers.Main) {
                try {
                    Shizuku.bindUserService(userServiceArgs, serviceConnection)
                } catch (error: Throwable) {
                    if (!connected.isCompleted) {
                        connected.complete(null)
                    }
                    return@withContext
                }
                remoteService?.let { service ->
                    if (!connected.isCompleted) {
                        connected.complete(service)
                    }
                }
            }
            val result = withTimeoutOrNull(3_000) {
                connected.await()
            }
            if (pendingBind === connected) {
                pendingBind = null
            }
            result
        }
    }

    private suspend fun ensureScreenCaptureServiceBound(): IOmnibotScreenCaptureService? {
        screenCaptureService?.let { return it }
        return bindMutex.withLock {
            screenCaptureService?.let { return@withLock it }
            if (!getStatus().isGranted()) {
                return@withLock null
            }
            val connected = pendingScreenBind?.takeIf { !it.isCompleted }
                ?: CompletableDeferred<IOmnibotScreenCaptureService?>().also {
                    pendingScreenBind = it
                }
            withContext(Dispatchers.Main) {
                try {
                    Shizuku.bindUserService(screenCaptureServiceArgs, screenCaptureServiceConnection)
                } catch (error: Throwable) {
                    if (!connected.isCompleted) {
                        connected.complete(null)
                    }
                    return@withContext
                }
                screenCaptureService?.let { service ->
                    if (!connected.isCompleted) {
                        connected.complete(service)
                    }
                }
            }
            val result = withTimeoutOrNull(3_000) {
                connected.await()
            }
            if (pendingScreenBind === connected) {
                pendingScreenBind = null
            }
            result
        }
    }

    private fun registerListenersIfNeeded() {
        if (listenersRegistered) {
            return
        }
        synchronized(this) {
            if (listenersRegistered) {
                return
            }
            runCatching {
                Shizuku.addBinderReceivedListenerSticky {
                    lastBinderDead = false
                }
                Shizuku.addBinderDeadListener {
                    lastBinderDead = true
                    remoteService = null
                    screenCaptureService = null
                    pendingBind?.takeIf { !it.isCompleted }?.complete(null)
                    pendingScreenBind?.takeIf { !it.isCompleted }?.complete(null)
                }
            }
            listenersRegistered = true
        }
    }

    private fun statusMessage(code: ShizukuStatusCode): String {
        return when (code) {
            ShizukuStatusCode.NOT_INSTALLED -> "Shizuku is not installed."
            ShizukuStatusCode.NOT_RUNNING -> "Shizuku is installed but not running."
            ShizukuStatusCode.PERMISSION_DENIED -> "Shizuku permission is not granted."
            ShizukuStatusCode.GRANTED_ADB -> "Shizuku is granted through adb."
            ShizukuStatusCode.GRANTED_ROOT -> "Shizuku is granted through root/Sui."
            ShizukuStatusCode.BINDER_DEAD -> "Shizuku binder died. Please restart Shizuku."
        }
    }

    private fun confirmationArguments(confirmed: Boolean): Map<String, String> {
        return if (confirmed) {
            mapOf("confirmed" to "true")
        } else {
            emptyMap()
        }
    }

    private fun remoteTimeoutMillisFor(request: PrivilegedRequest): Long {
        return when (PrivilegedActionPolicy.normalizeAction(request.action)) {
            PrivilegedActionPolicy.ACTION_SHELL_EXEC,
            PrivilegedActionPolicy.ACTION_SESSION_EXEC -> {
                val requestedSeconds = request.timeoutSeconds?.coerceIn(5, 600) ?: 60
                (requestedSeconds + 8L) * 1000L
            }

            PrivilegedActionPolicy.ACTION_SESSION_START -> 15_000L
            PrivilegedActionPolicy.ACTION_SESSION_READ,
            PrivilegedActionPolicy.ACTION_SESSION_STOP -> 10_000L
            else -> 12_000L
        }
    }

    companion object {
        private const val TAG = "ShizukuCapabilityMgr"
        private const val SHIZUKU_PACKAGE_NAME = "moe.shizuku.privileged.api"
        private const val USER_SERVICE_TAG = "omnibot-privileged-agent"
        private const val USER_SERVICE_VERSION = 4
        private const val SCREEN_CAPTURE_SERVICE_TAG = "omnibot-screen-capture"
        private const val SCREEN_CAPTURE_SERVICE_VERSION = 1
        private const val SCREENSHOT_STREAM_TIMEOUT_MS = 20_000L
        private const val SCREEN_CAPTURE_TIMEOUT_MS = 20_000L

        @Volatile
        private var instance: ShizukuCapabilityManager? = null

        fun get(context: Context): ShizukuCapabilityManager {
            return instance ?: synchronized(this) {
                instance ?: ShizukuCapabilityManager(context).also {
                    instance = it
                }
            }
        }
    }
}
