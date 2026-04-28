package cn.com.omnimind.bot.activity

import android.app.Activity
import android.content.Context
import android.os.Bundle
import cn.com.omnimind.baselib.i18n.AppLocaleManager

class QuickLogEntryActivity : Activity() {
    companion object {
        const val EXTRA_LOG_ID = "extra_quick_log_id"
        const val EXTRA_LOG_CONTENT = "extra_quick_log_content"
    }

    override fun attachBaseContext(newBase: Context?) {
        super.attachBaseContext(
            if (newBase == null) null else AppLocaleManager.localizedContext(newBase)
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        QuickLogEditorScreen.bind(this, intent)
    }
}
