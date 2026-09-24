package io.github.hamann.miefquirl

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * Settings and manual test screen.
 *
 * On the bike the fan is driven from the in-ride menu, not from here. This
 * exists to collect the Bluetooth permissions, show whether the link is up,
 * and let you exercise the buttons at a desk.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val link = (application as MiefquirlApplication).link

        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    MiefquirlScreen(link)
                }
            }
        }
    }
}

@Composable
private fun MiefquirlScreen(link: HeadwindLink) {
    val fan by link.fan.collectAsStateWithLifecycle()
    val status by link.link.collectAsStateWithLifecycle()

    val permissions = remember { requiredPermissions() }
    val request = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        // Whether or not everything was granted, let the link decide and report.
        link.start()
    }

    LaunchedEffect(Unit) { request.launch(permissions) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(text = fan.label, style = MaterialTheme.typography.displaySmall)
        Text(text = statusText(status), style = MaterialTheme.typography.bodyMedium)

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(onClick = { link.press("down") }, modifier = Modifier.weight(1f)) {
                Text("−")
            }
            Button(onClick = { link.press("up") }, modifier = Modifier.weight(1f)) {
                Text("+")
            }
        }

        OutlinedButton(
            onClick = { link.press("toggle") },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(if (fan.isOn) "Turn off" else "Turn on")
        }

        Text(
            text = "Add “Fan” to a data page to control the fan while riding.",
            style = MaterialTheme.typography.bodySmall,
            textAlign = TextAlign.Center,
        )

        // Only worth offering once there is something to forget. A replaced fan
        // is otherwise indistinguishable from one that is merely switched off,
        // and the extension would wait for the old one indefinitely.
        if (remember { link.hasKnownFan() }) {
            OutlinedButton(
                onClick = { link.forget() },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Forget this fan")
            }
        }
    }
}

private fun statusText(status: HeadwindLink.Link): String = when (status) {
    HeadwindLink.Link.Idle -> "Not started"
    HeadwindLink.Link.Scanning -> "Looking for the fan…"
    HeadwindLink.Link.Waiting -> "Waiting for the fan to switch on"
    HeadwindLink.Link.Connecting -> "Connecting…"
    HeadwindLink.Link.Ready -> "Connected"
    HeadwindLink.Link.Lost -> "Lost the fan, retrying…"
    HeadwindLink.Link.Unavailable -> "Bluetooth unavailable or not permitted"
}

/** BLE scanning moved out from under the location permission in Android 12. */
internal fun requiredPermissions(): Array<String> =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
    } else {
        arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
    }
