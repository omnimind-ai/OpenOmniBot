package cn.com.omnimind.bot.localmodel

import android.app.Activity
import android.content.Context
import android.content.Intent
import cn.com.omnimind.baselib.llm.LocalModelProviderBridge
import cn.com.omnimind.baselib.llm.MnnLocalProviderStateStore
import io.flutter.embedding.engine.FlutterEngine

interface LocalModelFeatureDelegate {
    val enabled: Boolean

    fun initialize(context: Context) = Unit

    fun onChannelManagerCreate(context: Context) = Unit

    fun setChannel(flutterEngine: FlutterEngine) = Unit

    fun clearChannel() = Unit

    fun handleAppOpen(activity: Activity) = Unit

    fun onActivityResult(
        activity: Activity,
        requestCode: Int,
        resultCode: Int,
        data: Intent?,
    ): Boolean = false

    fun listBuiltinProviderModels(): List<Map<String, Any?>> = emptyList()

    suspend fun prepareForRequest(
        profileId: String?,
        apiBase: String?,
        modelId: String,
    ): Boolean = false
}

object LocalModelFeature {
    private object NoOpDelegate : LocalModelFeatureDelegate {
        override val enabled: Boolean = false
    }

    @Volatile
    private var delegate: LocalModelFeatureDelegate = NoOpDelegate

    fun install(context: Context, featureDelegate: LocalModelFeatureDelegate) {
        delegate = featureDelegate
        MnnLocalProviderStateStore.setEnabled(featureDelegate.enabled)
        LocalModelProviderBridge.setDelegate(
            if (featureDelegate.enabled) {
                object : LocalModelProviderBridge.Delegate {
                    override suspend fun prepareForRequest(
                        profileId: String?,
                        apiBase: String?,
                        modelId: String,
                    ): Boolean {
                        return delegate.prepareForRequest(profileId, apiBase, modelId)
                    }
                }
            } else {
                null
            }
        )
        featureDelegate.initialize(context.applicationContext)
    }

    fun installNoOp(context: Context) {
        install(context, NoOpDelegate)
    }

    fun onChannelManagerCreate(context: Context) {
        delegate.onChannelManagerCreate(context)
    }

    fun setChannel(flutterEngine: FlutterEngine) {
        delegate.setChannel(flutterEngine)
    }

    fun clearChannel() {
        delegate.clearChannel()
    }

    fun handleAppOpen(activity: Activity) {
        delegate.handleAppOpen(activity)
    }

    fun onActivityResult(
        activity: Activity,
        requestCode: Int,
        resultCode: Int,
        data: Intent?,
    ): Boolean {
        return delegate.onActivityResult(activity, requestCode, resultCode, data)
    }

    fun listBuiltinProviderModels(): List<Map<String, Any?>> {
        return if (MnnLocalProviderStateStore.isEnabled()) {
            delegate.listBuiltinProviderModels()
        } else {
            emptyList()
        }
    }
}
