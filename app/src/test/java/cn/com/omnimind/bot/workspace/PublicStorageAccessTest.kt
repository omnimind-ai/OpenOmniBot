package cn.com.omnimind.bot.workspace

import android.provider.Settings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PublicStorageAccessTest {
    @Test
    fun detectsPublicStoragePathsAndUris() {
        assertTrue(PublicStorageAccess.isPublicStoragePath("/storage"))
        assertTrue(PublicStorageAccess.isPublicStoragePath("/storage/DCIM/Camera"))
        assertTrue(PublicStorageAccess.isPublicStoragePath("/sdcard"))
        assertTrue(PublicStorageAccess.isPublicStorageInput("/sdcard/Download/demo.txt"))
        assertTrue(PublicStorageAccess.isPublicStorageInput(" omnibot://public/DCIM/Camera "))
        assertTrue(PublicStorageAccess.isPublicStorageUri("omnibot://public/Music/demo.mp3"))
        assertTrue(
            PublicStorageAccess.isPublicStorageUri(
                "omnibot:///storage/emulated/0/Pictures/demo.jpg"
            )
        )
        assertTrue(
            PublicStorageAccess.isPublicStorageUri(
                "omnibot://storage/emulated/0/Pictures/demo.jpg"
            )
        )
        assertTrue(
            PublicStorageAccess.isPublicStorageUri(
                "omnibot:///sdcard/Download/demo.txt"
            )
        )
        assertFalse(PublicStorageAccess.isPublicStoragePath("/workspace/demo"))
        assertFalse(PublicStorageAccess.isPublicStorageUri("omnibot://workspace/demo.txt"))
    }

    @Test
    fun buildsAppSpecificManageAllFilesIntentWhenAvailable() {
        assertEquals(
            Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
            PublicStorageAccess.resolveAppSpecificSettingsAction()
        )
        assertEquals(
            "package:cn.com.omnimind.bot",
            PublicStorageAccess.packageSettingsUri("cn.com.omnimind.bot")
        )
    }

    @Test
    fun buildsFallbackIntentVariants() {
        assertEquals(
            Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION,
            PublicStorageAccess.resolveFallbackSettingsAction(useManageAllFilesAction = true)
        )
        assertEquals(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            PublicStorageAccess.resolveFallbackSettingsAction(useManageAllFilesAction = false)
        )
    }
}
