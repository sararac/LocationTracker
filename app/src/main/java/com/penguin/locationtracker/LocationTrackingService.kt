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
import android.net.wifi.WifiManager
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
import kotlin.math.*
import android.app.Notification
import com.google.firebase.database.ChildEventListener

class LocationTrackingService : Service() {

    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var locationCallback: LocationCallback
    private var locationRequest: LocationRequest? = null
    private val database = Firebase.database
    private val locationsRef = database.getReference("locations")
    private var wakeLock: PowerManager.WakeLock? = null

    // 🆕 DBSCAN 기반 위치 안정화 시스템
    private lateinit var locationStabilizer: LocationStabilizer
    private var currentWifiSSID: String? = null
    private var previousWifiSSID: String? = null

    // 위치 처리 관련 변수들
    private var lastLocation: Location? = null
    private var currentLocationData: LocationData? = null
    private var locationStartTime: Long = 0L
    private val locationBuffer = mutableListOf<Location>()
    private var lastValidLocation: Location? = null
    private var stationaryStartTime: Long = 0L
    private var isStationary = false

    // 🆕 WiFi 기반 처리 관련 변수
    private var wifiLocationStartTime: Long = 0L
    private var isWifiStationary = false

    // 🆕 지오펜스 알림 리스너 관련
    private var notificationListener: ChildEventListener? = null
    private val notificationHistoryRef = Firebase.database.getReference("notification_history")

    companion object {
        private const val TAG = "LocationTracking"
        private const val NOTIFICATION_ID = 1
        private const val CHANNEL_ID = "location_service_channel"
        const val ACTION_START_TRACKING = "START_TRACKING"
        const val ACTION_STOP_TRACKING = "STOP_TRACKING"

        // WiFi 관련 상수들
        private const val MIN_WIFI_STATIONARY_TIME = 30 * 1000L // 30초간 같은 WiFi에 연결되어야 정지 상태로 인정
    }

    override fun onCreate() {
        super.onCreate()

        // 🆕 위치 안정화 시스템 초기화 (Context 전달)
        locationStabilizer = LocationStabilizer(this)

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        setupLocationCallback()
        createNotificationChannels()
        acquireWakeLock()
        initializeLocationNotificationManager()
        startNotificationListener()

        // 🆕 현재 설정값 로그 출력
        locationStabilizer.logCurrentSettings()

        Log.d(TAG, "LocationTrackingService created with WiFi+DBSCAN stabilization")
    }

    private fun setupLocationCallback() {
        locationCallback = object : LocationCallback() {
            override fun onLocationResult(locationResult: LocationResult) {
                locationResult.lastLocation?.let { location ->
                    Log.d(TAG, "📍 Location update - Lat: ${location.latitude}, Lng: ${location.longitude}, Accuracy: ${location.accuracy}m")

                    // 🆕 WiFi + DBSCAN 기반 위치 처리
                    handleLocationWithWiFiDBSCAN(location)
                    updateNotification(location)
                }
            }
        }
    }

    // 🆕 WiFi + DBSCAN 기반 위치 처리 메인 함수
    private fun handleLocationWithWiFiDBSCAN(location: Location) {
        val prefs = getSharedPreferences("UserPrefs", Context.MODE_PRIVATE)
        val userId = prefs.getString("user_id", "") ?: ""
        val wifiStationaryDetection = prefs.getBoolean("wifi_stationary_detection", true)

        if (userId.isEmpty()) return

        val currentTime = System.currentTimeMillis()

        // 1. WiFi 상태 확인
        val newWifiSSID = getConnectedWifiSSID()

        if (newWifiSSID != currentWifiSSID) {
            Log.d(TAG, "🏠 WiFi state changed: $currentWifiSSID → $newWifiSSID")
            previousWifiSSID = currentWifiSSID
            currentWifiSSID = newWifiSSID

            // WiFi 상태 변경을 안정화 시스템에 알림
            locationStabilizer.onWiFiStateChanged(currentWifiSSID, previousWifiSSID)

            // WiFi 변경시 상태 초기화
            if (currentWifiSSID == null) {
                // WiFi 연결 해제됨
                isWifiStationary = false
                wifiLocationStartTime = 0L
                Log.d(TAG, "🏠 WiFi disconnected - resuming normal location processing")
            } else {
                // 새 WiFi 연결됨
                wifiLocationStartTime = currentTime
                Log.d(TAG, "🏠 WiFi connected to: $currentWifiSSID - starting stabilization")
            }
        }

        // 2. WiFi 연결 상태에 따른 처리 분기
        if (currentWifiSSID != null && wifiStationaryDetection && !isMobileWiFi(currentWifiSSID!!)) {
            handleWiFiConnectedLocation(location, userId, currentTime)
        } else {
            handleNormalLocation(location, userId, currentTime)
        }
    }

