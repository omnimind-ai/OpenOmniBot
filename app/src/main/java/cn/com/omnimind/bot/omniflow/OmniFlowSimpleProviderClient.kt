package cn.com.omnimind.bot.omniflow

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

interface OmniFlowSimpleProvider {
    suspend fun execute(function: OmniFlowSimpleFunction, input: Map<String, Any?>): ProviderExecutionHit?
}

data class ProviderExecutionHit(
    val functionId: String?,
    val response: Map<String, Any?>
)

class OmniFlowSimpleProviderClient(
    private val baseUrl: String = OMNIFLOW_SIMPLE_PROVIDER_URL,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(700, TimeUnit.MILLISECONDS)
        .readTimeout(4, TimeUnit.SECONDS)
        .writeTimeout(2, TimeUnit.SECONDS)
        .build(),
    private val json: Json = OmniFlowSimpleStore.defaultJson
) : OmniFlowSimpleProvider {
    private val mediaType = "application/json; charset=utf-8".toMediaType()

    suspend fun isAvailable(): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val request = Request.Builder()
                .url(baseUrl.trimEnd('/') + "/health")
                .get()
                .build()
            client.newCall(request).execute().use { response ->
                response.isSuccessful
            }
        }.getOrDefault(false)
    }

    override suspend fun execute(
        function: OmniFlowSimpleFunction,
        input: Map<String, Any?>
    ): ProviderExecutionHit? = withContext(Dispatchers.IO) {
        val preHook = postJsonOrNull(
            "/vlm/pre_hook",
            mapOf(
                "goal" to function.description,
                "current_package_name" to function.packageName,
                "fallback_allowed" to true
            )
        )
        val preHookObject = preHook?.let { parseObjectOrNull(it) }
        val hit = preHookObject?.providerHit() ?: false
        val matchedFunctionId = preHookObject?.providerFunctionId() ?: function.functionId
        if (!hit) return@withContext null

        val executeBody = postJsonOrNull(
            "/functions/execute",
            mapOf(
                "goal" to function.description,
                "function_id" to matchedFunctionId,
                "arguments" to input.mapValues { it.value?.toString().orEmpty() },
                "context" to mapOf(
                    "source" to "oob_simple_utg",
                    "local_function_id" to function.functionId
                )
            )
        ) ?: return@withContext null
        val executeObject = parseObjectOrNull(executeBody)
        ProviderExecutionHit(
            functionId = matchedFunctionId,
            response = executeObject?.toInteropValue() as? Map<String, Any?>
                ?: mapOf("raw" to executeBody)
        )
    }

    suspend fun ingestRunLog(runLog: OmniFlowSimpleRunLog): Boolean = withContext(Dispatchers.IO) {
        postJsonOrNull(
            "/run_logs/ingest",
            mapOf(
                "run_log" to runLog.toMap(),
                "auto_import" to true,
                "client_session_id" to OMNIFLOW_SIMPLE_PROJECT_ID
            )
        ) != null
    }

    private fun postJsonOrNull(path: String, payload: Map<String, Any?>): String? {
        return runCatching {
            val body = JsonObject(payload.mapValues { it.value.toJsonElement() }).toString()
            val request = Request.Builder()
                .url(baseUrl.trimEnd('/') + path)
                .post(body.toRequestBody(mediaType))
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                response.body?.string()
            }
        }.getOrNull()
    }

    private fun parseObjectOrNull(body: String): JsonObject? {
        return runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull()
    }

    private fun JsonObject.providerHit(): Boolean {
        val decision = this["decision"]?.jsonPrimitive?.contentOrNull
            ?: this["kind"]?.jsonPrimitive?.contentOrNull
        if (decision != null) {
            val normalized = decision.trim().lowercase()
            if (normalized == "hit" || normalized == "cache_hit") return true
            if (normalized == "miss" || normalized == "blocked") return false
        }
        this["execution_route"]?.jsonPrimitive?.contentOrNull
            ?.trim()
            ?.lowercase()
            ?.let { route ->
                if (route == "utg" || route == "function" || route == "cache") return true
                if (route == "vlm") return false
            }
        return this["use_cache"]?.jsonPrimitive?.booleanOrNull
            ?: this["hit"]?.jsonPrimitive?.booleanOrNull
            ?: this["matched"]?.jsonPrimitive?.booleanOrNull
            ?: false
    }

    private fun JsonObject.providerFunctionId(): String? {
        return this["function_id"]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
            ?: this["matched_function_id"]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
            ?: this["function"]?.jsonObject?.get("function_id")?.jsonPrimitive?.contentOrNull
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
    }
}
