package cn.com.omnimind.bot.localmodel

import android.content.Context

object LocalModelFeatureInstaller {
    fun install(context: Context) {
        LocalModelFeature.installNoOp(context)
    }
}
