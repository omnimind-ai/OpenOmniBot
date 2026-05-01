/*
 * Copyright (C) 2024 AutoGLM
 *
 * SurfaceControl screenshot wrapper using reflection to access hidden Android APIs.
 *
 * This code is inspired by and partially derived from the scrcpy project:
 * https://github.com/Genymobile/scrcpy
 *
 * scrcpy is licensed under the Apache License, Version 2.0:
 * Copyright (C) 2018 Genymobile
 * Copyright (C) 2018-2024 Romain Vimont
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * Modifications for Omnibot:
 * - Screenshot-only single frame capture for Shizuku VLM.
 * - No scrcpy video stream, virtual display, socket server, or remote-control loop.
 */

package cn.com.omnimind.baselib.shizuku.capture

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.ColorSpace
import android.graphics.Rect
import android.hardware.HardwareBuffer
import android.os.Build
import android.os.IBinder
import android.util.Log
import java.lang.reflect.Method

@SuppressLint("PrivateApi", "DiscouragedPrivateApi")
object SurfaceControlCompat {

    private const val TAG = "OmniSurfaceControl"

    private val surfaceControlClass: Class<*>? by lazy {
        runCatching { Class.forName("android.view.SurfaceControl") }.getOrNull()
    }

    private var cachedScreenshotMethod: Method? = null
    private var cachedScreenshotMethodType: ScreenshotMethodType? = null

    private enum class ScreenshotMethodType {
        SCREENSHOT_HARDWARE_BUFFER,
        SCREENSHOT_BITMAP_RECT_ROTATION,
        SCREENSHOT_BITMAP_RECT,
        SCREENSHOT_BITMAP_SIZE
    }

    init {
        applyHiddenApiExemptions()
    }

