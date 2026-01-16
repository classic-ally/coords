package sh.bentley.transponder

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import uniffi.transponder_core.LicenseGroup
import com.mikepenz.aboutlibraries.Libs
import com.mikepenz.aboutlibraries.util.withContext
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
import androidx.compose.ui.res.painterResource
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
import uniffi.transponder_core.getLicenses
import uniffi.transponder_core.getVersion
import uniffi.transponder_core.listFriends
import uniffi.transponder_core.getShareRecipients
import uniffi.transponder_core.mockFriends as coreMockFriends
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.util.Base64
import java.net.URLEncoder
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.asImageBitmap
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.QrCode2

/** Friend with precomputed display data to avoid expensive calculations during scroll */
private data class FriendDisplayData(
    val friend: Friend,
    val city: City?
)

// Colors for map annotations
private const val SELF_COLOR = "#4285F4" // Google blue
private const val FRIEND_COLOR = "#34A853" // Google green

// Source and layer IDs for native layers
private const val SOURCE_LOCATIONS = "locations-source"
private const val SOURCE_SELECTED = "selected-source"
private const val LAYER_ACCURACY = "accuracy-layer"
private const val LAYER_DOTS = "dots-layer"
private const val LAYER_SELECTED_LABEL = "selected-label-layer"

// Camera animation
private const val CAMERA_ANIMATION_DURATION_MS = 350

// Marker icon size in pixels (for generated bitmaps)
private const val MARKER_ICON_SIZE = 56

/** Cache for generated friend marker icons, keyed by pubkey */
private val markerIconCache = mutableMapOf<String, Bitmap>()

/**
 * Generate a circular marker icon with an initial letter.
 */
private fun generateMarkerIcon(
    initial: String,
    fillColor: Int,
    strokeColor: Int = Color.WHITE,
    textColor: Int = Color.WHITE
): Bitmap {
    val size = MARKER_ICON_SIZE
    val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)

    val centerX = size / 2f
    val centerY = size / 2f
    val radius = size / 2f - 4f // Leave room for stroke

    // Fill circle
    val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = fillColor
        style = Paint.Style.FILL
    }
    canvas.drawCircle(centerX, centerY, radius, fillPaint)

    // Stroke circle
    val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = strokeColor
        style = Paint.Style.STROKE
        strokeWidth = 4f
    }
    canvas.drawCircle(centerX, centerY, radius, strokePaint)

    // Draw initial letter
    val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = textColor
        textSize = size * 0.45f
        typeface = Typeface.DEFAULT_BOLD
        textAlign = Paint.Align.CENTER
    }
    // Center text vertically
    val textY = centerY - (textPaint.descent() + textPaint.ascent()) / 2
    canvas.drawText(initial, centerX, textY, textPaint)

    return bitmap
}

/**
 * Get or create a marker icon for a friend using their name initial and color.
 */
private fun getFriendMarkerIcon(pubkey: String, name: String, colorHex: String): Bitmap {
    return markerIconCache.getOrPut(pubkey) {
        val initial = name.firstOrNull()?.toString() ?: "?"
        val color = Color.parseColor(colorHex)
        generateMarkerIcon(initial, color)
    }
}

/**
 * Generate self location marker (blue circle, no initial).
 */
