package cn.com.omnimind.assists.task.vlmserver

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.os.Build
import android.provider.Settings
import org.w3c.dom.Element
import org.xml.sax.InputSource
import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.math.abs
import kotlin.math.min

object VLMSettingsToggleCompletionChecker {
    data class StateSnapshot(
        val wifiEnabled: Boolean? = null,
        val bluetoothEnabled: Boolean? = null
    )

    data class Result(
        val complete: Boolean,
        val reason: String = "",
        val summary: String = "",
        val stateSource: String = ""
    )

    private data class ToggleIntent(
        val wifiEnabled: Boolean? = null,
        val bluetoothEnabled: Boolean? = null
    ) {
        val hasAny: Boolean get() = wifiEnabled != null || bluetoothEnabled != null
    }

    fun check(
        goal: String,
        currentXml: String?,
        stateSnapshot: StateSnapshot,
        currentPackageName: String? = null,
        targetPackageName: String? = null
    ): Result {
        val intent = inferToggleIntent(goal)
        if (!intent.hasAny) return Result(complete = false, reason = "no_settings_toggle_intent")
        if (hasNonToggleBluetoothIntent(goal)) {
            return Result(complete = false, reason = "non_toggle_bluetooth_intent")
        }

        val xmlState = readStateFromXml(currentXml)
        val wifiState = stateSnapshot.wifiEnabled ?: xmlState.state.wifiEnabled
        val bluetoothState = stateSnapshot.bluetoothEnabled ?: xmlState.state.bluetoothEnabled
        val stateSource = stateSourceForIntent(intent, stateSnapshot, xmlState)
        val mismatches = mutableListOf<String>()
        val unknown = mutableListOf<String>()

        intent.wifiEnabled?.let { expected ->
            when (wifiState) {
                null -> unknown += "wifi"
                expected -> {}
                else -> mismatches += "wifi=${stateName(wifiState)} expected=${stateName(expected)}"
            }
        }
        intent.bluetoothEnabled?.let { expected ->
            when (bluetoothState) {
                null -> unknown += "bluetooth"
                expected -> {}
                else -> mismatches += "bluetooth=${stateName(bluetoothState)} expected=${stateName(expected)}"
            }
        }

        if (mismatches.isNotEmpty()) {
            return Result(
                complete = false,
                reason = "state_mismatch:${mismatches.joinToString(";")}",
                stateSource = stateSource
            )
        }
        if (unknown.isNotEmpty()) {
            return Result(
                complete = false,
                reason = "unknown_state:${unknown.joinToString(",")}",
                stateSource = stateSource
            )
        }

        val packageHint = listOfNotNull(currentPackageName, targetPackageName)
            .firstOrNull { it.equals("com.android.settings", ignoreCase = true) }
            ?.let { " in Settings" }
            .orEmpty()
        return Result(
            complete = true,
            reason = "settings_toggle_state_satisfied",
            summary = buildSummary(intent, wifiState, bluetoothState, packageHint),
            stateSource = stateSource
        )
    }

    fun mayHandle(goal: String): Boolean {
        val intent = inferToggleIntent(goal)
        return intent.hasAny && !hasNonToggleBluetoothIntent(goal)
    }

    fun progressMarkers(goal: String, stateSnapshot: StateSnapshot): List<String> {
        val intent = inferToggleIntent(goal)
        if (!intent.hasAny) return emptyList()
        val markers = mutableListOf<String>()
        intent.wifiEnabled?.let { expected ->
            markers += progressMarker(
                domain = "wifi",
                expected = expected,
                actual = stateSnapshot.wifiEnabled
            )
        }
        intent.bluetoothEnabled?.let { expected ->
            markers += progressMarker(
                domain = "bluetooth",
                expected = expected,
                actual = stateSnapshot.bluetoothEnabled
            )
        }
        return markers
    }

