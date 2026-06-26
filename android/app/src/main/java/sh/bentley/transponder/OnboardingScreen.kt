package sh.bentley.transponder

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import uniffi.transponder_core.generateIdentity

private enum class OnboardingPage {
    Welcome,
    Location,
    Identity
}

@Composable
fun OnboardingScreen(
    identityStore: IdentityStore,
    onComplete: () -> Unit
) {
    var currentPage by remember { mutableStateOf(OnboardingPage.Welcome) }
    var hasLocationPermission by remember { mutableStateOf(false) }
    var hasBackgroundPermission by remember { mutableStateOf(false) }

    BackHandler(enabled = currentPage != OnboardingPage.Welcome) {
        currentPage = when (currentPage) {
            OnboardingPage.Identity -> OnboardingPage.Location
            OnboardingPage.Location -> OnboardingPage.Welcome
            OnboardingPage.Welcome -> OnboardingPage.Welcome
        }
    }

    Scaffold { innerPadding ->
        AnimatedContent(
            targetState = currentPage,
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            label = "onboarding_page"
        ) { page ->
            when (page) {
                OnboardingPage.Welcome -> WelcomePage(
                    onNext = { currentPage = OnboardingPage.Location }
                )
                OnboardingPage.Location -> LocationPermissionPage(
                    onPermissionResult = { hasLocation, hasBackground ->
                        hasLocationPermission = hasLocation
                        hasBackgroundPermission = hasBackground
                        currentPage = OnboardingPage.Identity
                    },
                    onSkip = {
                        hasLocationPermission = false
                        hasBackgroundPermission = false
                        currentPage = OnboardingPage.Identity
                    }
                )
                OnboardingPage.Identity -> IdentityPage(
                    identityStore = identityStore,
                    hasBackgroundPermission = hasBackgroundPermission,
                    onComplete = onComplete
                )
            }
        }
    }
}

@Composable
private fun WelcomePage(onNext: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "Coords",
            style = MaterialTheme.typography.headlineLarge
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "Share your location with friends and family",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(48.dp))

        // Feature highlights
        FeatureItem(
            icon = Icons.Default.Lock,
            title = "Secure",
            detail = "Only your friends can see your location"
        )

        Spacer(modifier = Modifier.height(24.dp))

        FeatureItem(
            icon = Icons.Default.Devices,
            title = "Universal",
            detail = "Works across Android and iOS"
        )

        Spacer(modifier = Modifier.height(24.dp))

        FeatureItem(
            icon = Icons.Default.Code,
            title = "Open",
            detail = "Free forever & community-built"
        )

        Spacer(modifier = Modifier.height(64.dp))

        Button(
            onClick = onNext,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Get Started")
        }
    }
}

@Composable
private fun FeatureItem(
    icon: ImageVector,
    title: String,
    detail: String
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(36.dp)
        )

        Spacer(modifier = Modifier.width(16.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium
            )
            Text(
                text = detail,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun LocationPermissionPage(
    onPermissionResult: (hasLocation: Boolean, hasBackground: Boolean) -> Unit,
    onSkip: () -> Unit
) {
    val context = LocalContext.current

    // Check current permission state
    val hasCoarsePermission = ContextCompat.checkSelfPermission(
        context, Manifest.permission.ACCESS_COARSE_LOCATION
    ) == PackageManager.PERMISSION_GRANTED

    val hasFinePermission = ContextCompat.checkSelfPermission(
        context, Manifest.permission.ACCESS_FINE_LOCATION
    ) == PackageManager.PERMISSION_GRANTED

    val hasBackgroundPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_BACKGROUND_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
    } else {
        // Pre-Q, background is included with foreground permission
        hasCoarsePermission || hasFinePermission
    }

    // Track if we've already requested foreground permission
    var foregroundRequested by remember { mutableStateOf(false) }
    var foregroundGranted by remember { mutableStateOf(hasCoarsePermission || hasFinePermission) }

    // Foreground permission launcher
    val foregroundPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val granted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
                permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        foregroundGranted = granted
        foregroundRequested = true

        if (!granted) {
            // User denied, proceed without location
            onPermissionResult(false, false)
        }
        // If granted, UI will update to show background permission option
    }

    // Notification permission launcher (Android 13+) — needed so the
    // background upload foreground service can show its ongoing notification.
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ ->
        onPermissionResult(true, true)
    }

    // Background permission launcher (Android 10+)
    val backgroundPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        val needsNotificationPermission = granted &&
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(
                    context, Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
        if (needsNotificationPermission) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            onPermissionResult(true, granted)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            imageVector = Icons.Default.LocationOn,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(64.dp)
        )

        Spacer(modifier = Modifier.height(24.dp))

        Text(
            text = "Location Access",
            style = MaterialTheme.typography.headlineMedium
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "Coords needs location access to share where you are with friends",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(32.dp))

        // Permission explanation
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            PermissionExplainer(
                title = "While using the app",
                detail = "Share your location manually when you choose"
            )
            PermissionExplainer(
                title = "Always allow",
                detail = "Share automatically in the background, even when the app is closed"
            )
            PermissionExplainer(
                title = "Don't allow",
                detail = "You can still see friends' locations, but can't share yours"
            )
        }

        Spacer(modifier = Modifier.height(48.dp))

        if (!foregroundGranted) {
            // Step 1: Request foreground permission
            Button(
                onClick = {
                    foregroundPermissionLauncher.launch(
                        arrayOf(
                            Manifest.permission.ACCESS_FINE_LOCATION,
                            Manifest.permission.ACCESS_COARSE_LOCATION
                        )
                    )
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Enable Location")
            }
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && !hasBackgroundPermission) {
            // Step 2: Foreground granted, offer background permission
            Text(
                text = "Location access granted! Enable background access for automatic sharing?",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(16.dp))

            Button(
                onClick = {
                    backgroundPermissionLauncher.launch(
                        Manifest.permission.ACCESS_BACKGROUND_LOCATION
                    )
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Enable Background Location")
            }

            Spacer(modifier = Modifier.height(8.dp))

            OutlinedButton(
                onClick = { onPermissionResult(true, false) },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Skip — I'll share manually")
            }
        } else {
            // All permissions granted (or pre-Q with foreground granted)
            Button(
                onClick = { onPermissionResult(true, hasBackgroundPermission) },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Continue")
            }
        }

        if (!foregroundGranted) {
            Spacer(modifier = Modifier.height(8.dp))

            TextButton(onClick = onSkip) {
                Text("Skip — I'll just see friends' locations")
            }
        }
    }
}

