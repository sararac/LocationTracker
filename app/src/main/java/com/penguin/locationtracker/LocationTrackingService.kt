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
    private var currentLocationData: LocationData? = null
    private var locationStartTime: Long = 0L

    // 🆕 GPS 정확도 개선을 위한 추가 변수들
    private val locationBuffer = mutableListOf<Location>() // 최근 위치들을 저장
    private var lastValidLocation: Location? = null // 마지막 유효한 위치
    private var stationaryStartTime: Long = 0L // 정지 상태 시작 시간
    private var isStationary = false // 정지 상태 여부
    private val MIN_ACCURACY = 20.0 // 최소 정확도 (미터)
    private val MAX_ACCURACY = 100.0 // 최대 허용 정확도 (미터)
    private val BUFFER_SIZE = 5 // 버퍼에 저장할 위치 개수
    private val MIN_STATIONARY_TIME = 30 * 1000L // 30초간 정지해야 정지 상태로 인정
    private val STATIONARY_UPDATE_INTERVAL = 5 * 60 * 1000L // 정지 중 업데이트 간격 (5분)
    private var lastStationaryUpdate = 0L

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
        initializeLocationNotificationManager()
        Log.d(TAG, "LocationTrackingService created")
    }

    private fun initializeLocationNotificationManager() {
        try {
            val prefs = getSharedPreferences("UserPrefs", Context.MODE_PRIVATE)
            val notificationEnabled = prefs.getBoolean("location_notification_enabled", false)
            val trackedUsers = prefs.getStringSet("tracked_users", emptySet())?.toList() ?: emptyList()

            if (notificationEnabled && trackedUsers.isNotEmpty()) {
                val locationNotificationManager = LocationNotificationManager(this)
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
                    Log.d(TAG, "📍 Location update received - Lat: ${location.latitude}, Lng: ${location.longitude}, Accuracy: ${location.accuracy}m, Speed: ${location.speed}m/s, Time: ${java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())}")

                    // 🆕 개선된 위치 처리
                    handleNewLocationImproved(location)
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

        // 🆕 더 정확한 위치 요청 설정
        locationRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, (trackingInterval * 1000).toLong())
            .setMinUpdateIntervalMillis((trackingInterval * 1000).toLong())
            .setMaxUpdateDelayMillis((trackingInterval * 1000 * 2).toLong())
            .setWaitForAccurateLocation(true) // 정확한 위치를 기다림
            .setMinUpdateDistanceMeters(5.0f) // 최소 5미터 이동해야 업데이트
            .build()

        locationRequest?.let { request ->
            fusedLocationClient.requestLocationUpdates(
                request,
                locationCallback,
                Looper.getMainLooper()
            )
        }

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

        val stayDuration = if (locationStartTime > 0) {
            (System.currentTimeMillis() - locationStartTime) / (1000 * 60)
        } else 0L

        val stayText = if (stayDuration > 0) " | 머문시간: ${stayDuration}분" else ""
        val accuracyText = " | 정확도: ${location.accuracy.toInt()}m"
        val statusText = if (isStationary) " | 정지중" else " | 이동중"

        val updatedNotification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("위치 추적 중")
            .setContentText("$userId | ${java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())}$stayText$accuracyText$statusText")
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setAutoCancel(false)
            .setSilent(true)
            .build()

        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(NOTIFICATION_ID, updatedNotification)
    }

    // 🆕 개선된 위치 처리 함수
    private fun handleNewLocationImproved(location: Location) {
        val prefs = getSharedPreferences("UserPrefs", Context.MODE_PRIVATE)
        val userId = prefs.getString("user_id", "") ?: ""
        val locationThreshold = prefs.getInt("location_threshold", 10).toDouble()

        if (userId.isEmpty()) return

        val currentTime = System.currentTimeMillis()

        // 1. 정확도 필터링 - 너무 부정확한 위치는 무시
        if (location.accuracy > MAX_ACCURACY) {
            Log.d(TAG, "🚫 Location rejected - Poor accuracy: ${location.accuracy}m (threshold: ${MAX_ACCURACY}m)")
            return
        }

        // 2. 위치 버퍼에 추가
        locationBuffer.add(location)
        if (locationBuffer.size > BUFFER_SIZE) {
            locationBuffer.removeAt(0) // 가장 오래된 위치 제거
        }

        // 3. 버퍼가 충분히 찬 후에만 처리
        if (locationBuffer.size < 3) {
            Log.d(TAG, "📊 Building location buffer... (${locationBuffer.size}/$BUFFER_SIZE)")
            return
        }

        // 4. 평균 위치 계산 (스무딩)
        val smoothedLocation = calculateSmoothedLocation(locationBuffer)

        // 5. 정지 상태 감지
        val isCurrentlyStationary = detectStationaryState(smoothedLocation, locationThreshold)

        // 6. 위치 업데이트 결정
        if (shouldUpdateLocation(smoothedLocation, isCurrentlyStationary, currentTime)) {
            processLocationUpdate(smoothedLocation, userId, currentTime, isCurrentlyStationary)
        } else {
            Log.d(TAG, "📍 Location update skipped - No significant change or still filtering")
        }
    }

    // 🆕 위치 스무딩 (평균 계산)
    private fun calculateSmoothedLocation(locations: List<Location>): Location {
        if (locations.size == 1) return locations[0]

        // 정확도 기반 가중 평균 계산
        var totalWeight = 0.0
        var weightedLat = 0.0
        var weightedLng = 0.0
        var bestAccuracy = Float.MAX_VALUE

        locations.forEach { loc ->
            // 정확도가 좋을수록 가중치가 높음 (accuracy가 낮을수록 정확함)
            val weight = 1.0 / (loc.accuracy + 1.0)
            totalWeight += weight
            weightedLat += loc.latitude * weight
            weightedLng += loc.longitude * weight

            if (loc.accuracy < bestAccuracy) {
                bestAccuracy = loc.accuracy
            }
        }

        val smoothedLocation = Location("smoothed").apply {
            latitude = weightedLat / totalWeight
            longitude = weightedLng / totalWeight
            accuracy = bestAccuracy
            time = System.currentTimeMillis()
        }

        Log.d(TAG, "🎯 Smoothed location: accuracy=${smoothedLocation.accuracy}m, original_count=${locations.size}")
        return smoothedLocation
    }

    // 🆕 정지 상태 감지
    private fun detectStationaryState(location: Location, threshold: Double): Boolean {
        lastValidLocation?.let { lastLoc ->
            val distance = calculateDistance(
                lastLoc.latitude, lastLoc.longitude,
                location.latitude, location.longitude
            )

            val currentTime = System.currentTimeMillis()

            if (distance <= threshold) {
                // 임계값 이내 - 정지 중일 수 있음
                if (stationaryStartTime == 0L) {
                    stationaryStartTime = currentTime
                    Log.d(TAG, "🛑 Potential stationary state detected - distance: ${String.format("%.1f", distance)}m")
                }

                // 일정 시간 이상 정지해야 정지 상태로 인정
                val stationaryDuration = currentTime - stationaryStartTime
                if (stationaryDuration >= MIN_STATIONARY_TIME) {
                    if (!isStationary) {
                        Log.d(TAG, "✋ Stationary state confirmed - duration: ${stationaryDuration / 1000}s")
                    }
                    return true
                }
            } else {
                // 임계값을 벗어남 - 이동 중
                if (isStationary || stationaryStartTime > 0L) {
                    Log.d(TAG, "🚶 Movement detected - distance: ${String.format("%.1f", distance)}m, ending stationary state")
                }
                stationaryStartTime = 0L
                return false
            }
        }

        return false
    }

    // 🆕 위치 업데이트 여부 결정
    private fun shouldUpdateLocation(location: Location, isCurrentlyStationary: Boolean, currentTime: Long): Boolean {
        // 첫 번째 위치는 항상 저장
        if (lastValidLocation == null) {
            return true
        }

        // 정지 상태 변경 시 업데이트
        if (this.isStationary != isCurrentlyStationary) {
            Log.d(TAG, "📍 Status change detected: ${if (isCurrentlyStationary) "Moving → Stationary" else "Stationary → Moving"}")
            return true
        }

        // 정지 중일 때는 일정 시간마다만 업데이트
        if (isCurrentlyStationary) {
            val timeSinceLastUpdate = currentTime - lastStationaryUpdate
            if (timeSinceLastUpdate < STATIONARY_UPDATE_INTERVAL) {
                Log.d(TAG, "⏰ Stationary - skipping update (${timeSinceLastUpdate / 1000}s < ${STATIONARY_UPDATE_INTERVAL / 1000}s)")
                return false
            }
            Log.d(TAG, "⏰ Stationary - time-based update (${timeSinceLastUpdate / 1000}s)")
            return true
        }

        // 이동 중일 때는 거리 기반 판단
        lastValidLocation?.let { lastLoc ->
            val distance = calculateDistance(
                lastLoc.latitude, lastLoc.longitude,
                location.latitude, location.longitude
            )

            val prefs = getSharedPreferences("UserPrefs", Context.MODE_PRIVATE)
            val locationThreshold = prefs.getInt("location_threshold", 10).toDouble()

            if (distance > locationThreshold) {
                Log.d(TAG, "📍 Significant movement detected: ${String.format("%.1f", distance)}m > ${locationThreshold}m")
                return true
            } else {
                Log.d(TAG, "📏 Minor movement: ${String.format("%.1f", distance)}m <= ${locationThreshold}m - skipping")
                return false
            }
        }

        return false
    }

    // 🆕 위치 업데이트 처리
    private fun processLocationUpdate(location: Location, userId: String, currentTime: Long, isCurrentlyStationary: Boolean) {
        Log.d(TAG, "💾 Processing location update for $userId - Stationary: $isCurrentlyStationary")

        // 상태 업데이트
        this.isStationary = isCurrentlyStationary
        if (isCurrentlyStationary) {
            lastStationaryUpdate = currentTime
        }

        // 새로운 위치인지 기존 위치의 머문시간 업데이트인지 판단
        if (lastValidLocation == null || !isSameLocationImproved(location, lastValidLocation!!, 10.0)) {
            // 새로운 위치
            createNewLocationRecord(location, userId, currentTime)
        } else {
            // 같은 위치 - 머문시간 업데이트
            updateStayDuration(userId, location, currentTime)
        }

        lastValidLocation = location
    }

    // 🆕 개선된 같은 위치 판단
    private fun isSameLocationImproved(location1: Location, location2: Location, threshold: Double): Boolean {
        val distance = calculateDistance(
            location1.latitude, location1.longitude,
            location2.latitude, location2.longitude
        )
        return distance <= threshold
    }

    // 🆕 새로운 위치 레코드 생성
    private fun createNewLocationRecord(location: Location, userId: String, currentTime: Long) {
        Log.d(TAG, "✨ Creating new location record for $userId")

        val locationData = LocationData(
            userId = userId,
            latitude = location.latitude,
            longitude = location.longitude,
            timestamp = currentTime,
            stayDuration = 0L,
            lastUpdateTime = currentTime
        )

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
    }

    private fun updateStayDuration(userId: String, location: Location, currentTime: Long) {
        currentLocationData?.let { currentData ->
            if (locationStartTime == 0L) {
                locationStartTime = currentData.timestamp
            }

            val totalStayDuration = currentTime - locationStartTime
            val stayMinutes = totalStayDuration / (1000 * 60)

            Log.d(TAG, "⏰ Updating stay duration for $userId - Total: ${stayMinutes} minutes")

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