package sh.bentley.transponder.sheets

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import sh.bentley.transponder.components.SheetHeader
import sh.bentley.transponder.displayName
import sh.bentley.transponder.formatAge
import uniffi.transponder_core.Friend
import uniffi.transponder_core.findNearestCityInRegion

@Composable
fun FriendDetailSheet(
    friend: Friend,
    onDismiss: () -> Unit,
    onNameEdit: () -> Unit,
    onToggleShare: () -> Unit,
    onToggleFetch: () -> Unit,
    onDelete: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    var showDeleteConfirmation by remember { mutableStateOf(false) }

    // Delete confirmation dialog
    if (showDeleteConfirmation) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirmation = false },
            title = { Text("Delete ${friend.name}?") },
            text = { Text("This will remove ${friend.name} from your friends list.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteConfirmation = false
                        onDelete()
                        onDismiss()
                    }
                ) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirmation = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    // Parse the friend's color for the avatar
    val avatarColor = remember(friend.color) {
        try { ComposeColor(android.graphics.Color.parseColor(friend.color)) }
        catch (e: Exception) { ComposeColor.Gray }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
    ) {
        SheetHeader(name = friend.name, avatarColor = avatarColor) {
            IconButton(onClick = onNameEdit) {
                Icon(imageVector = Icons.Default.Edit, contentDescription = "Edit name")
            }
            IconButton(onClick = onDismiss) {
                Icon(imageVector = Icons.Default.Close, contentDescription = "Close")
            }
        }

        // Location info
        friend.location?.let { loc ->
            val city = findNearestCityInRegion(loc.latitude, loc.longitude)
            val age = formatAge(loc.timestamp)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = city?.displayName() ?: "Unknown location",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = age,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // Open in maps button
            OutlinedButton(
                onClick = {
                    val geoUri = Uri.parse("geo:${loc.latitude},${loc.longitude}?q=${loc.latitude},${loc.longitude}(${friend.name})")
                    val intent = Intent(Intent.ACTION_VIEW, geoUri)
                    context.startActivity(intent)
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(
                    imageVector = Icons.Default.Navigation,
                    contentDescription = null,
                    modifier = Modifier.padding(end = 8.dp)
                )
                Text("Open in Maps")
            }
        } ?: Text(
            text = "No location available",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 12.dp)
        )

        HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

        // Share with friend toggle
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(end = 12.dp)
                )
                Text("Share with ${friend.name}")
            }
            Switch(
                checked = friend.shareWith,
                onCheckedChange = { onToggleShare() }
            )
        }

        // See friend toggle
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(end = 12.dp)
                )
                Text("See ${friend.name}")
            }
            Switch(
                checked = friend.fetchFrom,
                onCheckedChange = { onToggleFetch() }
            )
        }

        // Delete row - appears when both toggles are off
        if (!friend.shareWith && !friend.fetchFrom) {
            HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { showDeleteConfirmation = true }
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.Delete,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(end = 12.dp)
                )
                Text(
                    text = "Delete ${friend.name}",
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}
