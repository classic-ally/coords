package sh.bentley.transponder

import android.content.Context
import android.location.Location
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.maplibre.android.geometry.LatLng
import sh.bentley.transponder.components.FriendDisplayData
import uniffi.transponder_core.City
import uniffi.transponder_core.Friend
import uniffi.transponder_core.findNearestCityInRegion
import uniffi.transponder_core.listFriends as coreListFriends
import uniffi.transponder_core.mockFriends as coreMockFriends
import uniffi.transponder_core.removeFriend as coreRemoveFriend
import uniffi.transponder_core.updateFriend as coreUpdateFriend

/** Pending camera actions for deferred execution after sheet layout */
sealed class CameraAction {
    data class CenterOn(val target: LatLng) : CameraAction()
    data object FitAllFriends : CameraAction()
    data object CenterOnMyLocation : CameraAction()
    data object ResetNorth : CameraAction()
}

/**
 * Holds MapScreen domain state and the side-effecting operations that sync with
 * the core (friends list, auto-share preference) and the foreground service. The
 * core-touching operations are injected as seams so the synced setters can be
 * unit-tested without the native library or Android.
 */
@Stable
class MapScreenState(
    private val listFriends: () -> List<Friend>,
    private val updateFriend: (pubkey: String, share: Boolean?, fetch: Boolean?, name: String?) -> Unit,
    private val removeFriend: (pubkey: String) -> Unit,
    private val getAutoSharePref: () -> Boolean,
    private val setAutoSharePref: (Boolean) -> Unit,
    private val onGateChanged: () -> Unit,
    private val cityFor: (latitude: Double, longitude: Double) -> City?,
    private val acquireLocation: suspend (LocationFreshness) -> LocationRequestResult,
    private val push: suspend (Location, String) -> LocationRepository.Result,
    private val fetchSelfLocation: suspend () -> LocationSyncService.FetchResult,
    private val scheduleWorker: () -> Unit,
    private val cancelWorker: () -> Unit,
    initialName: String,
) {
    var friends by mutableStateOf(listFriends())
        private set
    var autoShareEnabled by mutableStateOf(getAutoSharePref())
        private set

    // Plain UI state (no core sync)
    var selectedFriendPubkey by mutableStateOf<String?>(null)
    var isEditMode by mutableStateOf(false)
    var showServerLocation by mutableStateOf(false)
    var currentLocation by mutableStateOf<LatLng?>(null)
    var currentAccuracy by mutableStateOf(0f)
    var currentLocationTimestamp by mutableStateOf<Long?>(null)
    var serverLocation by mutableStateOf<LocationDisplayData?>(null)
    var serverVersion by mutableStateOf<String?>(null)
    var myName by mutableStateOf(initialName)
    var isUploading by mutableStateOf(false)
        private set
    var isFetchingServerLocation by mutableStateOf(false)
        private set

    // Map output state
    var pendingCameraAction by mutableStateOf<CameraAction?>(null)
        private set
    var mapBearing by mutableStateOf(0.0)
        private set

    fun requestCamera(action: CameraAction) {
        pendingCameraAction = action
    }

    fun clearCameraAction() {
        pendingCameraAction = null
    }

    fun onBearing(bearing: Double) {
        mapBearing = bearing
    }

    val selectedFriend: Friend?
        get() = friends.find { it.pubkey == selectedFriendPubkey }

    val friendsWithCities: List<FriendDisplayData>
        get() = friends.map { friend ->
            val city = friend.location?.let { cityFor(it.latitude, it.longitude) }
            FriendDisplayData(friend, city)
        }

    fun refreshFriends() {
        friends = listFriends()
    }

    fun setAutoShare(on: Boolean) {
        autoShareEnabled = on
        setAutoSharePref(on)
        onGateChanged()
    }

    fun toggleShare(friend: Friend) {
        updateFriend(friend.pubkey, !friend.shareWith, null, null)
        refreshFriends()
    }

    fun toggleFetch(friend: Friend) {
        updateFriend(friend.pubkey, null, !friend.fetchFrom, null)
        refreshFriends()
    }

    fun deleteFriend(friend: Friend) {
        removeFriend(friend.pubkey)
        if (selectedFriendPubkey == friend.pubkey) selectedFriendPubkey = null
        refreshFriends()
        onGateChanged()
    }

    /** Enable auto-share when background permission is already granted. */
    fun setAutoShareGranted() {
        autoShareEnabled = true
        setAutoSharePref(true)
        scheduleWorker()
        onGateChanged()
    }

    fun disableAutoShare() {
        autoShareEnabled = false
        setAutoSharePref(false)
        cancelWorker()
        onGateChanged()
    }

    /** Acquire a fresh fix and upload it; returns the result for UI feedback. */
    suspend fun uploadNow(): LocationRepository.Result {
        isUploading = true
        val result = acquireLocation(LocationFreshness.ALWAYS_FRESH)
        val location = result.location
        if (location == null) {
            isUploading = false
            return LocationRepository.Result.NoLocation
        }
        currentLocation = LatLng(location.latitude, location.longitude)
        currentLocationTimestamp = location.time
        currentAccuracy = location.accuracy
        val r = push(location, result.source)
        if (r is LocationRepository.Result.Uploaded) {
            serverLocation = displayData(location.latitude, location.longitude, location.accuracy, location.time)
        }
        isUploading = false
        return r
    }

    /**
     * Toggle between showing the server-published location and the current one.
     * Returns a user-facing message to surface, or null on success.
     */
    suspend fun toggleServerLocation(on: Boolean): String? {
        if (on) {
            isFetchingServerLocation = true
            val res = fetchSelfLocation()
            isFetchingServerLocation = false
            when (res) {
                is LocationSyncService.FetchResult.Success -> {
                    val loc = res.locations.firstOrNull()?.location
                        ?: return "No location published yet"
                    serverLocation = displayData(loc.latitude, loc.longitude, loc.accuracy, loc.timestamp.toLong())
                    showServerLocation = true
                }
                is LocationSyncService.FetchResult.Error -> return res.message
            }
        } else {
            showServerLocation = false
            acquireLocation(LocationFreshness.CACHED_OKAY).location?.let { loc ->
                currentLocation = LatLng(loc.latitude, loc.longitude)
                currentLocationTimestamp = loc.time
                currentAccuracy = loc.accuracy
            }
        }
        return null
    }

    private fun displayData(lat: Double, lng: Double, accuracy: Float, timestamp: Long) =
        LocationDisplayData(lat, lng, accuracy, timestamp, cityFor(lat, lng))

    companion object {
        fun from(
            context: Context,
            identityStore: IdentityStore,
        ): MapScreenState {
            val sync = LocationSyncService(identityStore)
            val repo = LocationRepository.from(context.applicationContext, identityStore)
            return MapScreenState(
            listFriends = {
                try {
                    val real = coreListFriends()
                    if (real.isEmpty() && BuildConfig.USE_MOCK_FRIENDS) coreMockFriends() else real
                } catch (e: Exception) {
                    if (BuildConfig.USE_MOCK_FRIENDS) coreMockFriends() else emptyList()
                }
            },
            updateFriend = { pubkey, share, fetch, name ->
                coreUpdateFriend(pubkey, share, fetch, name)
            },
            removeFriend = { pubkey -> coreRemoveFriend(pubkey) },
            getAutoSharePref = { identityStore.autoShareEnabled },
            setAutoSharePref = { identityStore.autoShareEnabled = it },
            onGateChanged = { LocationUploadService.poke(context) },
            cityFor = { lat, lng -> findNearestCityInRegion(lat, lng) },
            acquireLocation = { freshness ->
                requestLocation(context, freshness, LocationSettings.from(identityStore))
            },
            push = { location, source -> repo.push(location, source) },
            fetchSelfLocation = { sync.fetchSelfLocation() },
            scheduleWorker = { LocationSyncWorker.schedule(context) },
            cancelWorker = { LocationSyncWorker.cancel(context) },
            initialName = identityStore.displayName ?: "Me",
            )
        }
    }
}
