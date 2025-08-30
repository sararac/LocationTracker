package com.penguin.locationtracker

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.os.Build
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.location.*
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.ValueEventListener
import com.google.firebase.database.ktx.database
import com.google.firebase.ktx.Firebase
import java.util.*
import kotlin.math.*

class LocationTrackingService : Service() {

    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var locationCallback: LocationCallback
    private var locationRequest: LocationRequest? = null
    private val database = Firebase.database
    private val locationsRef = database.getReference("locations")

    private var lastLocation: Location? = null
    private val minDistanceThreshold = 10.0 // 10미터

    companion object {
        private const val TAG = "LocationTracking"
        private const val NOTIFICATION_ID = 1
        private const val CHANNEL_ID = "location_service_channel"
        const val ACTION_START_TRACKING = "START_TRACKING"
        const val ACTION_STOP_TRACKING = "STOP_TRACKING"
    }

    override fun onCreate() {
        super.onCreate()
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        setupLocationCallback()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_TRACKING -> startLocationTracking()
            ACTION_STOP_TRACKING -> stopLocationTracking()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "위치 추적 서비스",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "백그라운드에서 위치를 추적합니다"
                setShowBadge(false)
            }

            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)

            Log.d(TAG, "Notification channel created")
        }
    }

    private fun setupLocationCallback() {
        locationCallback = object : LocationCallback() {
            override fun onLocationResult(locationResult: LocationResult) {
                locationResult.lastLocation?.let { location ->
                    handleNewLocation(location)
                    updateNotification(location)
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun startLocationTracking() {
        if (!hasLocationPermission()) {
            Log.e(TAG, "Location permission not granted")
            return
        }

        val prefs = getSharedPreferences("UserPrefs", Context.MODE_PRIVATE)
        val userId = prefs.getString("user_id", "") ?: ""
        val trackingInterval = prefs.getInt("tracking_interval", 10) // 기본값 10초

        if (userId.isEmpty()) {
            Log.e(TAG, "User ID not set")
            return
        }

        // Foreground service 시작
        startForeground(NOTIFICATION_ID, createNotification(userId, trackingInterval))

        locationRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, (trackingInterval * 1000).toLong())
            .setMinUpdateIntervalMillis((trackingInterval * 1000).toLong())
            .setMaxUpdateDelayMillis((trackingInterval * 1000 * 2).toLong())
            .build()

        locationRequest?.let { request ->
            fusedLocationClient.requestLocationUpdates(
                request,
                locationCallback,
                Looper.getMainLooper()
            )
        }

        Log.d(TAG, "Location tracking started for user: $userId, interval: ${trackingInterval}sec")
    }

    private fun stopLocationTracking() {
        fusedLocationClient.removeLocationUpdates(locationCallback)
        stopForeground(STOP_FOREGROUND_REMOVE)
        Log.d(TAG, "Location tracking stopped")
    }

    private fun createNotification(userId: String, intervalSeconds: Int) = NotificationCompat.Builder(this, CHANNEL_ID)
        .setContentTitle("위치 추적 중")
        .setContentText("사용자: $userId | 추적 주기: ${intervalSeconds}초")
        .setSmallIcon(android.R.drawable.ic_menu_mylocation)
        .setPriority(NotificationCompat.PRIORITY_LOW)
        .setOngoing(true)
        .setAutoCancel(false)
        .build()

    private fun updateNotification(location: Location) {
        val prefs = getSharedPreferences("UserPrefs", Context.MODE_PRIVATE)
        val userId = prefs.getString("user_id", "") ?: ""
        val trackingInterval = prefs.getInt("tracking_interval", 10)

        val updatedNotification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("위치 추적 중")
            .setContentText("사용자: $userId | 마지막 업데이트: ${java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())}")
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setAutoCancel(false)
            .build()

        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(NOTIFICATION_ID, updatedNotification)
    }

    private fun handleNewLocation(location: Location) {
        val prefs = getSharedPreferences("UserPrefs", Context.MODE_PRIVATE)
        val userId = prefs.getString("user_id", "") ?: ""

        if (userId.isEmpty()) return

        // 이전 위치와 비교하여 10m 이상 차이날 때만 저장
        if (shouldSaveLocation(location)) {
            val locationData = LocationData(
                userId = userId,
                latitude = location.latitude,
                longitude = location.longitude,
                timestamp = System.currentTimeMillis()
            )

            locationsRef.child(userId).push().setValue(locationData)
                .addOnSuccessListener {
                    Log.d(TAG, "Location saved: ${location.latitude}, ${location.longitude}")
                    lastLocation = location
                    cleanupOldData(userId)
                }
                .addOnFailureListener { e ->
                    Log.e(TAG, "Failed to save location", e)
                }
        } else {
            Log.d(TAG, "Location change too small, not saving")
        }
    }

    private fun shouldSaveLocation(newLocation: Location): Boolean {
        lastLocation?.let { last ->
            val distance = calculateDistance(
                last.latitude, last.longitude,
                newLocation.latitude, newLocation.longitude
            )
            Log.d(TAG, "Distance from last location: ${distance}m")
            return distance >= minDistanceThreshold
        }
        return true // 첫 번째 위치는 항상 저장
    }

    private fun calculateDistance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val earthRadius = 6371000.0 // 지구 반지름 (미터)
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2).pow(2) + cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return earthRadius * c
    }

    private fun cleanupOldData(userId: String) {
        val prefs = getSharedPreferences("UserPrefs", Context.MODE_PRIVATE)
        val retentionDays = prefs.getInt("data_retention_days", 1)
        val cutoffTime = System.currentTimeMillis() - (retentionDays * 24 * 60 * 60 * 1000L)

        locationsRef.child(userId).orderByChild("timestamp").endAt(cutoffTime.toDouble())
            .addListenerForSingleValueEvent(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    var deletedCount = 0
                    for (childSnapshot in snapshot.children) {
                        childSnapshot.ref.removeValue()
                        deletedCount++
                    }
                    if (deletedCount > 0) {
                        Log.d(TAG, "Cleaned up $deletedCount old location records for user: $userId")
                    }
                }

                override fun onCancelled(error: DatabaseError) {
                    Log.e(TAG, "Failed to cleanup old data", error.toException())
                }
            })
    }

    private fun hasLocationPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
    }

    override fun onDestroy() {
        super.onDestroy()
        stopLocationTracking()
        Log.d(TAG, "LocationTrackingService destroyed")
    }
}