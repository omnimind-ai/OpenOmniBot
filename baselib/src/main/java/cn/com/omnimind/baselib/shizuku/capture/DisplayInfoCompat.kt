/*
 * Copyright (C) 2024 AutoGLM
 *
 * Display information wrapper inspired by the scrcpy project.
 *
 * scrcpy is licensed under the Apache License, Version 2.0:
 * Copyright (C) 2018 Genymobile
 * Copyright (C) 2018-2024 Romain Vimont
 * https://github.com/Genymobile/scrcpy
 *
 * Modifications for Omnibot:
 * - Kept only the single-display metrics needed by Shizuku VLM screenshots.
 * - Added command fallback in the caller instead of a video/display server.
 */

package cn.com.omnimind.baselib.shizuku.capture

import android.annotation.SuppressLint
import android.view.Display

@SuppressLint("PrivateApi", "DiscouragedPrivateApi")
object DisplayInfoCompat {

    data class DisplayMetrics(
        val width: Int,
        val height: Int,
        val rotation: Int,
        val density: Int
    )

    fun getDisplayInfo(displayId: Int = Display.DEFAULT_DISPLAY): DisplayMetrics? {
        return runCatching {
            val displayManagerGlobalClass = Class.forName("android.hardware.display.DisplayManagerGlobal")
            val displayInfoClass = Class.forName("android.view.DisplayInfo")
            val getInstance = displayManagerGlobalClass.getMethod("getInstance")
            val displayManager = getInstance.invoke(null) ?: return null
            val getDisplayInfo = displayManagerGlobalClass.getMethod(
                "getDisplayInfo",
                Int::class.javaPrimitiveType
            )
            val displayInfo = getDisplayInfo.invoke(displayManager, displayId) ?: return null
            DisplayMetrics(
                width = displayInfoClass.getField("logicalWidth").getInt(displayInfo),
                height = displayInfoClass.getField("logicalHeight").getInt(displayInfo),
                rotation = displayInfoClass.getField("rotation").getInt(displayInfo),
                density = displayInfoClass.getField("logicalDensityDpi").getInt(displayInfo)
            )
        }.getOrNull()
    }
}