    // 🆕 WiFi 연결된 상태에서의 위치 처리
    private fun handleWiFiConnectedLocation(location: Location, userId: String, currentTime: Long) {
        Log.d(TAG, "🏠 Processing WiFi-connected location for SSID: $currentWifiSSID")

        // WiFi 연결 후 최소 시간이 지나야 안정 상태로 판단
        val wifiConnectionDuration = currentTime - wifiLocationStartTime
        if (wifiConnectionDuration < MIN_WIFI_STATIONARY_TIME) {
            Log.d(TAG, "⏰ WiFi connection too recent (${wifiConnectionDuration}ms) - waiting for stabilization")
            handleNormalLocation(location, userId, currentTime)
            return
        }

        // DBSCAN 안정화 시스템에 위치 전달
        val stabilizedLocation = locationStabilizer.processLocationForWiFi(
            currentWifiSSID,
            location.latitude,
            location.longitude,
            location.accuracy,
            currentTime
        )

        when {
            stabilizedLocation == null -> {
                // 안정화된 위치에서 머문시간만 증가
                Log.d(TAG, "⏰ WiFi location stable - updating stay duration only")
                updateStayDurationForWiFiLocation(userId, currentTime)
            }

            stabilizedLocation.clusterId >= 0 -> {
                // 클러스터링된 안정화 위치 사용
                Log.d(TAG, "✅ Using DBSCAN stabilized location (cluster: ${stabilizedLocation.clusterId})")
                processStabilizedLocationUpdate(stabilizedLocation, userId, currentTime)
            }

            else -> {
                // 아직 안정화되지 않은 위치 사용
                Log.d(TAG, "📍 Using current location (not yet stabilized)")
                val locationPoint = LocationStabilizer.LocationPoint(
                    location.latitude, location.longitude, currentTime, location.accuracy
                )
                processStabilizedLocationUpdate(locationPoint, userId, currentTime)
            }
        }
    }

    // 🆕 안정화된 위치 업데이트 처리
    private fun processStabilizedLocationUpdate(
        stabilizedLocation: LocationStabilizer.LocationPoint,
        userId: String,
        currentTime: Long
    ) {
        val prefs = getSharedPreferences("UserPrefs", Context.MODE_PRIVATE)
        val locationThreshold = prefs.getInt("location_threshold", 10).toDouble()

        // 기존 위치와 비교
        val shouldUpdate = lastValidLocation?.let { lastLoc ->
            val distance = calculateDistance(
                lastLoc.latitude, lastLoc.longitude,
                stabilizedLocation.latitude, stabilizedLocation.longitude
            )
            distance > locationThreshold
        } ?: true

        if (shouldUpdate) {
            // 새로운 위치로 업데이트
            createNewLocationRecord(stabilizedLocation, userId, currentTime)
        } else {
            // 같은 위치에서 머문시간 업데이트
            updateStayDurationForStabilizedLocation(userId, stabilizedLocation, currentTime)
        }

        // lastValidLocation 업데이트
        lastValidLocation = Location("stabilized").apply {
            latitude = stabilizedLocation.latitude
            longitude = stabilizedLocation.longitude
            accuracy = stabilizedLocation.accuracy
            time = stabilizedLocation.timestamp
        }
    }

