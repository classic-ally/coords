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
import androidx.compose.ui.draw.rotate
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
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Refresh
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
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.Point
import uniffi.transponder_core.Friend
import uniffi.transponder_core.Location as CoreLocation
import uniffi.transponder_core.Identity
import uniffi.transponder_core.generateFriendLink
import uniffi.transponder_core.findNearestCityInRegion
import uniffi.transponder_core.City
import sh.bentley.transponder.components.FriendRow
import sh.bentley.transponder.components.FriendList
import sh.bentley.transponder.components.SheetHeader
import sh.bentley.transponder.components.QrCodeView
import sh.bentley.transponder.sheets.FriendDetailSheet
import sh.bentley.transponder.sheets.MyLinkSheet
import sh.bentley.transponder.sheets.ProfileSheet
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

/** Pending camera actions for deferred execution after sheet layout */
sealed class CameraAction {
    data class CenterOn(val target: LatLng) : CameraAction()
    data object FitAllFriends : CameraAction()
    data object CenterOnMyLocation : CameraAction()
}

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
    var myName by remember { mutableStateOf(identityStore.displayName ?: "Me") }

    // Check if auto-share should be enabled (requires both setting AND permission)
    val hasBackgroundPermission = LocationSyncWorker.hasBackgroundLocationPermission(context)
    var autoShareEnabled by remember {
        mutableStateOf(
            if (identityStore.autoShareEnabled && !hasBackgroundPermission) {
                // Permission was revoked, update stored value
                identityStore.autoShareEnabled = false
                false
            } else {
                identityStore.autoShareEnabled && hasBackgroundPermission
            }
        )
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

    var currentLocationTimestamp by remember { mutableStateOf<Long?>(null) }
    var currentAccuracy by remember { mutableStateOf(0f) }
    var serverLocation by remember { mutableStateOf<LocationDisplayData?>(null) }
    var isUploading by remember { mutableStateOf(false) }
    var isFetchingFriends by remember { mutableStateOf(false) }
    var isFetchingServerLocation by remember { mutableStateOf(false) }
    var serverVersion by remember { mutableStateOf<String?>(null) }
    var pendingCameraAction by remember { mutableStateOf<CameraAction?>(null) }
    var hasSetInitialBounds by remember { mutableStateOf(false) }
    var isInitialLocationLoading by remember { mutableStateOf(true) }
    var mapBearing by remember { mutableStateOf(0.0) }
    var mapRef by remember { mutableStateOf<MapLibreMap?>(null) }
    var mapViewRef by remember { mutableStateOf<MapView?>(null) }
    var styleRef by remember { mutableStateOf<Style?>(null) }
    var currentStyleUrl by remember { mutableStateOf<String?>(null) }
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
                    currentLocationTimestamp = location.time
                    currentAccuracy = location.accuracy
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
                serverVersion = result.info.version
            }
            is ServerValidationResult.Invalid -> {
                serverVersion = null
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
                autoShareEnabled = true
                identityStore.autoShareEnabled = true
                pendingAutoShareEnable = false
                requestNotificationPermissionIfNeeded()
                LocationUploadService.poke(context)
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
                currentLocationTimestamp = location.time
                currentAccuracy = location.accuracy

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

    // Update map annotations when location or friends change
    LaunchedEffect(mapState.currentLocation, currentAccuracy, styleRef, friends) {
        val style = styleRef ?: return@LaunchedEffect

        // Generate and add icons to the style for each friend
        friends.filter { it.fetchFrom && it.location != null }.forEach { friend ->
            val iconId = "icon-friend-${friend.pubkey}"
            if (style.getImage(iconId) == null) {
                val icon = getFriendMarkerIcon(friend.pubkey, friend.name, friend.color)
                style.addImage(iconId, icon)
            }
        }

        // Add self icon (plain blue dot)
        if (style.getImage("icon-self") == null) {
            style.addImage("icon-self", generateSelfMarkerIcon())
        }

        // Build features for location markers
        val dotFeatures = mutableListOf<Feature>()

        // Add self location
        mapState.currentLocation?.let { loc ->
            val point = Point.fromLngLat(loc.longitude, loc.latitude)
            val feature = Feature.fromGeometry(point)
            feature.addStringProperty("type", "self")
            feature.addStringProperty("iconId", "icon-self")
            feature.addStringProperty("color", SELF_COLOR)
            feature.addNumberProperty("accuracy", currentAccuracy)
            dotFeatures.add(feature)
        }

        // Add friend locations (only those we're tracking)
        friends.filter { it.fetchFrom }.forEach { friend ->
            friend.location?.let { loc ->
                val point = Point.fromLngLat(loc.longitude, loc.latitude)
                val feature = Feature.fromGeometry(point)
                feature.addStringProperty("type", "friend")
                feature.addStringProperty("pubkey", friend.pubkey)
                feature.addStringProperty("iconId", "icon-friend-${friend.pubkey}")
                feature.addStringProperty("color", friend.color)
                feature.addNumberProperty("accuracy", loc.accuracy)
                dotFeatures.add(feature)
            }
        }

        // Update or create the GeoJSON source
        val existingSource = style.getSourceAs<GeoJsonSource>(SOURCE_LOCATIONS)
        if (existingSource != null) {
            existingSource.setGeoJson(FeatureCollection.fromFeatures(dotFeatures))
        } else {
            // First time: create source and layers
            style.addSource(GeoJsonSource(SOURCE_LOCATIONS, FeatureCollection.fromFeatures(dotFeatures)))

            // Accuracy circles layer (rendered first, so underneath dots)
            // Uses per-feature color from the "color" property
            style.addLayer(
                CircleLayer(LAYER_ACCURACY, SOURCE_LOCATIONS).withProperties(
                    PropertyFactory.circleRadius(
                        Expression.interpolate(
                            Expression.exponential(2),
                            Expression.zoom(),
                            Expression.stop(0, Expression.division(Expression.get("accuracy"), Expression.literal(32768.0))),
                            Expression.stop(15, Expression.get("accuracy")),
                            Expression.stop(22, Expression.product(Expression.get("accuracy"), Expression.literal(128.0)))
                        )
                    ),
                    PropertyFactory.circleColor(Expression.get("color")),
                    PropertyFactory.circleOpacity(0.2f),
                    PropertyFactory.circleStrokeWidth(2f),
                    PropertyFactory.circleStrokeColor(Expression.get("color"))
                )
            )

            // Marker icons layer (rendered on top using generated bitmaps)
            style.addLayer(
                SymbolLayer(LAYER_DOTS, SOURCE_LOCATIONS).withProperties(
                    PropertyFactory.iconImage(Expression.get("iconId")),
                    PropertyFactory.iconAllowOverlap(true),
                    PropertyFactory.iconIgnorePlacement(true)
                )
            )
        }
    }

    // Update selected friend label on map
    LaunchedEffect(selectedFriend, styleRef, isDarkTheme) {
        val style = styleRef ?: return@LaunchedEffect

        val features = mutableListOf<Feature>()
        selectedFriend?.location?.let { loc ->
            val point = Point.fromLngLat(loc.longitude, loc.latitude)
            val feature = Feature.fromGeometry(point)
            feature.addStringProperty("name", selectedFriend!!.name)
            features.add(feature)
        }

        val existingSource = style.getSourceAs<GeoJsonSource>(SOURCE_SELECTED)
        if (existingSource != null) {
            existingSource.setGeoJson(FeatureCollection.fromFeatures(features))
        } else {
            // Create source and label layer
            style.addSource(GeoJsonSource(SOURCE_SELECTED, FeatureCollection.fromFeatures(features)))

            val textColor = if (isDarkTheme) "#FFFFFF" else "#000000"
            val haloColor = if (isDarkTheme) "#000000" else "#FFFFFF"

            style.addLayer(
                SymbolLayer(LAYER_SELECTED_LABEL, SOURCE_SELECTED).withProperties(
                    PropertyFactory.textField(Expression.get("name")),
                    PropertyFactory.textSize(14f),
                    PropertyFactory.textColor(textColor),
                    PropertyFactory.textHaloColor(haloColor),
                    PropertyFactory.textHaloWidth(2f),
                    PropertyFactory.textOffset(arrayOf(0f, -1.5f)),
                    PropertyFactory.textAnchor("bottom"),
                    PropertyFactory.textFont(arrayOf("Noto Sans Bold"))
                )
            )
        }
    }

    // Trigger initial fit when map is ready
    LaunchedEffect(mapRef) {
        if (mapRef != null && !hasSetInitialBounds) {
            pendingCameraAction = CameraAction.FitAllFriends
            hasSetInitialBounds = true
        }
    }

    // Execute pending camera actions
    // Account for partial sheet covering bottom 40% of screen
    LaunchedEffect(pendingCameraAction) {
        val action = pendingCameraAction ?: return@LaunchedEffect
        val map = mapRef ?: return@LaunchedEffect

        // Bottom padding to offset center into visible area above sheet
        val bottomPadding = sheetPeekHeightPx.toDouble()

        when (action) {
            is CameraAction.CenterOn -> {
                // Use CameraPosition with bottom padding to offset the focal point
                val cameraPosition = CameraPosition.Builder()
                    .target(action.target)
                    .zoom(15.0)
                    .padding(0.0, 0.0, 0.0, bottomPadding)
                    .build()
                map.animateCamera(
                    CameraUpdateFactory.newCameraPosition(cameraPosition),
                    CAMERA_ANIMATION_DURATION_MS
                )
            }
            is CameraAction.FitAllFriends -> {
                val friendLocations = friends.mapNotNull { friend ->
                    friend.location?.let { LatLng(it.latitude, it.longitude) }
                }

                when {
                    friendLocations.size >= 2 -> {
                        // Multiple locations: fit bounds
                        val boundsBuilder = LatLngBounds.Builder()
                        friendLocations.forEach { boundsBuilder.include(it) }
                        val bounds = boundsBuilder.build()
                        // Add extra bottom padding to account for sheet
                        val boundsBottomPadding = sheetPeekHeightPx.toInt() + 100
                        map.animateCamera(
                            CameraUpdateFactory.newLatLngBounds(bounds, 100, 100, 100, boundsBottomPadding),
                            CAMERA_ANIMATION_DURATION_MS
                        )
                    }
                    friendLocations.size == 1 -> {
                        // Single location: center on it with reasonable zoom
                        val cameraPosition = CameraPosition.Builder()
                            .target(friendLocations.first())
                            .zoom(12.0)
                            .padding(0.0, 0.0, 0.0, bottomPadding)
                            .build()
                        map.animateCamera(
                            CameraUpdateFactory.newCameraPosition(cameraPosition),
                            CAMERA_ANIMATION_DURATION_MS
                        )
                    }
                    else -> {
                        // No locations: show world view
                        map.animateCamera(
                            CameraUpdateFactory.newLatLngZoom(LatLng(20.0, 0.0), 1.0),
                            CAMERA_ANIMATION_DURATION_MS
                        )
                    }
                }
            }
            is CameraAction.CenterOnMyLocation -> {
                val loc = mapState.currentLocation ?: return@LaunchedEffect
                // Use CameraPosition with bottom padding to offset the focal point
                val cameraPosition = CameraPosition.Builder()
                    .target(loc)
                    .zoom(15.0)
                    .padding(0.0, 0.0, 0.0, bottomPadding)
                    .build()
                map.animateCamera(
                    CameraUpdateFactory.newCameraPosition(cameraPosition),
                    CAMERA_ANIMATION_DURATION_MS
                )
            }
        }
        pendingCameraAction = null
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
            pendingCameraAction = CameraAction.CenterOn(LatLng(lat, lng))
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
                pendingCameraAction = CameraAction.FitAllFriends
            } else if (mapState.selectedFriendPubkey != null) {
                mapState.selectedFriendPubkey = null
                pendingCameraAction = CameraAction.FitAllFriends
            }
        }

        ThreeStateBottomSheet(
            sheetState = sheetState,
            handleHeight = handleHeight,
            partialHeight = sheetPeekHeight,
            fullHeight = fullHeight,
            screenHeight = screenHeight,
            sheetContent = {
                when {
                    showProfile -> {
                        // My profile view
                        val currentLocationData = mapState.currentLocation?.let { loc ->
                            LocationDisplayData(
                                lat = loc.latitude,
                                lng = loc.longitude,
                                accuracy = currentAccuracy,
                                timestamp = currentLocationTimestamp ?: System.currentTimeMillis(),
                                city = findNearestCityInRegion(loc.latitude, loc.longitude)
                            )
                        }
                        ProfileSheet(
                            name = myName,
                            identityStore = identityStore,
                            currentLocation = currentLocationData,
                            serverLocation = serverLocation,
                            showServerLocation = mapState.showServerLocation,
                            onShowServerLocationChange = { newValue ->
                                if (isFetchingServerLocation) return@ProfileSheet
                                scope.launch {
                                    if (newValue) {
                                        // Fetch our location from the server first
                                        isFetchingServerLocation = true
                                        when (val result = locationSyncService.fetchSelfLocation()) {
                                            is LocationSyncService.FetchResult.Success -> {
                                                val loc = result.locations.firstOrNull()?.location
                                                if (loc != null) {
                                                    serverLocation = LocationDisplayData(
                                                        lat = loc.latitude,
                                                        lng = loc.longitude,
                                                        accuracy = loc.accuracy,
                                                        timestamp = loc.timestamp.toLong(),
                                                        city = findNearestCityInRegion(loc.latitude, loc.longitude)
                                                    )
                                                    mapState.showServerLocation = true
                                                } else {
                                                    android.widget.Toast.makeText(context, "No location published yet", android.widget.Toast.LENGTH_SHORT).show()
                                                }
                                            }
                                            is LocationSyncService.FetchResult.Error -> {
                                                android.widget.Toast.makeText(context, result.message, android.widget.Toast.LENGTH_SHORT).show()
                                            }
                                        }
                                        isFetchingServerLocation = false
                                    } else {
                                        // Refresh current location when switching back to show it
                                        mapState.showServerLocation = false
                                        val result = requestLocation(context, LocationFreshness.CACHED_OKAY, LocationSettings.from(identityStore))
                                        result.location?.let { location ->
                                            mapState.currentLocation = LatLng(location.latitude, location.longitude)
                                            currentLocationTimestamp = location.time
                                            currentAccuracy = location.accuracy
                                        }
                                    }
                                }
                            },
                            isFetchingServerLocation = isFetchingServerLocation,
                            autoShareEnabled = autoShareEnabled,
                            onAutoShareEnabledChange = { enabled ->
                                if (enabled) {
                                    // User wants to enable - check permission first
                                    if (LocationSyncWorker.hasBackgroundLocationPermission(context)) {
                                        autoShareEnabled = true
                                        identityStore.autoShareEnabled = true
                                        LocationSyncWorker.schedule(context)
                                        requestNotificationPermissionIfNeeded()
                                        LocationUploadService.poke(context)
                                    } else {
                                        // Need to request permission
                                        pendingAutoShareEnable = true
                                        permissionLauncher.launch(
                                            arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                                        )
                                    }
                                } else {
                                    // Disable auto-share
                                    autoShareEnabled = false
                                    identityStore.autoShareEnabled = false
                                    LocationSyncWorker.cancel(context)
                                    LocationUploadService.poke(context)
                                }
                            },
                            isUploading = isUploading,
                            onUpload = {
                                scope.launch {
                                    isUploading = true
                                    // Get fresh location before uploading
                                    val locationResult = requestLocation(context, LocationFreshness.ALWAYS_FRESH, LocationSettings.from(identityStore))
                                    val location = locationResult.location
                                    if (location == null) {
                                        android.widget.Toast.makeText(context, "Could not get current location", android.widget.Toast.LENGTH_SHORT).show()
                                        isUploading = false
                                        return@launch
                                    }
                                    // Update current location state
                                    mapState.currentLocation = LatLng(location.latitude, location.longitude)
                                    currentLocationTimestamp = location.time
                                    currentAccuracy = location.accuracy

                                    when (val r = repo.push(location, locationResult.source)) {
                                        is LocationRepository.Result.Uploaded -> {
                                            // Update server location to match what we uploaded
                                            serverLocation = LocationDisplayData(
                                                lat = location.latitude,
                                                lng = location.longitude,
                                                accuracy = location.accuracy,
                                                timestamp = location.time,
                                                city = findNearestCityInRegion(location.latitude, location.longitude)
                                            )
                                        }
                                        is LocationRepository.Result.Skipped -> {
                                            android.widget.Toast.makeText(context, "No friends to share with", android.widget.Toast.LENGTH_SHORT).show()
                                        }
                                        is LocationRepository.Result.Error -> {
                                            android.widget.Toast.makeText(context, r.message, android.widget.Toast.LENGTH_SHORT).show()
                                        }
                                        is LocationRepository.Result.NoLocation -> {}
                                    }
                                    isUploading = false
                                }
                            },
                            onDismiss = {
                                showProfile = false
                                pendingCameraAction = CameraAction.FitAllFriends
                            },
                            onNameEdit = {
                                showEditProfileNameDialog = true
                            },
                            onEditServerUrl = {
                                showServerUrlDialog = true
                            },
                            onLocationSettings = {
                                showLocationSettings = true
                            },
                            onShowAbout = { showAboutDialog = true },
                            serverVersion = serverVersion
                        )
                    }
                    selectedFriend != null -> {
                        // Friend detail view
                        FriendDetailSheet(
                            friend = selectedFriend!!,
                            onDismiss = {
                                mapState.selectedFriendPubkey = null
                                pendingCameraAction = CameraAction.FitAllFriends
                            },
                            onNameEdit = {
                                showEditFriendNameDialog = true
                            },
                            onToggleShare = { mapState.toggleShare(selectedFriend!!) },
                            onToggleFetch = { mapState.toggleFetch(selectedFriend!!) },
                            onDelete = {
                                mapState.deleteFriend(selectedFriend!!)
                                pendingCameraAction = CameraAction.FitAllFriends
                            }
                        )
                    }
                    else -> {
                        // Friends list view
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(horizontal = 16.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "Friends",
                                    style = MaterialTheme.typography.titleLarge
                                )
                                Row {
                                    // Compass - only visible when map is rotated
                                    if (mapBearing != 0.0) {
                                        IconButton(
                                            onClick = {
                                                mapRef?.let { map ->
                                                    map.animateCamera(
                                                        CameraUpdateFactory.bearingTo(0.0),
                                                        CAMERA_ANIMATION_DURATION_MS
                                                    )
                                                }
                                            }
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Explore,
                                                contentDescription = "Reset to north",
                                                // Offset by -45 because Explore icon points NE by default
                                                modifier = Modifier.rotate(-mapBearing.toFloat() - 45f)
                                            )
                                        }
                                    }

                                    if (mapState.isEditMode) {
                                        // Refresh button in edit mode
                                        IconButton(
                                            onClick = {
                                                scope.launch {
                                                    fetchFriendsIfNeeded(force = true)
                                                }
                                            },
                                            enabled = !isFetchingFriends
                                        ) {
                                            if (isFetchingFriends) {
                                                CircularProgressIndicator(
                                                    modifier = Modifier.size(24.dp),
                                                    strokeWidth = 2.dp
                                                )
                                            } else {
                                                Icon(
                                                    imageVector = Icons.Default.Refresh,
                                                    contentDescription = "Refresh friends"
                                                )
                                            }
                                        }
                                        // Done editing
                                        IconButton(onClick = { mapState.isEditMode = false }) {
                                            Icon(
                                                imageVector = Icons.Default.Check,
                                                contentDescription = "Done editing"
                                            )
                                        }
                                    } else {
                                        // Map style selector
                                        IconButton(onClick = { showStylePicker = true }) {
                                            Icon(
                                                imageVector = Icons.Default.Map,
                                                contentDescription = "Change map style"
                                            )
                                        }
                                        // Add Friend button
                                        IconButton(onClick = {
                                            // Kick off location request early so GPS can warm up
                                            addFriendLocationDeferred = scope.async {
                                                requestLocation(context, LocationFreshness.ALWAYS_FRESH, LocationSettings.from(identityStore)).location
                                            }
                                            showAddFriend = true
                                        }) {
                                            Icon(
                                                imageVector = Icons.Default.Add,
                                                contentDescription = "Add Friend"
                                            )
                                        }
                                        // Edit mode toggle
                                        IconButton(onClick = { mapState.isEditMode = true }) {
                                            Icon(
                                                imageVector = Icons.Default.Edit,
                                                contentDescription = "Edit friends"
                                            )
                                        }
                                        // Profile button
                                        IconButton(
                                            onClick = {
                                                showProfile = true
                                                pendingCameraAction = CameraAction.CenterOnMyLocation
                                                scope.launch { sheetState.animateToAnchor(SheetAnchor.Partial) }
                                            }
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Person,
                                                contentDescription = "Profile"
                                            )
                                        }
                                    }
                                }
                            }

                            FriendList(
                                items = friendsWithCities,
                                currentLocation = mapState.currentLocation,
                                isEditMode = mapState.isEditMode,
                                modifier = Modifier.weight(1f),
                                bottomPadding = listBottomPadding,
                                onClick = { friend ->
                                    if (!mapState.isEditMode) {
                                        friend.location?.let { loc ->
                                            selectAndCenterOnFriend(friend.pubkey, loc.latitude, loc.longitude)
                                        }
                                    }
                                },
                                onToggleShare = { friend -> mapState.toggleShare(friend) },
                                onToggleFetch = { friend -> mapState.toggleFetch(friend) },
                                onDelete = { friend ->
                                    friendToDelete = friend
                                    showDeleteConfirmation = true
                                }
                            )
                        }
                    }
                }
            }
        ) {
            // Map content
            Box(modifier = Modifier.fillMaxSize()) {
                AndroidView(
                    factory = { ctx ->
                        MapLibre.getInstance(ctx)
                        MapView(ctx).apply {
                            onCreate(null)
                            onStart()
                            onResume()
                            mapViewRef = this
                            getMapAsync { map ->
                                mapRef = map
                                // Prefetch tiles 4 zoom levels lower for smoother panning
                                map.setPrefetchZoomDelta(4)
                                // Disable built-in compass and attribution (we have our own)
                                map.uiSettings.isCompassEnabled = false
                                map.uiSettings.isAttributionEnabled = false
                                map.setStyle(styleUrl) { style ->
                                    currentStyleUrl = styleUrl
                                    styleRef = style
                                }
                                // Track bearing for compass
                                map.addOnCameraIdleListener {
                                    mapBearing = map.cameraPosition.bearing
                                }
                                // Handle tap on friend markers
                                map.addOnMapClickListener { latLng ->
                                    val screenPoint = map.projection.toScreenLocation(latLng)
                                    val features = map.queryRenderedFeatures(screenPoint, LAYER_DOTS)
                                    val friendFeature = features.firstOrNull {
                                        it.getStringProperty("type") == "friend"
                                    }
                                    if (friendFeature != null) {
                                        val pubkey = friendFeature.getStringProperty("pubkey")
                                        val point = friendFeature.geometry() as? Point
                                        if (pubkey != null && point != null) {
                                            selectAndCenterOnFriend(pubkey, point.latitude(), point.longitude())
                                        }
                                        true
                                    } else {
                                        mapState.selectedFriendPubkey = null
                                        false
                                    }
                                }
                            }
                        }
                    },
                    update = { _ ->
                        // Only update style when theme actually changes
                        if (currentStyleUrl != null && currentStyleUrl != styleUrl) {
                            mapRef?.setStyle(styleUrl) { style ->
                                currentStyleUrl = styleUrl
                                styleRef = style
                            }
                        }
                    },
                    modifier = Modifier.fillMaxSize()
                )

                // Dim overlay when viewing friend with no location
                if (selectedFriend != null && selectedFriend?.location == null) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.5f))
                    )
                }

                // Copyright/attribution button
                Text(
                    text = "©",
                    style = MaterialTheme.typography.titleLarge,
                    color = if (isDarkTheme) ComposeColor.White else ComposeColor.Black,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(
                            start = 12.dp,
                            top = statusBarHeight + 8.dp
                        )
                        .clickable { showAboutDialog = true }
                )
            }
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
                currentName = myName,
                label = "Your name",
                onSave = { newName ->
                    myName = newName
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
