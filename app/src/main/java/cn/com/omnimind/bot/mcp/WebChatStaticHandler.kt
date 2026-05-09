package cn.com.omnimind.bot.mcp

import android.content.Context
import io.ktor.http.ContentType
import io.ktor.server.application.call
import io.ktor.server.request.path
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondRedirect
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

/**
 * WebChat 静态文件托管路由。
 *
 * 从 McpServerManager 拆分而来，负责 Flutter Web 产物的 SPA 路由 + 资源分发。
 */
object WebChatStaticHandler {

    private const val WEBCHAT_ASSET_DIR = "flutter_web"

    fun Route.registerWebChatStaticRoutes(context: Context) {
        val appContext = context.applicationContext

        get("/webchat") {
            call.respondRedirect("/webchat/")
        }
        get("/webchat/") {
            serveStatic(call, appContext)
        }
        get("/webchat/{...}") {
            if (call.request.path().startsWith("/webchat/api/")) {
                call.respond(io.ktor.http.HttpStatusCode.NotFound)
                return@get
            }
            serveStatic(call, appContext)
        }
    }

    private suspend fun serveStatic(
        call: io.ktor.server.application.ApplicationCall,
        context: Context
    ) {
        val requestPath = call.request.path()
            .removePrefix("/webchat")
            .trimStart('/')
        val normalizedPath = requestPath
            .takeIf { it.isNotBlank() && !it.contains("..") }
            ?: "index.html"
        val assetPath = if (normalizedPath.contains('.')) {
            "$WEBCHAT_ASSET_DIR/$normalizedPath"
        } else {
            "$WEBCHAT_ASSET_DIR/index.html"
        }
        val assetBytes = openAssetBytes(context, assetPath, normalizedPath)
        if (assetBytes != null) {
            call.respondBytes(bytes = assetBytes, contentType = contentTypeForPath(assetPath))
            return
        }
        if (!normalizedPath.endsWith("index.html")) {
            val fallbackIndex = openAssetBytes(
                context,
                "$WEBCHAT_ASSET_DIR/index.html",
                "index.html"
            )
            if (fallbackIndex != null) {
                call.respondBytes(bytes = fallbackIndex, contentType = ContentType.Text.Html)
                return
            }
        }
        call.respondText(
            """
            <!doctype html>
            <html lang="zh-CN">
            <head>
              <meta charset="utf-8" />
              <meta name="viewport" content="width=device-width, initial-scale=1" />
              <title>Omnibot Web Chat</title>
              <style>
                body { font-family: sans-serif; margin: 0; padding: 32px; background: #f7f9fc; color: #24324a; }
                .card { max-width: 680px; margin: 8vh auto 0; background: white; border-radius: 20px; padding: 28px; box-shadow: 0 16px 48px rgba(19, 38, 72, 0.12); }
                code { background: #eef3fb; padding: 2px 6px; border-radius: 6px; }
              </style>
            </head>
            <body>
              <div class="card">
                <h2>Web Chat Bundle Missing</h2>
                <p>尚未找到 Flutter Web 构建产物，请重新构建并安装最新 APK，确保 <code>flutter build web --base-href /webchat/</code> 的产物已被打包进应用。</p>
              </div>
            </body>
            </html>
            """.trimIndent(),
            contentType = ContentType.Text.Html
        )
    }

    private fun openAssetBytes(context: Context, vararg assetPaths: String): ByteArray? {
        assetPaths.forEach { candidate ->
            val bytes = runCatching {
                context.assets.open(candidate).use { input -> input.readBytes() }
            }.getOrNull()
            if (bytes != null) return bytes
        }
        return null
    }

    private fun contentTypeForPath(path: String): ContentType {
        return when {
            path.endsWith(".html") -> ContentType.Text.Html
            path.endsWith(".js") -> ContentType.Application.JavaScript
            path.endsWith(".css") -> ContentType.Text.CSS
            path.endsWith(".json") -> ContentType.Application.Json
            path.endsWith(".png") -> ContentType.Image.PNG
            path.endsWith(".jpg") || path.endsWith(".jpeg") -> ContentType.Image.JPEG
            path.endsWith(".svg") -> ContentType.parse("image/svg+xml")
            path.endsWith(".wasm") -> ContentType.parse("application/wasm")
            path.endsWith(".ico") -> ContentType.parse("image/x-icon")
            else -> ContentType.Application.OctetStream
        }
    }
}
