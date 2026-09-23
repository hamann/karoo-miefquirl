package io.github.hamann.miefquirl

import io.github.hamann.miefquirl.headwind.Fan
import io.hammerhead.karooext.KarooSystemService
import io.hammerhead.karooext.extension.KarooExtension
import io.hammerhead.karooext.models.InRideAlert
import io.hammerhead.karooext.models.ReleaseBluetooth
import io.hammerhead.karooext.models.RequestBluetooth
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * The extension the Karoo System talks to.
 *
 * Its whole job is plumbing: hand bonus-action presses to [HeadwindLink],
 * expose the fan speed as a data field, and tell the rider what happened. The
 * decisions about what a press means live in the Clojure core.
 */
class MiefquirlExtension : KarooExtension(EXTENSION_ID, BuildConfig.VERSION_NAME) {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val karooSystem by lazy { KarooSystemService(applicationContext) }

    private val link by lazy { (application as MiefquirlApplication).link }

    override val types by lazy { listOf(FanSpeedDataType(applicationContext, extension, link.fan)) }

    override fun onCreate() {
        super.onCreate()
        karooSystem.connect { connected ->
            Timber.i("karoo system connected=%s", connected)
            if (!connected) return@connect

            // The Karoo arbitrates its own radio between the OS and extensions,
            // so scanning without asking first is not reliable.
            karooSystem.dispatch(RequestBluetooth(extension))
            link.start()
        }

        scope.launch {
            link.pressed.collect(::announce)
        }
    }

    override fun onBonusAction(actionId: String) {
        Timber.i("bonus action %s", actionId)
        link.press(actionId)
    }

    /** Brief confirmation on the head unit, since the fan is behind the rider. */
    private fun announce(fan: Fan) {
        karooSystem.dispatch(
            InRideAlert(
                id = "miefquirl-fan",
                icon = R.drawable.ic_fan,
                title = getString(R.string.alert_title),
                detail = fan.label,
                autoDismissMs = ALERT_MS,
                backgroundColor = R.color.alert_background,
                textColor = R.color.alert_text,
            ),
        )
    }

    override fun onDestroy() {
        link.stop()
        karooSystem.dispatch(ReleaseBluetooth(extension))
        karooSystem.disconnect()
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        /** Must match the id in res/xml/extension_info.xml. */
        const val EXTENSION_ID = "miefquirl"

        private const val ALERT_MS = 2_000L
    }
}
