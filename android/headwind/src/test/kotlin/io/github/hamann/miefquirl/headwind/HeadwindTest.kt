package io.github.hamann.miefquirl.headwind

import kotlin.test.Test
import kotlin.test.assertEquals

/** Frame as unsigned ints, so a failure prints `[2, 60, 0, 0]` and not `[2, 60, 0, 0]` where one is secretly -3. */
private fun ByteArray.octets(): List<Int> = map { it.toInt() and 0xFF }

private fun frameOf(vararg octets: Int) = ByteArray(octets.size) { octets[it].toByte() }

class HeadwindTest {

    @Test
    fun `speed frames are 0x02, speed and two pad bytes`() {
        assertEquals(listOf(0x02, 0, 0, 0), Headwind.setSpeedFrame(0).octets())
        assertEquals(listOf(0x02, 60, 0, 0), Headwind.setSpeedFrame(60).octets())
        assertEquals(listOf(0x02, 100, 0, 0), Headwind.setSpeedFrame(100).octets())
    }

    @Test
    fun `out-of-range speeds are clamped rather than wrapping around`() {
        // 255 would otherwise become -1 as a signed byte and read back as 0xFF.
        assertEquals(listOf(0x02, 100, 0, 0), Headwind.setSpeedFrame(255).octets())
        assertEquals(listOf(0x02, 0, 0, 0), Headwind.setSpeedFrame(-20).octets())
    }

    @Test
    fun `mode frames carry the mode code`() {
        assertEquals(listOf(0x04, 0x01, 0, 0), Headwind.setModeFrame(FanMode.OFF).octets())
        assertEquals(listOf(0x04, 0x02, 0, 0), Headwind.setModeFrame(FanMode.HEART_RATE).octets())
        assertEquals(listOf(0x04, 0x03, 0, 0), Headwind.setModeFrame(FanMode.SPEED).octets())
        assertEquals(listOf(0x04, 0x04, 0, 0), Headwind.setModeFrame(FanMode.MANUAL).octets())
        assertEquals(listOf(0x04, 0x05, 0, 0), Headwind.setModeFrame(FanMode.SLEEP).octets())
    }

    @Test
    fun `0xFD frames carry speed in byte 2 and mode in byte 3`() {
        assertEquals(
            Notification.State(FanMode.MANUAL, 60),
            Headwind.decode(frameOf(0xFD, 0x01, 60, 0x04)),
        )
        assertEquals(
            Notification.State(FanMode.OFF, 0),
            Headwind.decode(frameOf(0xFD, 0x01, 0, 0x01)),
        )
        assertEquals(
            Notification.State(FanMode.HEART_RATE, 80),
            Headwind.decode(frameOf(0xFD, 0x01, 80, 0x02)),
        )
        assertEquals(
            Notification.State(FanMode.SLEEP, 0),
            Headwind.decode(frameOf(0xFD, 0x01, 0, 0x05)),
        )
    }

    @Test
    fun `speed is read unsigned`() {
        val decoded = Headwind.decode(frameOf(0xFD, 0x01, 0xFF, 0x04))
        assertEquals(100, (decoded as Notification.State).speed)
    }

    @Test
    fun `acks are decoded`() {
        assertEquals(Notification.SpeedAck(40), Headwind.decode(frameOf(0xFE, 0x02, 0x00, 40)))
        assertEquals(
            Notification.ModeAck(FanMode.MANUAL),
            Headwind.decode(frameOf(0xFE, 0x04, 0x00, 0x04)),
        )
        assertEquals(
            Notification.ModeAck(FanMode.OFF),
            Headwind.decode(frameOf(0xFE, 0x04, 0x00, 0x01)),
        )
    }

    @Test
    fun `nothing off the radio should be able to throw`() {
        assertEquals(Notification.Unknown, Headwind.decode(null))
        assertEquals(Notification.Unknown, Headwind.decode(ByteArray(0)))
        assertEquals(Notification.Unknown, Headwind.decode(frameOf(0xFD, 0x01)))
        assertEquals(Notification.Unknown, Headwind.decode(frameOf(0xFD, 0x01, 0, 0, 0)))
        assertEquals(Notification.Unknown, Headwind.decode(frameOf(0x00, 0x00, 0x00, 0x00)))

        // Well-formed state frame, unrecognised mode code.
        assertEquals(Notification.Unknown, Headwind.decode(frameOf(0xFD, 0x01, 0, 0x09)))
        // Ack for an op we do not know.
        assertEquals(Notification.Unknown, Headwind.decode(frameOf(0xFE, 0x07, 0, 0)))
    }

    @Test
    fun `a speed we send comes back as the same speed in a state frame`() {
        for (pct in listOf(0, 20, 40, 60, 80, 100)) {
            val sent = Headwind.setSpeedFrame(pct)
            val echoed = frameOf(0xFD, 0x01, sent[1].toInt() and 0xFF, 0x04)
            assertEquals(Notification.State(FanMode.MANUAL, pct), Headwind.decode(echoed))
        }
    }

    @Test
    fun `mode codes round-trip`() {
        for (mode in FanMode.entries) {
            assertEquals(mode, FanMode.fromCode(mode.code))
        }
        assertEquals(null, FanMode.fromCode(0x09))
    }
}