    private fun applyHiddenApiExemptions() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            return
        }
        runCatching {
            val vmRuntimeClass = Class.forName("dalvik.system.VMRuntime")
            val getRuntimeMethod = vmRuntimeClass.getDeclaredMethod("getRuntime")
            val setHiddenApiExemptionsMethod = vmRuntimeClass.getDeclaredMethod(
                "setHiddenApiExemptions",
                Array<String>::class.java
            )
            val runtime = getRuntimeMethod.invoke(null)
            setHiddenApiExemptionsMethod.invoke(runtime, arrayOf("L") as Any)
        }.onFailure {
            Log.w(TAG, "Hidden API exemption failed: ${it.message}")
        }
    }

    fun isAvailable(): Boolean {
        return surfaceControlClass != null && getBuiltInDisplay() != null
    }

    private fun getBuiltInDisplay(): IBinder? {
        val clazz = surfaceControlClass ?: return null
        return runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                clazz.getMethod("getInternalDisplayToken").invoke(null) as? IBinder
            } else {
                clazz.getMethod("getBuiltInDisplay", Int::class.javaPrimitiveType)
                    .invoke(null, 0) as? IBinder
            }
        }.getOrNull()
    }

    fun screenshot(
        crop: Rect? = null,
        width: Int = 0,
        height: Int = 0,
        rotation: Int = 0
    ): Bitmap? {
        val clazz = surfaceControlClass ?: return null
        val token = getBuiltInDisplay() ?: return null

        cachedScreenshotMethod?.let { method ->
            invokeScreenshotMethod(method, cachedScreenshotMethodType ?: return@let null, token, crop, width, height, rotation)
                ?.let { return it }
        }

        return tryScreenshotMethods(clazz, token, crop, width, height, rotation)
    }

    private fun tryScreenshotMethods(
        clazz: Class<*>,
        token: IBinder,
        crop: Rect?,
        width: Int,
        height: Int,
        rotation: Int
    ): Bitmap? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            tryScreenshotHardwareBuffer(clazz, token)?.let { bitmap ->
                cacheMethod(clazz, ScreenshotMethodType.SCREENSHOT_HARDWARE_BUFFER)
                return bitmap
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            tryScreenshotBitmapRectRotation(clazz, crop, width, height, rotation)?.let { bitmap ->
                cacheMethod(clazz, ScreenshotMethodType.SCREENSHOT_BITMAP_RECT_ROTATION)
                return bitmap
            }
        }

        tryScreenshotBitmapRect(clazz, crop, width, height)?.let { bitmap ->
            cacheMethod(clazz, ScreenshotMethodType.SCREENSHOT_BITMAP_RECT)
            return bitmap
        }

        tryScreenshotBitmapSize(clazz, width, height)?.let { bitmap ->
            cacheMethod(clazz, ScreenshotMethodType.SCREENSHOT_BITMAP_SIZE)
            return bitmap
        }

        return null
    }

    private fun cacheMethod(clazz: Class<*>, type: ScreenshotMethodType) {
        runCatching {
            cachedScreenshotMethod = when (type) {
                ScreenshotMethodType.SCREENSHOT_HARDWARE_BUFFER ->
                    clazz.getMethod("screenshot", IBinder::class.java)
                ScreenshotMethodType.SCREENSHOT_BITMAP_RECT_ROTATION ->
                    clazz.getMethod(
                        "screenshot",
                        Rect::class.java,
                        Int::class.javaPrimitiveType,
                        Int::class.javaPrimitiveType,
                        Int::class.javaPrimitiveType
                    )
                ScreenshotMethodType.SCREENSHOT_BITMAP_RECT ->
                    clazz.getMethod(
                        "screenshot",
                        Rect::class.java,
                        Int::class.javaPrimitiveType,
                        Int::class.javaPrimitiveType
                    )
                ScreenshotMethodType.SCREENSHOT_BITMAP_SIZE ->
                    clazz.getMethod(
                        "screenshot",
                        Int::class.javaPrimitiveType,
                        Int::class.javaPrimitiveType
                    )
            }
            cachedScreenshotMethodType = type
        }
    }

    private fun invokeScreenshotMethod(
        method: Method,
        type: ScreenshotMethodType,
        token: IBinder,
        crop: Rect?,
        width: Int,
        height: Int,
        rotation: Int
    ): Bitmap? {
        return runCatching {
            when (type) {
                ScreenshotMethodType.SCREENSHOT_HARDWARE_BUFFER -> {
                    val result = method.invoke(null, token) ?: return null
                    extractBitmapFromHardwareBuffer(result)
                }
                ScreenshotMethodType.SCREENSHOT_BITMAP_RECT_ROTATION ->
                    method.invoke(null, crop ?: Rect(), width, height, rotation) as? Bitmap
                ScreenshotMethodType.SCREENSHOT_BITMAP_RECT ->
                    method.invoke(null, crop ?: Rect(), width, height) as? Bitmap
                ScreenshotMethodType.SCREENSHOT_BITMAP_SIZE ->
                    method.invoke(null, width, height) as? Bitmap
            }
        }.getOrElse {
            cachedScreenshotMethod = null
            cachedScreenshotMethodType = null
            null
        }
    }

    private fun tryScreenshotHardwareBuffer(clazz: Class<*>, token: IBinder): Bitmap? {
        return runCatching {
            val method = clazz.getMethod("screenshot", IBinder::class.java)
            val result = method.invoke(null, token) ?: return null
            extractBitmapFromHardwareBuffer(result)
        }.getOrNull()
    }

    @SuppressLint("WrongConstant")
    private fun extractBitmapFromHardwareBuffer(screenshotResult: Any): Bitmap? {
        return runCatching {
            val getHardwareBuffer = screenshotResult.javaClass.getMethod("getHardwareBuffer")
            val hardwareBuffer = getHardwareBuffer.invoke(screenshotResult) as? HardwareBuffer
                ?: return null
            val colorSpace = runCatching {
                screenshotResult.javaClass.getMethod("getColorSpace")
                    .invoke(screenshotResult) as? ColorSpace
            }.getOrNull()
            val hardwareBitmap = Bitmap.wrapHardwareBuffer(hardwareBuffer, colorSpace)
            hardwareBuffer.close()
            hardwareBitmap?.copy(Bitmap.Config.ARGB_8888, false)
        }.getOrNull()
    }

    private fun tryScreenshotBitmapRectRotation(
        clazz: Class<*>,
        crop: Rect?,
        width: Int,
        height: Int,
        rotation: Int
    ): Bitmap? {
        return runCatching {
            clazz.getMethod(
                "screenshot",
                Rect::class.java,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType
            ).invoke(null, crop ?: Rect(), width, height, rotation) as? Bitmap
        }.getOrNull()
    }

    private fun tryScreenshotBitmapRect(clazz: Class<*>, crop: Rect?, width: Int, height: Int): Bitmap? {
        return runCatching {
            clazz.getMethod(
                "screenshot",
                Rect::class.java,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType
            ).invoke(null, crop ?: Rect(), width, height) as? Bitmap
        }.getOrNull()
    }

    private fun tryScreenshotBitmapSize(clazz: Class<*>, width: Int, height: Int): Bitmap? {
        return runCatching {
            clazz.getMethod(
                "screenshot",
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType
            ).invoke(null, width, height) as? Bitmap
        }.getOrNull()
    }
}
