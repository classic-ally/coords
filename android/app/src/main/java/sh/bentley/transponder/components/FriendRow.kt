package sh.bentley.transponder.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.RemoveCircle
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.ui.unit.dp
import org.maplibre.android.geometry.LatLng
import sh.bentley.transponder.displayName
import sh.bentley.transponder.formatAge
import sh.bentley.transponder.formatDistance
import uniffi.transponder_core.City
import uniffi.transponder_core.Friend

@Composable
fun FriendRow(
    friend: Friend,
    city: City?,
    currentLocation: LatLng?,
    isEditMode: Boolean,
    onClick: () -> Unit,
    onToggleShare: () -> Unit,
    onToggleFetch: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier
) {
    // Distance is cheap to compute and depends on currentLocation which can change
    val distance = remember(friend.location, currentLocation) {
        friend.location?.let { loc ->
            currentLocation?.let { myLoc ->
                val friendLatLng = LatLng(loc.latitude, loc.longitude)
                myLoc.distanceTo(friendLatLng).toFloat()
            }
        }
    }

    ListItem(
        headlineContent = { Text(friend.name) },
        supportingContent = {
            if (isEditMode) {
                // Edit mode: show location age
                val loc = friend.location
                Text(text = if (loc != null) formatAge(loc.timestamp) else "No location")
            } else {
                // Normal mode: show city
                val cityText = city?.displayName() ?: ""
                Text(text = if (friend.location != null) cityText.ifBlank { "Unknown location" } else "No location")
            }
        },
        trailingContent = {
            if (isEditMode) {
                // Edit mode: show share/fetch toggles and delete
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onToggleShare) {
                        Icon(
                            imageVector = if (friend.shareWith) Icons.Filled.ArrowUpward else Icons.Outlined.ArrowUpward,
                            contentDescription = "Share with",
                            tint = if (friend.shareWith) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    IconButton(onClick = onToggleFetch) {
                        Icon(
                            imageVector = if (friend.fetchFrom) Icons.Filled.ArrowDownward else Icons.Outlined.ArrowDownward,
                            contentDescription = "Fetch from",
                            tint = if (friend.fetchFrom) ComposeColor(0xFF4CAF50) else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    IconButton(onClick = onDelete) {
                        Icon(
                            imageVector = Icons.Filled.RemoveCircle,
                            contentDescription = "Delete",
                            tint = MaterialTheme.colorScheme.error
                        )
                    }
                }
            } else {
                // Normal mode: show distance and time ago
                friend.location?.let { loc ->
                    val age = formatAge(loc.timestamp)
                    Column(horizontalAlignment = Alignment.End) {
                        if (distance != null) {
                            Text(
                                text = formatDistance(distance),
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                        Text(
                            text = age,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        },
        modifier = modifier.clickable(onClick = onClick)
    )
}
