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
import java.util.*
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

    // 🆕 속도 기반 필터링을 위한 추가 변수들
    private val speedBuffer = mutableListOf<Float>() // 최근 속도들을 저장
    private val SPEED_BUFFER_SIZE = 5 // 속도 버퍼 크기
    private val STATIONARY_SPEED_THRESHOLD = 0.5f // 0.5 m/s (1.8km/h) 이하면 정지
    private val STATIONARY_DISTANCE_THRESHOLD = 30.0 // 30m 이내 변화면 정지
    private val MOVING_SPEED_THRESHOLD = 2.0f // 2.0 m/s (7.2km/h) 이상이면 확실히 이동

    private var connectedWifiSSID: String? = null
    private var wifiStationaryCount = 0
    private val WIFI_STATIONARY_THRESHOLD = 3

    // 🆕 지오펜스 알림 리스너 관련
    private var notificationListener: ChildEventListener? = null
    private val notificationHistoryRef = Firebase.database.getReference("notification_history")

    // 알려진 이동형 WiFi 패턴
    private val MOBILE_WIFI_PATTERNS = listOf(
        "KTX",           // KTX WiFi
        "SRT",           // SRT WiFi
        "ITX",           // ITX WiFi
        "KorailWiFi",    // 코레일 WiFi
        "PublicWiFi@BUS", // 버스 WiFi
        "T wifi zone_Secure", // 이동형 T WiFi
        "_Free_U+zone",  // 이동형 U+ WiFi
        "korail",        // 코레일 변형
        "KORAIL"         // 코레일 대문자
    )

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
        createNotificationChannels()
        acquireWakeLock()
        initializeLocationNotificationManager()
        startNotificationListener() // 🆕 추가
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

                override fun onChildChanged(snapshot: DataSnapshot, previousChildName: String?) {
                    // 필요시 구현
                }

                override fun onChildRemoved(snapshot: DataSnapshot) {
                    // 필요시 구현
                }

                override fun onChildMoved(snapshot: DataSnapshot, previousChildName: String?) {
                    // 필요시 구현
                }

                override fun onCancelled(error: DatabaseError) {
                    Log.e(TAG, "Notification listener cancelled: ${error.message}")
                }
            }

            // 알림받을 사용자만 필터링하여 리스너 설정
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

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationManager = getSystemService(NotificationManager::class.java)

            // 기존 채널 삭제 (있다면)
            try {
                notificationManager.deleteNotificationChannel(CHANNEL_ID)
            } catch (e: Exception) {
                Log.e(TAG, "Error deleting old channel", e)
            }

            // 위치 추적용 채널 (최소 표시)
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

    private fun setupLocationCallback() {
        locationCallback = object : LocationCallback() {
            override fun onLocationResult(locationResult: LocationResult) {
                locationResult.lastLocation?.let { location ->
                    Log.d(TAG, "📍 Location update received - Lat: ${location.latitude}, Lng: ${location.longitude}, Accuracy: ${location.accuracy}m, Speed: ${if(location.hasSpeed()) "${location.speed}m/s" else "N/A"}, Time: ${java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())}")

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

        // 알림 채널 재생성 (설정이 변경되었을 수 있으므로)
        createNotificationChannels()

        // 알림 생성
        val notification = createNotification(userId, trackingInterval)
        startForeground(NOTIFICATION_ID, notification)

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
        // 알림 업데이트를 하지 않음 (MIN 우선순위에서는 업데이트해도 보이지 않음)
        return
    }

    // 🆕 개선된 위치 처리 함수
    private fun handleNewLocationImproved(location: Location) {
        val prefs = getSharedPreferences("UserPrefs", Context.MODE_PRIVATE)
        val userId = prefs.getString("user_id", "") ?: ""
        val locationThreshold = prefs.getInt("location_threshold", 10).toDouble()
        val wifiStationaryDetection = prefs.getBoolean("wifi_stationary_detection", true)

        if (userId.isEmpty()) return

        val currentTime = System.currentTimeMillis()

        // 1. 정확도 필터링 - 너무 부정확한 위치는 무시
        if (location.accuracy > MAX_ACCURACY) {
            Log.d(TAG, "🚫 Location rejected - Poor accuracy: ${location.accuracy}m (threshold: ${MAX_ACCURACY}m)")
            return
        }

        // 🆕 2. 속도 기반 1차 필터링
        val motionState = analyzeMotionState(location)
        Log.d(TAG, "🏃 Motion analysis: $motionState, Speed: ${if(location.hasSpeed()) "${location.speed}m/s" else "N/A"}, Accuracy: ${location.accuracy}m")

        // 🆕 3. WiFi + 속도 조합 정지 감지 (개선된 버전)
        if (wifiStationaryDetection) {
            val wifiState = analyzeWiFiState(location, motionState)
            if (wifiState == WiFiStationaryState.CONFIRMED_STATIONARY) {
                // WiFi + 속도 모두 정지 상태를 확인했으면 머문시간만 업데이트
                lastValidLocation?.let { lastLoc ->
                    updateStayDuration(userId, lastLoc, currentTime)
                }
                return
            }
        }

        // 4. 위치 버퍼에 추가 (기존 로직)
        locationBuffer.add(location)
        if (locationBuffer.size > BUFFER_SIZE) {
            locationBuffer.removeAt(0)
        }

        if (locationBuffer.size < 3) {
            Log.d(TAG, "📊 Building location buffer... (${locationBuffer.size}/$BUFFER_SIZE)")
            return
        }

        // 🆕 5. 스마트 스무딩 (움직임 상태에 따라 다르게 처리)
        val smoothedLocation = when (motionState) {
            MotionState.STATIONARY -> {
                // 정지 상태면 더 강한 스무딩 적용
                calculateStationarySmoothedLocation(locationBuffer)
            }
            MotionState.MOVING -> {
                // 이동 중이면 반응성을 위해 가벼운 스무딩
                calculateMovingSmoothedLocation(locationBuffer)
            }
            MotionState.UNCERTAIN -> {
                // 불확실하면 기본 스무딩
                calculateSmoothedLocation(locationBuffer)
            }
        }

        // 6. 위치 업데이트 결정
        if (shouldUpdateLocationSmart(smoothedLocation, motionState, currentTime)) {
            processLocationUpdate(smoothedLocation, userId, currentTime, motionState == MotionState.STATIONARY)
        } else {
            Log.d(TAG, "📍 Location update skipped - No significant change")
        }
    }

    // 🆕 움직임 상태 분석
    private fun analyzeMotionState(location: Location): MotionState {
        // 속도 버퍼에 추가
        if (location.hasSpeed()) {
            speedBuffer.add(location.speed)
            if (speedBuffer.size > SPEED_BUFFER_SIZE) {
                speedBuffer.removeAt(0)
            }
        }

        if (speedBuffer.size < 3) {
            return MotionState.UNCERTAIN
        }

        val avgSpeed = speedBuffer.average().toFloat()
        val maxSpeed = speedBuffer.maxOrNull() ?: 0f

        // 이전 위치와의 거리 계산
        val distanceFromLast = lastValidLocation?.let { lastLoc ->
            calculateDistance(
                lastLoc.latitude, lastLoc.longitude,
                location.latitude, location.longitude
            )
        } ?: 0.0

        Log.d(TAG, "🔍 Motion analysis - AvgSpeed: ${String.format("%.2f", avgSpeed)}m/s, MaxSpeed: ${String.format("%.2f", maxSpeed)}m/s, Distance: ${String.format("%.1f", distanceFromLast)}m")

        return when {
            // 확실히 이동 중: 높은 속도 또는 큰 거리 변화
            maxSpeed > MOVING_SPEED_THRESHOLD -> {
                Log.d(TAG, "✅ MOVING - High speed detected")
                MotionState.MOVING
            }

            // 확실히 정지: 낮은 속도 + 작은 거리 변화
            avgSpeed <= STATIONARY_SPEED_THRESHOLD && distanceFromLast <= STATIONARY_DISTANCE_THRESHOLD -> {
                Log.d(TAG, "✅ STATIONARY - Low speed + small distance")
                MotionState.STATIONARY
            }

            // 속도는 낮지만 거리 변화가 큰 경우 (GPS 노이즈)
            avgSpeed <= STATIONARY_SPEED_THRESHOLD && distanceFromLast > STATIONARY_DISTANCE_THRESHOLD -> {
                Log.d(TAG, "⚠️ STATIONARY - GPS noise detected (low speed but large distance)")
                MotionState.STATIONARY // GPS 노이즈로 간주하고 정지로 처리
            }

            else -> {
                Log.d(TAG, "❓ UNCERTAIN - Mixed signals")
                MotionState.UNCERTAIN
            }
        }
    }

    // 🆕 WiFi 상태 분석 (속도 정보와 조합)
    private fun analyzeWiFiState(location: Location, motionState: MotionState): WiFiStationaryState {
        val currentWifiSSID = getConnectedWifiSSID()

        if (currentWifiSSID == null) {
            connectedWifiSSID = null
            wifiStationaryCount = 0
            return WiFiStationaryState.NO_WIFI
        }

        // 이동형 WiFi 체크
        if (isMobileWiFi(currentWifiSSID)) {
            Log.d(TAG, "🚄 Mobile WiFi detected: $currentWifiSSID")
            return WiFiStationaryState.MOBILE_WIFI
        }

        // 고정형 WiFi + 움직임 상태 조합 분석
        return when {
            motionState == MotionState.MOVING -> {
                Log.d(TAG, "📶 WiFi connected but clearly moving")
                WiFiStationaryState.CONNECTED_BUT_MOVING
            }

            motionState == MotionState.STATIONARY && currentWifiSSID == connectedWifiSSID -> {
                wifiStationaryCount++
                if (wifiStationaryCount >= WIFI_STATIONARY_THRESHOLD) {
                    Log.d(TAG, "🏠 Confirmed stationary at WiFi: $currentWifiSSID")
                    WiFiStationaryState.CONFIRMED_STATIONARY
                } else {
                    WiFiStationaryState.LIKELY_STATIONARY
                }
            }

            else -> {
                connectedWifiSSID = currentWifiSSID
                wifiStationaryCount = 1
                WiFiStationaryState.NEWLY_CONNECTED
            }
        }
    }

    // 🆕 정지 상태용 강한 스무딩
    private fun calculateStationarySmoothedLocation(locations: List<Location>): Location {
        // 정지 상태에서는 더 강한 가중 평균 적용
        var totalWeight = 0.0
        var weightedLat = 0.0
        var weightedLng = 0.0
        var bestAccuracy = Float.MAX_VALUE

        locations.forEach { loc ->
            // 정확도가 좋을수록 + 속도가 낮을수록 가중치 증가
            val accuracyWeight = 1.0 / (loc.accuracy + 1.0)
            val speedWeight = if (loc.hasSpeed()) {
                1.0 / (loc.speed + 0.1) // 속도가 낮을수록 높은 가중치
            } else {
                1.0
            }

            val weight = accuracyWeight * speedWeight * 2.0 // 정지 상태에서는 가중치 2배

            totalWeight += weight
            weightedLat += loc.latitude * weight
            weightedLng += loc.longitude * weight

            if (loc.accuracy < bestAccuracy) {
                bestAccuracy = loc.accuracy
            }
        }

        return Location("stationary_smoothed").apply {
            latitude = weightedLat / totalWeight
            longitude = weightedLng / totalWeight
            accuracy = bestAccuracy * 0.8f // 스무딩으로 정확도 향상 반영
            time = System.currentTimeMillis()
            if (locations.last().hasSpeed()) {
                speed = 0f // 정지 상태로 강제 설정
            }
        }
    }

    // 🆕 이동 상태용 가벼운 스무딩
    private fun calculateMovingSmoothedLocation(locations: List<Location>): Location {
        // 이동 중에는 반응성을 위해 최근 위치에 더 높은 가중치
        val recentLocations = locations.takeLast(3) // 최근 3개만 사용

        var totalWeight = 0.0
        var weightedLat = 0.0
        var weightedLng = 0.0
        var bestAccuracy = Float.MAX_VALUE

        recentLocations.forEachIndexed { index, loc ->
            // 최근일수록 높은 가중치 (시간적 가중치)
            val timeWeight = (index + 1).toDouble() / recentLocations.size
            val accuracyWeight = 1.0 / (loc.accuracy + 1.0)
            val weight = timeWeight * accuracyWeight

            totalWeight += weight
            weightedLat += loc.latitude * weight
            weightedLng += loc.longitude * weight

            if (loc.accuracy < bestAccuracy) {
                bestAccuracy = loc.accuracy
            }
        }

        return Location("moving_smoothed").apply {
            latitude = weightedLat / totalWeight
            longitude = weightedLng / totalWeight
            accuracy = bestAccuracy
            time = System.currentTimeMillis()
            if (locations.last().hasSpeed()) {
                speed = locations.last().speed // 원래 속도 유지
            }
        }
    }

    // 기존 스무딩 함수 (UNCERTAIN 상태용)
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

    // 🆕 스마트 위치 업데이트 판단
    private fun shouldUpdateLocationSmart(
        location: Location,
        motionState: MotionState,
        currentTime: Long
    ): Boolean {
        if (lastValidLocation == null) {
            return true
        }

        val prefs = getSharedPreferences("UserPrefs", Context.MODE_PRIVATE)
        val timeSinceLastUpdate = currentTime - (currentLocationData?.lastUpdateTime ?: 0)
        val distanceFromLast = lastValidLocation?.let { lastLoc ->
            calculateDistance(
                lastLoc.latitude, lastLoc.longitude,
                location.latitude, location.longitude
            )
        } ?: 0.0

        return when (motionState) {
            MotionState.STATIONARY -> {
                // 정지 상태: 5분마다 또는 상태 변경 시
                if (this.isStationary != true) {
                    Log.d(TAG, "📍 State change to STATIONARY")
                    true
                } else {
                    val shouldUpdate = timeSinceLastUpdate > STATIONARY_UPDATE_INTERVAL
                    if (shouldUpdate) {
                        Log.d(TAG, "⏰ Stationary time-based update")
                    }
                    shouldUpdate
                }
            }

            MotionState.MOVING -> {
                // 이동 상태: 거리 기반 + 상태 변경
                val threshold = prefs.getInt("location_threshold", 10).toDouble()

                if (this.isStationary == true) {
                    Log.d(TAG, "📍 State change to MOVING")
                    true
                } else if (distanceFromLast > threshold) {
                    Log.d(TAG, "📍 Significant movement: ${String.format("%.1f", distanceFromLast)}m")
                    true
                } else {
                    false
                }
            }

            MotionState.UNCERTAIN -> {
                // 불확실한 상태: 기존 로직 사용
                val threshold = prefs.getInt("location_threshold", 10).toDouble()
                val shouldUpdate = distanceFromLast > threshold || timeSinceLastUpdate > (2 * 60 * 1000) // 2분

                if (shouldUpdate) {
                    Log.d(TAG, "📍 Uncertain state update: distance=${String.format("%.1f", distanceFromLast)}m, time=${timeSinceLastUpdate/1000}s")
                }
                shouldUpdate
            }
        }
    }

    // 위치 업데이트 처리
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

    // 개선된 같은 위치 판단
    private fun isSameLocationImproved(location1: Location, location2: Location, threshold: Double): Boolean {
        val distance = calculateDistance(
            location1.latitude, location1.longitude,
            location2.latitude, location2.longitude
        )
        return distance <= threshold
    }

    // 새로운 위치 레코드 생성
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

    // WiFi 관련 헬퍼 함수들
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

    private fun isMobileWiFi(ssid: String?): Boolean {
        if (ssid == null) return false

        return MOBILE_WIFI_PATTERNS.any { pattern ->
            ssid.contains(pattern, ignoreCase = true)
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
        stopNotificationListener() // 🆕 추가
        Log.d(TAG, "LocationTrackingService destroyed")
    }

    // 🆕 열거형 정의
    enum class MotionState {
        STATIONARY,  // 확실히 정지
        MOVING,      // 확실히 이동
        UNCERTAIN    // 불확실
    }

    enum class WiFiStationaryState {
        NO_WIFI,                // WiFi 연결 없음
        MOBILE_WIFI,           // 이동형 WiFi (KTX, 버스 등)
        NEWLY_CONNECTED,       // 새로 연결됨
        LIKELY_STATIONARY,     // 정지 가능성 높음
        CONFIRMED_STATIONARY,  // 정지 확정
        CONNECTED_BUT_MOVING   // 연결되어 있지만 이동 중
    }
}