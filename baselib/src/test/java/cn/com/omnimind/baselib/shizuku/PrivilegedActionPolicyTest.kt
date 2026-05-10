package cn.com.omnimind.baselib.shizuku

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PrivilegedActionPolicyTest {

    @Test
    fun adbBackendDoesNotExposeRootOnlyAction() {
        val actions = PrivilegedActionPolicy.visibleAgentActions(ShizukuBackend.ADB)

        assertFalse(actions.contains(PrivilegedActionPolicy.ACTION_DEVICE_SET_MOBILE_DATA_ENABLED))
        assertTrue(actions.contains(PrivilegedActionPolicy.ACTION_DEVICE_SET_WIFI_ENABLED))
    }

    @Test
    fun rootBackendExposesRootOnlyAction() {
        val actions = PrivilegedActionPolicy.visibleAgentActions(ShizukuBackend.ROOT)

        assertTrue(actions.contains(PrivilegedActionPolicy.ACTION_DEVICE_SET_MOBILE_DATA_ENABLED))
        assertTrue(actions.contains(PrivilegedActionPolicy.ACTION_SHELL_EXEC))
    }

    @Test
    fun kernelLogcatRequiresRoot() {
        assertFalse(
            PrivilegedActionPolicy.isSupported(
                action = PrivilegedActionPolicy.ACTION_DIAGNOSTICS_LOGCAT_TAIL,
                backend = ShizukuBackend.ADB,
                arguments = mapOf("buffer" to "kernel")
            )
        )

        assertTrue(
            PrivilegedActionPolicy.isSupported(
                action = PrivilegedActionPolicy.ACTION_DIAGNOSTICS_LOGCAT_TAIL,
                backend = ShizukuBackend.ROOT,
                arguments = mapOf("buffer" to "kernel")
            )
        )
    }

    @Test
    fun highRiskActionsRequireConfirmation() {
        assertTrue(
            PrivilegedActionPolicy.requiresConfirmation(
                PrivilegedActionPolicy.ACTION_PACKAGE_FORCE_STOP
            )
        )
        assertTrue(
            PrivilegedActionPolicy.requiresConfirmation(
                PrivilegedActionPolicy.ACTION_SHELL_EXEC
            )
        )
        assertTrue(
            PrivilegedActionPolicy.requiresConfirmation(
                PrivilegedActionPolicy.ACTION_SETTINGS_PUT
            )
        )
        assertFalse(
            PrivilegedActionPolicy.requiresConfirmation(
                PrivilegedActionPolicy.ACTION_DIAGNOSTICS_GETPROP
            )
        )
    }

    @Test
    fun dangerousCommandsAreBlockedBeforeExecution() {
        assertTrue(PrivilegedActionPolicy.blockedCommandReason("reboot now") != null)
        assertTrue(
            PrivilegedActionPolicy.blockedCommandReason(
                "dd if=/sdcard/boot.img of=/dev/block/by-name/boot"
            ) != null
        )
        assertTrue(
            PrivilegedActionPolicy.blockedCommandReason(
                "mount -o rw,remount /system"
            ) != null
        )
    }
}
