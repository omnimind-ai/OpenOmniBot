package cn.com.omnimind.bot.agent.tool.handlers

import cn.com.omnimind.bot.agent.AgentCallback
import cn.com.omnimind.bot.agent.AgentExecutionEnvironment
import cn.com.omnimind.bot.agent.AgentToolExecutionHandle
import cn.com.omnimind.bot.agent.AgentToolNames
import cn.com.omnimind.bot.agent.AgentToolRegistry
import cn.com.omnimind.bot.agent.AgentWorkspaceManager
import cn.com.omnimind.bot.agent.BrowserUseAction
import cn.com.omnimind.bot.agent.BrowserUseRequest
import cn.com.omnimind.bot.agent.LiveAgentBrowserSessionManager
import cn.com.omnimind.bot.agent.ToolExecutionResult
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

class WebSearchToolHandler(
    private val helper: SharedHelper,
    private val workspaceManager: AgentWorkspaceManager
) : ToolHandler {
    override val toolNames: Set<String> = setOf(AgentToolNames.WEB_SEARCH)

    override suspend fun execute(
        toolCall: cn.com.omnimind.baselib.llm.AssistantToolCall,
        args: JsonObject,
        runtimeDescriptor: AgentToolRegistry.RuntimeToolDescriptor,
        env: AgentExecutionEnvironment,
        callback: AgentCallback,
        toolHandle: AgentToolExecutionHandle
    ): ToolExecutionResult {
        val toolName = AgentToolNames.WEB_SEARCH
        return try {
            helper.requireWorkspaceStorageAccess(callback)?.let { return it }
            val query = args["query"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            require(query.isNotEmpty()) { "query 不能为空" }
            val limit = args["limit"]?.jsonPrimitive?.intOrNull?.coerceIn(1, 10) ?: DEFAULT_LIMIT
            val toolTitle = args["tool_title"]?.jsonPrimitive?.contentOrNull?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?: "网页搜索"
            val searchUrl = buildSearchUrl(query)
            val engine = LiveAgentBrowserSessionManager.acquireEngine(
                context = helper.context,
                workspaceManager = workspaceManager,
                agentRunId = env.agentRunId,
                workspace = env.workspaceDescriptor
            )
            toolHandle.bindStopAction { engine.requestInterruptCurrentAction() }

            helper.reportToolProgress(
                callback = callback,
                toolName = toolName,
                progress = "正在搜索网页",
                extras = mapOf("query" to query),
                toolHandle = toolHandle
            )
            val navigateOutcome = engine.execute(
                BrowserUseRequest(
                    toolTitle = toolTitle,
                    action = BrowserUseAction.NAVIGATE,
                    url = searchUrl
                )
            )

            helper.reportToolProgress(
                callback = callback,
                toolName = toolName,
                progress = "正在抽取搜索结果",
                extras = mapOf("query" to query),
                toolHandle = toolHandle
            )
            val extractOutcome = engine.execute(
                BrowserUseRequest(
                    toolTitle = toolTitle,
                    action = BrowserUseAction.EXECUTE_JS,
                    script = buildExtractResultsScript(limit)
                )
            )
            val extracted = extractOutcome.payload["result"] as? Map<*, *> ?: emptyMap<Any?, Any?>()
            val results = normalizeResults(extracted["results"], limit)
            val blockedByRiskChallenge = extractOutcome.payload["blockedByRiskChallenge"] == true ||
                navigateOutcome.payload["riskChallengeDetected"] == true ||
                extractOutcome.payload["riskChallengeDetected"] == true
            val payload = linkedMapOf<String, Any?>(
                "query" to query,
                "limit" to limit,
                "engine" to "duckduckgo_html",
                "searchUrl" to searchUrl,
                "pageUrl" to (extracted["url"] ?: navigateOutcome.payload["finalUrl"]),
                "pageTitle" to (extracted["title"] ?: navigateOutcome.payload["pageTitle"]),
                "resultCount" to results.size,
                "results" to results,
                "textSnippet" to extracted["textSnippet"],
                "blockedByRiskChallenge" to blockedByRiskChallenge,
                "riskChallengeKind" to (extractOutcome.payload["riskChallengeKind"]
                    ?: navigateOutcome.payload["riskChallengeKind"]),
                "recommendedNextAction" to (extractOutcome.payload["recommendedNextAction"]
                    ?: navigateOutcome.payload["recommendedNextAction"])
            )
            val encoded = helper.encodeLocalizedPayload(payload)
            ToolExecutionResult.ContextResult(
                toolName = toolName,
                summaryText = helper.localized(
                    when {
                        blockedByRiskChallenge -> "网页搜索被页面风控阻断"
                        results.isEmpty() -> "网页搜索完成，未抽取到结构化结果。"
                        else -> "找到 ${results.size} 条网页搜索结果。"
                    }
                ),
                previewJson = encoded,
                rawResultJson = encoded,
                success = !blockedByRiskChallenge,
                workspaceId = env.workspaceDescriptor.id
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            helper.workspacePermissionResult(e, callback)?.let { return it }
            helper.errorResult(toolName, e.message, "网页搜索失败")
        }
    }

    private fun buildSearchUrl(query: String): String {
        val encodedQuery = URLEncoder.encode(query, StandardCharsets.UTF_8.name())
        return "https://duckduckgo.com/html/?q=$encodedQuery"
    }

    private fun normalizeResults(rawResults: Any?, limit: Int): List<Map<String, Any?>> {
        return (rawResults as? List<*>).orEmpty()
            .mapNotNull { item ->
                val raw = item as? Map<*, *> ?: return@mapNotNull null
                val title = raw["title"]?.toString()?.trim().orEmpty()
                val url = raw["url"]?.toString()?.trim().orEmpty()
                val snippet = raw["snippet"]?.toString()?.trim().orEmpty()
                if (title.isEmpty() || url.isEmpty()) return@mapNotNull null
                linkedMapOf<String, Any?>(
                    "title" to title,
                    "url" to url,
                    "snippet" to snippet
                )
            }
            .take(limit)
    }

    private fun buildExtractResultsScript(limit: Int): String = """
        const limit = $limit;
        function cleanText(value) {
            return String(value || '').replace(/\s+/g, ' ').trim();
        }
        function normalizeUrl(raw) {
            try {
                const resolved = new URL(raw || '', location.href);
                const host = resolved.hostname.replace(/^www\./, '');
                if (host.endsWith('duckduckgo.com') && resolved.pathname.indexOf('/l/') === 0) {
                    const target = resolved.searchParams.get('uddg');
                    if (target) return decodeURIComponent(target);
                }
                if (host.indexOf('google.') >= 0 && resolved.pathname === '/url') {
                    const target = resolved.searchParams.get('q') || resolved.searchParams.get('url');
                    if (target) return target;
                }
                return resolved.href;
            } catch (error) {
                return '';
            }
        }
        function isUsefulUrl(rawUrl) {
            try {
                const url = new URL(rawUrl);
                if (url.protocol !== 'http:' && url.protocol !== 'https:') return false;
                const host = url.hostname.replace(/^www\./, '');
                const currentHost = location.hostname.replace(/^www\./, '');
                if (host === currentHost) return false;
                if (host.endsWith('duckduckgo.com') || host.endsWith('google.com') || host.endsWith('bing.com')) return false;
                return true;
            } catch (error) {
                return false;
            }
        }
        const results = [];
        const seen = new Set();
        for (const anchor of Array.from(document.querySelectorAll('a[href]'))) {
            if (results.length >= limit) break;
            const title = cleanText(anchor.innerText || anchor.textContent || anchor.getAttribute('aria-label'));
            const url = normalizeUrl(anchor.getAttribute('href') || anchor.href);
            if (title.length < 2 || !isUsefulUrl(url) || seen.has(url)) continue;
            seen.add(url);
            const block = anchor.closest('.result, article, li, div') || anchor.parentElement;
            let snippet = cleanText(block ? (block.innerText || block.textContent || '') : '');
            if (snippet.indexOf(title) === 0) {
                snippet = cleanText(snippet.slice(title.length));
            }
            results.push({
                title: title.slice(0, 240),
                url: url,
                snippet: snippet.slice(0, 500)
            });
        }
        return {
            url: String(location.href || ''),
            title: String(document.title || ''),
            resultCount: results.length,
            results: results,
            textSnippet: cleanText(document.body ? (document.body.innerText || document.body.textContent || '') : '').slice(0, 4000)
        };
    """.trimIndent()

    private companion object {
        const val DEFAULT_LIMIT = 5
    }
}
