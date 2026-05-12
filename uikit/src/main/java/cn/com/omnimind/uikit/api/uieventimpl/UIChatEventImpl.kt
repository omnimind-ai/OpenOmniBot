package cn.com.omnimind.uikit.api.uieventimpl

import android.content.Context
import cn.com.omnimind.uikit.api.uievent.UIChatEvent
import cn.com.omnimind.uikit.loader.FloatingHalfScreenLoader
import cn.com.omnimind.uikit.loader.ScreenMaskLoader
import cn.com.omnimind.uikit.loader.cat.DraggableBallInstance
import cn.com.omnimind.uikit.settings.CompanionOverlaySettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class UIChatEventImpl : UIChatEvent {
    private var context: Context? = null

    override fun onUIInit(context: Context) {
        this.context = context
    }

    private fun isFloatingUiEnabled(): Boolean {
        val enabled = CompanionOverlaySettings.isEnabled(context)
        if (!enabled) {
            CompanionOverlaySettings.dismissFloatingUi()
        }
        return enabled
    }

    override suspend fun dismissHalfScreen() = withContext(Dispatchers.Main) {
        FloatingHalfScreenLoader.destroyInstance()
    }

    override suspend fun closeChatBotBg() = withContext(Dispatchers.Main) {
        ScreenMaskLoader.loadGoneViewScreenMask()
        DraggableBallInstance.collapse()
        FloatingHalfScreenLoader.destroyInstance()
    }

    override fun closeChatBotBgInMain() {
        ScreenMaskLoader.loadGoneViewScreenMask()
        DraggableBallInstance.collapse()
        FloatingHalfScreenLoader.destroyInstance()
    }

    override suspend fun showChatBotHalfScreen(scene: String?) = withContext(Dispatchers.Main) {
        if (!isFloatingUiEnabled()) return@withContext
        val path = if (scene.isNullOrEmpty()) {
            "/home/command_overlay"
        } else {
            "/home/command_overlay?scene=$scene"
        }
        FloatingHalfScreenLoader.loadFloatingHalfScreen(path)
    }

    override fun dismissHalfScreenInMain() {
        FloatingHalfScreenLoader.destroyInstance()
    }

    override fun isChatBotHalfScreenShowing(): Boolean {
        return FloatingHalfScreenLoader.isShowing()
    }
}
