package sh.bentley.transponder

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import uniffi.transponder_core.getShareRecipients
import uniffi.transponder_core.getFetchTargets
import java.util.concurrent.TimeUnit

/**
 * Background worker for periodic location sync.
 * Uploads our location to the server and fetches friend locations.
 */
class LocationSyncWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    companion object {
        private const val TAG = "LocationSyncWorker"
        private const val WORK_NAME = "location_sync"

        /**
         * Schedule periodic location sync.
         * @param context Application context
         * @param intervalMinutes Sync interval in minutes (minimum 15)
         */
        fun schedule(context: Context, intervalMinutes: Long = 15) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val request = PeriodicWorkRequestBuilder<LocationSyncWorker>(
                intervalMinutes, TimeUnit.MINUTES
            )
                .setConstraints(constraints)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                request
            )

            Log.d(TAG, "Scheduled periodic sync every $intervalMinutes minutes")
        }

        /**
         * Cancel periodic location sync.
         */
        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
            Log.d(TAG, "Cancelled periodic sync")
        }

        /**
         * Check if we have background location permission.
         */
        fun hasBackgroundLocationPermission(context: Context): Boolean {
            return ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_BACKGROUND_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        }

        /**
         * Check if we have any location permission (fine or coarse).
         */
        fun hasAnyLocationPermission(context: Context): Boolean {
            val hasFine = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED

            val hasCoarse = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_COARSE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED

            return hasFine || hasCoarse
        }
    }

    override suspend fun doWork(): Result {
        Log.d(TAG, "Starting location sync work")

        // Check for any location permission (fine or coarse)
        if (!hasAnyLocationPermission(applicationContext)) {
            Log.w(TAG, "Missing location permission, skipping sync")
            return Result.success()
        }

        val identityStore = IdentityStore(applicationContext)
        val syncService = LocationSyncService(identityStore)

        // Check if we have an identity
        if (!identityStore.hasIdentity()) {
            Log.d(TAG, "No identity configured, skipping sync")
            return Result.success()
        }

        // Upload location if auto-share is enabled and we have friends to share with
        if (identityStore.autoShareEnabled) {
            val shareRecipients = getShareRecipients()
            if (shareRecipients.isNotEmpty()) {
                uploadLocation(syncService, identityStore)
            } else {
                Log.d(TAG, "No share recipients, skipping upload")
            }
        } else {
            Log.d(TAG, "Auto-share disabled, skipping upload")
        }

        // Fetch friend locations if we have targets
        val fetchTargets = getFetchTargets()
        if (fetchTargets.isNotEmpty()) {
            fetchFriendLocations(syncService)
        } else {
            Log.d(TAG, "No fetch targets, skipping fetch")
        }

        Log.d(TAG, "Location sync work completed")
        return Result.success()
    }

    private suspend fun uploadLocation(syncService: LocationSyncService, identityStore: IdentityStore) {
        Log.d(TAG, "Requesting location for upload...")

        val locationResult = requestLocation(applicationContext, LocationFreshness.CACHED_OKAY, LocationSettings.from(identityStore))

        if (locationResult.location == null) {
            Log.w(TAG, "Failed to get location: ${locationResult.error ?: "unknown error"}")
            return
        }

        val location = locationResult.location
        Log.d(TAG, "Got location: ${location.latitude}, ${location.longitude} " +
                "(accuracy: ${location.accuracy}m, source: ${locationResult.source})")

        val result = syncService.uploadLocation(
            latitude = location.latitude,
            longitude = location.longitude,
            accuracy = location.accuracy,
            timestamp = location.time
        )

        when (result) {
            is LocationSyncService.UploadResult.Success -> {
                Log.d(TAG, "Location uploaded successfully")
            }
            is LocationSyncService.UploadResult.Error -> {
                Log.w(TAG, "Failed to upload location: ${result.message}")
            }
        }
    }

    private suspend fun fetchFriendLocations(syncService: LocationSyncService) {
        Log.d(TAG, "Fetching friend locations...")

        val result = syncService.fetchTrackedFriends()

        when (result) {
            is LocationSyncService.FetchResult.Success -> {
                Log.d(TAG, "Fetched ${result.locations.size} friend locations")
            }
            is LocationSyncService.FetchResult.Error -> {
                Log.w(TAG, "Failed to fetch friend locations: ${result.message}")
            }
        }
    }
}
