package cn.com.omnimind.baselib.shizuku

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.annotation.Keep
import cn.com.omnimind.baselib.shizuku.capture.DisplayInfoCompat
import cn.com.omnimind.baselib.shizuku.capture.SurfaceControlCompat
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.math.roundToInt

@Keep
class OmnibotScreenCaptureUserService() : IOmnibotScreenCaptureService.Stub() {

    @Volatile
    private var forceScreencapFallback = !SurfaceControlCompat.isAvailable()

    @Volatile
    private var surfaceControlFailureCount = 0

    @Suppress("unused")
    @Keep
    constructor(context: Context) : this() {
        Log.d(TAG, "Screen capture user service created with context: $context")
    }

    override fun captureScreen(maxWidth: Int, maxHeight: Int, quality: Int): ShizukuScreenCaptureResult {
        val start = System.currentTimeMillis()
        val boundedQuality = quality.coerceIn(1, 100)
        return runCatching {
            if (!forceScreencapFallback) {
                captureViaSurfaceControl(maxWidth, maxHeight, boundedQuality, start)?.let { return it }
                surfaceControlFailureCount += 1
                if (surfaceControlFailureCount >= SURFACE_CONTROL_FAILURE_LIMIT) {
                    forceScreencapFallback = true
                }
                Log.w(TAG, "SurfaceControl capture failed, fallbackCount=$surfaceControlFailureCount")
            }
            captureViaScreencap(maxWidth, maxHeight, boundedQuality, start)
        }.getOrElse { error ->
            ShizukuScreenCaptureResult.error(
                message = error.message ?: "Screen capture failed.",
                method = if (forceScreencapFallback) METHOD_SCREENCAP else METHOD_SURFACE_CONTROL,
                elapsedMs = System.currentTimeMillis() - start
            )
        }
    }

    private fun captureViaSurfaceControl(
        maxWidth: Int,
        maxHeight: Int,
        quality: Int,
        start: Long
    ): ShizukuScreenCaptureResult? {
        val displayInfo = DisplayInfoCompat.getDisplayInfo()
        val displayWidth = displayInfo?.width?.takeIf { it > 0 } ?: 0
        val displayHeight = displayInfo?.height?.takeIf { it > 0 } ?: 0
        val rotation = displayInfo?.rotation ?: 0
        val targetSize = computeTargetSize(displayWidth, displayHeight, maxWidth, maxHeight)
        val bitmap = SurfaceControlCompat.screenshot(
            width = targetSize.first,
            height = targetSize.second,
            rotation = rotation
        ) ?: return null

        return bitmap.useForResult(
            method = METHOD_SURFACE_CONTROL,
            displayWidth = displayWidth.takeIf { it > 0 } ?: bitmap.width,
            displayHeight = displayHeight.takeIf { it > 0 } ?: bitmap.height,
            rotation = rotation,
            maxWidth = maxWidth,
            maxHeight = maxHeight,
            quality = quality,
            start = start
        )
    }

    private fun captureViaScreencap(
        maxWidth: Int,
        maxHeight: Int,
        quality: Int,
        start: Long
    ): ShizukuScreenCaptureResult {
        val result = execScreencap()
        if (!result.success || result.stdoutBytes.isEmpty()) {
            return ShizukuScreenCaptureResult.error(
                message = result.errorMessage.ifBlank { "screencap returned no image data." },
                method = METHOD_SCREENCAP,
                elapsedMs = System.currentTimeMillis() - start
            )
        }
        val bitmap = BitmapFactory.decodeByteArray(result.stdoutBytes, 0, result.stdoutBytes.size)
            ?: return ShizukuScreenCaptureResult.error(
                message = "Failed to decode screencap PNG.",
                method = METHOD_SCREENCAP,
                elapsedMs = System.currentTimeMillis() - start
            )
        val displayInfo = DisplayInfoCompat.getDisplayInfo()
        return bitmap.useForResult(
            method = METHOD_SCREENCAP,
            displayWidth = displayInfo?.width?.takeIf { it > 0 } ?: bitmap.width,
            displayHeight = displayInfo?.height?.takeIf { it > 0 } ?: bitmap.height,
            rotation = displayInfo?.rotation ?: 0,
            maxWidth = maxWidth,
            maxHeight = maxHeight,
            quality = quality,
            start = start
        )
    }

