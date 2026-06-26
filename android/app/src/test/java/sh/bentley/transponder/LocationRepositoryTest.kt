package sh.bentley.transponder

import android.location.Location
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocationRepositoryTest {

    private fun location(time: Long = 1_000L): Location =
        mockk(relaxed = true) { every { this@mockk.time } returns time }

    private fun repo(
        acquire: suspend (LocationFreshness) -> LocationRequestResult =
            { LocationRequestResult(location(), "fused", 0, false) },
        upload: suspend (Location) -> LocationSyncService.UploadResult =
            { LocationSyncService.UploadResult.Success },
        hasRecipients: () -> Boolean = { true },
        lastUploadRaw: () -> Long = { 5_000L },
        onUploaded: () -> Unit = {},
    ) = LocationRepository(acquire, upload, hasRecipients, lastUploadRaw, onUploaded)

    @Test
    fun `syncNow returns NoLocation when fix unavailable`() = runTest {
        val result = repo(
            acquire = { LocationRequestResult(null, "none", 0, false) }
        ).syncNow(LocationFreshness.CACHED_OKAY)
        assertEquals(LocationRepository.Result.NoLocation, result)
    }

    @Test
    fun `push skips when no recipients and never uploads`() = runTest {
        var uploaded = false
        val result = repo(
            hasRecipients = { false },
            upload = { uploaded = true; LocationSyncService.UploadResult.Success }
        ).push(location())
        assertEquals(LocationRepository.Result.Skipped, result)
        assertFalse("upload must not run when no recipients", uploaded)
    }

    @Test
    fun `push returns Uploaded with core timestamp and refreshes notification`() = runTest {
        var refreshed = false
        val result = repo(
            lastUploadRaw = { 42_000L },
            onUploaded = { refreshed = true }
        ).push(location(time = 1_000L), source = "gps")
        assertEquals(LocationRepository.Result.Uploaded(42_000L, "gps"), result)
        assertTrue("successful upload must ping notification", refreshed)
    }

    @Test
    fun `push falls back to location time when core has no timestamp`() = runTest {
        val result = repo(lastUploadRaw = { 0L })
            .push(location(time = 7_777L), source = "network")
        assertEquals(LocationRepository.Result.Uploaded(7_777L, "network"), result)
    }

    @Test
    fun `push returns Error and skips notification on upload failure`() = runTest {
        var refreshed = false
        val result = repo(
            upload = { LocationSyncService.UploadResult.Error("boom") },
            onUploaded = { refreshed = true }
        ).push(location())
        assertEquals(LocationRepository.Result.Error("boom"), result)
        assertFalse("failed upload must not ping notification", refreshed)
    }

    @Test
    fun `concurrent pushes are serialized by the mutex`() = runTest {
        var inFlight = 0
        var maxInFlight = 0
        val r = repo(
            upload = {
                inFlight++
                maxInFlight = maxOf(maxInFlight, inFlight)
                delay(50)
                inFlight--
                LocationSyncService.UploadResult.Success
            }
        )
        val a = launch { r.push(location()) }
        val b = launch { r.push(location()) }
        a.join(); b.join()
        assertEquals("uploads must not overlap", 1, maxInFlight)
    }
}