private fun generateSelfMarkerIcon(): Bitmap {
    val size = MARKER_ICON_SIZE
    val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)

    val centerX = size / 2f
    val centerY = size / 2f
    val radius = size / 2f - 4f

    // Fill circle (blue)
    val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor(SELF_COLOR)
        style = Paint.Style.FILL
    }
    canvas.drawCircle(centerX, centerY, radius, fillPaint)

    // Stroke circle
    val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 4f
    }
    canvas.drawCircle(centerX, centerY, radius, strokePaint)

    return bitmap
}

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
private fun mockFriends(): List<Friend> = coreMockFriends()

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

    var selectedMapStyle by remember { mutableStateOf(MapStyle.BASIC) }
    var showStylePicker by remember { mutableStateOf(false) }
    var showServerUrlDialog by remember { mutableStateOf(false) }
    var showLocationSettings by remember { mutableStateOf(false) }
    var selectedFriendPubkey by remember { mutableStateOf<String?>(null) }
    var showProfile by remember { mutableStateOf(false) }
    var isEditMode by remember { mutableStateOf(false) }
    var friendToDelete by remember { mutableStateOf<Friend?>(null) }
    var showDeleteConfirmation by remember { mutableStateOf(false) }
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

    var currentLocation by remember { mutableStateOf<LatLng?>(null) }
    var currentLocationTimestamp by remember { mutableStateOf<Long?>(null) }
    var currentAccuracy by remember { mutableStateOf(0f) }
    var serverLocation by remember { mutableStateOf<LocationDisplayData?>(null) }
    var showServerLocation by remember { mutableStateOf(false) }
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
    var friends by remember {
        mutableStateOf(
            try {
                val realFriends = listFriends()
                if (realFriends.isEmpty() && BuildConfig.USE_MOCK_FRIENDS) {
                    mockFriends()
                } else {
                    realFriends
                }
            } catch (e: Exception) {
                if (BuildConfig.USE_MOCK_FRIENDS) mockFriends() else emptyList()
            }
        )
    }
    // Derive selectedFriend from the friends list so it stays in sync
    val selectedFriend: Friend? = selectedFriendPubkey?.let { pubkey ->
        friends.find { it.pubkey == pubkey }
    }

    val locationSyncService = remember { LocationSyncService(identityStore) }

    // Track when city database finishes loading to trigger recomputation
    var citiesLoaded by remember { mutableStateOf(CityDatabase.isLoaded) }
    LaunchedEffect(Unit) {
        while (!CityDatabase.isLoaded) {
            kotlinx.coroutines.delay(100)
        }
        citiesLoaded = true
    }

    // Helper function to refresh friends from storage
    fun refreshFriends() {
        friends = try {
            val realFriends = listFriends()
            if (realFriends.isEmpty() && BuildConfig.USE_MOCK_FRIENDS) {
                mockFriends()
            } else {
                realFriends
            }
        } catch (e: Exception) {
            if (BuildConfig.USE_MOCK_FRIENDS) mockFriends() else emptyList()
        }
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

            // Periodic upload if auto-share enabled
            if (identityStore.autoShareEnabled && getShareRecipients().isNotEmpty()) {
                val result = requestLocation(context, LocationFreshness.ALWAYS_FRESH, LocationSettings.from(identityStore))
                result.location?.let { location ->
                    currentLocation = LatLng(location.latitude, location.longitude)
                    currentLocationTimestamp = location.time
                    currentAccuracy = location.accuracy
                    locationSyncService.uploadLocation(
                        latitude = location.latitude,
                        longitude = location.longitude,
                        accuracy = location.accuracy,
                        timestamp = location.time
                    )
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

    // Precompute cities once, recompute only when friends change or cities finish loading
    val friendsWithCities = remember(friends, citiesLoaded) {
        friends.map { friend ->
            FriendDisplayData(
                friend = friend,
                city = friend.location?.let { loc ->
                    CityDatabase.findNearest(loc.latitude, loc.longitude)
                }
            )
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
                currentLocation = LatLng(location.latitude, location.longitude)
                currentLocationTimestamp = location.time
                currentAccuracy = location.accuracy

                // Auto-upload on app launch if enabled and we have share recipients
                if (identityStore.autoShareEnabled && getShareRecipients().isNotEmpty()) {
                    locationSyncService.uploadLocation(
                        latitude = location.latitude,
                        longitude = location.longitude,
                        accuracy = location.accuracy,
                        timestamp = location.time
                    )
                }
            }
            isInitialLocationLoading = false
        } else {
            isInitialLocationLoading = false
        }
    }

    // Update map annotations when location or friends change
    LaunchedEffect(currentLocation, currentAccuracy, styleRef, friends) {
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
        currentLocation?.let { loc ->
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
                val loc = currentLocation ?: return@LaunchedEffect
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
            selectedFriendPubkey = pubkey
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
            } else if (selectedFriendPubkey != null) {
                selectedFriendPubkey = null
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
                        val currentLocationData = currentLocation?.let { loc ->
                            LocationDisplayData(
                                lat = loc.latitude,
                                lng = loc.longitude,
                                accuracy = currentAccuracy,
                                timestamp = currentLocationTimestamp ?: System.currentTimeMillis(),
                                city = if (citiesLoaded) CityDatabase.findNearest(loc.latitude, loc.longitude) else null
                            )
                        }
                        ProfileContent(
                            name = myName,
                            identityStore = identityStore,
                            currentLocation = currentLocationData,
                            serverLocation = serverLocation,
                            showServerLocation = showServerLocation,
                            onShowServerLocationChange = { newValue ->
                                if (isFetchingServerLocation) return@ProfileContent
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
                                                        city = if (citiesLoaded) CityDatabase.findNearest(loc.latitude, loc.longitude) else null
                                                    )
                                                    showServerLocation = true
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
                                        showServerLocation = false
                                        val result = requestLocation(context, LocationFreshness.CACHED_OKAY, LocationSettings.from(identityStore))
                                        result.location?.let { location ->
                                            currentLocation = LatLng(location.latitude, location.longitude)
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
                                    currentLocation = LatLng(location.latitude, location.longitude)
                                    currentLocationTimestamp = location.time
                                    currentAccuracy = location.accuracy

                                    val result = locationSyncService.uploadLocation(
                                        latitude = location.latitude,
                                        longitude = location.longitude,
                                        accuracy = location.accuracy,
                                        timestamp = location.time
                                    )
                                    when (result) {
                                        is LocationSyncService.UploadResult.Success -> {
                                            // Update server location to match what we uploaded
                                            serverLocation = LocationDisplayData(
                                                lat = location.latitude,
                                                lng = location.longitude,
                                                accuracy = location.accuracy,
                                                timestamp = location.time,
                                                city = if (citiesLoaded) CityDatabase.findNearest(location.latitude, location.longitude) else null
                                            )
                                        }
                                        is LocationSyncService.UploadResult.Error -> {
                                            android.widget.Toast.makeText(context, result.message, android.widget.Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                    isUploading = false
                                }
                            },
                            onDismiss = {
                                showProfile = false
                                pendingCameraAction = CameraAction.FitAllFriends
                            },
                            onNameEdit = {
                                // TODO: implement name editing
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
                        FriendDetailContent(
                            friend = selectedFriend!!,
                            onDismiss = {
                                selectedFriendPubkey = null
                                pendingCameraAction = CameraAction.FitAllFriends
                            },
                            onNameEdit = {
                                // TODO: implement name editing
                            },
                            onToggleShare = {
                                try {
                                    uniffi.transponder_core.updateFriend(
                                        selectedFriend!!.pubkey,
                                        !selectedFriend!!.shareWith,
                                        null,
                                        null
                                    )
                                    refreshFriends()
                                } catch (e: Exception) {
                                    e.printStackTrace()
                                }
                            },
                            onToggleFetch = {
                                try {
                                    uniffi.transponder_core.updateFriend(
                                        selectedFriend!!.pubkey,
                                        null,
                                        !selectedFriend!!.fetchFrom,
                                        null
                                    )
                                    refreshFriends()
                                } catch (e: Exception) {
                                    e.printStackTrace()
                                }
                            },
                            onDelete = {
                                try {
                                    uniffi.transponder_core.removeFriend(selectedFriend!!.pubkey)
                                    selectedFriendPubkey = null
                                    refreshFriends()
                                    pendingCameraAction = CameraAction.FitAllFriends
                                } catch (e: Exception) {
                                    e.printStackTrace()
                                }
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

                                    if (isEditMode) {
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
                                        IconButton(onClick = { isEditMode = false }) {
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
                                        IconButton(onClick = { isEditMode = true }) {
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

                            LazyColumn(
                                modifier = Modifier.weight(1f),
                                contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = listBottomPadding)
                            ) {
                                items(friendsWithCities, key = { it.friend.pubkey }) { data ->
                                    FriendRow(
                                        friend = data.friend,
                                        city = data.city,
                                        currentLocation = currentLocation,
                                        isEditMode = isEditMode,
                                        onClick = {
                                            if (!isEditMode) {
                                                data.friend.location?.let { loc ->
                                                    selectAndCenterOnFriend(data.friend.pubkey, loc.latitude, loc.longitude)
                                                }
                                            }
                                        },
                                        onToggleShare = {
                                            try {
                                                uniffi.transponder_core.updateFriend(
                                                    data.friend.pubkey,
                                                    !data.friend.shareWith,
                                                    null,
                                                    null
                                                )
                                                refreshFriends()
                                            } catch (e: Exception) {
                                                e.printStackTrace()
                                            }
                                        },
                                        onToggleFetch = {
                                            try {
                                                uniffi.transponder_core.updateFriend(
                                                    data.friend.pubkey,
                                                    null,
                                                    !data.friend.fetchFrom,
                                                    null
                                                )
                                                refreshFriends()
                                            } catch (e: Exception) {
                                                e.printStackTrace()
                                            }
                                        },
                                        onDelete = {
                                            friendToDelete = data.friend
                                            showDeleteConfirmation = true
                                        }
                                    )
                                }
                            }
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
                                        selectedFriendPubkey = null
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
                            friendToDelete?.let { friend ->
                                try {
                                    uniffi.transponder_core.removeFriend(friend.pubkey)
                                    refreshFriends()
                                } catch (e: Exception) {
                                    e.printStackTrace()
                                }
                            }
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
                            friends = uniffi.transponder_core.listFriends()
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    }
                },
                onComplete = {
                    friends = uniffi.transponder_core.listFriends()
                    addFriendLocationDeferred = null  // Clear on dismiss too
                    // Fetch friend locations in background - they may have uploaded when adding us
                    scope.launch {
                        val syncService = LocationSyncService(identityStore)
                        syncService.fetchTrackedFriends()
                        friends = uniffi.transponder_core.listFriends()
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

@Composable
fun FriendRow(
    friend: Friend,
    city: City?,
    currentLocation: LatLng?,
    isEditMode: Boolean,
    onClick: () -> Unit,
    onToggleShare: () -> Unit,
    onToggleFetch: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier
) {
    // Distance is cheap to compute and depends on currentLocation which can change
    val distance = remember(friend.location, currentLocation) {
        friend.location?.let { loc ->
            currentLocation?.let { myLoc ->
                val friendLatLng = LatLng(loc.latitude, loc.longitude)
                myLoc.distanceTo(friendLatLng).toFloat()
            }
        }
    }

    ListItem(
        headlineContent = { Text(friend.name) },
        supportingContent = {
            if (isEditMode) {
                // Edit mode: show location age
                val loc = friend.location
                Text(text = if (loc != null) formatAge(loc.timestamp) else "No location")
            } else {
                // Normal mode: show city
                val cityText = city?.displayName() ?: ""
                Text(text = if (friend.location != null) cityText.ifBlank { "Unknown location" } else "No location")
            }
        },
        trailingContent = {
            if (isEditMode) {
                // Edit mode: show share/fetch toggles and delete
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onToggleShare) {
                        Icon(
                            imageVector = if (friend.shareWith) Icons.Filled.ArrowUpward else Icons.Outlined.ArrowUpward,
                            contentDescription = "Share with",
                            tint = if (friend.shareWith) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    IconButton(onClick = onToggleFetch) {
                        Icon(
                            imageVector = if (friend.fetchFrom) Icons.Filled.ArrowDownward else Icons.Outlined.ArrowDownward,
                            contentDescription = "Fetch from",
                            tint = if (friend.fetchFrom) ComposeColor(0xFF4CAF50) else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    IconButton(onClick = onDelete) {
                        Icon(
                            imageVector = Icons.Filled.RemoveCircle,
                            contentDescription = "Delete",
                            tint = MaterialTheme.colorScheme.error
                        )
                    }
                }
            } else {
                // Normal mode: show distance and time ago
                friend.location?.let { loc ->
                    val age = formatAge(loc.timestamp)
                    Column(horizontalAlignment = Alignment.End) {
                        if (distance != null) {
                            Text(
                                text = formatDistance(distance),
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                        Text(
                            text = age,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        },
        modifier = modifier.clickable(onClick = onClick)
    )
}

private fun formatAge(timestampMs: ULong): String {
    val now = System.currentTimeMillis().toULong()
    val diffMs = now - timestampMs
    val diffSec = diffMs / 1000u
    val diffMin = diffSec / 60u
    val diffHour = diffMin / 60u
    val diffDay = diffHour / 24u

    return when {
        diffMin < 1u -> "just now"
        diffMin < 60u -> "${diffMin}m ago"
        diffHour < 24u -> "${diffHour}h ago"
        else -> "${diffDay}d ago"
    }
}

private fun formatDistance(meters: Float): String {
    return when {
        meters < 1000 -> "${meters.toInt()} m"
        meters < 10000 -> String.format("%.1f km", meters / 1000)
        else -> "${(meters / 1000).toInt()} km"
    }
}

@Composable
fun FriendDetailContent(
    friend: Friend,
    onDismiss: () -> Unit,
    onNameEdit: () -> Unit,
    onToggleShare: () -> Unit,
    onToggleFetch: () -> Unit,
    onDelete: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    var showDeleteConfirmation by remember { mutableStateOf(false) }

    // Delete confirmation dialog
    if (showDeleteConfirmation) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirmation = false },
            title = { Text("Delete ${friend.name}?") },
            text = { Text("This will remove ${friend.name} from your friends list.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteConfirmation = false
                        onDelete()
                        onDismiss()
                    }
                ) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirmation = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    // Parse the friend's color for the avatar
    val avatarColor = remember(friend.color) {
        try { ComposeColor(android.graphics.Color.parseColor(friend.color)) }
        catch (e: Exception) { ComposeColor.Gray }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
    ) {
        // Name header with avatar, edit and close buttons
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // Avatar with friend's color
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .background(avatarColor, shape = androidx.compose.foundation.shape.CircleShape)
                        .border(1.5.dp, ComposeColor.White, shape = androidx.compose.foundation.shape.CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = friend.name.firstOrNull()?.toString() ?: "?",
                        color = ComposeColor.White,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                    )
                }
                Spacer(modifier = Modifier.size(8.dp))
                Text(
                    text = friend.name,
                    style = MaterialTheme.typography.titleLarge
                )
            }
            Row {
                IconButton(onClick = onNameEdit) {
                    Icon(
                        imageVector = Icons.Default.Edit,
                        contentDescription = "Edit name"
                    )
                }
                IconButton(onClick = onDismiss) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Close"
                    )
                }
            }
        }

        // Location info
        friend.location?.let { loc ->
            val city = CityDatabase.findNearest(loc.latitude, loc.longitude)
            val age = formatAge(loc.timestamp)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = city?.displayName() ?: "Unknown location",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = age,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // Open in maps button
            OutlinedButton(
                onClick = {
                    val geoUri = Uri.parse("geo:${loc.latitude},${loc.longitude}?q=${loc.latitude},${loc.longitude}(${friend.name})")
                    val intent = Intent(Intent.ACTION_VIEW, geoUri)
                    context.startActivity(intent)
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(
                    imageVector = Icons.Default.Navigation,
                    contentDescription = null,
                    modifier = Modifier.padding(end = 8.dp)
                )
                Text("Open in Maps")
            }
        } ?: Text(
            text = "No location available",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 12.dp)
        )

        HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

        // Share with friend toggle
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(end = 12.dp)
                )
                Text("Share with ${friend.name}")
            }
            Switch(
                checked = friend.shareWith,
                onCheckedChange = { onToggleShare() }
            )
        }

        // See friend toggle
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(end = 12.dp)
                )
                Text("See ${friend.name}")
            }
            Switch(
                checked = friend.fetchFrom,
                onCheckedChange = { onToggleFetch() }
            )
        }

        // Delete row - appears when both toggles are off
        if (!friend.shareWith && !friend.fetchFrom) {
            HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { showDeleteConfirmation = true }
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.Delete,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(end = 12.dp)
                )
                Text(
                    text = "Delete ${friend.name}",
                    color = MaterialTheme.colorScheme.error
                )
            }
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileContent(
    name: String,
    identityStore: IdentityStore,
    currentLocation: LocationDisplayData?,
    serverLocation: LocationDisplayData?,
    showServerLocation: Boolean,
    onShowServerLocationChange: (Boolean) -> Unit,
    isFetchingServerLocation: Boolean,
    autoShareEnabled: Boolean,
    onAutoShareEnabledChange: (Boolean) -> Unit,
    isUploading: Boolean,
    onUpload: () -> Unit,
    onDismiss: () -> Unit,
    onNameEdit: () -> Unit,
    onEditServerUrl: () -> Unit,
    onLocationSettings: () -> Unit,
    onShowAbout: () -> Unit,
    serverVersion: String?,
    modifier: Modifier = Modifier
) {
    // Determine which location to display based on toggle
    val displayLocation = if (showServerLocation) serverLocation else currentLocation
    var showShareDialog by remember { mutableStateOf(false) }

    // Generate friend link and QR code
    val identity = remember { identityStore.getIdentity() }
    val friendLink = remember(identity, identityStore.serverUrl, name) {
        identity?.let { id ->
            identityStore.serverUrl?.let { serverUrl ->
                generateFriendLink(id, serverUrl, name)
            }
        }
    }

    val qrBitmap = remember(friendLink) {
        friendLink?.let { link ->
            generateQrCode(link, 512)
        }
    }

    // Share dialog
    if (showShareDialog && friendLink != null) {
        ShareLinkDialog(
            link = friendLink,
            qrBitmap = qrBitmap,
            onDismiss = { showShareDialog = false }
        )
    }

    // Parse the user's color for the avatar
    val avatarColor = remember(identityStore.myColor) {
        identityStore.myColor?.let {
            try { ComposeColor(android.graphics.Color.parseColor(it)) }
            catch (e: Exception) { ComposeColor.Gray }
        } ?: ComposeColor.Gray
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
    ) {
        // Name header with avatar, upload, edit and close buttons
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // Avatar showing how others see you
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .background(avatarColor, shape = androidx.compose.foundation.shape.CircleShape)
                        .border(1.5.dp, ComposeColor.White, shape = androidx.compose.foundation.shape.CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = name.firstOrNull()?.toString() ?: "?",
                        color = ComposeColor.White,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                    )
                }
                Spacer(modifier = Modifier.size(8.dp))
                Text(
                    text = name,
                    style = MaterialTheme.typography.titleLarge
                )
            }
            Row {
                // Upload button
                IconButton(
                    onClick = onUpload,
                    enabled = !isUploading && currentLocation != null
                ) {
                    if (isUploading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(24.dp),
                            strokeWidth = 2.dp
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Default.CloudUpload,
                            contentDescription = "Upload location"
                        )
                    }
                }
                IconButton(onClick = onNameEdit) {
                    Icon(
                        imageVector = Icons.Default.Edit,
                        contentDescription = "Edit name"
                    )
                }
                IconButton(onClick = onDismiss) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Close"
                    )
                }
            }
        }

        // Location info - shows current or server based on toggle
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = displayLocation?.city?.displayName() ?: "Unknown location",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            // Only show time when viewing server location
            if (showServerLocation) {
                displayLocation?.let { loc ->
                    Text(
                        text = formatAge(loc.timestamp.toULong()),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

        // Master sharing toggle
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text("Share automatically")
                Text(
                    text = if (autoShareEnabled) "Sharing in background" else "Manual sharing only",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(
                checked = autoShareEnabled,
                onCheckedChange = onAutoShareEnabledChange
            )
        }

        // Show server location toggle
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text("Show server location")
                Text(
                    text = when {
                        isFetchingServerLocation -> "Fetching from server..."
                        showServerLocation && serverLocation != null -> "Showing server location"
                        else -> "Showing current location"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (isFetchingServerLocation) {
                CircularProgressIndicator(
                    modifier = Modifier.size(24.dp),
                    strokeWidth = 2.dp
                )
            } else {
                Switch(
                    checked = showServerLocation,
                    onCheckedChange = onShowServerLocationChange
                )
            }
        }

        HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

        // Share my link button
        OutlinedButton(
            onClick = { showShareDialog = true },
            modifier = Modifier.fillMaxWidth(),
            enabled = friendLink != null
        ) {
            Icon(
                imageVector = Icons.Default.QrCode2,
                contentDescription = null,
                modifier = Modifier.padding(end = 8.dp)
            )
            Text("Share My Link")
        }

        HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

        // Server URL
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(if (serverVersion != null) "Server v$serverVersion" else "Server")
                Text(
                    text = identityStore.serverUrl ?: "Not configured",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                )
            }
            IconButton(onClick = onEditServerUrl) {
                Icon(
                    imageVector = Icons.Default.Edit,
                    contentDescription = "Edit server",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // Location settings row
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onLocationSettings() }
                .padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Location Settings")
                Text(
                    text = "GPS timeout, accuracy thresholds",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                contentDescription = "Open",
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        // App and core version
        Text(
            text = "Coords Android v${BuildConfig.VERSION_NAME} · Core ${getVersion()}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .padding(top = 8.dp)
                .clickable { onShowAbout() }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutSheet(
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val coreLicenses = remember { getLicenses() }
    val androidLibs = remember { Libs.Builder().withContext(context).build() }
    val totalCorePackages = remember { coreLicenses.sumOf { it.packages.size } }
    val totalAndroidPackages = remember { androidLibs.libraries.size }

    // Navigation state: null = about, "core" = core deps, "android" = android deps, "map" = map data
    var currentView by remember { mutableStateOf<String?>(null) }
    var expandedLicenses by remember { mutableStateOf(setOf<String>()) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
        ) {
            // Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (currentView != null) {
                    IconButton(onClick = { currentView = null; expandedLicenses = setOf() }) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                    Text(
                        text = when (currentView) {
                            "core" -> "Core Dependencies"
                            "android" -> "Android Dependencies"
                            "map" -> "Map Data"
                            else -> ""
                        },
                        style = MaterialTheme.typography.titleLarge
                    )
                } else {
                    Text(
                        text = "About",
                        style = MaterialTheme.typography.titleLarge
                    )
                }
                Spacer(modifier = Modifier.weight(1f))
                TextButton(onClick = onDismiss) {
                    Text("Done")
                }
            }

            when (currentView) {
                null -> {
                    // About content
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        // AGPL Logo
                        androidx.compose.foundation.Image(
                            painter = painterResource(id = R.drawable.agpl_logo),
                            contentDescription = "AGPL v3 License",
                            modifier = Modifier.height(80.dp)
                        )

                        Spacer(modifier = Modifier.height(16.dp))

                        Text(
                            text = "Coords for Android",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text(
                            text = "v${BuildConfig.VERSION_NAME} · Core ${getVersion()}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        Spacer(modifier = Modifier.height(12.dp))

                        Text(
                            text = "© 2026 Allison Bentley",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = "Made with ❤️ for Helen",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        Spacer(modifier = Modifier.height(24.dp))

                        // Map Data row
                        HorizontalDivider()
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { currentView = "map" }
                                .padding(vertical = 16.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Map Data",
                                style = MaterialTheme.typography.bodyLarge
                            )
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                                contentDescription = "View map data attribution",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        // Core Dependencies row
                        HorizontalDivider()
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { currentView = "core" }
                                .padding(vertical = 16.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Core Dependencies",
                                style = MaterialTheme.typography.bodyLarge
                            )
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = "$totalCorePackages",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                                    contentDescription = "View core dependencies",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(start = 8.dp)
                                )
                            }
                        }

                        // Android Dependencies row
                        HorizontalDivider()
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { currentView = "android" }
                                .padding(vertical = 16.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Android Dependencies",
                                style = MaterialTheme.typography.bodyLarge
                            )
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = "$totalAndroidPackages",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                                    contentDescription = "View Android dependencies",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(start = 8.dp)
                                )
                            }
                        }
                        HorizontalDivider()
                    }
                }
                "map" -> {
                    // Map data attribution
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            text = "Map tiles and geocoding services provided by:",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(bottom = 16.dp)
                        )

                        Column(modifier = Modifier.padding(bottom = 16.dp)) {
                            Text(
                                text = "MapTiler",
                                style = MaterialTheme.typography.titleMedium
                            )
                            Text(
                                text = "© MapTiler",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = "https://www.maptiler.com",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.clickable {
                                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.maptiler.com"))
                                    context.startActivity(intent)
                                }
                            )
                        }

                        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                        Column(modifier = Modifier.padding(bottom = 16.dp)) {
                            Text(
                                text = "OpenStreetMap",
                                style = MaterialTheme.typography.titleMedium
                            )
                            Text(
                                text = "© OpenStreetMap contributors",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = "https://www.openstreetmap.org/copyright",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.clickable {
                                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.openstreetmap.org/copyright"))
                                    context.startActivity(intent)
                                }
                            )
                        }

                        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                        Column(modifier = Modifier.padding(bottom = 16.dp)) {
                            Text(
                                text = "MapLibre",
                                style = MaterialTheme.typography.titleMedium
                            )
                            Text(
                                text = "Open-source mapping library",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = "https://maplibre.org",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.clickable {
                                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://maplibre.org"))
                                    context.startActivity(intent)
                                }
                            )
                        }
                    }
                }
                "core" -> {
                    // Core dependencies list (grouped by license)
                    LazyColumn(
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        items(coreLicenses) { group ->
                            val isExpanded = expandedLicenses.contains(group.id)
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        expandedLicenses = if (isExpanded) {
                                            expandedLicenses - group.id
                                        } else {
                                            expandedLicenses + group.id
                                        }
                                    }
                                    .padding(vertical = 12.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = group.name,
                                            style = MaterialTheme.typography.bodyLarge
                                        )
                                        Text(
                                            text = "${group.packages.size} packages",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    Icon(
                                        imageVector = if (isExpanded) Icons.Default.ArrowUpward else Icons.Default.ArrowDownward,
                                        contentDescription = if (isExpanded) "Collapse" else "Expand",
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }

                                if (isExpanded) {
                                    Spacer(modifier = Modifier.height(12.dp))
                                    Text(
                                        text = group.packages.joinToString(", "),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))
                                    Text(
                                        text = group.text,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                            HorizontalDivider()
                        }
                    }
                }
                "android" -> {
                    // Android dependencies list (grouped by license)
                    val androidByLicense = remember {
                        androidLibs.libraries
                            .groupBy { lib -> lib.licenses.firstOrNull()?.name ?: "Unknown" }
                            .toList()
                            .sortedBy { it.first }
                    }

                    LazyColumn(
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        items(androidByLicense) { (licenseName, libs) ->
                            val isExpanded = expandedLicenses.contains(licenseName)
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        expandedLicenses = if (isExpanded) {
                                            expandedLicenses - licenseName
                                        } else {
                                            expandedLicenses + licenseName
                                        }
                                    }
                                    .padding(vertical = 12.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = licenseName,
                                            style = MaterialTheme.typography.bodyLarge
                                        )
                                        Text(
                                            text = "${libs.size} packages",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    Icon(
                                        imageVector = if (isExpanded) Icons.Default.ArrowUpward else Icons.Default.ArrowDownward,
                                        contentDescription = if (isExpanded) "Collapse" else "Expand",
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }

                                if (isExpanded) {
                                    Spacer(modifier = Modifier.height(12.dp))
                                    Text(
                                        text = libs.joinToString(", ") { "${it.name} ${it.artifactVersion ?: ""}" },
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    val licenseText = libs.firstOrNull()?.licenses?.firstOrNull()?.licenseContent
                                    if (!licenseText.isNullOrBlank()) {
                                        HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))
                                        Text(
                                            text = licenseText,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                            HorizontalDivider()
                        }
                    }
                }
            }
        }
    }
}

// QR Code generation helper
private fun generateQrCode(content: String, size: Int): Bitmap? {
    return try {
        val writer = QRCodeWriter()
        val bitMatrix = writer.encode(content, BarcodeFormat.QR_CODE, size, size)
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565)
        for (x in 0 until size) {
            for (y in 0 until size) {
                bitmap.setPixel(x, y, if (bitMatrix[x, y]) android.graphics.Color.BLACK else android.graphics.Color.WHITE)
            }
        }
        bitmap
    } catch (e: Exception) {
        null
    }
}

@Composable
private fun ShareLinkDialog(
    link: String,
    qrBitmap: Bitmap?,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Share My Link") },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // QR Code
                qrBitmap?.let { bitmap ->
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = "QR Code",
                        modifier = Modifier
                            .size(200.dp)
                            .border(
                                width = 1.dp,
                                color = MaterialTheme.colorScheme.outline,
                                shape = RoundedCornerShape(8.dp)
                            )
                            .padding(8.dp)
                    )
                }

                Text(
                    text = "Scan this QR code to add me",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 16.dp, bottom = 8.dp)
                )

                Text(
                    text = link,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val sendIntent = Intent().apply {
                        action = Intent.ACTION_SEND
                        putExtra(Intent.EXTRA_TEXT, link)
                        type = "text/plain"
                    }
                    context.startActivity(Intent.createChooser(sendIntent, "Share link"))
                }
            ) {
                Icon(
                    imageVector = Icons.Default.Share,
                    contentDescription = null,
                    modifier = Modifier.padding(end = 4.dp)
                )
                Text("Share")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Close")
            }
        }
    )
}
