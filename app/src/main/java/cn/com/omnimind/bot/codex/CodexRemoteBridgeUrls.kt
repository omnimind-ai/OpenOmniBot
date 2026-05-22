package cn.com.omnimind.bot.codex

import java.net.URI

internal fun normalizeCodexBridgeWebSocketUrl(raw: String): String {
    val parsed = parseBridgeUri(raw)
    val scheme = when (parsed.scheme.lowercase()) {
        "https", "wss" -> "wss"
        else -> "ws"
    }
    return rebuildBridgeUri(parsed, scheme, defaultPath = "/codex")
}

internal fun normalizeCodexBridgeHealthUrl(raw: String): String {
    return normalizeCodexBridgeHttpUrl(raw, forcePath = "/health")
}

internal fun normalizeCodexBridgeFsListUrl(raw: String): String {
    return normalizeCodexBridgeHttpUrl(raw, forcePath = "/fs/list")
}

internal fun normalizeCodexBridgeFsReadUrl(raw: String): String {
    return normalizeCodexBridgeHttpUrl(raw, forcePath = "/fs/read")
}

internal fun normalizeCodexBridgeFsWriteUrl(raw: String): String {
    return normalizeCodexBridgeHttpUrl(raw, forcePath = "/fs/write")
}

internal fun normalizeCodexBridgeFsDeleteUrl(raw: String): String {
    return normalizeCodexBridgeHttpUrl(raw, forcePath = "/fs/delete")
}

internal fun normalizeCodexBridgeFsMoveUrl(raw: String): String {
    return normalizeCodexBridgeHttpUrl(raw, forcePath = "/fs/move")
}

private fun normalizeCodexBridgeHttpUrl(raw: String, forcePath: String): String {
    val parsed = parseBridgeUri(raw)
    val scheme = when (parsed.scheme.lowercase()) {
        "https", "wss" -> "https"
        else -> "http"
    }
    return rebuildBridgeUri(parsed, scheme, defaultPath = forcePath, forcePath = forcePath)
}

private data class ParsedBridgeUri(
    val scheme: String,
    val host: String,
    val port: Int,
    val path: String,
    val query: String?
)

private fun parseBridgeUri(raw: String): ParsedBridgeUri {
    val trimmed = raw.trim()
    require(trimmed.isNotEmpty()) { "Codex bridge URL is empty." }
    val withScheme = if (trimmed.contains("://")) trimmed else "ws://$trimmed"
    val uri = URI(withScheme)
    val scheme = uri.scheme?.lowercase()
        ?: throw IllegalArgumentException("Codex bridge URL is missing a scheme.")
    require(scheme in setOf("ws", "wss", "http", "https")) {
        "Codex bridge URL must use ws, wss, http, or https."
    }
    val host = uri.host ?: throw IllegalArgumentException("Codex bridge URL is missing a host.")
    return ParsedBridgeUri(
        scheme = scheme,
        host = host,
        port = uri.port,
        path = uri.rawPath.orEmpty(),
        query = uri.rawQuery
    )
}

private fun rebuildBridgeUri(
    parsed: ParsedBridgeUri,
    scheme: String,
    defaultPath: String,
    forcePath: String? = null
): String {
    val path = forcePath
        ?: parsed.path.takeIf { it.isNotBlank() && it != "/" }
        ?: defaultPath
    val portPart = if (parsed.port >= 0) ":${parsed.port}" else ""
    val queryPart = parsed.query?.takeIf { it.isNotBlank() }?.let { "?$it" }.orEmpty()
    return "$scheme://${parsed.host}$portPart$path$queryPart"
}
