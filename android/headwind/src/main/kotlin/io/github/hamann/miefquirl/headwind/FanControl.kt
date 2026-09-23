package io.github.hamann.miefquirl.headwind

/**
 * What the fan is doing, as far as this extension is concerned.
 *
 * @property lastSpeed what [FanAction.TOGGLE] restores after the fan is
 *   switched off.
 */
data class Fan(
    val mode: FanMode = FanMode.OFF,
    val speed: Int = 0,
    val lastSpeed: Int = FanControl.DEFAULT_SPEED,
) {
    /**
     * Current output under manual control.
     *
     * The fan's own heart-rate and speed modes are outside what this extension
     * drives, so from here they read as "not running under our control".
     */
    val output: Int get() = if (mode == FanMode.MANUAL) speed else 0

    val isOn: Boolean get() = output > 0

    /** Short human-readable state, for a data field or an alert. */
    val label: String get() = if (output > 0) "$output%" else "OFF"
}

/**
 * The actions this extension exposes.
 *
 * These are karoo-ext "bonus actions": the Karoo assigns them to buttons on a
 * paired controller (Di2, AXS, a BLE remote), not to an in-ride menu — there is
 * no extension API for adding menu entries. The settings activity drives the
 * same actions directly.
 */
enum class FanAction(val id: String) {
    UP("up"),
    DOWN("down"),
    OFF("off"),
    TOGGLE("toggle"),
    ;

    companion object {
        fun fromId(id: String): FanAction? = entries.firstOrNull { it.id == id }
    }
}

/**
 * The next state plus the frames that get the fan there.
 *
 * [writes] is ordered and must be written in sequence.
 */
data class Plan(val state: Fan, val writes: List<ByteArray>)

/**
 * Manual fan control.
 *
 * Pure: it turns a button press plus the current state into the next state and
 * the bytes to send. The Android layer owns the BLE socket and the coroutine
 * scope; this owns the decisions.
 *
 * Speeds move in fixed steps so that a rider wearing gloves, looking at the
 * road, gets a predictable result per button press.
 */
object FanControl {
    /** Percentage points per up/down press. 10 gives 0/10/20/.../100. */
    const val STEP = 10

    /** Where [FanAction.TOGGLE] starts the fan with no previous speed to restore. */
    const val DEFAULT_SPEED = 40

    /**
     * Apply [actionId] to [state].
     *
     * An unrecognised id is a no-op with no writes rather than an error: bonus
     * action ids come from a manifest that can drift ahead of this code.
     */
    fun plan(state: Fan, actionId: String): Plan =
        FanAction.fromId(actionId)
            ?.let { plan(state, it) }
            ?: Plan(state, emptyList())

    fun plan(state: Fan, action: FanAction): Plan = planFor(state, targetSpeed(state, action))

    /**
     * Fold a notification into [state], producing what the fan has actually
     * confirmed.
     *
     * Both kinds of inbound frame count. Acknowledgements land promptly after a
     * write; the cyclic state frames arrive about once a second regardless, so
     * even a fan that never acknowledges a command still gets reflected, just a
     * little later.
     *
     * [Fan.lastSpeed] is preserved across a stop, including one initiated
     * elsewhere, so that [FanAction.TOGGLE] still restores something sensible.
     */
    fun observe(state: Fan, notification: Notification): Fan = when (notification) {
        is Notification.State -> confirmed(state, notification.mode, notification.speed)

        is Notification.SpeedAck -> confirmed(state, FanMode.MANUAL, notification.speed)

        // A mode acknowledgement carries no speed. Switching to manual is only
        // the first half of turning the fan on — the speed frame follows — so
        // the speed we believe in is left alone until that is acknowledged too.
        is Notification.ModeAck -> when (notification.mode) {
            FanMode.MANUAL -> state.copy(mode = FanMode.MANUAL)
            else -> confirmed(state, notification.mode, 0)
        }

        Notification.Unknown -> state
    }

    private fun confirmed(state: Fan, mode: FanMode, speed: Int): Fan = state.copy(
        mode = mode,
        speed = if (mode == FanMode.MANUAL) speed else 0,
        lastSpeed = if (mode == FanMode.MANUAL && speed > 0) speed else state.lastSpeed,
    )

    /**
     * Frames to send once a fresh connection is established.
     *
     * The fan remembers whatever it was doing before, which may be heart-rate
     * mode driven by some other head unit. Explicitly restoring our own state on
     * connect means the in-ride buttons always do what the rider expects.
     */
    fun connectWrites(state: Fan): List<ByteArray> =
        if (state.mode == FanMode.MANUAL && state.speed > 0) {
            listOf(Headwind.setModeFrame(FanMode.MANUAL), Headwind.setSpeedFrame(state.speed))
        } else {
            listOf(Headwind.setModeFrame(FanMode.OFF))
        }

    private fun targetSpeed(state: Fan, action: FanAction): Int {
        val current = state.output
        return when (action) {
            FanAction.UP -> stepUp(current)
            FanAction.DOWN -> stepDown(current)
            FanAction.OFF -> 0
            FanAction.TOGGLE -> if (current > 0) 0 else Headwind.clampSpeed(state.lastSpeed)
        }
    }

    /**
     * Move [state] to [target] output.
     *
     * A mode frame is only emitted when the mode actually has to change, but the
     * fan rejects a speed write outside manual mode, so switching on always
     * sends mode before speed.
     */
    private fun planFor(state: Fan, target: Int): Plan =
        if (target == 0) {
            Plan(
                state = state.copy(mode = FanMode.OFF, speed = 0),
                writes = if (state.mode == FanMode.OFF) {
                    emptyList()
                } else {
                    listOf(Headwind.setModeFrame(FanMode.OFF))
                },
            )
        } else {
            Plan(
                state = state.copy(mode = FanMode.MANUAL, speed = target, lastSpeed = target),
                writes = buildList {
                    if (state.mode != FanMode.MANUAL) add(Headwind.setModeFrame(FanMode.MANUAL))
                    add(Headwind.setSpeedFrame(target))
                },
            )
        }

    /** Next step up, snapping a ragged speed onto the grid rather than past it. */
    private fun stepUp(speed: Int): Int {
        val remainder = speed % STEP
        return Headwind.clampSpeed(if (remainder == 0) speed + STEP else speed + (STEP - remainder))
    }

    private fun stepDown(speed: Int): Int {
        val remainder = speed % STEP
        return Headwind.clampSpeed(if (remainder == 0) speed - STEP else speed - remainder)
    }
}
