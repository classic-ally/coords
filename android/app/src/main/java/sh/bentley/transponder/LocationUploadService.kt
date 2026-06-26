package sh.bentley.transponder

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.IBinder
import android.os.Looper
import android.text.format.DateFormat
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.Date

/**
 * Foreground service for reactive location uploads.
 * Uploads location when the device moves 40+ meters (matching iOS behavior).
 */
class LocationUploadService : Service() {

    companion object {
        const val ACTION_UPLOAD_DONE = "sh.bentley.transponder.UPLOAD_DONE"
        private const val TAG = "LocationUploadService"
        private const val CHANNEL_ID = "location_sharing"
        private const val NOTIFICATION_ID = 1
        private const val MIN_TIME_MS = 180_000L  // 3 minutes
        private const val MIN_DISTANCE_M = 40f    // 40 meters
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var locationManager: LocationManager? = null
    private var locationListener: LocationListener? = null
    private var currentProvider: String? = null
    private lateinit var identityStore: IdentityStore
    private lateinit var repo: LocationRepository

    private val uploadDoneReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            updateNotification()
        }
    }

    private val listener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            Log.d(TAG, "Location changed: ${location.latitude}, ${location.longitude} (accuracy: ${location.accuracy}m)")
            serviceScope.launch { repo.push(location, currentProvider ?: "service") }
        }

        override fun onProviderEnabled(provider: String) {
            Log.d(TAG, "Provider enabled: $provider")
        }

        override fun onProviderDisabled(provider: String) {
            Log.d(TAG, "Provider disabled: $provider")
        }

        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) {}
    }

    override fun onCreate() {
        super.onCreate()
        identityStore = IdentityStore(this)
        repo = LocationRepository.from(applicationContext, identityStore)
        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        createNotificationChannel()
        ContextCompat.registerReceiver(
            this,
            uploadDoneReceiver,
            IntentFilter(ACTION_UPLOAD_DONE),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "Service starting")

        // Start as foreground service with notification
        val notification = createNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        startLocationUpdates()

        return START_STICKY
    }

    override fun onDestroy() {
        Log.d(TAG, "Service stopping")
        stopLocationUpdates()
        unregisterReceiver(uploadDoneReceiver)
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Location Sharing",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Shows when location is being shared"
            setShowBadge(false)
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }

    private fun createNotification(): Notification {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val lastUpload = repo.lastUploadTime()
        val contentText = when {
            lastUpload != null -> {
                val time = DateFormat.getTimeFormat(this).format(Date(lastUpload))
                if (BuildConfig.DEBUG && currentProvider != null) {
                    "Shared at $time via $currentProvider"
                } else {
                    "Shared at $time"
                }
            }
            BuildConfig.DEBUG && currentProvider != null -> "Sharing via $currentProvider"
            else -> "Your location is being shared with friends"
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Sharing location")
            .setContentText(contentText)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .build()
    }

    private fun updateNotification() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, createNotification())
    }

    @SuppressLint("MissingPermission")
    private fun startLocationUpdates() {
        val lm = locationManager ?: return

        // Check permissions
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "Missing fine location permission")
            stopSelf()
            return
        }

        // Use same FUSED -> GPS+Network fallback as LocationProvider
        val provider = when {
            lm.allProviders.contains(LocationManager.FUSED_PROVIDER) &&
                lm.isProviderEnabled(LocationManager.FUSED_PROVIDER) ->
                LocationManager.FUSED_PROVIDER
            lm.isProviderEnabled(LocationManager.GPS_PROVIDER) ->
                LocationManager.GPS_PROVIDER
            lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER) ->
                LocationManager.NETWORK_PROVIDER
            else -> {
                Log.w(TAG, "No location provider available")
                stopSelf()
                return
            }
        }

        Log.d(TAG, "Starting location updates with provider: $provider")

        currentProvider = provider
        updateNotification()

        locationListener = listener
        lm.requestLocationUpdates(
            provider,
            MIN_TIME_MS,
            MIN_DISTANCE_M,
            listener,
            Looper.getMainLooper()
        )
    }

    private fun stopLocationUpdates() {
        locationListener?.let { listener ->
            locationManager?.removeUpdates(listener)
        }
        locationListener = null
    }
}
