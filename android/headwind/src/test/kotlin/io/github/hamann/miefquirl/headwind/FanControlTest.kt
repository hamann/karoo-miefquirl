package io.github.hamann.miefquirl.headwind

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private fun List<ByteArray>.octets(): List<List<Int>> = map { frame -> frame.map { it.toInt() and 0xFF } }

private val OFF = Fan()

private fun running(pct: Int) = Fan(FanMode.MANUAL, pct, pct)

class FanControlTest {

    @Test
    fun `first press takes the fan out of whatever mode it was in`() {
        val plan = FanControl.plan(OFF, FanAction.UP)
        assertEquals(listOf(listOf(0x04, 0x04, 0, 0), listOf(0x02, 10, 0, 0)), plan.writes.octets())
        assertEquals(Fan(FanMode.MANUAL, 10, 10), plan.state)
    }

    @Test
    fun `already in manual, so no redundant mode frame`() {
        val plan = FanControl.plan(running(10), FanAction.UP)
        assertEquals(listOf(listOf(0x02, 20, 0, 0)), plan.writes.octets())
        assertEquals(20, plan.state.speed)
    }

    @Test
    fun `up saturates at full`() {
        val plan = FanControl.plan(running(100), FanAction.UP)
        assertEquals(100, plan.state.speed)
        assertEquals(listOf(listOf(0x02, 100, 0, 0)), plan.writes.octets())
    }

    @Test
    fun `up snaps to the step grid, not past it`() {
        assertEquals(20, FanControl.plan(running(13), FanAction.UP).state.speed)
        assertEquals(30, FanControl.plan(running(21), FanAction.UP).state.speed)
    }

    @Test
    fun `down steps and snaps`() {
        assertEquals(50, FanControl.plan(running(60), FanAction.DOWN).state.speed)
        assertEquals(30, FanControl.plan(running(33), FanAction.DOWN).state.speed)
    }

    @Test
    fun `stepping below one increment switches the fan off`() {
        val plan = FanControl.plan(running(FanControl.STEP), FanAction.DOWN)
        assertEquals(FanMode.OFF, plan.state.mode)
        assertEquals(0, plan.state.speed)
        assertEquals(listOf(listOf(0x04, 0x01, 0, 0)), plan.writes.octets())
    }

    @Test
    fun `nothing to say to a fan that is already off`() {
        val plan = FanControl.plan(OFF, FanAction.DOWN)
        assertEquals(FanMode.OFF, plan.state.mode)
        assertTrue(plan.writes.isEmpty())
    }

    @Test
    fun `off remembers the speed for toggle`() {
        val plan = FanControl.plan(running(80), FanAction.OFF)
        assertEquals(FanMode.OFF, plan.state.mode)
        assertEquals(listOf(listOf(0x04, 0x01, 0, 0)), plan.writes.octets())
        assertEquals(80, plan.state.lastSpeed)
    }

    @Test
    fun `toggle off, then back on to where it was`() {
        val stopped = FanControl.plan(running(80), FanAction.TOGGLE).state
        assertEquals(FanMode.OFF, stopped.mode)
        assertEquals(80, stopped.lastSpeed)

        val restarted = FanControl.plan(stopped, FanAction.TOGGLE)
        assertEquals(80, restarted.state.speed)
        assertEquals(
            listOf(listOf(0x04, 0x04, 0, 0), listOf(0x02, 80, 0, 0)),
            restarted.writes.octets(),
        )
    }

    @Test
    fun `toggle from a cold start uses the default`() {
        assertEquals(FanControl.DEFAULT_SPEED, FanControl.plan(OFF, FanAction.TOGGLE).state.speed)
    }

    @Test
    fun `a manifest that drifts ahead of this code must not disturb the fan`() {
        val state = running(60)
        val plan = FanControl.plan(state, "warp-speed")
        assertEquals(state, plan.state)
        assertTrue(plan.writes.isEmpty())
    }

    @Test
    fun `action ids map to actions`() {
        for (action in FanAction.entries) {
            assertEquals(action, FanAction.fromId(action.id))
        }
        assertEquals(null, FanAction.fromId("warp-speed"))
    }

