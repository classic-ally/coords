package sh.bentley.transponder.components

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.maplibre.android.geometry.LatLng
import uniffi.transponder_core.City
import uniffi.transponder_core.Friend

data class FriendDisplayData(
    val friend: Friend,
    val city: City?
)

@Composable
fun FriendList(
    items: List<FriendDisplayData>,
    currentLocation: LatLng?,
    isEditMode: Boolean,
    onClick: (Friend) -> Unit,
    onToggleShare: (Friend) -> Unit,
    onToggleFetch: (Friend) -> Unit,
    onDelete: (Friend) -> Unit,
    modifier: Modifier = Modifier,
    bottomPadding: Dp = 0.dp,
) {
    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(bottom = bottomPadding)
    ) {
        items(items, key = { it.friend.pubkey }) { data ->
            FriendRow(
                friend = data.friend,
                city = data.city,
                currentLocation = currentLocation,
                isEditMode = isEditMode,
                onClick = { onClick(data.friend) },
                onToggleShare = { onToggleShare(data.friend) },
                onToggleFetch = { onToggleFetch(data.friend) },
                onDelete = { onDelete(data.friend) }
            )
        }
    }
}