    fun readDeviceState(context: Context?): StateSnapshot {
        if (context == null) return StateSnapshot()
        val appContext = context.applicationContext ?: context
        val resolver = appContext.contentResolver

        val wifiEnabled = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                appContext.getSystemService(WifiManager::class.java)?.isWifiEnabled
            } else {
                @Suppress("DEPRECATION")
                (appContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager)?.isWifiEnabled
            }
        }.getOrNull() ?: runCatching {
            Settings.Global.getInt(resolver, "wifi_on") != 0
        }.getOrNull()

        val bluetoothGlobalEnabled = runCatching {
            Settings.Global.getInt(resolver, "bluetooth_on") != 0
        }.getOrNull()
        val bluetoothAdapterEnabled = runCatching {
            val hasConnectPermission = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
                appContext.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
            if (hasConnectPermission) {
                @Suppress("DEPRECATION")
                BluetoothAdapter.getDefaultAdapter()?.isEnabled
            } else {
                null
            }
        }.getOrNull()
        val bluetoothEnabled = bluetoothGlobalEnabled ?: bluetoothAdapterEnabled

        return StateSnapshot(
            wifiEnabled = wifiEnabled,
            bluetoothEnabled = bluetoothEnabled
        )
    }

    private fun inferToggleIntent(goal: String): ToggleIntent {
        val normalized = normalize(goal)
        if (normalized.isBlank()) return ToggleIntent()
        return ToggleIntent(
            wifiEnabled = desiredStateNearDomain(normalized, WIFI_PATTERNS),
            bluetoothEnabled = desiredStateNearDomain(normalized, BLUETOOTH_PATTERNS)
        )
    }

    private fun desiredStateNearDomain(
        normalizedGoal: String,
        domainPatterns: List<String>
    ): Boolean? {
        val candidates = mutableListOf<Pair<Boolean, Int>>()
        domainPatterns.forEach { domain ->
            var index = normalizedGoal.indexOf(domain)
            while (index >= 0) {
                val start = (index - WINDOW_CHARS).coerceAtLeast(0)
                val end = (index + domain.length + WINDOW_CHARS).coerceAtMost(normalizedGoal.length)
                val window = normalizedGoal.substring(start, end)
                nearestStateTerm(window, index - start)?.let { candidates += it }
                index = normalizedGoal.indexOf(domain, startIndex = index + domain.length)
            }
        }
        return candidates.minByOrNull { it.second }?.first
    }

    private fun nearestStateTerm(window: String, domainIndex: Int): Pair<Boolean, Int>? {
        val off = nearestDistance(window, domainIndex, OFF_TERMS)
        val on = nearestDistance(window, domainIndex, ON_TERMS)
        return when {
            off == null && on == null -> null
            off != null && on == null -> false to off
            off == null && on != null -> true to on
            off != null && on != null -> if (off <= on) false to off else true to on
            else -> null
        }
    }

    private fun nearestDistance(text: String, anchor: Int, terms: List<String>): Int? =
        terms
            .flatMap { term ->
                findTermIndexes(text, term).map { index -> abs(index - anchor) }
            }
            .minOrNull()

    private fun hasNonToggleBluetoothIntent(goal: String): Boolean {
        val normalized = normalize(goal)
        if (BLUETOOTH_PATTERNS.none { normalized.contains(it) }) return false
        return NON_TOGGLE_BLUETOOTH_TERMS.any { normalized.contains(it) }
    }

    private fun readStateFromXml(xml: String?): ParsedStateSnapshot {
        if (xml.isNullOrBlank()) return ParsedStateSnapshot()
        val nodes = parseNodes(xml)
        var wifi: Boolean? = null
        var bluetooth: Boolean? = null
        var wifiEvidence: XmlStateEvidence? = null
        var bluetoothEvidence: XmlStateEvidence? = null

        nodes.forEach { node ->
            val label = normalize(node.semanticText)
            val checkedState = node.checked
            val state = checkedState ?: stateFromText(label)
            if (state == null) return@forEach
            val evidence = if (checkedState != null) {
                XmlStateEvidence.CHECKED_ATTRIBUTE
            } else {
                XmlStateEvidence.TEXT_STATE
            }
            if (wifi == null && WIFI_PATTERNS.any { label.contains(it) }) {
                wifi = state
                wifiEvidence = evidence
            }
            if (bluetooth == null && BLUETOOTH_PATTERNS.any { label.contains(it) }) {
                bluetooth = state
                bluetoothEvidence = evidence
            }
        }
        return ParsedStateSnapshot(
            state = StateSnapshot(wifiEnabled = wifi, bluetoothEnabled = bluetooth),
            wifiEvidence = wifiEvidence,
            bluetoothEvidence = bluetoothEvidence
        )
    }

    private fun stateSourceForIntent(
        intent: ToggleIntent,
        stateSnapshot: StateSnapshot,
        xmlState: ParsedStateSnapshot
    ): String {
        val sources = mutableListOf<String>()
        intent.wifiEnabled?.let {
            sources += stateSourceForDomain(stateSnapshot.wifiEnabled, xmlState.wifiEvidence)
        }
        intent.bluetoothEnabled?.let {
            sources += stateSourceForDomain(stateSnapshot.bluetoothEnabled, xmlState.bluetoothEvidence)
        }
        val distinct = sources.distinct()
        return when {
            distinct.isEmpty() -> ""
            distinct.size == 1 -> distinct.single()
            else -> distinct.joinToString("+")
        }
    }

    private fun stateSourceForDomain(
        snapshotState: Boolean?,
        xmlEvidence: XmlStateEvidence?,
    ): String =
        when {
            snapshotState != null -> "state_snapshot"
            xmlEvidence == XmlStateEvidence.CHECKED_ATTRIBUTE -> "xml_checked"
            xmlEvidence == XmlStateEvidence.TEXT_STATE -> "xml_text"
            else -> "unknown"
        }

    private fun parseNodes(xml: String): List<XmlNode> {
        val document = runCatching {
            val factory = DocumentBuilderFactory.newInstance().apply {
                isNamespaceAware = false
                isExpandEntityReferences = false
                runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
                runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
                runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
            }
            factory.newDocumentBuilder().parse(InputSource(StringReader(xml)))
        }.getOrNull() ?: return emptyList()

        val nodeList = document.getElementsByTagName("node")
        val nodes = ArrayList<XmlNode>(nodeList.length)
        for (index in 0 until nodeList.length) {
            val element = nodeList.item(index) as? Element ?: continue
            nodes += XmlNode(
                semanticText = listOf(
                    element.attr("text"),
                    element.attr("content-desc"),
                    element.attr("state-description"),
                    element.attr("resource-id"),
                    descendantSemanticText(element)
                ).filter { it.isNotBlank() }.joinToString(" "),
                checked = element.boolAttrOrNull("checked")
            )
        }
        return nodes
    }

    private fun descendantSemanticText(element: Element): String {
        val parts = linkedSetOf<String>()
        val descendants = element.getElementsByTagName("node")
        for (index in 0 until min(descendants.length, MAX_DESCENDANTS)) {
            val child = descendants.item(index) as? Element ?: continue
            if (child === element) continue
            listOf(
                child.attr("text"),
                child.attr("content-desc"),
                child.attr("state-description")
            ).filter { it.isNotBlank() }.forEach { parts += it }
        }
        return parts.joinToString(" ")
    }

    private fun stateFromText(text: String): Boolean? {
        if (hasNonCurrentStatePhrase(text)) return null
        return when {
            ON_STATE_TERMS.any { containsTerm(text, it) } -> true
            OFF_STATE_TERMS.any { containsTerm(text, it) } -> false
            else -> null
        }
    }

    private fun hasNonCurrentStatePhrase(text: String): Boolean =
        NON_CURRENT_STATE_PHRASES.any { containsTerm(text, it) } ||
            CONDITIONAL_STATE_PREFIXES.any { prefix ->
                text.contains(prefix) && (text.contains(" turned on") || text.contains(" enabled"))
            }

    private fun buildSummary(
        intent: ToggleIntent,
        wifiState: Boolean?,
        bluetoothState: Boolean?,
        packageHint: String
    ): String {
        val states = mutableListOf<String>()
        intent.wifiEnabled?.let { states += "Wi-Fi is ${stateName(wifiState ?: it)}" }
        intent.bluetoothEnabled?.let { states += "Bluetooth is ${stateName(bluetoothState ?: it)}" }
        return "Settings toggle goal satisfied$packageHint: ${states.joinToString(", ")}."
    }

    private fun stateName(enabled: Boolean): String = if (enabled) "on" else "off"

    private fun progressMarker(domain: String, expected: Boolean, actual: Boolean?): String =
        when (actual) {
            null -> "settings_state_unknown:$domain=${stateName(expected)}"
            expected -> "settings_state_verified:$domain=${stateName(expected)}"
            else -> "settings_state_pending:$domain=${stateName(expected)} actual=${stateName(actual)}"
        }

    private fun normalize(value: String): String =
        value.lowercase()
            .replace(Regex("""[\p{Pd}_/\\|]+"""), " ")
            .replace(Regex("""[^\p{L}\p{N}\u4e00-\u9fff ]+"""), " ")
            .replace(Regex("""\s+"""), " ")
            .trim()

    private fun containsTerm(text: String, term: String): Boolean =
        findTermIndexes(text, term).isNotEmpty()

    private fun findTermIndexes(text: String, term: String): List<Int> {
        if (text.isBlank() || term.isBlank()) return emptyList()
        val hits = mutableListOf<Int>()
        var index = text.indexOf(term)
        while (index >= 0) {
            if (containsCjk(term) || (hasBoundary(text, index - 1) && hasBoundary(text, index + term.length))) {
                hits += index
            }
            index = text.indexOf(term, startIndex = index + term.length)
        }
        return hits
    }

    private fun containsCjk(value: String): Boolean =
        value.any { it in '\u4e00'..'\u9fff' }

    private fun hasBoundary(text: String, index: Int): Boolean =
        index < 0 || index >= text.length || !text[index].isLetterOrDigit()

    private fun Element.attr(name: String): String =
        if (hasAttribute(name)) getAttribute(name) else ""

    private fun Element.boolAttrOrNull(name: String): Boolean? =
        if (!hasAttribute(name)) null else getAttribute(name).equals("true", ignoreCase = true)

    private data class XmlNode(
        val semanticText: String,
        val checked: Boolean?
    )

    private data class ParsedStateSnapshot(
        val state: StateSnapshot = StateSnapshot(),
        val wifiEvidence: XmlStateEvidence? = null,
        val bluetoothEvidence: XmlStateEvidence? = null
    )

    private enum class XmlStateEvidence {
        CHECKED_ATTRIBUTE,
        TEXT_STATE
    }

    private const val WINDOW_CHARS = 36
    private const val MAX_DESCENDANTS = 24
    private val WIFI_PATTERNS = listOf("wifi", "wi fi", "wireless", "无线", "wifi")
    private val BLUETOOTH_PATTERNS = listOf("bluetooth", "blue tooth", "蓝牙")
    private val OFF_TERMS = listOf("turn off", "turned off", "disable", "disabled", "deactivate", "off", "close", "关闭", "关掉", "禁用")
    private val ON_TERMS = listOf("turn on", "turned on", "enable", "enabled", "activate", "on", "开启", "启用", "打开")
    private val OFF_STATE_TERMS = listOf("off", "disabled", "关闭", "已关闭", "禁用")
    private val ON_STATE_TERMS = listOf("on", "enabled", "开启", "已开启", "启用")
    private val CONDITIONAL_STATE_PREFIXES = listOf("when ", "after ", "once ", "if ")
    private val NON_CURRENT_STATE_PHRASES = listOf(
        "will turn on",
        "will enable",
        "turn on to",
        "enable to",
        "tap to turn on",
        "tap to enable",
        "click to turn on",
        "click to enable",
        "press to turn on",
        "press to enable",
        "select to turn on",
        "select to enable",
        "try to turn on",
        "try to enable",
        "cannot turn on",
        "cannot enable",
        "can turn on",
        "can enable",
        "to turn on",
        "to enable",
        "将开启",
        "将打开",
        "会开启",
        "会打开",
        "点击开启",
        "点击打开",
        "开启后",
        "打开后",
        "开启时",
        "打开时",
    )
    private val NON_TOGGLE_BLUETOOTH_TERMS = listOf("pair", "pairing", "bluetooth settings", "配对", "蓝牙设置")
}
