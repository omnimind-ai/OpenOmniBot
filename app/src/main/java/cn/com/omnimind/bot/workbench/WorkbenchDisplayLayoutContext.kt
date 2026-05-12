package cn.com.omnimind.bot.workbench

import android.content.Context
import cn.com.omnimind.baselib.i18n.PromptLocale
import kotlin.math.round

/**
 * Last measured Workbench Display viewport reported by Flutter.
 *
 * HTML generation should target the actual right-side Workspace/WebView surface
 * instead of relying on fixed phone-size guesses. Android display metrics are
 * used only as a fallback before Flutter has reported a concrete layout.
 */
object WorkbenchDisplayLayoutContext {
    @Volatile
    private var latestProfile: Map<String, Any?>? = null

    fun updateFromFrontendContext(frontendContext: Map<String, Any?>): Map<String, Any?>? {
        val rawProfile = extractLayoutProfile(frontendContext) ?: return null
        val normalized = normalizeProfile(rawProfile, frontendContext) ?: return null
        latestProfile = normalized
        return normalized
    }

    fun latestMeasuredProfile(): Map<String, Any?>? = latestProfile

    fun promptSection(context: Context, locale: PromptLocale): String {
        val measured = latestProfile
        val profile = measured ?: fallbackDeviceProfile(context)
        val source = profile["source"]?.toString().orEmpty()
        val width = formatDp(profile["viewportWidthDp"])
        val height = formatDp(profile["viewportHeightDp"])
        val screenWidth = formatDp(profile["screenWidthDp"])
        val screenHeight = formatDp(profile["screenHeightDp"])
        val orientation = profile["orientation"]?.toString().orEmpty().ifBlank { "unknown" }
        val measuredLabel = if (measured == null) {
            when (locale) {
                PromptLocale.ZH_CN -> "Android 设备显示指标兜底，等待 Flutter Workspace 上报后会被实测值替换"
                PromptLocale.EN_US -> "Android display-metrics fallback; replaced by measured Flutter Workspace values once reported"
            }
        } else {
            when (locale) {
                PromptLocale.ZH_CN -> "Flutter LayoutBuilder/MediaQuery 实测"
                PromptLocale.EN_US -> "measured by Flutter LayoutBuilder/MediaQuery"
            }
        }
        return when (locale) {
            PromptLocale.ZH_CN -> """
                当前 Workbench Display 布局（App 运行时获取，不是写死默认值）：
                - source: $source
                - measurement: $measuredLabel
                - viewportDp: $width x $height（宽 x 可见高）
                - screenDp: $screenWidth x $screenHeight
                - orientation: $orientation
                HTML 生成必须以 viewportDp 作为右侧 Workspace/WebView 的目标尺寸；首屏内容应控制在当前 viewportHeightDp 内，普通交互 UI 和竖屏报告默认 `width=device-width`，不要使用桌面 hero、满屏装饰区或横向宽表格。只有用户明确要求宽屏报告/PPT/横向画布时才使用固定宽画布。
            """.trimIndent()
            PromptLocale.EN_US -> """
                Current Workbench Display layout (runtime app measurement, not hard-coded defaults):
                - source: $source
                - measurement: $measuredLabel
                - viewportDp: $width x $height (width x visible height)
                - screenDp: $screenWidth x $screenHeight
                - orientation: $orientation
                Generated HTML must target viewportDp as the right-side Workspace/WebView size. Keep the first viewport within the current viewportHeightDp. Mobile interaction UI and portrait reports default to `width=device-width`; avoid desktop heroes, full-screen decorative sections, and wide horizontal tables. Use a fixed wide canvas only when the user explicitly asks for a wide report, slide deck, or landscape canvas.
            """.trimIndent()
        }
    }

    fun toolGuidance(locale: PromptLocale): String {
        val profile = latestProfile
        if (profile == null) {
            return when (locale) {
                PromptLocale.ZH_CN ->
                    "使用系统提示中的当前 Workbench Display 布局；不要写死手机宽高。若本轮尚无实测布局，生成响应式手机竖屏页面，并等待 App 注入 layout profile 后再按实测值热更新。"
                PromptLocale.EN_US ->
                    "Use the current Workbench Display layout from the system prompt; do not hard-code phone dimensions. If no measured layout exists yet, generate a responsive portrait mobile page and adjust after the app injects a layout profile."
            }
        }
        val width = formatDp(profile["viewportWidthDp"])
        val height = formatDp(profile["viewportHeightDp"])
        return when (locale) {
            PromptLocale.ZH_CN ->
                "当前 App 实测右侧 Workspace/WebView 尺寸为 $width x $height（宽 x 可见高）；HTML 必须按这个 viewport 生成，不要写死默认宽高。"
            PromptLocale.EN_US ->
                "The app currently measured the right-side Workspace/WebView at $width x $height (width x visible height); generated HTML must target this viewport and must not hard-code default dimensions."
        }
    }

