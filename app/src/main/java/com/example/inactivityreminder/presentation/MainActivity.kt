package com.example.inactivityreminder.presentation

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.AppScaffold
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.FilledIconButton
import androidx.wear.compose.material3.IconButtonDefaults
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import com.example.inactivityreminder.data.SettingsRepository
import com.example.inactivityreminder.presentation.theme.InActivityReminderTheme
import com.example.inactivityreminder.service.InactivityForegroundService
import kotlinx.coroutines.launch

/**
 * MainActivity — Entry point of the Wear OS app.
 *
 * Responsibilities:
 *   1. Request runtime permissions (BODY_SENSORS, ACTIVITY_RECOGNITION, POST_NOTIFICATIONS).
 *   2. Host the Compose UI (HomeScreen) that lets the user start/stop monitoring
 *      and configure the inactivity interval.
 *
 * This activity does NOT directly interact with sensors or notifications.
 * It only reads/writes through SettingsRepository and starts/stops the foreground service.
 */
class MainActivity : ComponentActivity() {

    /**
     * Activity Result API launcher for requesting multiple permissions at once.
     * The result callback is intentionally empty because the UI reacts to state
     * from SettingsRepository, not directly from permission grants.
     * If permissions are denied, the service will fail to start (caught gracefully).
     */
    companion object {
        private const val TAG = "MainActivity"
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        // Log which permissions were granted/denied for debugging on physical device
        results.forEach { (perm, granted) ->
            android.util.Log.d(TAG, "Permission $perm: ${if (granted) "GRANTED" else "DENIED"}")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        android.util.Log.d(TAG, "onCreate — requesting permissions")
        requestNeededPermissions()

        // Set up the Compose UI hierarchy
        setContent {
            InActivityReminderTheme {
                // AppScaffold provides the top-level structure for Wear Compose Material3
                AppScaffold {
                    HomeScreen()
                }
            }
        }
    }

    /**
     * Checks which permissions are not yet granted and requests them in a batch.
     *
     * Required permissions:
     *   - BODY_SENSORS: Access accelerometer data (runtime permission on Wear OS).
     *   - ACTIVITY_RECOGNITION: Required by foregroundServiceType="health" on targetSdk 36.
     *   - POST_NOTIFICATIONS (API 33+): Required to show notifications on Android 13+.
     */
    private fun requestNeededPermissions() {
        val perms = mutableListOf(
            Manifest.permission.BODY_SENSORS,
            Manifest.permission.ACTIVITY_RECOGNITION
        )
        // POST_NOTIFICATIONS permission only exists on API 33+
        if (Build.VERSION.SDK_INT >= 33) {
            perms.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        // Filter to only permissions not yet granted (avoid unnecessary prompts)
        val needed = perms.filter {
            checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
        }
        if (needed.isNotEmpty()) {
            android.util.Log.d(TAG, "Requesting permissions: $needed")
            permissionLauncher.launch(needed.toTypedArray())
        } else {
            android.util.Log.d(TAG, "All permissions already granted")
        }
    }
}

/**
 * HomeScreen — The single screen of the Wear OS app.
 *
 * Displays:
 *   1. Current monitoring status (Active / Off)
 *   2. Start/Stop toggle button — starts or stops the foreground service
 *   3. Stepper control to adjust the inactivity interval (1–60 minutes)
 *
 * All state is observed reactively from SettingsRepository via Flows.
 * When the user changes settings, they're persisted to DataStore immediately,
 * and the service picks up the new values on its next check cycle.
 */
@Composable
fun HomeScreen() {
    // Get the current context (Activity) for starting services and accessing DataStore
    val context = LocalContext.current

    // Repository instance for reading/writing user settings
    val repo = SettingsRepository(context)

    // Coroutine scope tied to this composable's lifecycle for launching suspend functions
    val scope = rememberCoroutineScope()

    // Observe monitoring state reactively — recomposes when value changes
    val isMonitoring by repo.isMonitoringEnabled.collectAsState(initial = false)

    // Observe interval state reactively — recomposes when value changes
    val interval by repo.inactivityIntervalMinutes.collectAsState(
        initial = SettingsRepository.DEFAULT_INTERVAL_MINUTES
    )

    ScreenScaffold {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            // Status text
            Text(
                text = if (isMonitoring) "Monitoring Active" else "Monitoring Off",
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center
            )

            // Start/Stop button — compact
            Button(
                onClick = {
                    scope.launch {
                        if (isMonitoring) {
                            repo.setMonitoringEnabled(false)
                            context.stopService(
                                Intent(context, InactivityForegroundService::class.java)
                            )
                        } else {
                            repo.setMonitoringEnabled(true)
                            context.startForegroundService(
                                Intent(context, InactivityForegroundService::class.java)
                            )
                        }
                    }
                },
                modifier = Modifier.height(32.dp).padding(top = 4.dp)
            ) {
                Text(
                    text = if (isMonitoring) "Stop" else "Start",
                    style = MaterialTheme.typography.labelSmall
                )
            }

            // Compact interval selector: [ - ]  30 min  [ + ]
            Row(
                modifier = Modifier.padding(top = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                FilledIconButton(
                    onClick = {
                        scope.launch { repo.setInactivityInterval(interval - 1) }
                    },
                    modifier = Modifier.size(28.dp),
                    colors = IconButtonDefaults.filledIconButtonColors()
                ) {
                    Text("-", style = MaterialTheme.typography.labelLarge)
                }

                Text(
                    text = "${interval} min",
                    style = MaterialTheme.typography.bodyMedium
                )

                FilledIconButton(
                    onClick = {
                        scope.launch { repo.setInactivityInterval(interval + 1) }
                    },
                    modifier = Modifier.size(28.dp),
                    colors = IconButtonDefaults.filledIconButtonColors()
                ) {
                    Text("+", style = MaterialTheme.typography.labelLarge)
                }
            }
        }
    }
}
