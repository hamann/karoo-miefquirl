package io.github.hamann.miefquirl

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import io.github.hamann.miefquirl.headwind.Fan
import io.github.hamann.miefquirl.headwind.FanControl
import io.github.hamann.miefquirl.headwind.FanMode
import io.github.hamann.miefquirl.headwind.Headwind
import io.github.hamann.miefquirl.headwind.Notification
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber
import java.util.UUID

/**
 * Owns the BLE conversation with the fan.
 *
 * Everything is funnelled through a single worker coroutine reading [commands].
 * Android's GATT stack permits exactly one outstanding operation per
 * connection, so frames have to go out one at a time, each waiting for the
 * previous callback — the worker does that, and as a side effect the fan state
 * is only ever mutated from one place.
 *
 * Connection handling is deliberately forgiving: the fan is a bit of gym
 * equipment that gets switched off at the wall, so losing the link is normal
 * and the class simply goes back to scanning.
 */
@SuppressLint("MissingPermission")
class HeadwindLink(
    private val context: Context,
    private val scope: CoroutineScope,
) {
    enum class Link { Idle, Scanning, Connecting, Ready, Lost, Unavailable }

    /**
     * What the fan has actually confirmed, by acknowledgement or by its own
     * state broadcast. This is what gets displayed, so the field never claims a
     * speed the fan did not reach.
     */
    private val _fan = MutableStateFlow(Fan())
    val fan: StateFlow<Fan> = _fan.asStateFlow()

    /**
     * What the rider has asked for.
     *
     * Runs ahead of [_fan] between a press and its acknowledgement. Presses have
     * to be computed against this rather than against the confirmed state —
     * otherwise three quick taps on "+" would each start from the same
     * unconfirmed value and the fan would never get past the first step.
     *
     * Only ever touched from the worker coroutine.
     */
    private var target = Fan()

    /** Set on a press, cleared by the confirmation that follows it. */
    private var pressPending = false

    /** Last state frame logged, so the cyclic repeats stay out of the log. */
    private var lastLoggedState: Notification.State? = null

    private val _link = MutableStateFlow(Link.Idle)
    val link: StateFlow<Link> = _link.asStateFlow()

    private val _pressed = MutableSharedFlow<Fan>(extraBufferCapacity = 8)

    /**
     * Emits once per press, carrying the state the fan confirmed in response,
     * so callers can show the rider what actually happened rather than what was
     * asked for. Separate from [fan], which also moves on the fan's unprompted
     * state broadcasts — not worth an alert.
     */
    val pressed: SharedFlow<Fan> = _pressed.asSharedFlow()

    private val adapter: BluetoothAdapter? =
        context.getSystemService(BluetoothManager::class.java)?.adapter

    private val serviceUuid: UUID = UUID.fromString(Headwind.SERVICE_UUID)
    private val characteristicUuid: UUID = UUID.fromString(Headwind.CHARACTERISTIC_UUID)

    @Volatile
    private var gatt: BluetoothGatt? = null

    @Volatile
    private var characteristic: BluetoothGattCharacteristic? = null

    private val commands = Channel<Command>(Channel.BUFFERED)
    private val writeAcks = Channel<Unit>(Channel.CONFLATED)

    private var worker: Job? = null
    private var discovery: Job? = null

    @Volatile
    private var scanning = false

    private sealed interface Command {
        class Press(val action: String) : Command
        class Notified(val frame: ByteArray) : Command
        data object Restore : Command
    }

    /** Begin scanning and processing commands. Safe to call more than once. */
    fun start() {
        if (worker != null) return
        if (!hasPermissions()) {
            Timber.e("Bluetooth permissions not granted; open Miefquirl to grant them")
            _link.value = Link.Unavailable
            return
        }
        if (adapter?.isEnabled != true) {
            Timber.e("Bluetooth adapter unavailable or disabled")
            _link.value = Link.Unavailable
            return
        }
        worker = scope.launch {
            startDiscovery()
            processCommands()
        }
    }

    fun stop() {
        discovery?.cancel()
        discovery = null
        stopScan()
        gatt?.let {
            runCatching { it.disconnect() }
            runCatching { it.close() }
        }
        gatt = null
        characteristic = null
        worker?.cancel()
        worker = null
        _link.value = Link.Idle
    }

    /**
     * Queue a bonus action. The id goes straight to [FanControl]; ids it does
     * not recognise come back as a no-op rather than an error.
     */
    fun press(action: String) {
        commands.trySend(Command.Press(action))
    }


    private suspend fun processCommands() {
        for (command in commands) {
            try {
                when (command) {
                    is Command.Press -> {
                        val plan = FanControl.plan(target, command.action)
                        target = plan.state
                        if (plan.writes.isNotEmpty()) pressPending = true
                        for (frame in plan.writes) transmit(frame)
                    }

                    is Command.Notified -> {
                        val notification = Headwind.decode(command.frame)
                        // State frames arrive about once a second, so only log
                        // one when it actually says something new. The acks are
                        // rare enough to log unconditionally.
                        if (notification is Notification.Unknown) {
                            // Worth the bytes: this is a frame the protocol
                            // write-ups did not cover.
                            Timber.d("notify Unknown %s", command.frame.toHex())
                        } else if (notification !is Notification.State) {
                            Timber.d("notify %s", notification)
                        } else if (notification != lastLoggedState) {
                            lastLoggedState = notification
                            Timber.d("notify %s", notification)
                        }

                        val confirmed = FanControl.observe(_fan.value, notification)
                        _fan.value = confirmed

                        // The fan closes out a command with an acknowledgement:
                        // a speed ack, or a mode ack for anything but manual —
                        // manual is only ever the first half of switching on.
                        val completesPress = notification is Notification.SpeedAck ||
                            (notification is Notification.ModeAck && notification.mode != FanMode.MANUAL)

                        if (pressPending && completesPress) {
                            pressPending = false
                            _pressed.tryEmit(confirmed)
                        }

                        // A state frame is the fan's own account of itself, so
                        // pull intent back into line with it — but never while a
                        // press is in flight. These frames are cyclic and lag;
                        // one issued just before our write lands reports the old
                        // speed and would roll the intent back under us.
                        if (notification is Notification.State && !pressPending) {
                            target = confirmed
                        }
                    }

                    Command.Restore ->
                        for (frame in FanControl.connectWrites(target)) transmit(frame)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.e(e, "command failed")
            }
        }
    }

    /** Write one frame and wait for the stack to report it went out. */
    private suspend fun transmit(frame: ByteArray) {
        val gatt = this.gatt
        val characteristic = this.characteristic
        if (gatt == null || characteristic == null) {
            Timber.w("not connected, dropping frame")
            return
        }
        // An acknowledgement left over from a write that timed out would
        // otherwise satisfy this one immediately.
        writeAcks.tryReceive()

        if (!write(gatt, characteristic, frame)) {
            Timber.w("stack refused the write")
            return
        }
        if (withTimeoutOrNull(WRITE_TIMEOUT_MS) { writeAcks.receive() } == null) {
            Timber.w("no write callback after %d ms", WRITE_TIMEOUT_MS)
        }
    }

    private fun write(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        frame: ByteArray,
    ): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gatt.writeCharacteristic(
                characteristic,
                frame,
                BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE,
            ) == BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION")
            run {
                characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                characteristic.value = frame
                gatt.writeCharacteristic(characteristic)
            }
        }

    /**
     * Look for the fan in short windows, backing off when it is not there.
     *
     * This matters more than it looks. The scan has to be unfiltered — the
     * Headwind keeps its control service out of the advertisement, so a
     * ScanFilter never matches and the advertised name is the only thing to go
     * on — which means every nearby beacon wakes this process. An unbounded
     * scan would run flat out for an entire ride with the fan sitting at home,
     * which is most rides. So: bounded windows, a moderate duty cycle, and a
     * backoff that settles at one brief look every few minutes.
     */
    private fun startDiscovery() {
        if (discovery?.isActive == true || gatt != null) return
        discovery = scope.launch {
            var attempt = 0
            while (gatt == null) {
                if (!scanWindow()) return@launch
                if (gatt != null) break

                val wait = RETRY_BACKOFF_MS[minOf(attempt, RETRY_BACKOFF_MS.lastIndex)]
                attempt++
                Timber.d("fan not found, next look in %ds", wait / 1000)
                delay(wait)
            }
        }
    }

    /** One scan window. Returns false only if the radio is unusable. */
    private suspend fun scanWindow(): Boolean {
        val scanner = adapter?.bluetoothLeScanner
        if (scanner == null) {
            Timber.w("no BLE scanner available")
            _link.value = Link.Unavailable
            return false
        }
        scanning = true
        _link.value = Link.Scanning
        scanner.startScan(
            emptyList(),
            // BALANCED rather than LOW_LATENCY: a quarter of the radio time,
            // and the fan advertises often enough to still be found in seconds.
            ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_BALANCED).build(),
            scanCallback,
        )
        delay(SCAN_WINDOW_MS)
        stopScan()
        return true
    }

    private fun stopScan() {
        if (!scanning) return
        scanning = false
        // Throws if the adapter was switched off underneath us.
        runCatching { adapter?.bluetoothLeScanner?.stopScan(scanCallback) }
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device ?: return
            val name = result.scanRecord?.deviceName ?: device.name ?: return
            if (!name.startsWith(Headwind.DEVICE_NAME_PREFIX, ignoreCase = true)) return

            Timber.i("found %s at %s", name, device.address)
            stopScan()
            _link.value = Link.Connecting
            gatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        }

        override fun onScanFailed(errorCode: Int) {
            Timber.e("scan failed with %d", errorCode)
            scanning = false
            _link.value = Link.Lost
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    Timber.i("connected, discovering services")
                    gatt.discoverServices()
                }

                BluetoothProfile.STATE_DISCONNECTED -> {
                    Timber.w("disconnected (status %d)", status)
                    characteristic = null
                    this@HeadwindLink.gatt = null
                    runCatching { gatt.close() }
                    _link.value = Link.Lost
                    // Fresh backoff: a fan that just vanished is worth looking
                    // for promptly, unlike one that was never there.
                    startDiscovery()
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            val found = gatt.getService(serviceUuid)?.getCharacteristic(characteristicUuid)
            if (found == null) {
                Timber.e("no Headwind control characteristic on this device")
                gatt.disconnect()
                return
            }
            characteristic = found
            subscribe(gatt, found)
        }

        override fun onDescriptorWrite(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int,
        ) {
            Timber.i("notifications enabled (status %d)", status)
            ready()
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int,
        ) {
            writeAcks.trySend(Unit)
        }

        // Android 13 and up.
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            commands.trySend(Command.Notified(value))
        }

        // Android 12 and below. The framework calls one or the other, never
        // both, so there is no risk of handling a notification twice.
        @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
        ) {
            val value = characteristic.value ?: return
            commands.trySend(Command.Notified(value.copyOf()))
        }
    }

    private fun subscribe(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
        gatt.setCharacteristicNotification(characteristic, true)
        val cccd = characteristic.getDescriptor(CCCD_UUID)
        if (cccd == null) {
            // Without state frames we are flying blind, but manual control
            // still works — every press sends an absolute speed, not a delta.
            Timber.w("no client config descriptor; continuing without state updates")
            ready()
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gatt.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
        } else {
            @Suppress("DEPRECATION")
            run {
                cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                gatt.writeDescriptor(cccd)
            }
        }
    }

    /**
     * The link is usable. Push our idea of the fan state onto the fan, which
     * may have been left in heart-rate mode by whatever last talked to it.
     */
    private fun ready() {
        _link.value = Link.Ready
        commands.trySend(Command.Restore)
    }

    private fun ByteArray.toHex(): String = joinToString(" ") { "%02x".format(it) }

    private fun hasPermissions(): Boolean = requiredPermissions().all {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }

    private companion object {
        /** Standard Client Characteristic Configuration descriptor. */
        val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        const val WRITE_TIMEOUT_MS = 1_000L

        /** How long each look for the fan lasts. */
        const val SCAN_WINDOW_MS = 12_000L

        /**
         * Gap after each unsuccessful window, settling at one look every five
         * minutes so a ride with the fan left at home costs almost nothing.
         */
        val RETRY_BACKOFF_MS = longArrayOf(5_000, 15_000, 30_000, 60_000, 300_000)
    }
}
