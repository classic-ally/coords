package sh.bentley.transponder

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.Point
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource

/**
 * The map surface: a MapLibre AndroidView plus the marker, selected-label, and
 * camera side-effects. Reads domain state from [MapScreenState] and reports map
 * output (bearing, tapped friend, requested camera fits) back through it.
 */
@Composable
fun MapCanvas(
    state: MapScreenState,
    styleUrl: String,
    isDarkTheme: Boolean,
    sheetPeekHeightPx: Float,
    statusBarHeight: Dp,
    onFriendTapped: (pubkey: String, lat: Double, lng: Double) -> Unit,
    onShowAbout: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var mapRef by remember { mutableStateOf<MapLibreMap?>(null) }
    var mapViewRef by remember { mutableStateOf<MapView?>(null) }
    var styleRef by remember { mutableStateOf<Style?>(null) }
    var currentStyleUrl by remember { mutableStateOf<String?>(null) }
    var hasSetInitialBounds by remember { mutableStateOf(false) }

    // Update map annotations when location or friends change
    LaunchedEffect(state.currentLocation, state.currentAccuracy, styleRef, state.friends) {
        val style = styleRef ?: return@LaunchedEffect

        state.friends.filter { it.fetchFrom && it.location != null }.forEach { friend ->
            val iconId = "icon-friend-${friend.pubkey}"
            if (style.getImage(iconId) == null) {
                style.addImage(iconId, getFriendMarkerIcon(friend.pubkey, friend.name, friend.color))
            }
        }
        if (style.getImage("icon-self") == null) {
            style.addImage("icon-self", generateSelfMarkerIcon())
        }

        val dotFeatures = mutableListOf<Feature>()
        state.currentLocation?.let { loc ->
            val feature = Feature.fromGeometry(Point.fromLngLat(loc.longitude, loc.latitude))
            feature.addStringProperty("type", "self")
            feature.addStringProperty("iconId", "icon-self")
            feature.addStringProperty("color", SELF_COLOR)
            feature.addNumberProperty("accuracy", state.currentAccuracy)
            dotFeatures.add(feature)
        }
        state.friends.filter { it.fetchFrom }.forEach { friend ->
            friend.location?.let { loc ->
                val feature = Feature.fromGeometry(Point.fromLngLat(loc.longitude, loc.latitude))
                feature.addStringProperty("type", "friend")
                feature.addStringProperty("pubkey", friend.pubkey)
                feature.addStringProperty("iconId", "icon-friend-${friend.pubkey}")
                feature.addStringProperty("color", friend.color)
                feature.addNumberProperty("accuracy", loc.accuracy)
                dotFeatures.add(feature)
            }
        }

        val existingSource = style.getSourceAs<GeoJsonSource>(SOURCE_LOCATIONS)
        if (existingSource != null) {
            existingSource.setGeoJson(FeatureCollection.fromFeatures(dotFeatures))
        } else {
            style.addSource(GeoJsonSource(SOURCE_LOCATIONS, FeatureCollection.fromFeatures(dotFeatures)))
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
    LaunchedEffect(state.selectedFriend, styleRef, isDarkTheme) {
        val style = styleRef ?: return@LaunchedEffect

        val features = mutableListOf<Feature>()
        state.selectedFriend?.location?.let { loc ->
            val feature = Feature.fromGeometry(Point.fromLngLat(loc.longitude, loc.latitude))
            feature.addStringProperty("name", state.selectedFriend!!.name)
            features.add(feature)
        }

        val existingSource = style.getSourceAs<GeoJsonSource>(SOURCE_SELECTED)
        if (existingSource != null) {
            existingSource.setGeoJson(FeatureCollection.fromFeatures(features))
        } else {
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
            state.requestCamera(CameraAction.FitAllFriends)
            hasSetInitialBounds = true
        }
    }

    // Execute pending camera actions, accounting for the sheet covering the bottom
    LaunchedEffect(state.pendingCameraAction) {
        val action = state.pendingCameraAction ?: return@LaunchedEffect
        val map = mapRef ?: return@LaunchedEffect
        val bottomPadding = sheetPeekHeightPx.toDouble()

        when (action) {
            is CameraAction.CenterOn -> {
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
                val friendLocations = state.friends.mapNotNull { friend ->
                    friend.location?.let { LatLng(it.latitude, it.longitude) }
                }
                when {
                    friendLocations.size >= 2 -> {
                        val boundsBuilder = LatLngBounds.Builder()
                        friendLocations.forEach { boundsBuilder.include(it) }
                        val bounds = boundsBuilder.build()
                        val boundsBottomPadding = sheetPeekHeightPx.toInt() + 100
                        map.animateCamera(
                            CameraUpdateFactory.newLatLngBounds(bounds, 100, 100, 100, boundsBottomPadding),
                            CAMERA_ANIMATION_DURATION_MS
                        )
                    }
                    friendLocations.size == 1 -> {
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
                        map.animateCamera(
                            CameraUpdateFactory.newLatLngZoom(LatLng(20.0, 0.0), 1.0),
                            CAMERA_ANIMATION_DURATION_MS
                        )
                    }
                }
            }
            is CameraAction.CenterOnMyLocation -> {
                val loc = state.currentLocation ?: return@LaunchedEffect
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
            is CameraAction.ResetNorth -> {
                map.animateCamera(
                    CameraUpdateFactory.bearingTo(0.0),
                    CAMERA_ANIMATION_DURATION_MS
                )
            }
        }
        state.clearCameraAction()
    }

    Box(modifier = modifier.fillMaxSize()) {
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
                        map.setPrefetchZoomDelta(4)
                        map.uiSettings.isCompassEnabled = false
                        map.uiSettings.isAttributionEnabled = false
                        map.setStyle(styleUrl) { style ->
                            currentStyleUrl = styleUrl
                            styleRef = style
                        }
                        map.addOnCameraIdleListener {
                            state.onBearing(map.cameraPosition.bearing)
                        }
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
                                    onFriendTapped(pubkey, point.latitude(), point.longitude())
                                }
                                true
                            } else {
                                state.selectedFriendPubkey = null
                                false
                            }
                        }
                    }
                }
            },
            update = { _ ->
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
        if (state.selectedFriend != null && state.selectedFriend?.location == null) {
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
                .padding(start = 12.dp, top = statusBarHeight + 8.dp)
                .clickable { onShowAbout() }
        )
    }
}
