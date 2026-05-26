package cn.com.omnimind.bot.runlog

import org.junit.Assert.assertEquals
import org.junit.Test

class RunLogPagePackageInferenceTest {
    @Test
    fun `infers package from flattened activity component when package and xml are blank`() {
        assertEquals(
            "com.android.settings",
            RunLogPagePackageInference.effectivePackage(
                recordedPackage = "",
                xml = "",
                activityName = "com.android.settings/.Settings",
            )
        )
    }

    @Test
    fun `infers package from component info activity name`() {
        assertEquals(
            "com.google.android.deskclock",
            RunLogPagePackageInference.effectivePackage(
                recordedPackage = "android",
                xml = "",
                activityName = "ComponentInfo{com.google.android.deskclock/com.android.deskclock.DeskClock}",
            )
        )
    }

    @Test
    fun `xml package remains authoritative over activity fallback`() {
        val xml = """
            <hierarchy>
              <node package="com.example.target" resource-id="com.example.target:id/title" />
            </hierarchy>
        """.trimIndent()

        assertEquals(
            "com.example.target",
            RunLogPagePackageInference.effectivePackage(
                recordedPackage = "",
                xml = xml,
                activityName = "com.example.other/.MainActivity",
            )
        )
    }

    @Test
    fun `extracts class package prefix from fully qualified activity class`() {
        assertEquals(
            "com.example.app.ui",
            RunLogPagePackageInference.packageFromActivity("com.example.app.ui.MainActivity")
        )
    }
}