    @Test
    fun `the fan in its own heart-rate mode reads as not-ours, so up starts low`() {
        val plan = FanControl.plan(Fan(FanMode.HEART_RATE, 90, 0), FanAction.UP)
        assertEquals(10, plan.state.speed)
        assertEquals(listOf(listOf(0x04, 0x04, 0, 0), listOf(0x02, 10, 0, 0)), plan.writes.octets())
    }

    @Test
    fun `a state frame is the fan's truth and overrides ours`() {
        val state = FanControl.observe(running(20), Headwind.decode(frame(0xFD, 0x01, 60, 0x04)))
        assertEquals(Fan(FanMode.MANUAL, 60, 60), state)
    }

    @Test
    fun `the fan going off elsewhere keeps our restore point`() {
        val state = FanControl.observe(running(60), Headwind.decode(frame(0xFD, 0x01, 0, 0x01)))
        assertEquals(FanMode.OFF, state.mode)
        assertEquals(60, state.lastSpeed)
    }

    @Test
    fun `garbage leaves state alone`() {
        assertEquals(running(40), FanControl.observe(running(40), Notification.Unknown))
    }

    @Test
    fun `a speed ack confirms the fan reached that speed`() {
        val state = FanControl.observe(OFF, Notification.SpeedAck(60))
        assertEquals(FanMode.MANUAL, state.mode)
        assertEquals(60, state.speed)
        assertEquals(60, state.lastSpeed)
    }

    @Test
    fun `a manual mode ack does not invent a speed`() {
        // Switching to manual is only half of turning the fan on; the speed
        // frame follows and is acknowledged separately.
        val state = FanControl.observe(OFF, Notification.ModeAck(FanMode.MANUAL))
        assertEquals(FanMode.MANUAL, state.mode)
        assertEquals(0, state.speed)
        assertEquals("OFF", state.label)
    }

    @Test
    fun `an off mode ack confirms the fan stopped`() {
        val state = FanControl.observe(running(80), Notification.ModeAck(FanMode.OFF))
        assertEquals(FanMode.OFF, state.mode)
        assertEquals(0, state.speed)
        assertEquals(80, state.lastSpeed, "toggle still has somewhere to go back to")
    }

    @Test
    fun `turning on confirms in the two steps the protocol uses`() {
        var state = OFF
        state = FanControl.observe(state, Notification.ModeAck(FanMode.MANUAL))
        assertEquals("OFF", state.label)
        state = FanControl.observe(state, Notification.SpeedAck(20))
        assertEquals("20%", state.label)
    }

    @Test
    fun `label reflects what we are driving`() {
        assertEquals("OFF", OFF.label)
        assertEquals("60%", running(60).label)
        // The fan's own modes are not ours to report a percentage for.
        assertEquals("OFF", Fan(FanMode.HEART_RATE, 90, 0).label)
    }

    @Test
    fun `reconnecting while off parks the fan rather than leaving it running`() {
        assertEquals(listOf(listOf(0x04, 0x01, 0, 0)), FanControl.connectWrites(OFF).octets())
    }

    @Test
    fun `reconnecting while running restores mode and speed`() {
        assertEquals(
            listOf(listOf(0x04, 0x04, 0, 0), listOf(0x02, 60, 0, 0)),
            FanControl.connectWrites(running(60)).octets(),
        )
    }

    @Test
    fun `presses walk all the way up and all the way back down`() {
        val steps = Headwind.MAX_SPEED / FanControl.STEP
        val grid = (0..steps).map { it * FanControl.STEP }

        // One extra press at each end to prove it saturates rather than wrapping.
        val up = generateSequence(OFF) { FanControl.plan(it, FanAction.UP).state }
            .take(steps + 2).map { it.output }.toList()
        assertEquals(grid + Headwind.MAX_SPEED, up)

        val top = Fan(FanMode.MANUAL, Headwind.MAX_SPEED, Headwind.MAX_SPEED)
        val down = generateSequence(top) { FanControl.plan(it, FanAction.DOWN).state }
            .take(steps + 2).map { it.output }.toList()
        assertEquals(grid.reversed() + 0, down)
    }

    private fun frame(vararg octets: Int) = ByteArray(octets.size) { octets[it].toByte() }
}
