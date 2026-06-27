package sh.bentley.transponder

import android.content.Context
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

    companion object {
        fun from(
            context: Context,
            identityStore: IdentityStore,
        ): MapScreenState = MapScreenState(
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
        )
    }
}
