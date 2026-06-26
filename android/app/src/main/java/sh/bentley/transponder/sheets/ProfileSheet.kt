package sh.bentley.transponder.sheets

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import sh.bentley.transponder.BuildConfig
import sh.bentley.transponder.IdentityStore
import sh.bentley.transponder.LocationDisplayData
import sh.bentley.transponder.components.SheetHeader
import sh.bentley.transponder.displayName
import sh.bentley.transponder.formatAge
import uniffi.transponder_core.generateFriendLink
import uniffi.transponder_core.getVersion

@Composable
fun ProfileSheet(
    name: String,
    identityStore: IdentityStore,
    currentLocation: LocationDisplayData?,
    serverLocation: LocationDisplayData?,
    showServerLocation: Boolean,
    onShowServerLocationChange: (Boolean) -> Unit,
    isFetchingServerLocation: Boolean,
    autoShareEnabled: Boolean,
    onAutoShareEnabledChange: (Boolean) -> Unit,
    isUploading: Boolean,
    onUpload: () -> Unit,
    onDismiss: () -> Unit,
    onNameEdit: () -> Unit,
    onEditServerUrl: () -> Unit,
    onLocationSettings: () -> Unit,
    onShowAbout: () -> Unit,
    serverVersion: String?,
    modifier: Modifier = Modifier
) {
    // Determine which location to display based on toggle
    val displayLocation = if (showServerLocation) serverLocation else currentLocation
    var showShareDialog by remember { mutableStateOf(false) }

    // Generate friend link and QR code
    val identity = remember { identityStore.getIdentity() }
    val friendLink = remember(identity, identityStore.serverUrl, name) {
        identity?.let { id ->
            identityStore.serverUrl?.let { serverUrl ->
                generateFriendLink(id, serverUrl, name)
            }
        }
    }

    // Share dialog
    if (showShareDialog && friendLink != null) {
        MyLinkSheet(
            link = friendLink,
            onDismiss = { showShareDialog = false }
        )
    }

    // Parse the user's color for the avatar
    val avatarColor = remember(identityStore.myColor) {
        identityStore.myColor?.let {
            try { ComposeColor(android.graphics.Color.parseColor(it)) }
            catch (e: Exception) { ComposeColor.Gray }
        } ?: ComposeColor.Gray
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
    ) {
        SheetHeader(name = name, avatarColor = avatarColor) {
            IconButton(
                onClick = onUpload,
                enabled = !isUploading && currentLocation != null
            ) {
                if (isUploading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        strokeWidth = 2.dp
                    )
                } else {
                    Icon(imageVector = Icons.Default.CloudUpload, contentDescription = "Upload location")
                }
            }
            IconButton(onClick = onNameEdit) {
                Icon(imageVector = Icons.Default.Edit, contentDescription = "Edit name")
            }
            IconButton(onClick = onDismiss) {
                Icon(imageVector = Icons.Default.Close, contentDescription = "Close")
            }
        }

        // Location info - shows current or server based on toggle
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = displayLocation?.city?.displayName() ?: "Unknown location",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            // Only show time when viewing server location
            if (showServerLocation) {
                displayLocation?.let { loc ->
                    Text(
                        text = formatAge(loc.timestamp.toULong()),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

        // Master sharing toggle
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text("Share automatically")
                Text(
                    text = if (autoShareEnabled) "Sharing in background" else "Manual sharing only",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(
                checked = autoShareEnabled,
                onCheckedChange = onAutoShareEnabledChange
            )
        }

        // Show server location toggle
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text("Show server location")
                Text(
                    text = when {
                        isFetchingServerLocation -> "Fetching from server..."
                        showServerLocation && serverLocation != null -> "Showing server location"
                        else -> "Showing current location"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (isFetchingServerLocation) {
                CircularProgressIndicator(
                    modifier = Modifier.size(24.dp),
                    strokeWidth = 2.dp
                )
            } else {
                Switch(
                    checked = showServerLocation,
                    onCheckedChange = onShowServerLocationChange
                )
            }
        }

        HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

        // Share my link button
        OutlinedButton(
            onClick = { showShareDialog = true },
            modifier = Modifier.fillMaxWidth(),
            enabled = friendLink != null
        ) {
            Icon(
                imageVector = Icons.Default.QrCode2,
                contentDescription = null,
                modifier = Modifier.padding(end = 8.dp)
            )
            Text("Share My Link")
        }

        HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

        // Server URL
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(if (serverVersion != null) "Server v$serverVersion" else "Server")
                Text(
                    text = identityStore.serverUrl ?: "Not configured",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            IconButton(onClick = onEditServerUrl) {
                Icon(
                    imageVector = Icons.Default.Edit,
                    contentDescription = "Edit server",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // Location settings row
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onLocationSettings() }
                .padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Location Settings")
                Text(
                    text = "GPS timeout, accuracy thresholds",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                contentDescription = "Open",
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        // App and core version
        Text(
            text = "Coords Android v${BuildConfig.VERSION_NAME} · Core ${getVersion()}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .padding(top = 8.dp)
                .clickable { onShowAbout() }
        )
    }
}
