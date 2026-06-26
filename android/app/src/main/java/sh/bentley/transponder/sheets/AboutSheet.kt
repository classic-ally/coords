package sh.bentley.transponder.sheets

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.mikepenz.aboutlibraries.Libs
import com.mikepenz.aboutlibraries.util.withContext
import sh.bentley.transponder.BuildConfig
import sh.bentley.transponder.R
import uniffi.transponder_core.getLicenses
import uniffi.transponder_core.getVersion

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutSheet(
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val coreLicenses = remember { getLicenses() }
    val androidLibs = remember { Libs.Builder().withContext(context).build() }
    val totalCorePackages = remember { coreLicenses.sumOf { it.packages.size } }
    val totalAndroidPackages = remember { androidLibs.libraries.size }

    // Navigation state: null = about, "core" = core deps, "android" = android deps, "map" = map data
    var currentView by remember { mutableStateOf<String?>(null) }
    var expandedLicenses by remember { mutableStateOf(setOf<String>()) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
        ) {
            // Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (currentView != null) {
                    IconButton(onClick = { currentView = null; expandedLicenses = setOf() }) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                    Text(
                        text = when (currentView) {
                            "core" -> "Core Dependencies"
                            "android" -> "Android Dependencies"
                            "map" -> "Map Data"
                            else -> ""
                        },
                        style = MaterialTheme.typography.titleLarge
                    )
                } else {
                    Text(
                        text = "About",
                        style = MaterialTheme.typography.titleLarge
                    )
                }
                Spacer(modifier = Modifier.weight(1f))
                TextButton(onClick = onDismiss) {
                    Text("Done")
                }
            }

            when (currentView) {
                null -> {
                    // About content
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        // AGPL Logo
                        androidx.compose.foundation.Image(
                            painter = painterResource(id = R.drawable.agpl_logo),
                            contentDescription = "AGPL v3 License",
                            modifier = Modifier.height(80.dp)
                        )

                        Spacer(modifier = Modifier.height(16.dp))

                        Text(
                            text = "Coords for Android",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text(
                            text = "v${BuildConfig.VERSION_NAME} · Core ${getVersion()}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        Spacer(modifier = Modifier.height(12.dp))

                        Text(
                            text = "© 2026 Allison Bentley",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = "Made with ❤️ for Helen",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        Spacer(modifier = Modifier.height(24.dp))

                        // Map Data row
                        HorizontalDivider()
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { currentView = "map" }
                                .padding(vertical = 16.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Map Data",
                                style = MaterialTheme.typography.bodyLarge
                            )
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                                contentDescription = "View map data attribution",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        // Core Dependencies row
                        HorizontalDivider()
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { currentView = "core" }
                                .padding(vertical = 16.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Core Dependencies",
                                style = MaterialTheme.typography.bodyLarge
                            )
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = "$totalCorePackages",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                                    contentDescription = "View core dependencies",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(start = 8.dp)
                                )
                            }
                        }

                        // Android Dependencies row
                        HorizontalDivider()
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { currentView = "android" }
                                .padding(vertical = 16.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Android Dependencies",
                                style = MaterialTheme.typography.bodyLarge
                            )
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = "$totalAndroidPackages",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                                    contentDescription = "View Android dependencies",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(start = 8.dp)
                                )
                            }
                        }
                        HorizontalDivider()
                    }
                }
                "map" -> {
                    // Map data attribution
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            text = "Map tiles and geocoding services provided by:",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(bottom = 16.dp)
                        )

                        Column(modifier = Modifier.padding(bottom = 16.dp)) {
                            Text(
                                text = "MapTiler",
                                style = MaterialTheme.typography.titleMedium
                            )
                            Text(
                                text = "© MapTiler",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = "https://www.maptiler.com",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.clickable {
                                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.maptiler.com"))
                                    context.startActivity(intent)
                                }
                            )
                        }

                        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                        Column(modifier = Modifier.padding(bottom = 16.dp)) {
                            Text(
                                text = "OpenStreetMap",
                                style = MaterialTheme.typography.titleMedium
                            )
                            Text(
                                text = "© OpenStreetMap contributors",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = "https://www.openstreetmap.org/copyright",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.clickable {
                                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.openstreetmap.org/copyright"))
                                    context.startActivity(intent)
                                }
                            )
                        }

                        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                        Column(modifier = Modifier.padding(bottom = 16.dp)) {
                            Text(
                                text = "MapLibre",
                                style = MaterialTheme.typography.titleMedium
                            )
                            Text(
                                text = "Open-source mapping library",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = "https://maplibre.org",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.clickable {
                                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://maplibre.org"))
                                    context.startActivity(intent)
                                }
                            )
                        }
                    }
                }
                "core" -> {
                    // Core dependencies list (grouped by license)
                    LazyColumn(
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        items(coreLicenses) { group ->
                            val isExpanded = expandedLicenses.contains(group.id)
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        expandedLicenses = if (isExpanded) {
                                            expandedLicenses - group.id
                                        } else {
                                            expandedLicenses + group.id
                                        }
                                    }
                                    .padding(vertical = 12.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = group.name,
                                            style = MaterialTheme.typography.bodyLarge
                                        )
                                        Text(
                                            text = "${group.packages.size} packages",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    Icon(
                                        imageVector = if (isExpanded) Icons.Default.ArrowUpward else Icons.Default.ArrowDownward,
                                        contentDescription = if (isExpanded) "Collapse" else "Expand",
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }

                                if (isExpanded) {
                                    Spacer(modifier = Modifier.height(12.dp))
                                    Text(
                                        text = group.packages.joinToString(", "),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))
                                    Text(
                                        text = group.text,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                            HorizontalDivider()
                        }
                    }
                }
                "android" -> {
                    // Android dependencies list (grouped by license)
                    val androidByLicense = remember {
                        androidLibs.libraries
                            .groupBy { lib -> lib.licenses.firstOrNull()?.name ?: "Unknown" }
                            .toList()
                            .sortedBy { it.first }
                    }

                    LazyColumn(
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        items(androidByLicense) { (licenseName, libs) ->
                            val isExpanded = expandedLicenses.contains(licenseName)
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        expandedLicenses = if (isExpanded) {
                                            expandedLicenses - licenseName
                                        } else {
                                            expandedLicenses + licenseName
                                        }
                                    }
                                    .padding(vertical = 12.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = licenseName,
                                            style = MaterialTheme.typography.bodyLarge
                                        )
                                        Text(
                                            text = "${libs.size} packages",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    Icon(
                                        imageVector = if (isExpanded) Icons.Default.ArrowUpward else Icons.Default.ArrowDownward,
                                        contentDescription = if (isExpanded) "Collapse" else "Expand",
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }

                                if (isExpanded) {
                                    Spacer(modifier = Modifier.height(12.dp))
                                    Text(
                                        text = libs.joinToString(", ") { "${it.name} ${it.artifactVersion ?: ""}" },
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    val licenseText = libs.firstOrNull()?.licenses?.firstOrNull()?.licenseContent
                                    if (!licenseText.isNullOrBlank()) {
                                        HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))
                                        Text(
                                            text = licenseText,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                            HorizontalDivider()
                        }
                    }
                }
            }
        }
    }
}
