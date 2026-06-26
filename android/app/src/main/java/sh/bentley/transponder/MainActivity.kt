package sh.bentley.transponder

import android.content.Intent
import android.location.Location
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import sh.bentley.transponder.ui.theme.TransponderTheme
import uniffi.transponder_core.initStorage
import uniffi.transponder_core.migrateServerUrls
import uniffi.transponder_core.parseFriendLink

class MainActivity : ComponentActivity() {
    private lateinit var identityStore: IdentityStore
    private var pendingDeepLink: String? = null
    private var pendingLocationDeferred: Deferred<Location?>? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Initialize Rust storage for persistent friends database
        try {
            initStorage(filesDir.absolutePath)
            // Migrate friends from old server domain to new one
            val migrated = migrateServerUrls("transponder.bentley.sh", "coord.is")
            if (migrated > 0u) {
                println("Migrated $migrated friend(s) to coord.is")
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        identityStore = IdentityStore(this)

        // Migrate user's own server URL from old domain to new one
        identityStore.migrateServerUrl("transponder.bentley.sh", "coord.is")

        // Schedule background sync if user has identity and background location permission
        if (identityStore.hasIdentity() && LocationSyncWorker.hasBackgroundLocationPermission(this)) {
            LocationSyncWorker.schedule(this)
        }

        // Handle deep link from initial launch
        handleDeepLink(intent)

        enableEdgeToEdge()
        setContent {
            TransponderTheme {
                var hasIdentity by remember { mutableStateOf(identityStore.hasIdentity()) }
                var showShareBack by remember { mutableStateOf(false) }
                var addedFriendName by remember { mutableStateOf<String?>(null) }
                var currentPendingLink by remember { mutableStateOf(pendingDeepLink) }

                // Process pending deep link after onboarding
                LaunchedEffect(hasIdentity, currentPendingLink) {
                    if (hasIdentity && currentPendingLink != null) {
                        val link = currentPendingLink
                        currentPendingLink = null
                        pendingDeepLink = null
                        if (link != null) {
                            val name = processDeepLink(link)
                            if (name != null) {
                                addedFriendName = name
                                showShareBack = true
                            }
                        }
                    }
                }

                when {
                    showShareBack && addedFriendName != null -> {
                        ShareBackScreen(
                            identityStore = identityStore,
                            friendName = addedFriendName!!,
                            onDismiss = {
                                showShareBack = false
                                // Fetch friend locations in background - they may have uploaded when adding us
                                lifecycleScope.launch {
                                    val syncService = LocationSyncService(identityStore)
                                    syncService.fetchTrackedFriends()
                                }
                            }
                        )
                    }
                    hasIdentity -> {
                        MainScreen(
                            identityStore = identityStore,
                            onDeepLink = { link ->
                                // Kick off location request for in-app deep links too
                                pendingLocationDeferred = lifecycleScope.async {
                                    requestLocation(this@MainActivity, LocationFreshness.CACHED_OKAY, LocationSettings.from(identityStore)).location
                                }
                                lifecycleScope.launch {
                                    val name = processDeepLink(link)
                                    if (name != null) {
                                        addedFriendName = name
                                        showShareBack = true
                                    }
                                }
                            }
                        )
                    }
                    else -> {
                        OnboardingScreen(
                            identityStore = identityStore,
                            onComplete = {
                                hasIdentity = true
                                // Schedule background sync after onboarding if permission granted
                                if (LocationSyncWorker.hasBackgroundLocationPermission(this@MainActivity)) {
                                    LocationSyncWorker.schedule(this@MainActivity)
                                }
                            }
                        )
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleDeepLink(intent)
    }

    private fun handleDeepLink(intent: Intent?) {
        val data = intent?.data ?: return
        val url = data.toString()
        if (url.startsWith("coord://")) {
            pendingDeepLink = url
            // Kick off location request immediately so GPS can warm up
            // while user reviews the share-back screen (only if onboarded)
            if (identityStore.hasIdentity()) {
                pendingLocationDeferred = lifecycleScope.async {
                    requestLocation(this@MainActivity, LocationFreshness.ALWAYS_FRESH, LocationSettings.from(identityStore)).location
                }
            }
        }
    }

    private suspend fun processDeepLink(url: String): String? {
        return try {
            val parsed = parseFriendLink(url)
            val syncService = LocationSyncService(identityStore)
            val result = syncService.addFriendAndUploadLocation(
                pubkey = parsed.pubkey,
                server = parsed.server,
                name = parsed.name,
                locationDeferred = pendingLocationDeferred
            )
            pendingLocationDeferred = null  // Clear after use
            when (result) {
                is LocationSyncService.AddFriendResult.AddFriendFailed -> {
                    android.util.Log.e("MainActivity", "Failed to add friend: ${result.message}")
                    null
                }
                is LocationSyncService.AddFriendResult.SuccessUploadFailed -> {
                    android.util.Log.w("MainActivity", "Friend added but upload failed: ${result.message}")
                    parsed.name
                }
                else -> parsed.name
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    override fun onResume() {
        super.onResume()
        LocationUploadService.poke(this)
    }
}

