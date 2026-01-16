package sh.bentley.transponder

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.core.content.ContextCompat
import java.util.concurrent.TimeUnit
import androidx.activity.ComponentActivity
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import sh.bentley.transponder.ui.theme.TransponderTheme
import kotlin.coroutines.resume
import uniffi.transponder_core.initStorage
import uniffi.transponder_core.migrateServerUrls
import uniffi.transponder_core.parseFriendLink

enum class LocationFreshness {
    CACHED_OKAY,    // Use cached if fresh and accurate enough
    ALWAYS_FRESH    // Always request new GPS fix
}

data class LocationSettings(
    val activeTimeoutMs: Long = IdentityStore.DEFAULT_LOCATION_ACTIVE_TIMEOUT_MS,
    val activeAccuracyThresholdM: Float = IdentityStore.DEFAULT_LOCATION_ACTIVE_ACCURACY_M,
    val passiveMaxAgeMs: Long = IdentityStore.DEFAULT_LOCATION_PASSIVE_MAX_AGE_MS,
    val passiveAccuracyThresholdM: Float = IdentityStore.DEFAULT_LOCATION_PASSIVE_ACCURACY_M
) {
    companion object {
        fun from(identityStore: IdentityStore) = LocationSettings(
            activeTimeoutMs = identityStore.locationActiveTimeoutMs,
            activeAccuracyThresholdM = identityStore.locationActiveAccuracyThresholdM,
            passiveMaxAgeMs = identityStore.locationPassiveMaxAgeMs,
            passiveAccuracyThresholdM = identityStore.locationPassiveAccuracyThresholdM
        )
    }
}

data class LocationRequestResult(
    val location: Location?,
    val source: String,
    val elapsedMs: Long,
    val passiveUsed: Boolean,
    val error: String? = null
)

