package cn.com.omnimind.uikit

import android.content.Context
import cn.com.omnimind.assists.AssistsCore
import cn.com.omnimind.assists.api.eventapi.AssistsEventApi
import cn.com.omnimind.assists.api.eventapi.CommentEventApi
import cn.com.omnimind.assists.api.eventapi.CompanionTaskEventApi
import cn.com.omnimind.assists.api.eventapi.ExecutionTaskEventApi
import cn.com.omnimind.assists.api.eventapi.ScreenshotImageEventApi
import cn.com.omnimind.uikit.api.callback.CatApi
import cn.com.omnimind.uikit.api.callback.CatLayoutApi
import cn.com.omnimind.uikit.api.callback.CatStepLayoutApi
import cn.com.omnimind.uikit.api.callback.HalfScreenApi
import cn.com.omnimind.uikit.api.callback.MenuApi
import cn.com.omnimind.uikit.api.callbackimpl.CatApiImpl
import cn.com.omnimind.uikit.api.callbackimpl.CatLayoutApiImpl
import cn.com.omnimind.uikit.api.callbackimpl.CatStepLayoutApiImpl
import cn.com.omnimind.uikit.api.callbackimpl.MenuApiImpl
import cn.com.omnimind.uikit.api.eventimpl.CommentUIImpl
import cn.com.omnimind.uikit.api.eventimpl.CompanionUIImpl
import cn.com.omnimind.uikit.api.eventimpl.ExecutionUIImpl
import cn.com.omnimind.uikit.api.eventimpl.ScreenshotImageUIImpl
import cn.com.omnimind.uikit.api.uievent.UIBaseEvent
import cn.com.omnimind.uikit.api.uievent.UIChatEvent
import cn.com.omnimind.uikit.api.uievent.UITaskEvent
import cn.com.omnimind.uikit.api.uieventimpl.UIBaseEventImpl
import cn.com.omnimind.uikit.api.uieventimpl.UIChatEventImpl
import cn.com.omnimind.uikit.api.uieventimpl.UITaskEventImpl
import cn.com.omnimind.uikit.settings.CompanionOverlaySettings

class UIKit {
    companion object {
        const val TAG = "[UIKit]"
        var halfScreenApi: HalfScreenApi? = null
        var catApi: CatApi? = null
        var catLayoutApi: CatLayoutApi? = null
        var menuApi: MenuApi? = null
        var catStepLayoutApi: CatStepLayoutApi? = null
        var uiTaskEvent: UITaskEvent? = null
        var uiChatEvent: UIChatEvent? = null
        var uiBaseEvent: UIBaseEvent? = null
        var commentEventApi: CommentEventApi? = null
        var companionTaskEventApi: CompanionTaskEventApi? = null
        var executionTaskEventApi: ExecutionTaskEventApi? = null
        var appContext: Context? = null

        fun init(context: Context, halfScreenApi: HalfScreenApi) {
            CompanionOverlaySettings.init(context)
            appContext = context.applicationContext
            UIKit.halfScreenApi = halfScreenApi
            catApi = CatApiImpl()
            catLayoutApi = CatLayoutApiImpl()
            catStepLayoutApi = CatStepLayoutApiImpl()
            menuApi = MenuApiImpl()
            uiTaskEvent = UITaskEventImpl()
            uiChatEvent = UIChatEventImpl()
            uiBaseEvent = UIBaseEventImpl()
            uiTaskEvent!!.onUIInit(context)
            uiBaseEvent!!.onUIInit(context)
            uiChatEvent!!.onUIInit(context)
            commentEventApi = CommentUIImpl(uiChatEvent!!, uiBaseEvent!!, uiTaskEvent!!)
            companionTaskEventApi = CompanionUIImpl(uiChatEvent!!, uiBaseEvent!!, uiTaskEvent!!)
            executionTaskEventApi = ExecutionUIImpl(uiChatEvent!!, uiBaseEvent!!, uiTaskEvent!!)
            val screenshotImageUIImpl = ScreenshotImageUIImpl(uiBaseEvent!!)
            val assetsEventApi = object : AssistsEventApi {
                override fun getCompanionEventImpl(): CompanionTaskEventApi? {
                    return companionTaskEventApi
                }

                override fun getExecutionEventImpl(): ExecutionTaskEventApi? {
                    return executionTaskEventApi
                }

                override fun getCommentEventImpl(): CommentEventApi? {
                    return commentEventApi
                }

                override fun getScreenshotImageEventImpl(): ScreenshotImageEventApi? {
                    return screenshotImageUIImpl
                }
            }
            AssistsCore.initCoreWithEvent(context, assetsEventApi, screenshotImageUIImpl)
        }
    }
}
