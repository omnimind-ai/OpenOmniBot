package cn.com.omnimind.assists.task.vlmserver

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class PromptTemplateTest {
    @Test
    fun `turn prompt only renders focused installed apps`() {
        val previousLocale = Locale.getDefault()
        Locale.setDefault(Locale.US)
        try {
            val prompt = PromptTemplate.buildTurnUserPrompt(
                UIContext(
                    overallTask = "Open Android Settings and then open Display",
                    targetPackageName = "com.android.settings",
                    currentPackageName = "com.android.launcher3",
                    installedApplications = linkedMapOf(
                        "com.android.settings" to "Settings",
                        "com.android.launcher3" to "Launcher",
                        "com.google.android.contacts" to "Contacts",
                        "com.google.android.apps.messaging" to "Messages",
                        "com.example.unrelated" to "Unrelated"
                    )
                )
            )

            assertTrue(prompt.contains("Relevant installed apps"))
            assertTrue(prompt.contains("com.android.settings -> Settings"))
            assertTrue(prompt.contains("com.android.launcher3 -> Launcher"))
            assertFalse(prompt.contains("com.google.android.contacts -> Contacts"))
            assertFalse(prompt.contains("com.google.android.apps.messaging -> Messages"))
            assertFalse(prompt.contains("com.example.unrelated -> Unrelated"))
            assertTrue(prompt.contains("only focused candidates are shown"))
        } finally {
            Locale.setDefault(previousLocale)
        }
    }

    @Test
    fun `turn prompt uses compact output reminder instead of repeating full protocol`() {
        val previousLocale = Locale.getDefault()
        Locale.setDefault(Locale.US)
        try {
            val prompt = PromptTemplate.buildTurnUserPrompt(
                UIContext(
                    overallTask = "Open Settings",
                    targetPackageName = "com.android.settings",
                    installedApplications = linkedMapOf("com.android.settings" to "Settings")
                )
            )

            assertTrue(prompt.contains("Turn reminder"))
            assertTrue(prompt.contains("exactly one native tool_call"))
            assertTrue(prompt.contains("black/blank"))
            assertTrue(prompt.contains("do not repeatedly call get_state"))
            assertFalse(prompt.contains("1. Pick the next action directly from the tools list"))
            assertFalse(prompt.contains("8. Do not output any idle"))
        } finally {
            Locale.setDefault(previousLocale)
        }
    }

    @Test
    fun `focused app ranking is capped and keeps exact target first`() {
        val apps = linkedMapOf<String, String>()
        apps["com.android.settings"] = "Settings"
        repeat(20) { index ->
            apps["com.example.settings$index"] = "Settings Tool $index"
        }

        val focused = PromptTemplate.focusedInstalledAppEntries(
            UIContext(
                overallTask = "Open settings",
                targetPackageName = "com.android.settings",
                installedApplications = apps
            )
        )

        assertTrue(focused.size <= 12)
        assertTrue(focused.first().key == "com.android.settings")
    }
}