    private fun extractLayoutProfile(frontendContext: Map<String, Any?>): Map<*, *>? {
        val direct = frontendContext["workbenchLayout"] as? Map<*, *>
            ?: frontendContext["layoutProfile"] as? Map<*, *>
        if (direct != null) return direct
        val visibleState = frontendContext["visibleState"] as? Map<*, *> ?: return null
        return visibleState["workbenchLayout"] as? Map<*, *>
            ?: visibleState["layoutProfile"] as? Map<*, *>
    }

    private fun normalizeProfile(
        raw: Map<*, *>,
        frontendContext: Map<String, Any?>
    ): Map<String, Any?>? {
        val width = firstNumber(raw, "viewportWidthDp", "visibleWidthDp", "widthDp")
            ?: return null
        val height = firstNumber(raw, "viewportHeightDp", "visibleHeightDp", "heightDp")
            ?: return null
        if (width <= 0.0 || height <= 0.0) return null
        val screenWidth = firstNumber(raw, "screenWidthDp")
        val screenHeight = firstNumber(raw, "screenHeightDp")
        val orientation = raw["orientation"]?.toString()?.trim()?.ifBlank { null }
            ?: if (width <= height) "portrait" else "landscape"
        return linkedMapOf(
            "source" to (
                raw["source"]?.toString()?.trim()?.takeIf { it.isNotEmpty() }
                    ?: frontendContext["source"]?.toString()?.trim()?.takeIf { it.isNotEmpty() }
                    ?: "flutter_workbench_layout"
                ),
            "viewportWidthDp" to roundOne(width),
            "viewportHeightDp" to roundOne(height),
            "screenWidthDp" to screenWidth?.let(::roundOne),
            "screenHeightDp" to screenHeight?.let(::roundOne),
            "devicePixelRatio" to firstNumber(raw, "devicePixelRatio")?.let(::roundOne),
            "orientation" to orientation,
            "projectId" to (
                raw["projectId"]?.toString()?.trim()?.takeIf { it.isNotEmpty() }
                    ?: frontendContext["projectId"]?.toString()?.trim()?.takeIf { it.isNotEmpty() }
                ),
            "displayId" to (
                raw["displayId"]?.toString()?.trim()?.takeIf { it.isNotEmpty() }
                    ?: frontendContext["displayId"]?.toString()?.trim()?.takeIf { it.isNotEmpty() }
                ),
            "route" to (
                raw["route"]?.toString()?.trim()?.takeIf { it.isNotEmpty() }
                    ?: frontendContext["route"]?.toString()?.trim()?.takeIf { it.isNotEmpty() }
                ),
            "measuredAtMillis" to (
                (raw["measuredAtMillis"] as? Number)?.toLong()
                    ?: System.currentTimeMillis()
                ),
            "measured" to true
        ).filterValues { it != null }
    }

    private fun fallbackDeviceProfile(context: Context): Map<String, Any?> {
        val metrics = context.resources.displayMetrics
        val density = metrics.density.takeIf { it > 0f } ?: 1f
        val widthDp = metrics.widthPixels / density
        val heightDp = metrics.heightPixels / density
        return linkedMapOf(
            "source" to "android_display_metrics_fallback",
            "viewportWidthDp" to roundOne(widthDp.toDouble()),
            "viewportHeightDp" to roundOne(heightDp.toDouble()),
            "screenWidthDp" to roundOne(widthDp.toDouble()),
            "screenHeightDp" to roundOne(heightDp.toDouble()),
            "devicePixelRatio" to roundOne(density.toDouble()),
            "orientation" to if (widthDp <= heightDp) "portrait" else "landscape",
            "measured" to false
        )
    }

    private fun firstNumber(raw: Map<*, *>, vararg keys: String): Double? {
        for (key in keys) {
            val number = toDouble(raw[key])
            if (number != null) return number
        }
        return null
    }

    private fun toDouble(value: Any?): Double? = when (value) {
        is Number -> value.toDouble()
        is String -> value.trim().toDoubleOrNull()
        else -> null
    }

    private fun roundOne(value: Double): Double = round(value * 10.0) / 10.0

    private fun formatDp(value: Any?): String {
        val number = toDouble(value) ?: return "unknown"
        return if (number % 1.0 == 0.0) {
            "${number.toInt()}dp"
        } else {
            "${roundOne(number)}dp"
        }
    }
}
