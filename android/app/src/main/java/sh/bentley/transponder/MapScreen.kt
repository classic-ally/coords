package sh.bentley.transponder

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.graphics.Color
import androidx.compose.ui.graphics.Color as ComposeColor
import android.graphics.PointF
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.clickable
import uniffi.transponder_core.LicenseGroup
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material.icons.Icons
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.RemoveCircle
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.statusBars
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import org.maplibre.android.geometry.LatLng
import uniffi.transponder_core.Friend
import uniffi.transponder_core.Location as CoreLocation
import uniffi.transponder_core.Identity
import uniffi.transponder_core.generateFriendLink
import uniffi.transponder_core.City
import sh.bentley.transponder.components.FriendRow
import sh.bentley.transponder.components.SheetHeader
import sh.bentley.transponder.components.QrCodeView
import sh.bentley.transponder.sheets.AboutSheet
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.util.Base64
import java.net.URLEncoder
import androidx.compose.foundation.border
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.QrCode2

/** Friend with precomputed display data to avoid expensive calculations during scroll */


// Colors for map annotations


// Available map styles
enum class MapStyle(val displayName: String, val lightUrl: String, val darkUrl: String) {
    BASIC(
        "Basic",
        "https://api.maptiler.com/maps/basic-v2/style.json?key=${BuildConfig.MAPTILER_API_KEY}",
        "https://api.maptiler.com/maps/basic-v2-dark/style.json?key=${BuildConfig.MAPTILER_API_KEY}"
    ),
    STREETS(
        "Streets",
        "https://api.maptiler.com/maps/streets-v2/style.json?key=${BuildConfig.MAPTILER_API_KEY}",
        "https://api.maptiler.com/maps/streets-v2-dark/style.json?key=${BuildConfig.MAPTILER_API_KEY}"
    ),
    OUTDOOR(
        "Outdoor",
        "https://api.maptiler.com/maps/outdoor-v2/style.json?key=${BuildConfig.MAPTILER_API_KEY}",
        "https://api.maptiler.com/maps/outdoor-v2/style.json?key=${BuildConfig.MAPTILER_API_KEY}"
    ),
    SATELLITE(
        "Satellite",
        "https://api.maptiler.com/maps/satellite/style.json?key=${BuildConfig.MAPTILER_API_KEY}",
        "https://api.maptiler.com/maps/satellite/style.json?key=${BuildConfig.MAPTILER_API_KEY}"
    );

    fun getUrl(isDark: Boolean) = if (isDark) darkUrl else lightUrl
}

