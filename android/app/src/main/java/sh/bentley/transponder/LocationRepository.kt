package sh.bentley.transponder

import android.content.Context
import android.content.Intent
import android.location.Location
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import uniffi.transponder_core.getLastUploadTimestamp
import uniffi.transponder_core.getShareRecipients

/**
 * Single upload engine shared by every trigger (in-app, foreground service, worker).
 * Acquires/accepts a location, encrypts + uploads, and pings the foreground-service
 * notification to refresh. The upload mutex serializes concurrent triggers so the worker
 * and service can't double-upload.
 *
 * The primary constructor takes plain seams so it can be unit-tested without Android or
 * the native core. Production builds it via [from].
 */
class LocationRepository(
    private val acquire: suspend (LocationFreshness) -> LocationRequestResult,
    private val upload: suspend (Location) -> LocationSyncService.UploadResult,
    private val hasRecipients: () -> Boolean,
    private val lastUploadRaw: () -> Long,
    private val onUploaded: () -> Unit,
) {

    sealed class Result {
        data class Uploaded(val timestamp: Long, val source: String) : Result()
        data object Skipped : Result()
        data object NoLocation : Result()
        data class Error(val message: String) : Result()
    }

    /** Trigger with no location in hand: acquire a fix, then upload. */
    suspend fun syncNow(freshness: LocationFreshness): Result {
        val result = acquire(freshness)
        val location = result.location ?: return Result.NoLocation
        return push(location, result.source)
    }

    /** Trigger that already has a location (e.g. the service's location listener). */
    suspend fun push(location: Location, source: String = "external"): Result =
        uploadMutex.withLock {
            if (!hasRecipients()) return Result.Skipped
            when (val r = upload(location)) {
                is LocationSyncService.UploadResult.Success -> {
                    onUploaded()
                    Result.Uploaded(lastUploadTime() ?: location.time, source)
                }
                is LocationSyncService.UploadResult.Error -> Result.Error(r.message)
            }
        }

    fun lastUploadTime(): Long? = lastUploadRaw().takeIf { it > 0 }

    companion object {
        // Shared across all instances so worker/service/app uploads serialize
        // process-wide, not just within a single repository.
        private val uploadMutex = Mutex()

        fun from(appContext: Context, identityStore: IdentityStore): LocationRepository {
            val sync = LocationSyncService(identityStore)
            return LocationRepository(
                acquire = { freshness ->
                    requestLocation(appContext, freshness, LocationSettings.from(identityStore))
                },
                upload = { location ->
                    sync.uploadLocation(
                        latitude = location.latitude,
                        longitude = location.longitude,
                        altitude = location.altitude,
                        accuracy = location.accuracy,
                        timestamp = location.time
                    )
                },
                hasRecipients = { getShareRecipients().isNotEmpty() },
                lastUploadRaw = { getLastUploadTimestamp().toLong() },
                onUploaded = {
                    appContext.sendBroadcast(
                        Intent(LocationUploadService.ACTION_UPLOAD_DONE)
                            .setPackage(appContext.packageName)
                    )
                },
            )
        }
    }
}
