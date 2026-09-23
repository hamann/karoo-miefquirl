package io.github.hamann.miefquirl

import android.app.Application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import timber.log.Timber

/**
 * Holds the one [HeadwindLink] for the process.
 *
 * There is a single fan and a single Bluetooth radio, and both the extension
 * service and the settings activity need to talk to them. Hanging the link off
 * the Application is the least ceremonious way to share one instance between
 * them without binding the activity to the service.
 */
class MiefquirlApplication : Application() {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    val link: HeadwindLink by lazy { HeadwindLink(this, scope) }

    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
        }
    }
}
