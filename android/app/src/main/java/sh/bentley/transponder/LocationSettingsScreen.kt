package sh.bentley.transponder

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LocationSettingsScreen(
    identityStore: IdentityStore,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    BackHandler { onDismiss() }

    // Settings state
    var activeTimeoutSec by remember { mutableIntStateOf((identityStore.locationActiveTimeoutMs / 1000).toInt()) }
    var activeAccuracyM by remember { mutableIntStateOf(identityStore.locationActiveAccuracyThresholdM.toInt()) }
    var passiveAgeSec by remember { mutableIntStateOf((identityStore.locationPassiveMaxAgeMs / 1000).toInt()) }
    var passiveAccuracyM by remember { mutableIntStateOf(identityStore.locationPassiveAccuracyThresholdM.toInt()) }

    // Test state
    var isTesting by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<LocationRequestResult?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Location Settings") },
                navigationIcon = {
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "Close")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
        ) {
            // Active GPS Section
            Text(
                text = "Active GPS Request",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 16.dp, bottom = 8.dp)
            )

            Text(
                text = "How long to wait for GPS and when to stop early if accuracy is good enough.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 16.dp)
            )

            // Active timeout slider
            Text(
                text = "Timeout: ${activeTimeoutSec}s",
                style = MaterialTheme.typography.bodyMedium
            )
            Slider(
                value = activeTimeoutSec.toFloat(),
                onValueChange = { activeTimeoutSec = it.toInt() },
                onValueChangeFinished = {
                    identityStore.locationActiveTimeoutMs = activeTimeoutSec * 1000L
                },
                valueRange = 5f..60f,
                steps = 10,
                modifier = Modifier.padding(bottom = 8.dp)
            )

            // Active accuracy threshold slider
            val earlyReturnText = if (activeAccuracyM == 0) "Disabled (use full timeout)" else "${activeAccuracyM}m or better"
            Text(
                text = "Early return: $earlyReturnText",
                style = MaterialTheme.typography.bodyMedium
            )
            Slider(
                value = activeAccuracyM.toFloat(),
                onValueChange = { activeAccuracyM = it.toInt() },
                onValueChangeFinished = {
                    identityStore.locationActiveAccuracyThresholdM = activeAccuracyM.toFloat()
                },
                valueRange = 0f..200f,
                steps = 19,
                modifier = Modifier.padding(bottom = 16.dp)
            )

            HorizontalDivider()

            // Cached Location Section
            Text(
                text = "Cached Location",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 16.dp, bottom = 8.dp)
            )

            Text(
                text = "When to accept a recent cached location instead of requesting fresh GPS.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 16.dp)
            )

            // Passive max age slider
            Text(
                text = "Max age: ${passiveAgeSec / 60}m ${passiveAgeSec % 60}s",
                style = MaterialTheme.typography.bodyMedium
            )
            Slider(
                value = passiveAgeSec.toFloat(),
                onValueChange = { passiveAgeSec = it.toInt() },
                onValueChangeFinished = {
                    identityStore.locationPassiveMaxAgeMs = passiveAgeSec * 1000L
                },
                valueRange = 60f..600f,
                steps = 8,
                modifier = Modifier.padding(bottom = 8.dp)
            )

            // Passive accuracy threshold slider
            Text(
                text = "Max accuracy: ${passiveAccuracyM}m or better",
                style = MaterialTheme.typography.bodyMedium
            )
            Slider(
                value = passiveAccuracyM.toFloat(),
                onValueChange = { passiveAccuracyM = it.toInt() },
                onValueChangeFinished = {
                    identityStore.locationPassiveAccuracyThresholdM = passiveAccuracyM.toFloat()
                },
                valueRange = 50f..500f,
                steps = 8,
                modifier = Modifier.padding(bottom = 16.dp)
            )

            HorizontalDivider()

            // Test Section
            Text(
                text = "Test",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 16.dp, bottom = 8.dp)
            )

            Text(
                text = "Request a fresh GPS location using current settings (ignores cached).",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 16.dp)
            )

            Button(
                onClick = {
                    scope.launch {
                        isTesting = true
                        testResult = null
                        val settings = LocationSettings.from(identityStore)
                        testResult = requestLocation(
                            context,
                            LocationFreshness.ALWAYS_FRESH,
                            settings
                        )
                        isTesting = false
                    }
                },
                enabled = !isTesting,
                modifier = Modifier.fillMaxWidth()
            ) {
                if (isTesting) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Requesting GPS...")
                } else {
                    Text("Test Active GPS")
                }
            }

            // Test result
            testResult?.let { result ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = if (result.location != null)
                            MaterialTheme.colorScheme.primaryContainer
                        else
                            MaterialTheme.colorScheme.errorContainer
                    )
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        if (result.location != null) {
                            Text(
                                text = "Location acquired",
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column {
                                    Text(
                                        text = "Time",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                                    )
                                    Text(
                                        text = "${result.elapsedMs}ms",
                                        style = MaterialTheme.typography.bodyLarge,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer
                                    )
                                }
                                Column {
                                    Text(
                                        text = "Accuracy",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                                    )
                                    Text(
                                        text = "${result.location!!.accuracy.toInt()}m",
                                        style = MaterialTheme.typography.bodyLarge,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer
                                    )
                                }
                                Column {
                                    Text(
                                        text = "Source",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                                    )
                                    Text(
                                        text = result.source,
                                        style = MaterialTheme.typography.bodyLarge,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer
                                    )
                                }
                            }
                        } else {
                            Text(
                                text = "No location acquired",
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "Time: ${result.elapsedMs}ms",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                            result.error?.let { error ->
                                Text(
                                    text = "Error: $error",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onErrorContainer
                                )
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.weight(1f))

            // Reset button at bottom
            OutlinedButton(
                onClick = {
                    activeTimeoutSec = (IdentityStore.DEFAULT_LOCATION_ACTIVE_TIMEOUT_MS / 1000).toInt()
                    activeAccuracyM = IdentityStore.DEFAULT_LOCATION_ACTIVE_ACCURACY_M.toInt()
                    passiveAgeSec = (IdentityStore.DEFAULT_LOCATION_PASSIVE_MAX_AGE_MS / 1000).toInt()
                    passiveAccuracyM = IdentityStore.DEFAULT_LOCATION_PASSIVE_ACCURACY_M.toInt()
                    identityStore.locationActiveTimeoutMs = IdentityStore.DEFAULT_LOCATION_ACTIVE_TIMEOUT_MS
                    identityStore.locationActiveAccuracyThresholdM = IdentityStore.DEFAULT_LOCATION_ACTIVE_ACCURACY_M
                    identityStore.locationPassiveMaxAgeMs = IdentityStore.DEFAULT_LOCATION_PASSIVE_MAX_AGE_MS
                    identityStore.locationPassiveAccuracyThresholdM = IdentityStore.DEFAULT_LOCATION_PASSIVE_ACCURACY_M
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp)
            ) {
                Text("Reset to Defaults")
            }
        }
    }
}
