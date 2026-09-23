package io.github.hamann.miefquirl

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import timber.log.Timber

/**
 * Receives taps from the data field.
 *
 * The field is shipped to the Karoo as RemoteViews and inflated in the Karoo's
 * process, so a tap cannot call back into this app directly. The buttons carry
 * PendingIntents instead, which the system fires with *this* app's identity —
 * which is why this receiver does not need to be exported.
 */
class FanActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.getStringExtra(EXTRA_ACTION)
        if (action == null) {
            Timber.w("fan action broadcast with no action extra")
            return
        }
        Timber.i("data field tap: %s", action)
        val app = context.applicationContext as? MiefquirlApplication
        if (app == null) {
            Timber.e("unexpected application class; cannot reach the link")
            return
        }
        // If the Karoo started this process just to deliver the broadcast, the
        // link will not be scanning yet. start() is idempotent.
        app.link.start()
        app.link.press(action)
    }

    companion object {
        const val ACTION = "io.github.hamann.miefquirl.FAN_ACTION"
        const val EXTRA_ACTION = "action"
    }
}