class MainActivity : ComponentActivity() {
    private lateinit var identityStore: IdentityStore
    private var pendingDeepLink: String? = null
    private var pendingLocationDeferred: Deferred<Location?>? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Initialize Rust storage for persistent friends database
        try {
            initStorage(filesDir.absolutePath)
            // Migrate friends from old server domain to new one
            val migrated = migrateServerUrls("transponder.bentley.sh", "coord.is")
            if (migrated > 0u) {
                println("Migrated $migrated friend(s) to coord.is")
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // Load city database asynchronously to avoid blocking UI
        lifecycleScope.launch(Dispatchers.IO) {
            CityDatabase.load(this@MainActivity)
        }

        identityStore = IdentityStore(this)

        // Migrate user's own server URL from old domain to new one
        identityStore.migrateServerUrl("transponder.bentley.sh", "coord.is")

        // Schedule background sync if user has identity and background location permission
        if (identityStore.hasIdentity() && LocationSyncWorker.hasBackgroundLocationPermission(this)) {
            LocationSyncWorker.schedule(this)
        }

        // Handle deep link from initial launch
        handleDeepLink(intent)

        enableEdgeToEdge()
        setContent {
            TransponderTheme {
                var hasIdentity by remember { mutableStateOf(identityStore.hasIdentity()) }
                var showShareBack by remember { mutableStateOf(false) }
                var addedFriendName by remember { mutableStateOf<String?>(null) }
                var currentPendingLink by remember { mutableStateOf(pendingDeepLink) }

                // Process pending deep link after onboarding
                LaunchedEffect(hasIdentity, currentPendingLink) {
                    if (hasIdentity && currentPendingLink != null) {
                        val link = currentPendingLink
                        currentPendingLink = null
                        pendingDeepLink = null
                        if (link != null) {
                            val name = processDeepLink(link)
                            if (name != null) {
                                addedFriendName = name
                                showShareBack = true
                            }
                        }
                    }
                }

                when {
                    showShareBack && addedFriendName != null -> {
                        ShareBackScreen(
                            identityStore = identityStore,
                            friendName = addedFriendName!!,
                            onDismiss = {
                                showShareBack = false
                                // Fetch friend locations in background - they may have uploaded when adding us
                                lifecycleScope.launch {
                                    val syncService = LocationSyncService(identityStore)
                                    syncService.fetchTrackedFriends()
                                }
                            }
                        )
                    }
                    hasIdentity -> {
                        MainScreen(
                            identityStore = identityStore,
                            onDeepLink = { link ->
                                // Kick off location request for in-app deep links too
                                pendingLocationDeferred = lifecycleScope.async {
                                    requestLocation(this@MainActivity, LocationFreshness.CACHED_OKAY, LocationSettings.from(identityStore)).location
                                }
                                lifecycleScope.launch {
                                    val name = processDeepLink(link)
                                    if (name != null) {
                                        addedFriendName = name
                                        showShareBack = true
                                    }
                                }
                            }
                        )
                    }
                    else -> {
                        OnboardingScreen(
                            identityStore = identityStore,
                            onComplete = {
                                hasIdentity = true
                                // Schedule background sync after onboarding if permission granted
                                if (LocationSyncWorker.hasBackgroundLocationPermission(this@MainActivity)) {
                                    LocationSyncWorker.schedule(this@MainActivity)
                                }
                            }
                        )
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleDeepLink(intent)
    }

    private fun handleDeepLink(intent: Intent?) {
        val data = intent?.data ?: return
        val url = data.toString()
        if (url.startsWith("coord://")) {
            pendingDeepLink = url
            // Kick off location request immediately so GPS can warm up
            // while user reviews the share-back screen (only if onboarded)
            if (identityStore.hasIdentity()) {
                pendingLocationDeferred = lifecycleScope.async {
                    requestLocation(this@MainActivity, LocationFreshness.ALWAYS_FRESH, LocationSettings.from(identityStore)).location
                }
            }
        }
    }

    private suspend fun processDeepLink(url: String): String? {
        return try {
            val parsed = parseFriendLink(url)
            val syncService = LocationSyncService(identityStore)
            val result = syncService.addFriendAndUploadLocation(
                pubkey = parsed.pubkey,
                server = parsed.server,
                name = parsed.name,
                locationDeferred = pendingLocationDeferred
            )
            pendingLocationDeferred = null  // Clear after use
            when (result) {
                is LocationSyncService.AddFriendResult.AddFriendFailed -> {
                    android.util.Log.e("MainActivity", "Failed to add friend: ${result.message}")
                    null
                }
                is LocationSyncService.AddFriendResult.SuccessUploadFailed -> {
                    android.util.Log.w("MainActivity", "Friend added but upload failed: ${result.message}")
                    parsed.name
                }
                else -> parsed.name
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
}

@SuppressLint("MissingPermission")
internal suspend fun requestLocation(
    context: Context,
    freshness: LocationFreshness = LocationFreshness.CACHED_OKAY,
    settings: LocationSettings = LocationSettings()
): LocationRequestResult {
    val startTime = SystemClock.elapsedRealtime()
    val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager

    // Check if we have fine location permission (needed for GPS)
    val hasFinePermission = ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.ACCESS_FINE_LOCATION
    ) == PackageManager.PERMISSION_GRANTED

    // Phase 0: Check passive (only if cached is okay)
    if (freshness == LocationFreshness.CACHED_OKAY) {
        val passive = locationManager.getLastKnownLocation(LocationManager.PASSIVE_PROVIDER)
        if (passive != null) {
            val ageMs = TimeUnit.NANOSECONDS.toMillis(
                SystemClock.elapsedRealtimeNanos() - passive.elapsedRealtimeNanos
            )
            if (ageMs < settings.passiveMaxAgeMs && passive.accuracy <= settings.passiveAccuracyThresholdM) {
                return LocationRequestResult(
                    location = passive,
                    source = "passive",
                    elapsedMs = SystemClock.elapsedRealtime() - startTime,
                    passiveUsed = true
                )
            }
        }
    }

    // Phase 1: Active request
    val hasFused = locationManager.allProviders.contains(LocationManager.FUSED_PROVIDER)
    // GPS requires fine location permission
    val hasGps = hasFinePermission && locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)
    val hasNetwork = locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)

    val activeResult = if (hasFused && locationManager.isProviderEnabled(LocationManager.FUSED_PROVIDER)) {
        requestFromProvider(context, LocationManager.FUSED_PROVIDER, startTime, hasFinePermission, settings)
    } else if (hasGps || hasNetwork) {
        requestFromMultipleProviders(context, hasGps, hasNetwork, startTime, settings)
    } else {
        null
    }

    // If active succeeded, return it
    if (activeResult?.location != null) {
        return activeResult
    }

    // Fall back to any passive location (regardless of age)
    val passive = locationManager.getLastKnownLocation(LocationManager.PASSIVE_PROVIDER)
    if (passive != null) {
        return LocationRequestResult(
            location = passive,
            source = "passive-fallback",
            elapsedMs = SystemClock.elapsedRealtime() - startTime,
            passiveUsed = true
        )
    }

    return LocationRequestResult(
        location = null,
        source = "none",
        elapsedMs = SystemClock.elapsedRealtime() - startTime,
        passiveUsed = false,
        error = activeResult?.error ?: "No location providers available"
    )
}

@SuppressLint("MissingPermission")
private suspend fun requestFromProvider(
    context: Context,
    provider: String,
    startTime: Long,
    hasFinePermission: Boolean = true,
    settings: LocationSettings = LocationSettings()
): LocationRequestResult {
    val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    val handler = Handler(Looper.getMainLooper())

    var bestLocation: Location? = null

    val location = withTimeoutOrNull(settings.activeTimeoutMs) {
        suspendCancellableCoroutine { continuation ->
            val listener = object : LocationListener {
                override fun onLocationChanged(location: Location) {
                    if (bestLocation == null || location.accuracy < bestLocation!!.accuracy) {
                        bestLocation = location
                    }
                    // Return immediately if we meet threshold (0 = never early return)
                    if (settings.activeAccuracyThresholdM > 0 && location.accuracy <= settings.activeAccuracyThresholdM) {
                        locationManager.removeUpdates(this)
                        if (continuation.isActive) {
                            continuation.resume(location)
                        }
                    }
                }

                override fun onProviderEnabled(provider: String) {}
                override fun onProviderDisabled(provider: String) {
                    locationManager.removeUpdates(this)
                    if (continuation.isActive) {
                        continuation.resume(bestLocation)
                    }
                }
                @Deprecated("Deprecated in Java")
                override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
            }

            continuation.invokeOnCancellation {
                locationManager.removeUpdates(listener)
            }

            locationManager.requestLocationUpdates(
                provider,
                1000L, // min time interval
                0f,    // min distance
                listener,
                handler.looper
            )
        }
    }

    return LocationRequestResult(
        location = location ?: bestLocation,
        source = provider,
        elapsedMs = SystemClock.elapsedRealtime() - startTime,
        passiveUsed = false
    )
}

@SuppressLint("MissingPermission")
private suspend fun requestFromMultipleProviders(
    context: Context,
    hasGps: Boolean,
    hasNetwork: Boolean,
    startTime: Long,
    settings: LocationSettings = LocationSettings()
): LocationRequestResult {
    val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    val handler = Handler(Looper.getMainLooper())

    var bestLocation: Location? = null
    var bestSource: String = "none"
    val lock = Any()

    val result = withTimeoutOrNull(settings.activeTimeoutMs) {
        suspendCancellableCoroutine { continuation ->
            val listeners = mutableListOf<Pair<String, LocationListener>>()

            fun createListener(provider: String): LocationListener {
                return object : LocationListener {
                    override fun onLocationChanged(location: Location) {
                        synchronized(lock) {
                            if (bestLocation == null || location.accuracy < bestLocation!!.accuracy) {
                                bestLocation = location
                                bestSource = provider
                            }
                            // Return immediately if we meet threshold (0 = never early return)
                            if (settings.activeAccuracyThresholdM > 0 && location.accuracy <= settings.activeAccuracyThresholdM) {
                                listeners.forEach { (_, l) -> locationManager.removeUpdates(l) }
                                if (continuation.isActive) {
                                    continuation.resume(location to provider)
                                }
                            }
                        }
                    }

                    override fun onProviderEnabled(provider: String) {}
                    override fun onProviderDisabled(provider: String) {}
                    @Deprecated("Deprecated in Java")
                    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
                }
            }

            continuation.invokeOnCancellation {
                listeners.forEach { (_, l) -> locationManager.removeUpdates(l) }
            }

            if (hasGps) {
                val listener = createListener(LocationManager.GPS_PROVIDER)
                listeners.add(LocationManager.GPS_PROVIDER to listener)
                locationManager.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER,
                    1000L,
                    0f,
                    listener,
                    handler.looper
                )
            }

            if (hasNetwork) {
                val listener = createListener(LocationManager.NETWORK_PROVIDER)
                listeners.add(LocationManager.NETWORK_PROVIDER to listener)
                locationManager.requestLocationUpdates(
                    LocationManager.NETWORK_PROVIDER,
                    1000L,
                    0f,
                    listener,
                    handler.looper
                )
            }
        }
    }

    return LocationRequestResult(
        location = result?.first ?: bestLocation,
        source = result?.second ?: bestSource,
        elapsedMs = SystemClock.elapsedRealtime() - startTime,
        passiveUsed = false
    )
}

