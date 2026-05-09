package cn.com.omnimind.bot.omniinfer

import android.content.Context
import android.os.Build
import cn.com.omnimind.baselib.util.OmniLog
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * QNN model catalog for ExecuTorch QNN backend.
 * Models are pre-exported .pte files hosted on ModelScope and filtered by device SoC.
 */
object OmniInferQnnMarketRepository {
    private const val TAG = "OmniInferQnnMarketRepository"
    private const val MARKET_ASSET_NAME = "omniinfer_qnn_model_market.json"

    private var appContext: Context? = null
    private var cachedEntries: List<QnnModelEntry>? = null

    data class QnnModelEntry(
        val modelId: String,
        val modelName: String,
        val soc: String,
        val socLabel: String,
        val pteUrl: String,
        val tokenizerUrl: String,
        val fileSize: Long,
        val sizeGb: Double,
        val decoderModelVersion: String = "qwen3",
    )

    data class ResolvedQnnModel(
        val modelId: String,
        val entry: QnnModelEntry,
    )

    fun setContext(context: Context) {
        appContext = context.applicationContext
    }

    fun getDeviceSoc(): String {
        val soc = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Build.SOC_MODEL.trim()
        } else {
            ""
        }
        OmniLog.i(
            TAG,
            "[getDeviceSoc] Build.SOC_MODEL='${Build.SOC_MODEL}', " +
                "Build.HARDWARE='${Build.HARDWARE}', Build.BOARD='${Build.BOARD}', " +
                "Build.DEVICE='${Build.DEVICE}', normalized='$soc'",
        )
        return soc
    }

    fun listModels(filterBySoc: Boolean = true): List<ResolvedQnnModel> {
        val soc = getDeviceSoc()
        OmniLog.d(TAG, "[listModels] device SoC=$soc, filterBySoc=$filterBySoc")
        val entries = if (filterBySoc && soc.isNotEmpty()) {
            allEntries().filter { it.soc.equals(soc, ignoreCase = true) }
        } else {
            allEntries()
        }
        return entries.map { ResolvedQnnModel(modelId = it.modelId, entry = it) }
    }

    fun findModel(modelId: String): ResolvedQnnModel? {
        val normalizedId = modelId.trim()
        if (normalizedId.isEmpty()) return null
        val entry = allEntries().firstOrNull { it.modelId == normalizedId }
            ?: return null
        return ResolvedQnnModel(modelId = entry.modelId, entry = entry)
    }

    fun allModels(): List<ResolvedQnnModel> = listModels(filterBySoc = false)

    fun isDeviceSupported(): Boolean {
        val soc = getDeviceSoc()
        return allEntries().any { it.soc.equals(soc, ignoreCase = true) }
    }

    private fun allEntries(): List<QnnModelEntry> {
        cachedEntries?.let { return it }
        val context = appContext ?: error("OmniInferQnnMarketRepository not initialized, call setContext() first")
        val raw = context.assets.open(MARKET_ASSET_NAME).bufferedReader().use { it.readText() }
        val root = Json.parseToJsonElement(raw).jsonObject
        val baseUrl = root.stringValue("baseUrl")?.trimEnd('/')
        val tokenizerUrl = root.stringValue("tokenizerUrl").orEmpty()
        val entries = root["models"]?.jsonArray
            ?.mapNotNull { parseEntry(it.jsonObject, baseUrl, tokenizerUrl) }
            .orEmpty()
        cachedEntries = entries
        return entries
    }

    private fun parseEntry(
        obj: JsonObject,
        baseUrl: String?,
        defaultTokenizerUrl: String,
    ): QnnModelEntry? {
        val modelId = obj.stringValue("modelId") ?: return null
        val modelName = obj.stringValue("modelName") ?: modelId
        val soc = obj.stringValue("soc") ?: return null
        val socLabel = obj.stringValue("socLabel") ?: soc
        val pteUrl = obj.stringValue("pteUrl")
            ?: obj.stringValue("ptePath")?.let { path -> buildUrl(baseUrl, path) }
            ?: return null
        val tokenizerUrl = obj.stringValue("tokenizerUrl") ?: defaultTokenizerUrl
        val fileSize = obj.longValue("fileSize")
        val sizeGb = obj.doubleValue("sizeGb")
        return QnnModelEntry(
            modelId = modelId,
            modelName = modelName,
            soc = soc,
            socLabel = socLabel,
            pteUrl = pteUrl,
            tokenizerUrl = tokenizerUrl,
            fileSize = fileSize,
            sizeGb = sizeGb,
            decoderModelVersion = obj.stringValue("decoderModelVersion") ?: "qwen3",
        )
    }

    private fun buildUrl(baseUrl: String?, path: String): String {
        val trimmedPath = path.trimStart('/')
        return if (baseUrl.isNullOrBlank()) trimmedPath else "$baseUrl/$trimmedPath"
    }

    private fun JsonObject.stringValue(key: String): String? =
        this[key]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }

    private fun JsonObject.longValue(key: String): Long =
        this[key]?.jsonPrimitive?.longOrNull ?: 0L

    private fun JsonObject.doubleValue(key: String): Double =
        this[key]?.jsonPrimitive?.doubleOrNull ?: 0.0
}
