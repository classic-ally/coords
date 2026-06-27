package sh.bentley.transponder

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import sh.bentley.transponder.sheets.FriendDetailSheet
import sh.bentley.transponder.sheets.ProfileSheet
import sh.bentley.transponder.components.FriendList
import uniffi.transponder_core.Friend
import uniffi.transponder_core.findNearestCityInRegion

/**
 * The content placed inside the bottom sheet: profile, friend detail, or the
 * friends list with its toolbar. Reads and mutates [MapScreenState]; UI-only
 * actions (opening dialogs, navigating, requesting permissions) are delegated
 * to the host via callbacks.
 */
@Composable
fun SheetContent(
    state: MapScreenState,
    showProfile: Boolean,
    isFetchingFriends: Boolean,
    listBottomPadding: Dp,
    onProfileDismiss: () -> Unit,
    onOpenProfile: () -> Unit,
    onRefreshFriends: () -> Unit,
    onOpenStylePicker: () -> Unit,
    onOpenServerUrl: () -> Unit,
    onOpenEditProfileName: () -> Unit,
    onOpenEditFriendName: () -> Unit,
    onOpenLocationSettings: () -> Unit,
    onShowAbout: () -> Unit,
    onAddFriend: () -> Unit,
    onAutoShareEnabledChange: (Boolean) -> Unit,
    onDeleteFriendRequest: (Friend) -> Unit,
    onSelectFriend: (pubkey: String, lat: Double, lng: Double) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val selectedFriend = state.selectedFriend

    when {
        showProfile -> {
            val currentLocationData = state.currentLocation?.let { loc ->
                LocationDisplayData(
                    lat = loc.latitude,
                    lng = loc.longitude,
                    accuracy = state.currentAccuracy,
                    timestamp = state.currentLocationTimestamp ?: System.currentTimeMillis(),
                    city = findNearestCityInRegion(loc.latitude, loc.longitude)
                )
            }
            ProfileSheet(
                name = state.myName,
                identityStore = IdentityStore(context),
                currentLocation = currentLocationData,
                serverLocation = state.serverLocation,
                showServerLocation = state.showServerLocation,
                onShowServerLocationChange = { newValue ->
                    scope.launch {
                        state.toggleServerLocation(newValue)?.let { msg ->
                            android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_SHORT).show()
                        }
                    }
                },
                isFetchingServerLocation = state.isFetchingServerLocation,
                autoShareEnabled = state.autoShareEnabled,
                onAutoShareEnabledChange = onAutoShareEnabledChange,
                isUploading = state.isUploading,
                onUpload = {
                    scope.launch {
                        when (val r = state.uploadNow()) {
                            is LocationRepository.Result.Skipped ->
                                android.widget.Toast.makeText(context, "No friends to share with", android.widget.Toast.LENGTH_SHORT).show()
                            is LocationRepository.Result.Error ->
                                android.widget.Toast.makeText(context, r.message, android.widget.Toast.LENGTH_SHORT).show()
                            is LocationRepository.Result.NoLocation ->
                                android.widget.Toast.makeText(context, "Could not get current location", android.widget.Toast.LENGTH_SHORT).show()
                            is LocationRepository.Result.Uploaded -> {}
                        }
                    }
                },
                onDismiss = onProfileDismiss,
                onNameEdit = onOpenEditProfileName,
                onEditServerUrl = onOpenServerUrl,
                onLocationSettings = onOpenLocationSettings,
                onShowAbout = onShowAbout,
                serverVersion = state.serverVersion
            )
        }
        selectedFriend != null -> {
            FriendDetailSheet(
                friend = selectedFriend,
                onDismiss = {
                    state.selectedFriendPubkey = null
                    state.requestCamera(CameraAction.FitAllFriends)
                },
                onNameEdit = onOpenEditFriendName,
                onToggleShare = { state.toggleShare(selectedFriend) },
                onToggleFetch = { state.toggleFetch(selectedFriend) },
                onDelete = {
                    state.deleteFriend(selectedFriend)
                    state.requestCamera(CameraAction.FitAllFriends)
                }
            )
        }
        else -> {
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
                    Text(text = "Friends", style = MaterialTheme.typography.titleLarge)
                    Row {
                        if (state.mapBearing != 0.0) {
                            IconButton(onClick = { state.requestCamera(CameraAction.ResetNorth) }) {
                                Icon(
                                    imageVector = Icons.Default.Explore,
                                    contentDescription = "Reset to north",
                                    modifier = Modifier.rotate(-state.mapBearing.toFloat() - 45f)
                                )
                            }
                        }
                        if (state.isEditMode) {
                            IconButton(onClick = onRefreshFriends, enabled = !isFetchingFriends) {
                                if (isFetchingFriends) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(24.dp),
                                        strokeWidth = 2.dp
                                    )
                                } else {
                                    Icon(imageVector = Icons.Default.Refresh, contentDescription = "Refresh friends")
                                }
                            }
                            IconButton(onClick = { state.isEditMode = false }) {
                                Icon(imageVector = Icons.Default.Check, contentDescription = "Done editing")
                            }
                        } else {
                            IconButton(onClick = onOpenStylePicker) {
                                Icon(imageVector = Icons.Default.Map, contentDescription = "Change map style")
                            }
                            IconButton(onClick = onAddFriend) {
                                Icon(imageVector = Icons.Default.Add, contentDescription = "Add Friend")
                            }
                            IconButton(onClick = { state.isEditMode = true }) {
                                Icon(imageVector = Icons.Default.Edit, contentDescription = "Edit friends")
                            }
                            IconButton(onClick = onOpenProfile) {
                                Icon(imageVector = Icons.Default.Person, contentDescription = "Profile")
                            }
                        }
                    }
                }

                FriendList(
                    items = state.friendsWithCities,
                    currentLocation = state.currentLocation,
                    isEditMode = state.isEditMode,
                    modifier = Modifier.weight(1f),
                    bottomPadding = listBottomPadding,
                    onClick = { friend ->
                        if (!state.isEditMode) {
                            friend.location?.let { loc ->
                                onSelectFriend(friend.pubkey, loc.latitude, loc.longitude)
                            }
                        }
                    },
                    onToggleShare = { friend -> state.toggleShare(friend) },
                    onToggleFetch = { friend -> state.toggleFetch(friend) },
                    onDelete = { friend -> onDeleteFriendRequest(friend) }
                )
            }
        }
    }
}