    // 🆕 WiFi 위치에서 머문시간 업데이트
    private fun updateStayDurationForWiFiLocation(userId: String, currentTime: Long) {
        currentLocationData?.let { currentData ->
            if (locationStartTime == 0L) {
                locationStartTime = currentData.timestamp
            }

            val totalStayDuration = currentTime - locationStartTime

            Log.d(TAG, "⏰ Updating WiFi stay duration: ${totalStayDuration / (1000 * 60)} minutes")

            // Firebase에서 현재 위치 데이터 찾아서 업데이트
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
                                    Log.d(TAG, "✅ WiFi stay duration updated - Duration: ${totalStayDuration / (1000 * 60)}min")
                                    currentLocationData = currentData.copy(
                                        stayDuration = totalStayDuration,
                                        lastUpdateTime = currentTime
                                    )
                                }
                                .addOnFailureListener { e ->
                                    Log.e(TAG, "❌ Failed to update WiFi stay duration", e)
                                }
                            break
                        }
                    }

                    override fun onCancelled(error: DatabaseError) {
                        Log.e(TAG, "❌ Error finding location data to update", error.toException())
                    }
                })
        }
    }

    // 🆕 안정화된 위치의 머문시간 업데이트
    private fun updateStayDurationForStabilizedLocation(
        userId: String,
        stabilizedLocation: LocationStabilizer.LocationPoint,
        currentTime: Long
    ) {
        currentLocationData?.let { currentData ->
            if (locationStartTime == 0L) {
                locationStartTime = currentData.timestamp
            }

            val totalStayDuration = currentTime - locationStartTime

            Log.d(TAG, "⏰ Updating stabilized location stay duration: ${totalStayDuration / (1000 * 60)} minutes")

            locationsRef.child(userId).orderByChild("timestamp").equalTo(currentData.timestamp.toDouble())
                .addListenerForSingleValueEvent(object : ValueEventListener {
                    override fun onDataChange(snapshot: DataSnapshot) {
                        for (childSnapshot in snapshot.children) {
                            // 안정화된 위치와 머문시간 모두 업데이트
                            val updates = mapOf(
                                "latitude" to stabilizedLocation.latitude,
                                "longitude" to stabilizedLocation.longitude,
                                "stayDuration" to totalStayDuration,
                                "lastUpdateTime" to currentTime
                            )

                            childSnapshot.ref.updateChildren(updates)
                                .addOnSuccessListener {
                                    Log.d(TAG, "✅ Stabilized location and stay duration updated")
                                    currentLocationData = currentData.copy(
                                        latitude = stabilizedLocation.latitude,
                                        longitude = stabilizedLocation.longitude,
                                        stayDuration = totalStayDuration,
                                        lastUpdateTime = currentTime
                                    )
                                }
                                .addOnFailureListener { e ->
                                    Log.e(TAG, "❌ Failed to update stabilized location", e)
                                }
                            break
                        }
                    }

                    override fun onCancelled(error: DatabaseError) {
                        Log.e(TAG, "❌ Error finding location data to update", error.toException())
                    }
                })
        }
    }

    // 기존 일반 위치 처리 (WiFi 연결 없을 때)
    private fun handleNormalLocation(location: Location, userId: String, currentTime: Long) {
        // 기존의 handleNewLocationImproved 로직 사용
        Log.d(TAG, "📍 Processing normal location (no WiFi or mobile WiFi)")

        val prefs = getSharedPreferences("UserPrefs", Context.MODE_PRIVATE)
        val locationThreshold = prefs.getInt("location_threshold", 10).toDouble()

        // 정확도 필터링
        if (location.accuracy > 100.0) {
            Log.d(TAG, "🚫 Location rejected - Poor accuracy: ${location.accuracy}m")
            return
        }

        // 위치 버퍼에 추가
        locationBuffer.add(location)
        if (locationBuffer.size > 5) {
            locationBuffer.removeAt(0)
        }

        if (locationBuffer.size < 3) return

        // 스무딩된 위치 계산
        val smoothedLocation = calculateSmoothedLocation(locationBuffer)

        // 위치 업데이트 결정
        if (shouldUpdateNormalLocation(smoothedLocation, currentTime, locationThreshold)) {
            processNormalLocationUpdate(smoothedLocation, userId, currentTime)
        }
    }

    // 일반 위치 업데이트 판단
    private fun shouldUpdateNormalLocation(location: Location, currentTime: Long, threshold: Double): Boolean {
        if (lastValidLocation == null) return true

        val distance = calculateDistance(
            lastValidLocation!!.latitude, lastValidLocation!!.longitude,
            location.latitude, location.longitude
        )

        val timeSinceLastUpdate = currentTime - (currentLocationData?.lastUpdateTime ?: 0)

        return distance > threshold || timeSinceLastUpdate > (5 * 60 * 1000) // 5분
    }

    // 일반 위치 업데이트 처리
    private fun processNormalLocationUpdate(location: Location, userId: String, currentTime: Long) {
        if (lastValidLocation == null || !isSameLocation(location, lastValidLocation!!, 10.0)) {
            // 새로운 위치
            val locationPoint = LocationStabilizer.LocationPoint(
                location.latitude, location.longitude, currentTime, location.accuracy
            )
            createNewLocationRecord(locationPoint, userId, currentTime)
        } else {
            // 같은 위치 - 머문시간 업데이트
            updateStayDuration(userId, location, currentTime)
        }

        lastValidLocation = location
    }

    // 🆕 LocationPoint를 사용한 새로운 위치 레코드 생성
    private fun createNewLocationRecord(locationPoint: LocationStabilizer.LocationPoint, userId: String, currentTime: Long) {
        Log.d(TAG, "✨ Creating new location record for $userId")
        Log.d(TAG, "📍 Location: ${String.format("%.6f, %.6f", locationPoint.latitude, locationPoint.longitude)}")
        Log.d(TAG, "🎯 Accuracy: ${locationPoint.accuracy}m, ClusterID: ${locationPoint.clusterId}")

        val locationData = LocationData(
            userId = userId,
            latitude = locationPoint.latitude,
            longitude = locationPoint.longitude,
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

    // Location을 사용한 기존 함수 (호환성 유지)
    private fun createNewLocationRecord(location: Location, userId: String, currentTime: Long) {
        val locationPoint = LocationStabilizer.LocationPoint(
            location.latitude, location.longitude, currentTime, location.accuracy
        )
        createNewLocationRecord(locationPoint, userId, currentTime)
    }

    // 기존 머문시간 업데이트 함수들 유지
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

    // 기존 헬퍼 함수들
    private fun calculateSmoothedLocation(locations: List<Location>): Location {
        if (locations.size == 1) return locations[0]

        var totalWeight = 0.0
        var weightedLat = 0.0
        var weightedLng = 0.0
        var bestAccuracy = Float.MAX_VALUE

        locations.forEach { loc ->
            val weight = 1.0 / (loc.accuracy + 1.0)
            totalWeight += weight
            weightedLat += loc.latitude * weight
            weightedLng += loc.longitude * weight

            if (loc.accuracy < bestAccuracy) {
                bestAccuracy = loc.accuracy
            }
        }

        return Location("smoothed").apply {
            latitude = weightedLat / totalWeight
            longitude = weightedLng / totalWeight
            accuracy = bestAccuracy
            time = System.currentTimeMillis()
        }
    }

    private fun isSameLocation(location1: Location, location2: Location, threshold: Double): Boolean {
        val distance = calculateDistance(
            location1.latitude, location1.longitude,
            location2.latitude, location2.longitude
        )
        return distance <= threshold
    }

    private fun calculateDistance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val earthRadius = 6371000.0 // 지구 반지름 (미터)
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = kotlin.math.sin(dLat / 2).pow(2) +
                kotlin.math.cos(Math.toRadians(lat1)) * kotlin.math.cos(Math.toRadians(lat2)) *
                kotlin.math.sin(dLon / 2).pow(2)
        val c = 2 * kotlin.math.atan2(kotlin.math.sqrt(a), kotlin.math.sqrt(1 - a))
        return earthRadius * c
    }

    private fun getConnectedWifiSSID(): String? {
        return try {
            val wifiManager = getSystemService(Context.WIFI_SERVICE) as WifiManager
            val wifiInfo = wifiManager.connectionInfo

            if (wifiInfo != null && wifiInfo.networkId != -1) {
                wifiInfo.ssid?.replace("\"", "") // SSID에서 따옴표 제거
            } else {
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting WiFi SSID", e)
            null
        }
    }

    private fun isMobileWiFi(ssid: String): Boolean {
        val mobilePatterns = listOf(
            "KTX", "SRT", "ITX", "KorailWiFi", "PublicWiFi@BUS",
            "T wifi zone_Secure", "_Free_U+zone", "korail", "KORAIL"
        )

        return mobilePatterns.any { pattern ->
            ssid.contains(pattern, ignoreCase = true)
        }
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
                        Log.d(TAG, "🧹 Cleaned up $deletedCount old location records for user: $userId")
                    }
                }

                override fun onCancelled(error: DatabaseError) {
                    Log.e(TAG, "Failed to cleanup old data", error.toException())
                }
            })
    }

    // 🆕 지오펜스 알림 리스너 시작
    private fun startNotificationListener() {
        try {
            val prefs = getSharedPreferences("UserPrefs", Context.MODE_PRIVATE)
            val currentUserId = prefs.getString("user_id", "") ?: ""

            if (currentUserId.isEmpty()) {
                Log.d(TAG, "No user ID set, skipping notification listener")
                return
            }

            Log.d(TAG, "Starting notification listener for user: $currentUserId")

            notificationListener = object : ChildEventListener {
                override fun onChildAdded(snapshot: DataSnapshot, previousChildName: String?) {
                    val notification = snapshot.getValue(GeofenceNotificationData::class.java)

                    if (notification != null &&
                        notification.notifyUserId == currentUserId &&
                        !notification.isRead) {

                        // 새로운 알림이 추가되면 로컬 알림 표시
                        showLocalNotification(notification)

                        Log.d(TAG, "🔔 New geofence notification received for: ${notification.geofenceName}")
                    }
                }

                override fun onChildChanged(snapshot: DataSnapshot, previousChildName: String?) {}
                override fun onChildRemoved(snapshot: DataSnapshot) {}
                override fun onChildMoved(snapshot: DataSnapshot, previousChildName: String?) {}

                override fun onCancelled(error: DatabaseError) {
                    Log.e(TAG, "Notification listener cancelled: ${error.message}")
                }
            }

            notificationHistoryRef
                .orderByChild("notifyUserId")
                .equalTo(currentUserId)
                .addChildEventListener(notificationListener!!)

            Log.d(TAG, "✅ Notification listener started for user: $currentUserId")

        } catch (e: Exception) {
            Log.e(TAG, "Error starting notification listener", e)
        }
    }

    // 🆕 로컬 알림 표시 메서드
    private fun showLocalNotification(data: GeofenceNotificationData) {
        try {
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

            // 알림 채널 생성 (필요시)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(
                    "geofence_notifications",
                    "위치 알림",
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = "지정된 장소 도착/출발 알림"
                    enableVibration(true)
                    enableLights(true)
                    setShowBadge(true)
                }
                notificationManager.createNotificationChannel(channel)
            }

            // 앱 실행 인텐트
            val intent = packageManager.getLaunchIntentForPackage(packageName)?.apply {
                putExtra("show_notification_history", true)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }

            val pendingIntent = PendingIntent.getActivity(
                this,
                data.id.hashCode(),
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            // 알림 생성 및 표시
            val notification = NotificationCompat.Builder(this, "geofence_notifications")
                .setContentTitle(data.getNotificationTitle())
                .setContentText(data.getNotificationMessage())
                .setStyle(NotificationCompat.BigTextStyle().bigText(data.getNotificationMessage()))
                .setSmallIcon(android.R.drawable.ic_dialog_map)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setDefaults(NotificationCompat.DEFAULT_ALL)
                .setAutoCancel(true)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setContentIntent(pendingIntent)
                .addAction(
                    android.R.drawable.ic_menu_view,
                    "이력 보기",
                    pendingIntent
                )
                .build()

            notificationManager.notify(data.id.hashCode(), notification)

            Log.d(TAG, "🔔 Local notification shown: ${data.getNotificationTitle()}")

        } catch (e: Exception) {
            Log.e(TAG, "Error showing local notification", e)
        }
    }

    // 🆕 알림 리스너 정리
    private fun stopNotificationListener() {
        notificationListener?.let { listener ->
            try {
                notificationHistoryRef.removeEventListener(listener)
                notificationListener = null
                Log.d(TAG, "Notification listener stopped")
            } catch (e: Exception) {
                Log.e(TAG, "Error stopping notification listener", e)
            }
        }
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

    // 🆕 서비스 종료 시 정리
    override fun onDestroy() {
        super.onDestroy()

        try {
            // WiFi 상태 정리
            locationStabilizer.cleanupOldWiFiStates()

            // 기존 정리 작업들
            stopLocationTracking()
            releaseWakeLock()
            stopNotificationListener()

            Log.d(TAG, "LocationTrackingService destroyed with cleanup")
        } catch (e: Exception) {
            Log.e(TAG, "Error during service destruction", e)
        }
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationManager = getSystemService(NotificationManager::class.java)

            try {
                notificationManager.deleteNotificationChannel(CHANNEL_ID)
            } catch (e: Exception) {
                Log.e(TAG, "Error deleting old channel", e)
            }

            val trackingChannel = NotificationChannel(
                CHANNEL_ID,
                "위치 추적 (백그라운드)",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "백그라운드 위치 추적 상태"
                setShowBadge(false)
                enableVibration(false)
                setSound(null, null)
                lockscreenVisibility = Notification.VISIBILITY_SECRET
            }

            notificationManager.createNotificationChannel(trackingChannel)
            Log.d(TAG, "Location tracking channel created with LOW importance")
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

        createNotificationChannels()

        val notification = createNotification(userId, trackingInterval)
        startForeground(NOTIFICATION_ID, notification)

        // 🆕 WiFi 기반 처리를 위해 조금 더 자주 위치 업데이트
        locationRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, (trackingInterval * 1000).toLong())
            .setMinUpdateIntervalMillis((trackingInterval * 1000).toLong())
            .setMaxUpdateDelayMillis((trackingInterval * 1000 * 2).toLong())
            .setWaitForAccurateLocation(true)
            .setMinUpdateDistanceMeters(3.0f) // 🆕 WiFi 실내에서 더 세밀한 감지를 위해 3m로 감소
            .build()

        locationRequest?.let { request ->
            fusedLocationClient.requestLocationUpdates(
                request,
                locationCallback,
                Looper.getMainLooper()
            )
        }

        Log.d(TAG, "🚀 Location tracking STARTED with WiFi+DBSCAN - User: $userId, Interval: ${trackingInterval}sec")
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
            .setContentTitle("위치 추적 중 (WiFi+DBSCAN)")
            .setContentText("사용자: $userId | 스마트 위치 안정화 활성")
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setOngoing(true)
            .setAutoCancel(false)
            .setSilent(true)
            .setShowWhen(false)
            .setLocalOnly(true)
            .setContentIntent(pendingIntent)
            .build()
    }

    private fun updateNotification(location: Location) {
        // 알림 업데이트 생략 (MIN 우선순위에서는 보이지 않음)
        return
    }

    private fun hasLocationPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
    }

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

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand called with action: ${intent?.action}")

        when (intent?.action) {
            ACTION_START_TRACKING -> startLocationTracking()
            ACTION_STOP_TRACKING -> stopLocationTracking()
        }

        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

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
}