@Composable
private fun PermissionExplainer(
    title: String,
    detail: String
) {
    Column {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall
        )
        Text(
            text = detail,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun IdentityPage(
    identityStore: IdentityStore,
    hasBackgroundPermission: Boolean,
    onComplete: () -> Unit
) {
    var name by remember { mutableStateOf("") }
    var serverUrl by remember { mutableStateOf(identityStore.serverUrl ?: "https://coord.is") }
    var showServerDialog by remember { mutableStateOf(false) }
    var isLoading by remember { mutableStateOf(false) }

    val isCustomServer = serverUrl != "https://coord.is"

    fun submit() {
        if (name.isBlank() || isLoading) return
        isLoading = true

        // Generate identity from Rust core
        val identity = generateIdentity()

        // Save to secure storage
        identityStore.saveIdentity(identity)
        identityStore.displayName = name.trim()
        if (serverUrl.isNotBlank()) {
            identityStore.serverUrl = serverUrl.trim()
        }

        // Auto-enable background sharing if we have background permission
        if (hasBackgroundPermission) {
            identityStore.autoShareEnabled = true
        }

        onComplete()
    }

    Box(modifier = Modifier.fillMaxSize()) {
        // Top right server button
        TextButton(
            onClick = { showServerDialog = true },
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(8.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Dns,
                contentDescription = null,
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = if (isCustomServer) "Custom server" else "Using your own server?",
                style = MaterialTheme.typography.bodySmall
            )
        }

        // Main content
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 32.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "Set Up Your Profile",
                style = MaterialTheme.typography.headlineMedium
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "What should your friends call you?",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(32.dp))

            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Your name") },
                singleLine = true,
                enabled = !isLoading,
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Words,
                    imeAction = ImeAction.Done
                ),
                keyboardActions = KeyboardActions(
                    onDone = { submit() }
                ),
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(32.dp))

            Button(
                onClick = { submit() },
                enabled = name.isNotBlank() && !isLoading,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (isLoading) "Setting up..." else "Finish")
            }
        }
    }

    // Server configuration dialog
    if (showServerDialog) {
        ServerConfigDialog(
            currentUrl = serverUrl,
            onConfirm = { newUrl ->
                serverUrl = newUrl
                showServerDialog = false
            },
            onDismiss = { showServerDialog = false }
        )
    }
}

@Composable
private fun ServerConfigDialog(
    currentUrl: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var url by remember { mutableStateOf(currentUrl) }
    var isValidating by remember { mutableStateOf(false) }
    var validationResult by remember { mutableStateOf<ServerValidationResult?>(null) }
    val http = remember { TransponderHttp() }

    // Debounced validation (same pattern as MapScreen)
    LaunchedEffect(url) {
        val trimmed = url.trim()
        if (trimmed.isEmpty()) {
            validationResult = null
            return@LaunchedEffect
        }
        validationResult = null
        isValidating = true
        kotlinx.coroutines.delay(500)
        validationResult = http.validateServer(trimmed)
        isValidating = false
    }

    val isValid = validationResult is ServerValidationResult.Valid
    val canSave = url.isNotBlank() && isValid

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Custom Server") },
        text = {
            Column {
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text("Server URL") },
                    singleLine = true,
                    isError = validationResult is ServerValidationResult.Invalid,
                    modifier = Modifier.fillMaxWidth()
                )
                when {
                    isValidating -> {
                        Row(
                            modifier = Modifier.padding(top = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp
                            )
                            Text(
                                text = "Checking server...",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(start = 8.dp)
                            )
                        }
                    }
                    validationResult is ServerValidationResult.Valid -> {
                        val info = (validationResult as ServerValidationResult.Valid).info
                        Text(
                            text = "${info.name} v${info.version}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(top = 8.dp)
                        )
                    }
                    validationResult is ServerValidationResult.Invalid -> {
                        Text(
                            text = (validationResult as ServerValidationResult.Invalid).error,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(top = 8.dp)
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(url.trim()) },
                enabled = canSave && !isValidating
            ) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}