    private fun Bitmap.useForResult(
        method: String,
        displayWidth: Int,
        displayHeight: Int,
        rotation: Int,
        maxWidth: Int,
        maxHeight: Int,
        quality: Int,
        start: Long
    ): ShizukuScreenCaptureResult {
        var source: Bitmap? = this
        var encodedBitmap: Bitmap? = null
        return try {
            encodedBitmap = scaleBitmapIfNeeded(this, maxWidth, maxHeight)
            val imageBytes = encodeJpeg(encodedBitmap, quality)
            if (imageBytes.isEmpty()) {
                return ShizukuScreenCaptureResult.error(
                    message = "Encoded screenshot is empty.",
                    method = method,
                    elapsedMs = System.currentTimeMillis() - start
                )
            }
            val readFd = openImagePipe(imageBytes)
            val elapsed = System.currentTimeMillis() - start
            Log.d(
                TAG,
                "capture method=$method display=${displayWidth}x${displayHeight} encoded=${encodedBitmap.width}x${encodedBitmap.height} bytes=${imageBytes.size} elapsed=${elapsed}ms"
            )
            ShizukuScreenCaptureResult.success(
                width = encodedBitmap.width,
                height = encodedBitmap.height,
                displayWidth = displayWidth,
                displayHeight = displayHeight,
                rotation = rotation,
                mimeType = MIME_JPEG,
                method = method,
                elapsedMs = elapsed,
                imageFd = readFd
            )
        } finally {
            if (encodedBitmap != null && encodedBitmap !== source && !encodedBitmap.isRecycled) {
                encodedBitmap.recycle()
            }
            if (source != null && !source.isRecycled) {
                source.recycle()
            }
        }
    }

    private fun computeTargetSize(
        displayWidth: Int,
        displayHeight: Int,
        maxWidth: Int,
        maxHeight: Int
    ): Pair<Int, Int> {
        if (displayWidth <= 0 || displayHeight <= 0) {
            return 0 to 0
        }
        val scale = computeScale(displayWidth, displayHeight, maxWidth, maxHeight)
        return (displayWidth * scale).roundToInt().coerceAtLeast(1) to
            (displayHeight * scale).roundToInt().coerceAtLeast(1)
    }

    private fun scaleBitmapIfNeeded(bitmap: Bitmap, maxWidth: Int, maxHeight: Int): Bitmap {
        val scale = computeScale(bitmap.width, bitmap.height, maxWidth, maxHeight)
        if (scale >= 1f) {
            return bitmap
        }
        val width = (bitmap.width * scale).roundToInt().coerceAtLeast(1)
        val height = (bitmap.height * scale).roundToInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(bitmap, width, height, true)
    }

    private fun computeScale(width: Int, height: Int, maxWidth: Int, maxHeight: Int): Float {
        if (width <= 0 || height <= 0) return 1f
        val widthScale = if (maxWidth > 0) maxWidth.toFloat() / width else 1f
        val heightScale = if (maxHeight > 0) maxHeight.toFloat() / height else 1f
        return minOf(widthScale, heightScale, 1f)
    }

    private fun encodeJpeg(bitmap: Bitmap, quality: Int): ByteArray {
        val output = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, quality, output)
        return output.toByteArray()
    }

    private fun openImagePipe(imageBytes: ByteArray): ParcelFileDescriptor {
        val pipe = ParcelFileDescriptor.createPipe()
        val readFd = pipe[0]
        val writeFd = pipe[1]
        thread(start = true, isDaemon = true, name = "omnibot-shizuku-screen-pipe") {
            ParcelFileDescriptor.AutoCloseOutputStream(writeFd).use { output ->
                output.write(imageBytes)
                output.flush()
            }
        }
        return readFd
    }

    private fun execScreencap(): ScreencapExecResult {
        val process = ProcessBuilder("/system/bin/screencap", "-p")
            .redirectErrorStream(false)
            .start()
        val stdout = ByteArrayOutputStream()
        val stderr = ByteArrayOutputStream()
        val stdoutThread = thread(start = true, isDaemon = true) {
            process.inputStream.use { it.copyTo(stdout) }
        }
        val stderrThread = thread(start = true, isDaemon = true) {
            process.errorStream.use { it.copyTo(stderr) }
        }
        val finished = process.waitFor(SCREENCAP_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        if (!finished) {
            process.destroyForcibly()
        }
        stdoutThread.join(500)
        stderrThread.join(500)
        val exitCode = if (finished) process.exitValue() else null
        return ScreencapExecResult(
            success = finished && exitCode == 0,
            stdoutBytes = stdout.toByteArray(),
            errorMessage = stderr.toString().trim().ifBlank {
                if (finished) "screencap exitCode=$exitCode" else "screencap timed out"
            }
        )
    }

    override fun destroy() {
        Log.i(TAG, "Screen capture user service destroy requested")
        System.exit(0)
    }

    private data class ScreencapExecResult(
        val success: Boolean,
        val stdoutBytes: ByteArray,
        val errorMessage: String
    )

    private companion object {
        private const val TAG = "OmniScreenCaptureSvc"
        private const val METHOD_SURFACE_CONTROL = "surface_control"
        private const val METHOD_SCREENCAP = "screencap"
        private const val MIME_JPEG = "image/jpeg"
        private const val SURFACE_CONTROL_FAILURE_LIMIT = 2
        private const val SCREENCAP_TIMEOUT_SECONDS = 8L
    }
}
