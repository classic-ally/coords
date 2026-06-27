package sh.bentley.transponder

import android.location.Location as AndroidLocation
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import uniffi.transponder_core.City
import uniffi.transponder_core.Friend
import uniffi.transponder_core.Location

private fun friend(
    name: String,
    share: Boolean = true,
    fetch: Boolean = true,
    location: Location? = null,
) = Friend(
    pubkey = "pk-$name",
    server = "coord.is",
    name = name,
    shareWith = share,
    fetchFrom = fetch,
    location = location,
    fetchedAt = null,
    color = "#4A90D9"
)

private fun androidLocation(lat: Double = 51.0, lng: Double = -1.0, acc: Float = 5f, time: Long = 1_000L) =
    mockk<AndroidLocation>(relaxed = true) {
        every { latitude } returns lat
        every { longitude } returns lng
        every { accuracy } returns acc
        every { this@mockk.time } returns time
    }

class MapScreenStateTest {

    // Should: seed the friends list from the core list seam at construction
    // Should: seed autoShareEnabled from the preference getter at construction
    @Test
    fun seeds_state_from_seams_at_construction() {
        val seed = listOf(friend("Alex"))
        val state = newState(listFriends = { seed }, autoShare = true)
        assertEquals(seed, state.friends)
        assertTrue(state.autoShareEnabled)
    }

    // Should: persist the new auto-share preference via the setter seam
    // Should: poke the foreground-service gate so it starts/stops to match
    // Should: reflect the new value in autoShareEnabled
    @Test
    fun set_auto_share_persists_pokes_and_updates_state() {
        val setPref: (Boolean) -> Unit = mockk(relaxed = true)
        val onGate: () -> Unit = mockk(relaxed = true)
        val state = newState(setAutoSharePref = setPref, onGateChanged = onGate, autoShare = false)

        state.setAutoShare(true)

        assertTrue(state.autoShareEnabled)
        verify { setPref(true) }
        verify { onGate() }
    }

    // Should: enable auto-share, schedule the periodic worker, and poke the service gate
    @Test
    fun set_auto_share_granted_schedules_worker_and_pokes() {
        val setPref: (Boolean) -> Unit = mockk(relaxed = true)
        val schedule: () -> Unit = mockk(relaxed = true)
        val onGate: () -> Unit = mockk(relaxed = true)
        val state = newState(setAutoSharePref = setPref, scheduleWorker = schedule, onGateChanged = onGate)

        state.setAutoShareGranted()

        assertTrue(state.autoShareEnabled)
        verify { setPref(true) }
        verify { schedule() }
        verify { onGate() }
    }

    // Should: disable auto-share, cancel the periodic worker, and poke the gate to stop the service
    @Test
    fun disable_auto_share_cancels_worker_and_pokes() {
        val setPref: (Boolean) -> Unit = mockk(relaxed = true)
        val cancel: () -> Unit = mockk(relaxed = true)
        val onGate: () -> Unit = mockk(relaxed = true)
        val state = newState(setAutoSharePref = setPref, cancelWorker = cancel, onGateChanged = onGate, autoShare = true)

        state.disableAutoShare()

        assertFalse(state.autoShareEnabled)
        verify { setPref(false) }
        verify { cancel() }
        verify { onGate() }
    }

    // Impact: the flag is negated before the core write; a sign slip would toggle the wrong direction
    // Should: write the negated shareWith flag for the friend, leaving fetch and name null
    // Should: refresh the friends list from core after the write
    @Test
    fun toggle_share_writes_negated_share_flag_and_refreshes() {
        val update: (String, Boolean?, Boolean?, String?) -> Unit = mockk(relaxed = true)
        val list: () -> List<Friend> = mockk()
        val f = friend("Alex", share = true)
        every { list() } returns listOf(f)
        val state = newState(listFriends = list, updateFriend = update)

        state.toggleShare(f)

        verify { update("pk-Alex", false, null, null) }
        verify(atLeast = 2) { list() } // once at construction, once on refresh
    }