// Mock friends for UI development - calls Rust core for consistent data

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun MainScreen(
    identityStore: IdentityStore,
    modifier: Modifier = Modifier,
    onDeepLink: ((String) -> Unit)? = null
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var showAddFriend by remember { mutableStateOf(false) }
    var addFriendLocationDeferred by remember { mutableStateOf<Deferred<android.location.Location?>?>(null) }
    val isDarkTheme = isSystemInDarkTheme()
    val configuration = LocalConfiguration.current
    val density = androidx.compose.ui.platform.LocalDensity.current
    val statusBarHeight = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val sheetPeekHeight = (configuration.screenHeightDp * 0.4f).dp
    val sheetPeekHeightPx = with(density) { sheetPeekHeight.toPx() }
    val hasBackgroundPermission = LocationSyncWorker.hasBackgroundLocationPermission(context)
    // Revoke the stored auto-share preference if the permission is gone, before seeding the holder
    if (identityStore.autoShareEnabled && !hasBackgroundPermission) {
        identityStore.autoShareEnabled = false
    }
    val mapState = remember { MapScreenState.from(context, identityStore) }

    var selectedMapStyle by remember { mutableStateOf(MapStyle.BASIC) }
    var showStylePicker by remember { mutableStateOf(false) }
    var showServerUrlDialog by remember { mutableStateOf(false) }
    var showLocationSettings by remember { mutableStateOf(false) }
    var showProfile by remember { mutableStateOf(false) }
    var friendToDelete by remember { mutableStateOf<Friend?>(null) }
    var showDeleteConfirmation by remember { mutableStateOf(false) }
    var showEditFriendNameDialog by remember { mutableStateOf(false) }
    var showEditProfileNameDialog by remember { mutableStateOf(false) }

    // Exit edit mode on back press
    BackHandler(enabled = mapState.isEditMode) {
        mapState.isEditMode = false
    }
    var pendingAutoShareEnable by remember { mutableStateOf(false) }
    var showAboutDialog by remember { mutableStateOf(false) }
    val styleUrl = selectedMapStyle.getUrl(isDarkTheme)

    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        )
    }

    var isFetchingFriends by remember { mutableStateOf(false) }
    var isInitialLocationLoading by remember { mutableStateOf(true) }
    val friends = mapState.friends
    val selectedFriend = mapState.selectedFriend

    val locationSyncService = remember { LocationSyncService(identityStore) }
    val repo = remember { LocationRepository.from(context.applicationContext, identityStore) }

    fun refreshFriends() {
        mapState.refreshFriends()
    }

    // Debounced friend location fetching
    val fetchIntervalMs = 5 * 60 * 1000L  // 5 minutes
    var lastFetchTime by remember { mutableStateOf(0L) }

    suspend fun fetchFriendsIfNeeded(force: Boolean = false): Boolean {
        val now = System.currentTimeMillis()
        if (!force && now - lastFetchTime < fetchIntervalMs) {
            return false  // Too recent, skip
        }
        isFetchingFriends = true
        locationSyncService.fetchTrackedFriends()
        refreshFriends()
        lastFetchTime = System.currentTimeMillis()
        isFetchingFriends = false
        return true
    }

    // Fetch friend locations on initial appear
    LaunchedEffect(Unit) {
        fetchFriendsIfNeeded(force = true)
    }

    // Periodic foreground refresh every 60s
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(60_000)
            fetchFriendsIfNeeded(force = true)

            // Periodic upload if auto-share enabled (repo skips when no recipients)
            if (identityStore.autoShareEnabled) {
                val result = requestLocation(context, LocationFreshness.ALWAYS_FRESH, LocationSettings.from(identityStore))
                result.location?.let { location ->
                    mapState.currentLocation = LatLng(location.latitude, location.longitude)
                    mapState.currentLocationTimestamp = location.time
                    mapState.currentAccuracy = location.accuracy
                    repo.push(location, result.source)
                }
            }
        }
    }

    // Refresh on app resume
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                scope.launch {
                    fetchFriendsIfNeeded()
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    // Fetch server version on app start (non-blocking)
    val http = remember { TransponderHttp() }
    LaunchedEffect(identityStore.serverUrl) {
        val url = identityStore.serverUrl ?: return@LaunchedEffect
        when (val result = http.validateServer(url)) {
            is ServerValidationResult.Valid -> {
                mapState.serverVersion = result.info.version
            }
            is ServerValidationResult.Invalid -> {
                mapState.serverVersion = null
            }
        }
    }

    // Precompute cities once, recompute only when friends change
    val friendsWithCities = mapState.friendsWithCities

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

    fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                context, Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        hasPermission = permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
                permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true

        // Handle background permission result
        if (permissions[Manifest.permission.ACCESS_BACKGROUND_LOCATION] == true) {
            // If user was trying to enable auto-share, complete that action
            if (pendingAutoShareEnable) {
                mapState.setAutoShareGranted()
                pendingAutoShareEnable = false
                requestNotificationPermissionIfNeeded()
            }
            LocationSyncWorker.schedule(context)
        } else if (pendingAutoShareEnable) {
            // Permission denied, clear pending state
            pendingAutoShareEnable = false
        }
    }

    // Request location when permission is granted
    LaunchedEffect(hasPermission) {
        if (hasPermission) {
            isInitialLocationLoading = true
            val result = requestLocation(context, LocationFreshness.CACHED_OKAY, LocationSettings.from(identityStore))
            result.location?.let { location ->
                mapState.currentLocation = LatLng(location.latitude, location.longitude)
                mapState.currentLocationTimestamp = location.time
                mapState.currentAccuracy = location.accuracy

                // Auto-upload on app launch if enabled (repo skips when no recipients)
                if (identityStore.autoShareEnabled) {
                    repo.push(location, result.source)
                }
            }
            isInitialLocationLoading = false
        } else {
            isInitialLocationLoading = false
        }
    }


    if (!hasPermission) {
        Box(
            modifier = modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = "Location permission required",
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(bottom = 16.dp)
                )
                Button(onClick = {
                    permissionLauncher.launch(
                        arrayOf(
                            Manifest.permission.ACCESS_FINE_LOCATION,
                            Manifest.permission.ACCESS_COARSE_LOCATION,
                            Manifest.permission.ACCESS_BACKGROUND_LOCATION
                        )
                    )
                }) {
                    Text("Grant Permission")
                }
            }
        }
    } else {
        val screenHeight = configuration.screenHeightDp.dp
        val handleHeight = 80.dp
        val fullHeight = screenHeight * 0.85f

        val sheetState = rememberThreeStateSheetState(
            initialAnchor = SheetAnchor.Partial,
            handleHeight = handleHeight,
            partialHeight = sheetPeekHeight,
            fullHeight = fullHeight,
            screenHeight = screenHeight
        )

        // Helper to select a friend, center map, and show detail sheet
        fun selectAndCenterOnFriend(pubkey: String, lat: Double, lng: Double) {
            mapState.selectedFriendPubkey = pubkey
            mapState.requestCamera(CameraAction.CenterOn(LatLng(lat, lng)))
            scope.launch { sheetState.animateToAnchor(SheetAnchor.Partial) }
        }

        // Calculate bottom padding for LazyColumn based on hidden sheet area
        val listBottomPadding by remember {
            derivedStateOf {
                with(density) { sheetState.offset.toDp() }
            }
        }

        // Handle back button to dismiss profile/friend detail
        BackHandler(enabled = showProfile || selectedFriend != null) {
            if (showProfile) {
                showProfile = false
                mapState.requestCamera(CameraAction.FitAllFriends)
            } else if (mapState.selectedFriendPubkey != null) {
                mapState.selectedFriendPubkey = null
                mapState.requestCamera(CameraAction.FitAllFriends)
            }
        }

        ThreeStateBottomSheet(
            sheetState = sheetState,
            handleHeight = handleHeight,
            partialHeight = sheetPeekHeight,
            fullHeight = fullHeight,
            screenHeight = screenHeight,
            sheetContent = {
                SheetContent(
                    state = mapState,
                    showProfile = showProfile,
                    isFetchingFriends = isFetchingFriends,
                    listBottomPadding = listBottomPadding,
                    onProfileDismiss = {
                        showProfile = false
                        mapState.requestCamera(CameraAction.FitAllFriends)
                    },
                    onOpenProfile = {
                        showProfile = true
                        mapState.requestCamera(CameraAction.CenterOnMyLocation)
                        scope.launch { sheetState.animateToAnchor(SheetAnchor.Partial) }
                    },
                    onRefreshFriends = { scope.launch { fetchFriendsIfNeeded(force = true) } },
                    onOpenStylePicker = { showStylePicker = true },
                    onOpenServerUrl = { showServerUrlDialog = true },
                    onOpenEditProfileName = { showEditProfileNameDialog = true },
                    onOpenEditFriendName = { showEditFriendNameDialog = true },
                    onOpenLocationSettings = { showLocationSettings = true },
                    onShowAbout = { showAboutDialog = true },
                    onAddFriend = {
                        addFriendLocationDeferred = scope.async {
                            requestLocation(context, LocationFreshness.ALWAYS_FRESH, LocationSettings.from(identityStore)).location
                        }
                        showAddFriend = true
                    },
                    onAutoShareEnabledChange = { enabled ->
                        if (enabled) {
                            if (LocationSyncWorker.hasBackgroundLocationPermission(context)) {
                                mapState.setAutoShareGranted()
                                requestNotificationPermissionIfNeeded()
                            } else {
                                pendingAutoShareEnable = true
                                permissionLauncher.launch(arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION))
                            }
                        } else {
                            mapState.disableAutoShare()
                        }
                    },
                    onDeleteFriendRequest = { friend ->
                        friendToDelete = friend
                        showDeleteConfirmation = true
                    },
                    onSelectFriend = { pubkey, lat, lng -> selectAndCenterOnFriend(pubkey, lat, lng) }
                )
            }
        ) {
            // Map content
            MapCanvas(
                state = mapState,
                styleUrl = styleUrl,
                isDarkTheme = isDarkTheme,
                sheetPeekHeightPx = sheetPeekHeightPx,
                statusBarHeight = statusBarHeight,
                onFriendTapped = { pubkey, lat, lng -> selectAndCenterOnFriend(pubkey, lat, lng) },
                onShowAbout = { showAboutDialog = true }
            )
        }

        // Map style picker sheet
        if (showStylePicker) {
            ModalBottomSheet(
                onDismissRequest = { showStylePicker = false },
                sheetState = rememberModalBottomSheetState()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 32.dp)
                ) {
                    Text(
                        text = "Map Style",
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                    )
                    MapStyle.entries.forEach { style ->
                        ListItem(
                            headlineContent = { Text(style.displayName) },
                            trailingContent = {
                                if (style == selectedMapStyle) {
                                    Icon(
                                        imageVector = Icons.Default.Check,
                                        contentDescription = "Selected"
                                    )
                                }
                            },
                            modifier = Modifier.clickable {
                                selectedMapStyle = style
                                showStylePicker = false
                            }
                        )
                    }
                }
            }
        }

        // Server URL dialog
        if (showServerUrlDialog) {
            var serverUrlInput by remember { mutableStateOf(identityStore.serverUrl ?: "") }
            var isValidating by remember { mutableStateOf(false) }
            var validationResult by remember { mutableStateOf<ServerValidationResult?>(null) }
            val http = remember { TransponderHttp() }

            // Debounced validation
            LaunchedEffect(serverUrlInput) {
                val url = serverUrlInput.trim()
                if (url.isEmpty()) {
                    validationResult = null
                    return@LaunchedEffect
                }
                // Reset and wait for debounce
                validationResult = null
                isValidating = true
                kotlinx.coroutines.delay(500)
                validationResult = http.validateServer(url)
                isValidating = false
            }

            val isValid = validationResult is ServerValidationResult.Valid
            val canSave = serverUrlInput.isBlank() || isValid

            AlertDialog(
                onDismissRequest = { showServerUrlDialog = false },
                title = { Text("Server URL") },
                text = {
                    Column {
                        OutlinedTextField(
                            value = serverUrlInput,
                            onValueChange = { serverUrlInput = it },
                            label = { Text("URL") },
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
                        onClick = {
                            val url = serverUrlInput.trim()
                            identityStore.serverUrl = url.ifEmpty { null }
                            showServerUrlDialog = false
                        },
                        enabled = canSave && !isValidating
                    ) {
                        Text("Save")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showServerUrlDialog = false }) {
                        Text("Cancel")
                    }
                }
            )
        }

        // Delete confirmation dialog
        if (showDeleteConfirmation && friendToDelete != null) {
            AlertDialog(
                onDismissRequest = {
                    showDeleteConfirmation = false
                    friendToDelete = null
                },
                title = { Text("Delete Friend") },
                text = { Text("Are you sure you want to delete ${friendToDelete?.name}?") },
                confirmButton = {
                    TextButton(
                        onClick = {
                            friendToDelete?.let { friend -> mapState.deleteFriend(friend) }
                            showDeleteConfirmation = false
                            friendToDelete = null
                        }
                    ) {
                        Text("Delete", color = MaterialTheme.colorScheme.error)
                    }
                },
                dismissButton = {
                    TextButton(
                        onClick = {
                            showDeleteConfirmation = false
                            friendToDelete = null
                        }
                    ) {
                        Text("Cancel")
                    }
                }
            )
        }

        // Edit friend name dialog
        if (showEditFriendNameDialog && selectedFriend != null) {
            EditNameDialog(
                currentName = selectedFriend!!.name,
                onSave = { newName ->
                    try {
                        uniffi.transponder_core.updateFriend(
                            selectedFriend!!.pubkey,
                            null,
                            null,
                            newName
                        )
                        // Clear cached marker icon so it regenerates with new initial
                        markerIconCache.remove(selectedFriend!!.pubkey)
                        refreshFriends()
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                },
                onDismiss = { showEditFriendNameDialog = false }
            )
        }

        // Edit profile name dialog
        if (showEditProfileNameDialog) {
            EditNameDialog(
                currentName = mapState.myName,
                label = "Your name",
                onSave = { newName ->
                    mapState.myName = newName
                    identityStore.displayName = newName
                },
                onDismiss = { showEditProfileNameDialog = false }
            )
        }

        // About/Licenses bottom sheet (triggered by © button on map)
        if (showAboutDialog) {
            AboutSheet(
                onDismiss = { showAboutDialog = false }
            )
        }

        // Add Friend fullscreen overlay
        if (showAddFriend) {
            AddFriendScreen(
                identityStore = identityStore,
                onAddFriend = { link ->
                    scope.launch {
                        try {
                            val parsed = uniffi.transponder_core.parseFriendLink(link)
                            val syncService = LocationSyncService(identityStore)
                            val result = syncService.addFriendAndUploadLocation(
                                pubkey = parsed.pubkey,
                                server = parsed.server,
                                name = parsed.name,
                                locationDeferred = addFriendLocationDeferred
                            )
                            addFriendLocationDeferred = null  // Clear after use
                            when (result) {
                                is LocationSyncService.AddFriendResult.AddFriendFailed -> {
                                    android.util.Log.e("MapScreen", "Failed to add friend: ${result.message}")
                                }
                                is LocationSyncService.AddFriendResult.SuccessUploadFailed -> {
                                    android.util.Log.w("MapScreen", "Friend added but upload failed: ${result.message}")
                                }
                                else -> {}
                            }
                            // Refresh friends list
                            refreshFriends()
                            // New recipient may flip the gate; start the service now
                            LocationUploadService.poke(context)
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    }
                },
                onComplete = {
                    refreshFriends()
                    addFriendLocationDeferred = null  // Clear on dismiss too
                    // Fetch friend locations in background - they may have uploaded when adding us
                    scope.launch {
                        val syncService = LocationSyncService(identityStore)
                        syncService.fetchTrackedFriends()
                        refreshFriends()
                    }
                },
                onDismiss = {
                    showAddFriend = false
                    addFriendLocationDeferred = null  // Clear on dismiss
                }
            )
        }

        // Location Settings fullscreen overlay
        if (showLocationSettings) {
            LocationSettingsScreen(
                identityStore = identityStore,
                onDismiss = { showLocationSettings = false }
            )
        }
    }
}



/** Location data for display */
data class LocationDisplayData(
    val lat: Double,
    val lng: Double,
    val accuracy: Float,
    val timestamp: Long,
    val city: City?
)


// QR Code generation helper
