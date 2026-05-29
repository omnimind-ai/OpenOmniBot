package cn.com.omnimind.assists

import cn.com.omnimind.assists.task.vlmserver.ManualRawTouchParser
import cn.com.omnimind.assists.task.vlmserver.ManualRawTouchEventLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ManualRawTouchParserTest {
    @Test
    fun `parse raw getevent stream line for diagnostics`() {
        val parsed = ManualRawTouchParser.parseEventLine(
            "[ 1000.001000] /dev/input/event4: EV_ABS ABS_MT_POSITION_X 00004000"
        )

        assertNotNull(parsed)
        assertEquals(1_000_001L, parsed?.eventTimeMs)
        assertEquals("/dev/input/event4", parsed?.devicePath)
        assertEquals("EV_ABS", parsed?.eventType)
        assertEquals("ABS_MT_POSITION_X", parsed?.code)
        assertEquals(0x4000, parsed?.value)
    }

    @Test
    fun `raw getevent diagnostic line preserves original stream text`() {
        val line = "[ 1000.001000] /dev/input/event4: EV_ABS ABS_MT_POSITION_X 00004000"
        val diagnostic = ManualRawTouchEventLine(
            seq = 7,
            capturedAtMs = 1234,
            line = line,
            eventTimeMs = 1_000_001L,
            devicePath = "/dev/input/event4",
            eventType = "EV_ABS",
            code = "ABS_MT_POSITION_X",
            value = 0x4000
        ).asMap()

        assertEquals(7L, diagnostic["seq"])
        assertEquals(line, diagnostic["line"])
        assertEquals("ABS_MT_POSITION_X", diagnostic["code"])
        assertEquals(0x4000, diagnostic["value"])
    }

    @Test
    fun `parse touch device from getevent capabilities`() {
        val devices = ManualRawTouchParser.parseTouchDevices(
            """
                add device 1: /dev/input/event0
                  name:     "gpio-keys"
                  events:
                add device 2: /dev/input/event4
                  name:     "touchscreen"
                  events:
                    ABS (0003): 0035  : value 0, min 0, max 32767, fuzz 0, flat 0, resolution 0
                                0036  : value 0, min 0, max 65535, fuzz 0, flat 0, resolution 0
            """.trimIndent()
        )

        assertEquals(1, devices.size)
        assertEquals("/dev/input/event4", devices.first().path)
        assertEquals("touchscreen", devices.first().name)
        assertEquals(32767, devices.first().xAxis.max)
        assertEquals(65535, devices.first().yAxis.max)
    }

    @Test
    fun `parse touch devices prefers direct touchscreen over fingerprint xy devices`() {
        val devices = ManualRawTouchParser.parseTouchDevices(
            """
                add device 1: /dev/input/event7
                  name:     "vivo_fp"
                  events:
                    ABS (0003): ABS_MT_POSITION_X     : value 0, min 0, max 1000, fuzz 0, flat 0, resolution 0
                                ABS_MT_POSITION_Y     : value 0, min 0, max 1000, fuzz 0, flat 0, resolution 0
                  input props:
                    <none>
                add device 2: /dev/input/event6
                  name:     "vivo_ts"
                  events:
                    KEY (0001): BTN_TOUCH
                    ABS (0003): ABS_MT_SLOT           : value 0, min 0, max 9, fuzz 0, flat 0, resolution 0
                                ABS_MT_POSITION_X     : value 0, min 0, max 12599, fuzz 0, flat 0, resolution 0
                                ABS_MT_POSITION_Y     : value 0, min 0, max 27999, fuzz 0, flat 0, resolution 0
                                ABS_MT_TRACKING_ID    : value 0, min 0, max 65535, fuzz 0, flat 0, resolution 0
                  input props:
                    INPUT_PROP_DIRECT
                add device 3: /dev/input/event5
                  name:     "vivo_ts_fp"
                  events:
                    ABS (0003): ABS_MT_POSITION_X     : value 0, min 0, max 12599, fuzz 0, flat 0, resolution 0
                                ABS_MT_POSITION_Y     : value 0, min 0, max 27999, fuzz 0, flat 0, resolution 0
                  input props:
                    <none>
            """.trimIndent()
        )

        assertEquals("/dev/input/event6", devices.first().path)
        assertEquals("vivo_ts", devices.first().name)
        assertTrue(devices.first().directInput)
        assertTrue(devices.first().hasBtnTouch)
        assertTrue(devices.first().hasTrackingId)
    }

    @Test
    fun `stream parser converts raw tap into screen click`() {
        val device = ManualRawTouchParser.TouchDevice(
            path = "/dev/input/event4",
            name = "touchscreen",
            xAxis = ManualRawTouchParser.Axis(min = 0, max = 32767),
            yAxis = ManualRawTouchParser.Axis(min = 0, max = 65535),
        )
        val starts = mutableListOf<Any>()
        val parser = ManualRawTouchParser.StreamParser(
            device = device,
            displayWidth = 1080,
            displayHeight = 2400,
            backend = "device_getevent",
            onGestureStarted = { starts += it }
        )

        val gesture = listOf(
            "[ 1000.000000] /dev/input/event4: EV_ABS ABS_MT_TRACKING_ID 00000001",
            "[ 1000.001000] /dev/input/event4: EV_ABS ABS_MT_POSITION_X 00004000",
            "[ 1000.001000] /dev/input/event4: EV_ABS ABS_MT_POSITION_Y 00008000",
            "[ 1000.002000] /dev/input/event4: EV_SYN SYN_REPORT 00000000",
            "[ 1000.080000] /dev/input/event4: EV_ABS ABS_MT_TRACKING_ID ffffffff",
            "[ 1000.081000] /dev/input/event4: EV_SYN SYN_REPORT 00000000",
        ).mapNotNull(parser::acceptLine).singleOrNull()

        assertNotNull(gesture)
        assertEquals("click", gesture?.actionName)
        assertTrue(starts.isNotEmpty())
        assertTrue((gesture?.startX ?: 0f) in 530f..550f)
        assertTrue((gesture?.startY ?: 0f) in 1190f..1210f)
    }

    @Test
    fun `stream parser ignores events from non selected devices`() {
        val device = ManualRawTouchParser.TouchDevice(
            path = "/dev/input/event6",
            name = "vivo_ts",
            xAxis = ManualRawTouchParser.Axis(min = 0, max = 12599),
            yAxis = ManualRawTouchParser.Axis(min = 0, max = 27999),
        )
        val parser = ManualRawTouchParser.StreamParser(
            device = device,
            displayWidth = 1260,
            displayHeight = 2800,
            backend = "device_getevent",
        )

        val gestures = listOf(
            "[ 1000.000000] /dev/input/event7: EV_ABS ABS_MT_TRACKING_ID 00000001",
            "[ 1000.001000] /dev/input/event7: EV_ABS ABS_MT_POSITION_X 00000400",
            "[ 1000.001000] /dev/input/event7: EV_ABS ABS_MT_POSITION_Y 00000400",
            "[ 1000.002000] /dev/input/event7: EV_SYN SYN_REPORT 00000000",
            "[ 1000.080000] /dev/input/event7: EV_ABS ABS_MT_TRACKING_ID ffffffff",
            "[ 1000.081000] /dev/input/event7: EV_SYN SYN_REPORT 00000000",
            "[ 1000.160000] /dev/input/event6: EV_ABS ABS_MT_TRACKING_ID 00000002",
            "[ 1000.161000] /dev/input/event6: EV_ABS ABS_MT_POSITION_X 0000189c",
            "[ 1000.161000] /dev/input/event6: EV_ABS ABS_MT_POSITION_Y 000036b0",
            "[ 1000.162000] /dev/input/event6: EV_SYN SYN_REPORT 00000000",
            "[ 1000.240000] /dev/input/event6: EV_ABS ABS_MT_TRACKING_ID ffffffff",
            "[ 1000.241000] /dev/input/event6: EV_SYN SYN_REPORT 00000000",
        ).mapNotNull(parser::acceptLine)

        assertEquals(1, gestures.size)
        assertEquals("click", gestures.single().actionName)
        assertEquals("/dev/input/event6", gestures.single().devicePath)
    }

    @Test
    fun `stream parser converts raw movement into swipe`() {
        val device = ManualRawTouchParser.TouchDevice(
            path = "/dev/input/event4",
            name = "touchscreen",
            xAxis = ManualRawTouchParser.Axis(min = 0, max = 32767),
            yAxis = ManualRawTouchParser.Axis(min = 0, max = 65535),
        )
        val parser = ManualRawTouchParser.StreamParser(
            device = device,
            displayWidth = 1080,
            displayHeight = 2400,
            backend = "device_getevent",
        )

        val gesture = listOf(
            "[ 1000.000000] /dev/input/event4: EV_ABS ABS_MT_TRACKING_ID 00000001",
            "[ 1000.001000] /dev/input/event4: EV_ABS ABS_MT_POSITION_X 00004000",
            "[ 1000.001000] /dev/input/event4: EV_ABS ABS_MT_POSITION_Y 0000d000",
            "[ 1000.002000] /dev/input/event4: EV_SYN SYN_REPORT 00000000",
            "[ 1000.120000] /dev/input/event4: EV_ABS ABS_MT_POSITION_Y 00004000",
            "[ 1000.121000] /dev/input/event4: EV_SYN SYN_REPORT 00000000",
            "[ 1000.200000] /dev/input/event4: EV_ABS ABS_MT_TRACKING_ID ffffffff",
            "[ 1000.201000] /dev/input/event4: EV_SYN SYN_REPORT 00000000",
        ).mapNotNull(parser::acceptLine).singleOrNull()

        assertNotNull(gesture)
        assertEquals("swipe", gesture?.actionName)
        assertTrue((gesture?.distancePx ?: 0f) > 500f)
        assertTrue((gesture?.endY ?: Float.MAX_VALUE) < (gesture?.startY ?: 0f))
    }

    @Test
    fun `stream parser preserves repeated taps at the same coordinate`() {
        val device = ManualRawTouchParser.TouchDevice(
            path = "/dev/input/event4",
            name = "touchscreen",
            xAxis = ManualRawTouchParser.Axis(min = 0, max = 32767),
            yAxis = ManualRawTouchParser.Axis(min = 0, max = 65535),
        )
        val parser = ManualRawTouchParser.StreamParser(
            device = device,
            displayWidth = 1080,
            displayHeight = 2400,
            backend = "device_getevent",
        )

        val gestures = listOf(
            "[ 1000.000000] /dev/input/event4: EV_ABS ABS_MT_TRACKING_ID 00000001",
            "[ 1000.001000] /dev/input/event4: EV_ABS ABS_MT_POSITION_X 00004000",
            "[ 1000.001000] /dev/input/event4: EV_ABS ABS_MT_POSITION_Y 00008000",
            "[ 1000.002000] /dev/input/event4: EV_SYN SYN_REPORT 00000000",
            "[ 1000.080000] /dev/input/event4: EV_ABS ABS_MT_TRACKING_ID ffffffff",
            "[ 1000.081000] /dev/input/event4: EV_SYN SYN_REPORT 00000000",
            "[ 1000.160000] /dev/input/event4: EV_ABS ABS_MT_TRACKING_ID 00000002",
            "[ 1000.161000] /dev/input/event4: EV_SYN SYN_REPORT 00000000",
            "[ 1000.240000] /dev/input/event4: EV_ABS ABS_MT_TRACKING_ID ffffffff",
            "[ 1000.241000] /dev/input/event4: EV_SYN SYN_REPORT 00000000",
        ).mapNotNull(parser::acceptLine)

        assertEquals(2, gestures.size)
        assertEquals("click", gestures[0].actionName)
        assertEquals("click", gestures[1].actionName)
        assertEquals(gestures[0].startX, gestures[1].startX, 0.01f)
        assertEquals(gestures[0].startY, gestures[1].startY, 0.01f)
    }
}