    // Should: write the negated fetchFrom flag, leaving share and name null
    // Should: refresh the friends list after the write
    @Test
    fun toggle_fetch_writes_negated_fetch_flag_and_refreshes() {
        val update: (String, Boolean?, Boolean?, String?) -> Unit = mockk(relaxed = true)
        val list: () -> List<Friend> = mockk()
        val f = friend("Sam", fetch = false)
        every { list() } returns listOf(f)
        val state = newState(listFriends = list, updateFriend = update)

        state.toggleFetch(f)

        verify { update("pk-Sam", null, true, null) }
        verify(atLeast = 2) { list() }
    }

    // Should: remove the friend from core by pubkey
    // Should: poke the foreground-service gate, since the share-recipient set may have shrunk
    // Should: refresh the friends list after removal
    @Test
    fun delete_friend_removes_pokes_and_refreshes() {
        val remove: (String) -> Unit = mockk(relaxed = true)
        val onGate: () -> Unit = mockk(relaxed = true)
        val list: () -> List<Friend> = mockk()
        every { list() } returns emptyList()
        val state = newState(listFriends = list, removeFriend = remove, onGateChanged = onGate)

        state.deleteFriend(friend("Jo"))

        verify { remove("pk-Jo") }
        verify { onGate() }
        verify(atLeast = 2) { list() }
    }

    // Should: clear the selection when the deleted friend was the selected one
    @Test
    fun delete_selected_friend_clears_selection() {
        val f = friend("Alex")
        val state = newState(listFriends = { listOf(f) })
        state.selectedFriendPubkey = "pk-Alex"

        state.deleteFriend(f)

        assertNull(state.selectedFriendPubkey)
    }

    // Should not: clear the selection when a different friend is deleted
    @Test
    fun delete_other_friend_keeps_selection() {
        val alex = friend("Alex")
        val sam = friend("Sam")
        val state = newState(listFriends = { listOf(alex, sam) })
        state.selectedFriendPubkey = "pk-Alex"

        state.deleteFriend(sam)

        assertEquals("pk-Alex", state.selectedFriendPubkey)
    }

    // Should: re-pull the friends list from core, replacing current state
    @Test
    fun refresh_friends_repulls_list() {
        val first = listOf(friend("Alex"))
        val second = listOf(friend("Alex"), friend("Sam"))
        val list: () -> List<Friend> = mockk()
        every { list() } returnsMany listOf(first, second)
        val state = newState(listFriends = list)
        assertEquals(first, state.friends)

        state.refreshFriends()

        assertEquals(second, state.friends)
    }

    // Should: resolve the friend whose pubkey matches the current selection
    // Should not: resolve any friend when the selection is unset
    @Test
    fun selected_friend_resolves_by_selection() {
        val alex = friend("Alex")
        val state = newState(listFriends = { listOf(alex) })

        assertNull(state.selectedFriend)

        state.selectedFriendPubkey = "pk-Alex"
        assertSame(alex, state.selectedFriend)
    }

    // Impact: city lookup is a native call; skipping it for location-less friends avoids needless work
    // Should: attach a looked-up city for friends that have a location
    // Should not: invoke the city lookup for friends without a location
    @Test
    fun friends_with_cities_looks_up_only_located_friends() {
        val located = friend("Alex", location = mockk(relaxed = true) {
            every { latitude } returns 51.0
            every { longitude } returns -1.0
        })
        val unlocated = friend("Sam", location = null)
        val cityFor: (Double, Double) -> City? = mockk()
        every { cityFor(any(), any()) } returns mockk(relaxed = true)
        val state = newState(listFriends = { listOf(located, unlocated) }, cityFor = cityFor)

        val result = state.friendsWithCities

        assertEquals(2, result.size)
        verify(exactly = 1) { cityFor(51.0, -1.0) }
    }

    // Should: queue the requested camera action for the map to consume
    @Test
    fun request_camera_sets_pending_action() {
        val state = newState()
        val target = CameraAction.FitAllFriends

        state.requestCamera(target)

        assertSame(target, state.pendingCameraAction)
    }

    // Should: drop the pending camera action once the map has handled it
    @Test
    fun clear_camera_action_nulls_pending() {
        val state = newState()
        state.requestCamera(CameraAction.FitAllFriends)

        state.clearCameraAction()

        assertNull(state.pendingCameraAction)
    }

