package com.penguin.locationtracker

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.os.Build
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
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
    private var wakeLock: PowerManager.WakeLock? = null

    private var lastLocation: Location? = null
    private var currentLocationData: LocationData? = null // 현재 위치 데이터 추가
    private var locationStartTime: Long = 0L // 현재 위치에서 시작한 시간 추가

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
        acquireWakeLock()

        // 위치 추적 알림 매니저 초기화
        initializeLocationNotificationManager()

        Log.d(TAG, "LocationTrackingService created")
    }

    // 위치 추적 알림 매니저 초기화 메서드 추가
    private fun initializeLocationNotificationManager() {
        try {
            val prefs = getSharedPreferences("UserPrefs", Context.MODE_PRIVATE)
            val notificationEnabled = prefs.getBoolean("location_notification_enabled", false)
            val trackedUsers = prefs.getStringSet("tracked_users", emptySet())?.toList() ?: emptyList()

            if (notificationEnabled && trackedUsers.isNotEmpty()) {
                val locationNotificationManager = LocationNotificationManager(this)

                // 추적할 사용자들에 대해 알림 시작
                trackedUsers.forEach { userId ->
                    locationNotificationManager.startLocationNotifications(userId)
                }

                Log.d(TAG, "Location notification manager initialized for users: $trackedUsers")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing location notification manager", e)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand called with action: ${intent?.action}")

        when (intent?.action) {
            ACTION_START_TRACKING -> startLocationTracking()
            ACTION_STOP_TRACKING -> stopLocationTracking()
        }

        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun acquireWakeLock() {
        try {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "LocationTracker::LocationService"
            )
            wakeLock?.acquire(10*60*1000L /*10 minutes*/)
            Log.d(TAG, "WakeLock acquired")
        } catch (e: Exception) {
            Log.e(TAG, "Error acquiring WakeLock", e)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "위치 추적 서비스",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "백그라운드에서 위치를 추적합니다"
                setShowBadge(false)
                setSound(null, null)
                enableVibration(false)
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
                    // 🆕 위치 갱신 로그 추가
                    Log.d(TAG, "📍 Location update received - Lat: ${location.latitude}, Lng: ${location.longitude}, Accuracy: ${location.accuracy}m, Time: ${java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())}")

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
        val trackingInterval = prefs.getInt("tracking_interval", 10)

        if (userId.isEmpty()) {
            Log.e(TAG, "User ID not set")
            return
        }

        startForeground(NOTIFICATION_ID, createNotification(userId, trackingInterval))

        locationRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, (trackingInterval * 1000).toLong())
            .setMinUpdateIntervalMillis((trackingInterval * 1000).toLong())
            .setMaxUpdateDelayMillis((trackingInterval * 1000 * 2).toLong())
            .setWaitForAccurateLocation(false)
            .build()

        locationRequest?.let { request ->
            fusedLocationClient.requestLocationUpdates(
                request,
                locationCallback,
                Looper.getMainLooper()
            )
        }

        // 🆕 상세한 시작 로그
        Log.d(TAG, "🚀 Location tracking STARTED - User: $userId, Interval: ${trackingInterval}sec, Time: ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())}")
    }

    private fun stopLocationTracking() {
        fusedLocationClient.removeLocationUpdates(locationCallback)
        stopForeground(STOP_FOREGROUND_REMOVE)
        releaseWakeLock()
        Log.d(TAG, "Location tracking stopped")
    }

    private fun createNotification(userId: String, intervalSeconds: Int): android.app.Notification {
        val intent = packageManager.getLaunchIntentForPackage(packageName)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("위치 추적 중")
            .setContentText("사용자: $userId | 추적 주기: ${intervalSeconds}초")
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setAutoCancel(false)
            .setSilent(true)
            .setContentIntent(pendingIntent)
            .build()
    }

    private fun updateNotification(location: Location) {
        val prefs = getSharedPreferences("UserPrefs", Context.MODE_PRIVATE)
        val userId = prefs.getString("user_id", "") ?: ""

        // 현재 머문 시간 계산
        val stayDuration = if (locationStartTime > 0) {
            (System.currentTimeMillis() - locationStartTime) / (1000 * 60) // 분 단위
        } else 0L

        val stayText = if (stayDuration > 0) " | 머문시간: ${stayDuration}분" else ""

        val updatedNotification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("위치 추적 중")
            .setContentText("$userId | ${java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())}$stayText")
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setAutoCancel(false)
            .setSilent(true)
            .build()

        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(NOTIFICATION_ID, updatedNotification)
    }

    private fun handleNewLocation(location: Location) {
        val prefs = getSharedPreferences("UserPrefs", Context.MODE_PRIVATE)
        val userId = prefs.getString("user_id", "") ?: ""
        val locationThreshold = prefs.getInt("location_threshold", 10).toDouble()

        if (userId.isEmpty()) return

        val currentTime = System.currentTimeMillis()

        // 🆕 위치 처리 시작 로그
        Log.d(TAG, "🔄 Processing location for user: $userId")

        // 위치 변동 감지
        if (shouldSaveOrUpdateLocation(location, locationThreshold)) {

            if (lastLocation == null || !isSameLocation(location, lastLocation!!, locationThreshold)) {
                // 새로운 위치 - 새 항목 생성
                Log.d(TAG, "✨ NEW LOCATION detected for $userId - Distance from last: ${if (lastLocation != null) String.format("%.1f", calculateDistance(lastLocation!!.latitude, lastLocation!!.longitude, location.latitude, location.longitude)) else "N/A"}m")

                val locationData = LocationData(
                    userId = userId,
                    latitude = location.latitude,
                    longitude = location.longitude,
                    timestamp = currentTime,
                    stayDuration = 0L,
                    lastUpdateTime = currentTime
                )

                // Firebase에 새 위치 저장
                val newLocationRef = locationsRef.child(userId).push()
                newLocationRef.setValue(locationData)
                    .addOnSuccessListener {
                        Log.d(TAG, "💾 New location SAVED to Firebase - Key: ${newLocationRef.key}")
                        currentLocationData = locationData.copy()
                        locationStartTime = currentTime
                        cleanupOldData(userId)
                    }
                    .addOnFailureListener { e ->
                        Log.e(TAG, "❌ Failed to save new location", e)
                    }

                lastLocation = location

            } else {
                // 같은 위치 - 머문 시간 업데이트
                val stayDurationMinutes = if (locationStartTime > 0) {
                    (currentTime - locationStartTime) / (1000 * 60)
                } else 0L

                Log.d(TAG, "⏱️ STAY DURATION update for $userId - Duration: ${stayDurationMinutes} minutes")
                updateStayDuration(userId, location, currentTime)
            }
        } else {
            val distance = lastLocation?.let { last ->
                calculateDistance(
                    last.latitude, last.longitude,
                    location.latitude, location.longitude
                )
            } ?: 0.0

            Log.d(TAG, "📏 Location change too small for $userId - Distance: ${String.format("%.1f", distance)}m (threshold: ${locationThreshold}m)")
        }
    }

    // 같은 위치인지 판단하는 함수
    private fun isSameLocation(location1: Location, location2: Location, threshold: Double): Boolean {
        val distance = calculateDistance(
            location1.latitude, location1.longitude,
            location2.latitude, location2.longitude
        )
        return distance <= threshold
    }

    // 위치를 저장하거나 업데이트해야 하는지 판단
    private fun shouldSaveOrUpdateLocation(newLocation: Location, threshold: Double): Boolean {
        lastLocation?.let { last ->
            val distance = calculateDistance(
                last.latitude, last.longitude,
                newLocation.latitude, newLocation.longitude
            )
            Log.d(TAG, "Distance from last location: ${distance}m (threshold: ${threshold}m)")

            // 거리가 임계값보다 크면 새 위치, 작거나 같으면 같은 위치로 판단
            return distance > threshold || (currentLocationData != null)
        }
        return true // 첫 번째 위치는 항상 저장
    }

    // 머문 시간 업데이트
    private fun updateStayDuration(userId: String, location: Location, currentTime: Long) {
        currentLocationData?.let { currentData ->

            if (locationStartTime == 0L) {
                locationStartTime = currentData.timestamp
            }

            val totalStayDuration = currentTime - locationStartTime
            val stayMinutes = totalStayDuration / (1000 * 60)

            Log.d(TAG, "⏰ Updating stay duration for $userId - Total: ${stayMinutes} minutes (${totalStayDuration / 1000} seconds)")

            // Firebase에서 현재 위치 데이터 업데이트
            locationsRef.child(userId).orderByChild("timestamp").equalTo(currentData.timestamp.toDouble())
                .addListenerForSingleValueEvent(object : ValueEventListener {
                    override fun onDataChange(snapshot: DataSnapshot) {
                        for (childSnapshot in snapshot.children) {
                            val updates = mapOf(
                                "stayDuration" to totalStayDuration,
                                "lastUpdateTime" to currentTime
                            )

                            childSnapshot.ref.updateChildren(updates)
                                .addOnSuccessListener {
                                    Log.d(TAG, "✅ Stay duration UPDATED in Firebase - User: $userId, Duration: ${stayMinutes}min")
                                    currentLocationData = currentData.copy(
                                        stayDuration = totalStayDuration,
                                        lastUpdateTime = currentTime
                                    )
                                }
                                .addOnFailureListener { e ->
                                    Log.e(TAG, "❌ Failed to update stay duration for $userId", e)
                                }
                            break
                        }
                    }

                    override fun onCancelled(error: DatabaseError) {
                        Log.e(TAG, "❌ Error finding location data to update for $userId", error.toException())
                    }
                })
        }
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
        val retentionDays = prefs.getInt("data_retention_days", 7)
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

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)

        val prefs = getSharedPreferences("UserPrefs", Context.MODE_PRIVATE)
        val autoRestart = prefs.getBoolean("auto_restart_service", true)

        Log.d(TAG, "Task removed, auto restart: $autoRestart")

        if (autoRestart) {
            val restartIntent = Intent(applicationContext, LocationTrackingService::class.java)
            restartIntent.action = ACTION_START_TRACKING

            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(restartIntent)
                } else {
                    startService(restartIntent)
                }
                Log.d(TAG, "Service restarted after task removal")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to restart service after task removal", e)
            }
        }
    }

    private fun hasLocationPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun releaseWakeLock() {
        try {
            wakeLock?.let {
                if (it.isHeld) {
                    it.release()
                }
            }
            Log.d(TAG, "WakeLock released")
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing WakeLock", e)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        stopLocationTracking()
        releaseWakeLock()
        Log.d(TAG, "LocationTrackingService destroyed")
    }
}