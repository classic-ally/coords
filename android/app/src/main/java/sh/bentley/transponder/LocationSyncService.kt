package sh.bentley.transponder

import kotlinx.coroutines.Deferred
import uniffi.transponder_core.CoreException
import uniffi.transponder_core.FetchedLocation
import uniffi.transponder_core.Friend
import uniffi.transponder_core.Location
import uniffi.transponder_core.addFriend
import uniffi.transponder_core.getFetchTargets
import uniffi.transponder_core.getShareRecipients
import uniffi.transponder_core.markUploadSuccess
import uniffi.transponder_core.prepareLocationFetch
import uniffi.transponder_core.prepareLocationUpload
import uniffi.transponder_core.prepareSelfLocationFetch
import uniffi.transponder_core.processFetchResponse
import uniffi.transponder_core.updateFriendLocation

/**
 * Service for syncing locations with the Transponder server.
 * Coordinates between Rust core (crypto) and HTTP layer (network).
 */
class LocationSyncService(
    private val identityStore: IdentityStore,
    private val http: TransponderHttp = TransponderHttp()
) {
    sealed class UploadResult {
        data object Success : UploadResult()
        data class Error(val message: String) : UploadResult()
    }

    sealed class FetchResult {
        data class Success(val locations: List<FetchedLocation>) : FetchResult()
        data class Error(val message: String) : FetchResult()
    }

    sealed class AddFriendResult {
        data object Success : AddFriendResult()              // Friend added, no upload attempted
        data object SuccessWithUpload : AddFriendResult()    // Friend added, upload succeeded
        data class SuccessUploadFailed(val message: String) : AddFriendResult()  // Friend added, upload failed
        data class AddFriendFailed(val message: String) : AddFriendResult()      // Failed to add friend
    }

    /**
     * Adds a friend and optionally uploads current location if auto-share is enabled.
     * Location is passed as a Deferred so it can resolve in the background while
     * the friend is being added.
     */
    suspend fun addFriendAndUploadLocation(
        pubkey: String,
        server: String,
        name: String,
        locationDeferred: Deferred<android.location.Location?>?
    ): AddFriendResult {
        // First, add the friend immediately (no waiting for location)
        try {
            addFriend(
                pubkey = pubkey,
                server = server,
                name = name,
                shareWith = true,
                fetchFrom = true
            )
        } catch (e: Exception) {
            return AddFriendResult.AddFriendFailed(e.message ?: "Unknown error")
        }

        // Now await the location (which has been resolving in background) and upload
        if (identityStore.autoShareEnabled && locationDeferred != null) {
            val location = locationDeferred.await()
            if (location != null) {
                val uploadResult = uploadLocation(
                    latitude = location.latitude,
                    longitude = location.longitude,
                    altitude = location.altitude,
                    accuracy = location.accuracy,
                    timestamp = location.time
                )
                return when (uploadResult) {
                    is UploadResult.Success -> AddFriendResult.SuccessWithUpload
                    is UploadResult.Error -> AddFriendResult.SuccessUploadFailed(uploadResult.message)
                }
            }
        }

        return AddFriendResult.Success
    }

    /**
     * Upload our current location to the server.
     */
    suspend fun uploadLocation(
        latitude: Double,
        longitude: Double,
        altitude: Double,
        accuracy: Float,
        timestamp: Long
    ): UploadResult {
        val identity = identityStore.getIdentity()
            ?: return UploadResult.Error("No identity configured")

        val uploadPubkeyHex = identity.ed25519Public.take(8).joinToString("") { "%02x".format(it.toByte()) }
        android.util.Log.d("LocationSync", "upload identity pubkey: $uploadPubkeyHex...")

        val serverUrl = identityStore.serverUrl
            ?: return UploadResult.Error("No server URL configured")

        // Get friends we should encrypt for (those with shareWith == true)
        val friends = getShareRecipients()

        val location = Location(
            latitude = latitude,
            longitude = longitude,
            altitude = altitude,
            accuracy = accuracy,
            timestamp = timestamp.toULong()
        )

        return try {
            val request = prepareLocationUpload(identity, location, friends, serverUrl)

            when (val result = http.execute(request)) {
                is TransponderHttp.Result.Success -> {
                    markUploadSuccess(timestamp.toULong())
                    UploadResult.Success
                }
                is TransponderHttp.Result.HttpError ->
                    UploadResult.Error("Server error: ${result.code} ${result.message}")
                is TransponderHttp.Result.NetworkError ->
                    UploadResult.Error("Network error: ${result.exception.message}")
            }
        } catch (e: CoreException.StaleLocation) {
            // Location is older than last upload, skip silently
            android.util.Log.d("LocationSync", "Skipping stale location upload")
            UploadResult.Success
        } catch (e: Exception) {
            UploadResult.Error("Upload failed: ${e.message}")
        }
    }

    /**
     * Fetch locations for all friends we're tracking (fetchFrom == true).
     * Updates the cached locations in storage.
     */
    suspend fun fetchTrackedFriends(): FetchResult {
        val identity = identityStore.getIdentity()
            ?: return FetchResult.Error("No identity configured")

        val targets = getFetchTargets()
        if (targets.isEmpty()) {
            return FetchResult.Success(emptyList())
        }

        return try {
            val requests = prepareLocationFetch(targets)
            val allLocations = mutableListOf<FetchedLocation>()
            val now = System.currentTimeMillis().toULong()

            for (request in requests) {
                when (val result = http.execute(request)) {
                    is TransponderHttp.Result.Success -> {
                        val locations = processFetchResponse(identity, result.body)

                        // Update cached locations in storage (only if we got a valid location)
                        for (loc in locations) {
                            // Only update if we successfully decrypted a location
                            // If decryption fails (e.g., friend removed us), keep showing last known location
                            if (loc.location != null) {
                                try {
                                    updateFriendLocation(loc.pubkey, loc.location, now)
                                } catch (e: Exception) {
                                    android.util.Log.e("LocationSync", "Failed to update location for ${loc.pubkey}: ${e.message}")
                                }
                            }
                        }

                        allLocations.addAll(locations)
                    }
                    is TransponderHttp.Result.HttpError -> {
                        android.util.Log.w("LocationSync", "HTTP error fetching friends: ${result.code}")
                    }
                    is TransponderHttp.Result.NetworkError -> {
                        android.util.Log.w("LocationSync", "Network error fetching friends: ${result.exception.message}")
                    }
                }
            }

            FetchResult.Success(allLocations)
        } catch (e: Exception) {
            FetchResult.Error("Fetch failed: ${e.message}")
        }
    }

    /**
     * Fetch locations for all friends.
     * Handles federation by grouping requests by server.
     */
    suspend fun fetchFriendLocations(friends: List<Friend>): FetchResult {
        val identity = identityStore.getIdentity()
            ?: return FetchResult.Error("No identity configured")

        if (friends.isEmpty()) {
            return FetchResult.Success(emptyList())
        }

        return try {
            // Prepare requests grouped by server
            val requests = prepareLocationFetch(friends)

            // Execute all requests and collect results
            val allLocations = mutableListOf<FetchedLocation>()

            for (request in requests) {
                when (val result = http.execute(request)) {
                    is TransponderHttp.Result.Success -> {
                        val locations = processFetchResponse(identity, result.body)
                        allLocations.addAll(locations)
                    }
                    is TransponderHttp.Result.HttpError -> {
                        // Log but continue with other servers
                    }
                    is TransponderHttp.Result.NetworkError -> {
                        // Log but continue with other servers
                    }
                }
            }

            FetchResult.Success(allLocations)
        } catch (e: Exception) {
            FetchResult.Error("Fetch failed: ${e.message}")
        }
    }

    /**
     * Fetch our own location from the server.
     * Used to verify what the server has stored for us.
     */
    suspend fun fetchSelfLocation(): FetchResult {
        val identity = identityStore.getIdentity()
            ?: return FetchResult.Error("No identity configured")

        val fetchPubkeyHex = identity.ed25519Public.take(8).joinToString("") { "%02x".format(it.toByte()) }
        android.util.Log.d("LocationSync", "fetch identity pubkey: $fetchPubkeyHex...")

        val serverUrl = identityStore.serverUrl
            ?: return FetchResult.Error("No server URL configured")

        return try {
            val request = prepareSelfLocationFetch(identity, serverUrl)

            when (val result = http.execute(request)) {
                is TransponderHttp.Result.Success -> {
                    android.util.Log.d("LocationSync", "fetchSelfLocation response size: ${result.body.size}")
                    android.util.Log.d("LocationSync", "fetchSelfLocation response: ${result.body.decodeToString()}")
                    val locations = processFetchResponse(identity, result.body)
                    android.util.Log.d("LocationSync", "fetchSelfLocation parsed ${locations.size} locations")
                    locations.forEach { loc ->
                        android.util.Log.d("LocationSync", "  pubkey=${loc.pubkey.take(8)}..., hasLocation=${loc.location != null}")
                    }
                    FetchResult.Success(locations)
                }
                is TransponderHttp.Result.HttpError ->
                    FetchResult.Error("Server error: ${result.code} ${result.message}")
                is TransponderHttp.Result.NetworkError ->
                    FetchResult.Error("Network error: ${result.exception.message}")
            }
        } catch (e: Exception) {
            FetchResult.Error("Fetch failed: ${e.message}")
        }
    }
}