    // Should: record the map's reported bearing for the compass to read
    @Test
    fun on_bearing_updates_map_bearing() {
        val state = newState()

        state.onBearing(42.0)

        assertEquals(42.0, state.mapBearing, 0.0)
    }

    // Should: report NoLocation and not attempt an upload when no fix is available
    // Should not: leave isUploading stuck true after bailing
    @Test
    fun upload_now_bails_when_no_fix() = runTest {
        val push: suspend (AndroidLocation, String) -> LocationRepository.Result = mockk(relaxed = true)
        val acquire: suspend (LocationFreshness) -> LocationRequestResult =
            { LocationRequestResult(null, "none", 0, false) }
        val state = newState(acquireLocation = acquire, push = push)

        val result = state.uploadNow()

        assertEquals(LocationRepository.Result.NoLocation, result)
        assertFalse(state.isUploading)
    }

    // Should: push the freshly acquired fix and record it as the server location on success
    // Should not: leave isUploading true after completing
    @Test
    fun upload_now_pushes_fix_and_sets_server_location() = runTest {
        val loc = androidLocation(lat = 45.5, lng = -73.6, time = 7_000L)
        val acquire: suspend (LocationFreshness) -> LocationRequestResult =
            { LocationRequestResult(loc, "gps", 0, false) }
        val push: suspend (AndroidLocation, String) -> LocationRepository.Result = mockk()
        coEvery { push(loc, "gps") } returns LocationRepository.Result.Uploaded(7_000L, "gps")
        val state = newState(acquireLocation = acquire, push = push)

        val result = state.uploadNow()

        assertEquals(LocationRepository.Result.Uploaded(7_000L, "gps"), result)
        assertEquals(45.5, state.serverLocation?.lat)
        assertFalse(state.isUploading)
    }

    // Should: stop showing the server location and refresh from a current fix when toggled off
    @Test
    fun toggle_server_location_off_refreshes_current() = runTest {
        val loc = androidLocation(lat = 1.0, lng = 2.0)
        val acquire: suspend (LocationFreshness) -> LocationRequestResult =
            { LocationRequestResult(loc, "passive", 0, false) }
        val state = newState(acquireLocation = acquire)
        state.showServerLocation = true

        val msg = state.toggleServerLocation(false)

        assertNull(msg)
        assertFalse(state.showServerLocation)
        assertEquals(1.0, state.currentLocation?.latitude)
    }

    private fun newState(
        listFriends: () -> List<Friend> = { emptyList() },
        updateFriend: (String, Boolean?, Boolean?, String?) -> Unit = mockk(relaxed = true),
        removeFriend: (String) -> Unit = mockk(relaxed = true),
        autoShare: Boolean = false,
        setAutoSharePref: (Boolean) -> Unit = mockk(relaxed = true),
        onGateChanged: () -> Unit = mockk(relaxed = true),
        cityFor: (Double, Double) -> City? = { _, _ -> null },
        acquireLocation: suspend (LocationFreshness) -> LocationRequestResult =
            { LocationRequestResult(null, "none", 0, false) },
        push: suspend (AndroidLocation, String) -> LocationRepository.Result =
            { _, _ -> LocationRepository.Result.NoLocation },
        fetchSelfLocation: suspend () -> LocationSyncService.FetchResult = mockk(relaxed = true),
        scheduleWorker: () -> Unit = mockk(relaxed = true),
        cancelWorker: () -> Unit = mockk(relaxed = true),
        initialName: String = "Me",
    ) = MapScreenState(
        listFriends = listFriends,
        updateFriend = updateFriend,
        removeFriend = removeFriend,
        getAutoSharePref = { autoShare },
        setAutoSharePref = setAutoSharePref,
        onGateChanged = onGateChanged,
        cityFor = cityFor,
        acquireLocation = acquireLocation,
        push = push,
        fetchSelfLocation = fetchSelfLocation,
        scheduleWorker = scheduleWorker,
        cancelWorker = cancelWorker,
        initialName = initialName,
    )
}
