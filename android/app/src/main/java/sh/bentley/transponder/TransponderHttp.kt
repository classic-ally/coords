package sh.bentley.transponder

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import uniffi.transponder_core.PreparedRequest
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Server info returned from GET / */
data class ServerInfo(
    val name: String,
    val version: String
)

/** Result of validating a server URL */
sealed class ServerValidationResult {
    data class Valid(val info: ServerInfo) : ServerValidationResult()
    data class Invalid(val error: String) : ServerValidationResult()
}

/**
 * HTTP executor for Transponder requests.
 * Takes PreparedRequest from Rust core and executes via OkHttp.
 */
class TransponderHttp(
    private val client: OkHttpClient = defaultClient()
) {
    sealed class Result {
        data class Success(val body: ByteArray) : Result()
        data class HttpError(val code: Int, val message: String) : Result()
        data class NetworkError(val exception: IOException) : Result()
    }

    /**
     * Execute a prepared request from Rust core.
     * Returns the response body on success, or an error.
     */
    suspend fun execute(request: PreparedRequest): Result = withContext(Dispatchers.IO) {
        try {
            val mediaType = request.headers["Content-Type"]?.toMediaType()
            val body = when (request.method) {
                "PUT", "POST" -> request.body.toRequestBody(mediaType)
                else -> null
            }

            val builder = Request.Builder()
                .url(request.url)
                .method(request.method, body)

            for (entry in request.headers) {
                builder.addHeader(entry.key, entry.value)
            }

            val httpRequest = builder.build()

            val response = client.newCall(httpRequest).execute()

            if (response.isSuccessful) {
                Result.Success(response.body?.bytes() ?: ByteArray(0))
            } else {
                Result.HttpError(response.code, response.message)
            }
        } catch (e: IOException) {
            Result.NetworkError(e)
        }
    }

    /**
     * Validate a server URL by hitting GET /version and checking for Coords server.
     * Requires version >= minVersion.
     */
    suspend fun validateServer(
        url: String,
        minVersion: String = "2026.1.6"
    ): ServerValidationResult = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url(url.trimEnd('/') + "/api/version")
                .get()
                .build()

            val response = client.newCall(request).execute()

            if (!response.isSuccessful) {
                return@withContext ServerValidationResult.Invalid("Server returned ${response.code}")
            }

            val body = response.body?.string()
                ?: return@withContext ServerValidationResult.Invalid("Empty response")

            val json = try {
                JSONObject(body)
            } catch (e: Exception) {
                return@withContext ServerValidationResult.Invalid("Invalid JSON response")
            }

            val name = json.optString("name", "")
            val version = json.optString("version", "")

            if (name != "coords" && name != "transponder") {
                return@withContext ServerValidationResult.Invalid("Not a Coords server")
            }

            if (!isVersionAtLeast(version, minVersion)) {
                return@withContext ServerValidationResult.Invalid("Server version $version is too old (need $minVersion+)")
            }

            ServerValidationResult.Valid(ServerInfo(name, version))
        } catch (e: IOException) {
            ServerValidationResult.Invalid("Connection failed: ${e.message}")
        } catch (e: Exception) {
            ServerValidationResult.Invalid("Error: ${e.message}")
        }
    }

    companion object {
        private fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()

        /** Compare semver/calver versions (e.g., "2026.1.6" >= "2026.1.6") */
        private fun isVersionAtLeast(version: String, minVersion: String): Boolean {
            val vParts = version.split(".").mapNotNull { it.toIntOrNull() }
            val mParts = minVersion.split(".").mapNotNull { it.toIntOrNull() }

            for (i in 0 until maxOf(vParts.size, mParts.size)) {
                val v = vParts.getOrElse(i) { 0 }
                val m = mParts.getOrElse(i) { 0 }
                if (v > m) return true
                if (v < m) return false
            }
            return true // equal
        }
    }
}
