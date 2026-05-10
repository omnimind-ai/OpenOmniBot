package cn.com.omnimind.bot.localmodel

import android.app.Activity
import android.content.Context
import android.content.Intent
import cn.com.omnimind.bot.omniinfer.OmniInferLocalRuntime
import cn.com.omnimind.bot.omniinfer.OmniInferLiteRtModelsManager
import cn.com.omnimind.bot.omniinfer.OmniInferMnnModelsManager
import cn.com.omnimind.bot.omniinfer.OmniInferModelsManager
import cn.com.omnimind.bot.omniinfer.OmniInferQnnModelsManager
import cn.com.omnimind.bot.ui.channel.MnnLocalModelsChannel
import com.omniinfer.server.OmniInferServer
import io.flutter.embedding.engine.FlutterEngine

object LocalModelFeatureInstaller {
    fun install(context: Context) {
        LocalModelFeature.install(context, OmniInferLocalModelFeature)
    }
}

private object OmniInferLocalModelFeature : LocalModelFeatureDelegate {
    override val enabled: Boolean = true

    private val channel = MnnLocalModelsChannel()

    override fun initialize(context: Context) {
        val applicationContext = context.applicationContext
        OmniInferServer.init(applicationContext)
        OmniInferLocalRuntime.setContext(applicationContext)
        OmniInferModelsManager.setContext(applicationContext)
        OmniInferMnnModelsManager.setContext(applicationContext)
        OmniInferQnnModelsManager.setContext(applicationContext)
        OmniInferLiteRtModelsManager.setContext(applicationContext)
    }

    override fun onChannelManagerCreate(context: Context) {
        channel.onCreate(context)
    }

    override fun setChannel(flutterEngine: FlutterEngine) {
        channel.setChannel(flutterEngine)
    }

    override fun clearChannel() {
        channel.clear()
    }

    override fun handleAppOpen(activity: Activity) {
        OmniInferLocalRuntime.handleAppOpen(activity)
    }

    override fun onActivityResult(
        activity: Activity,
        requestCode: Int,
        resultCode: Int,
        data: Intent?,
    ): Boolean {
        return MnnLocalModelsChannel.onActivityResult(activity, requestCode, resultCode, data)
    }

    override fun listBuiltinProviderModels(): List<Map<String, Any?>> {
        return OmniInferLocalRuntime.listBuiltinProviderModels()
    }

    override suspend fun prepareForRequest(
        profileId: String?,
        apiBase: String?,
        modelId: String,
    ): Boolean {
        val ggufReady = runCatching {
            OmniInferModelsManager.ensureModelReady(modelId)
        }.getOrDefault(false)
        if (ggufReady) {
            return true
        }
        val mnnReady = runCatching {
            OmniInferMnnModelsManager.ensureModelReady(modelId)
        }.getOrDefault(false)
        if (mnnReady) {
            return true
        }
        val liteRtReady = runCatching {
            OmniInferLiteRtModelsManager.ensureModelReady(modelId)
        }.getOrDefault(false)
        if (liteRtReady) {
            return true
        }
        return runCatching {
            OmniInferQnnModelsManager.ensureModelReady(modelId)
        }.getOrDefault(false)
    }
}
