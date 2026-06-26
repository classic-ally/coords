package sh.bentley.transponder

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.core.content.ContextCompat
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

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

@SuppressLint("MissingPermission")
suspend fun requestLocation(
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
