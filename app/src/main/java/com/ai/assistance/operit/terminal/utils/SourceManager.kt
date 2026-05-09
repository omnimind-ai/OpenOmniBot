package com.ai.assistance.operit.terminal.utils

import android.content.Context
import com.rk.terminal.runtime.AlpineRepositoryManager

class SourceManager(
    @Suppress("unused") private val context: Context
) {
    fun buildRepositorySetupCommand(): String {
        return AlpineRepositoryManager.buildSelectedRepositorySetupCommand()
    }
}
