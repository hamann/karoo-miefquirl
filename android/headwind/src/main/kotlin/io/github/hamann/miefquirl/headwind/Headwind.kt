package io.github.hamann.miefquirl.headwind

/**
 * Wire format for the Wahoo KICKR Headwind's BLE control characteristic.
 *
 * The Headwind speaks a small, undocumented protocol. The constants below were
 * taken from three independent reverse-engineering efforts that agree with each
 * other:
 *
 *  - garanj/wearwind           (Kotlin, Wear OS)
 *  - tuna-f1sh/tailwind        (Rust, nRF52 remote)
 *  - myanshin/headwind_control (Kotlin, Android)
 *
 * One service, one characteristic, and every frame is exactly four bytes.
 *
 * Writes (without response):
 * ```
 *   02 ss 00 00   set fan output, ss = 0..100
 *   04 mm 00 00   set mode, see FanMode
 * ```
 *
 * The fan ignores a speed write unless it is already in manual mode, so a mode
 * frame has to precede the first speed frame. [FanControl] handles that.
 *
 * Notifications on the same characteristic:
 * ```
 *   FD __ ss mm   device state, broadcast cyclically
 *   FE 02 __ ss   acknowledgement of a speed write
 *   FE 04 __ mm   acknowledgement of a mode write
 * ```
 *
 * The `FD` frames are not true notifications but a fixed cyclic update, so they
 * can lag behind a command that was just issued. Treat a fresh local command as
 * more authoritative than a state frame that contradicts it.
 */
object Headwind {
    const val SERVICE_UUID = "a026ee0c-0a7d-4ab3-97fa-f1500f9feb8b"
    const val CHARACTERISTIC_UUID = "a026e038-0a7d-4ab3-97fa-f1500f9feb8b"

    /** The fan advertises as "HEADWIND <serial>" unless its owner renamed it. */
    const val DEVICE_NAME_PREFIX = "HEADWIND"

    const val MIN_SPEED = 0
    const val MAX_SPEED = 100
    const val FRAME_LENGTH = 4

    private const val OP_SET_SPEED = 0x02
    private const val OP_SET_MODE = 0x04
    private const val FRAME_STATE = 0xFD
    private const val FRAME_ACK = 0xFE

    /** Constrain a fan percentage to the range the Headwind accepts. */
    fun clampSpeed(pct: Int): Int = pct.coerceIn(MIN_SPEED, MAX_SPEED)

    /** Frame setting fan output to [pct] percent. Only honoured in manual mode. */
    fun setSpeedFrame(pct: Int): ByteArray = frame(OP_SET_SPEED, clampSpeed(pct), 0, 0)

    /** Frame switching the fan to [mode]. */
    fun setModeFrame(mode: FanMode): ByteArray = frame(OP_SET_MODE, mode.code, 0, 0)

    /**
     * Decode a notification frame.
     *
     * Anything malformed, truncated or carrying an unrecognised code decodes as
     * [Notification.Unknown] rather than throwing — this parses bytes off a
     * radio, and a firmware revision we have not seen must not be able to crash
     * the extension.
     */
    fun decode(bytes: ByteArray?): Notification {
        if (bytes == null || bytes.size != FRAME_LENGTH) return Notification.Unknown

        return when (bytes.u(0)) {
            FRAME_STATE -> {
                val mode = FanMode.fromCode(bytes.u(3)) ?: return Notification.Unknown
                Notification.State(mode, clampSpeed(bytes.u(2)))
            }

            FRAME_ACK -> when (bytes.u(1)) {
                OP_SET_SPEED -> Notification.SpeedAck(clampSpeed(bytes.u(3)))
                OP_SET_MODE -> FanMode.fromCode(bytes.u(3))
                    ?.let { Notification.ModeAck(it) }
                    ?: Notification.Unknown

                else -> Notification.Unknown
            }

            else -> Notification.Unknown
        }
    }

    private fun frame(vararg octets: Int) = ByteArray(octets.size) { octets[it].toByte() }

    /** Byte [index] as an unsigned value. */
    private fun ByteArray.u(index: Int): Int = this[index].toInt() and 0xFF
}

/** Operating modes the Headwind understands. */
enum class FanMode(val code: Int) {
    OFF(0x01),
    HEART_RATE(0x02),
    SPEED(0x03),
    MANUAL(0x04),
    SLEEP(0x05),
    ;

    companion object {
        fun fromCode(code: Int): FanMode? = entries.firstOrNull { it.code == code }
    }
}

/** What the fan just told us. */
sealed interface Notification {
    data class State(val mode: FanMode, val speed: Int) : Notification
    data class SpeedAck(val speed: Int) : Notification
    data class ModeAck(val mode: FanMode) : Notification
    data object Unknown : Notification
}
