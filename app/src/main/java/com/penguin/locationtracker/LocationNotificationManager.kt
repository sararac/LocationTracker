package com.penguin.locationtracker

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import com.google.firebase.database.ChildEventListener
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.ValueEventListener
import com.google.firebase.database.ktx.database
import com.google.firebase.ktx.Firebase
import java.text.SimpleDateFormat
import java.util.*

class LocationNotificationManager(private val context: Context) {

    companion object {
        private const val TAG = "LocationNotification"
        private const val CHANNEL_ID = "location_tracking_notifications"
        private const val NOTIFICATION_GROUP = "location_tracking_group"
        private const val SUMMARY_NOTIFICATION_ID = 1000
    }

    private val database = Firebase.database
    private val locationsRef = database.getReference("locations")
    private val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    private val userListeners = mutableMapOf<String, ChildEventListener>()

    init {
        createNotificationChannel()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "위치 추적 알림",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "사용자들의 위치 변경 및 머문시간 알림"
                enableVibration(true)
                enableLights(true)
                setShowBadge(true)
            }
            notificationManager.createNotificationChannel(channel)
            Log.d(TAG, "Location notification channel created")
        }
    }

    /**
     * 특정 사용자의 위치 추적 알림 시작
     */
    fun startLocationNotifications(targetUserId: String) {
        if (userListeners.containsKey(targetUserId)) {
            Log.d(TAG, "Already listening for $targetUserId")
            return
        }

        val listener = object : ChildEventListener {
            private var lastLocationData: LocationData? = null

            override fun onChildAdded(snapshot: DataSnapshot, previousChildName: String?) {
                val locationData = snapshot.getValue(LocationData::class.java)
                if (locationData != null && locationData.userId == targetUserId) {
                    lastLocationData = locationData
                    showLocationNotification(locationData, isNewLocation = true)
                }
            }

            override fun onChildChanged(snapshot: DataSnapshot, previousChildName: String?) {
                val locationData = snapshot.getValue(LocationData::class.java)
                if (locationData != null && locationData.userId == targetUserId) {
                    // 머문시간이 업데이트된 경우에만 알림 (새 위치가 아닌 경우)
                    lastLocationData?.let { last ->
                        if (isSameLocation(last, locationData) && locationData.stayDuration > 0) {
                            showLocationNotification(locationData, isNewLocation = false)
                        }
                    }
                    lastLocationData = locationData
                }
            }

            override fun onChildRemoved(snapshot: DataSnapshot) {
                // 위치 데이터 삭제시 처리 (필요시)
            }

            override fun onChildMoved(snapshot: DataSnapshot, previousChildName: String?) {
                // 이동시 처리 (일반적으로 사용안함)
            }

            override fun onCancelled(error: DatabaseError) {
                Log.e(TAG, "Error listening to $targetUserId locations: ${error.message}")
            }
        }

        locationsRef.child(targetUserId).addChildEventListener(listener)
        userListeners[targetUserId] = listener

        Log.d(TAG, "Started location notifications for $targetUserId")
    }

    /**
     * 특정 사용자의 위치 추적 알림 중단
     */
    fun stopLocationNotifications(targetUserId: String) {
        userListeners[targetUserId]?.let { listener ->
            locationsRef.child(targetUserId).removeEventListener(listener)
            userListeners.remove(targetUserId)
            Log.d(TAG, "Stopped location notifications for $targetUserId")
        }
    }

    /**
     * 모든 위치 추적 알림 중단
     */
    fun stopAllNotifications() {
        userListeners.forEach { (userId, listener) ->
            locationsRef.child(userId).removeEventListener(listener)
        }
        userListeners.clear()
        Log.d(TAG, "Stopped all location notifications")
    }

    /**
     * 같은 위치인지 판단 (거리 기준)
     */
    private fun isSameLocation(location1: LocationData, location2: LocationData): Boolean {
        val distance = calculateDistance(
            location1.latitude, location1.longitude,
            location2.latitude, location2.longitude
        )
        return distance <= 50.0 // 50미터 이내면 같은 위치로 판단
    }

    /**
     * 두 위치간 거리 계산
     */
    private fun calculateDistance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val earthRadius = 6371000.0 // 지구 반지름 (미터)
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = kotlin.math.sin(dLat / 2) * kotlin.math.sin(dLat / 2) +
                kotlin.math.cos(Math.toRadians(lat1)) * kotlin.math.cos(Math.toRadians(lat2)) *
                kotlin.math.sin(dLon / 2) * kotlin.math.sin(dLon / 2)
        val c = 2 * kotlin.math.atan2(kotlin.math.sqrt(a), kotlin.math.sqrt(1 - a))
        return earthRadius * c
    }

    /**
     * 위치 알림 표시
     */
    private fun showLocationNotification(locationData: LocationData, isNewLocation: Boolean) {
        try {
            val currentTime = SimpleDateFormat("HH시mm분", Locale.KOREAN).format(Date())

            val (title, content) = if (isNewLocation) {
                // 새로운 위치 이동 알림
                val title = "📍 ${locationData.userId} 위치 이동"
                val content = "${locationData.userId}이(가) ${currentTime}에 새로운 위치로 이동했습니다"
                Pair(title, content)
            } else {
                // 머문시간 업데이트 알림
                val stayDurationText = locationData.getFormattedStayDuration()
                val title = "⏱️ ${locationData.userId} 머문시간 업데이트"
                val content = "${locationData.userId}이(가) 현재 위치에 ${stayDurationText} 머물고 있습니다"
                Pair(title, content)
            }

            // 알림 클릭시 해당 사용자 선택하여 앱 실행
            val intent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra("selected_user_id", locationData.userId)
                putExtra("notification_type", "location_tracking")
            }

            val pendingIntent = PendingIntent.getActivity(
                context,
                locationData.userId.hashCode(),
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_menu_mylocation)
                .setContentTitle(title)
                .setContentText(content)
                .setStyle(NotificationCompat.BigTextStyle().bigText(content))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setDefaults(NotificationCompat.DEFAULT_ALL)
                .setAutoCancel(true)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setContentIntent(pendingIntent)
                .setGroup(NOTIFICATION_GROUP)
                .build()

            // 사용자별로 고유한 알림 ID 생성
            val notificationId = locationData.userId.hashCode() + if (isNewLocation) 0 else 1
            notificationManager.notify(notificationId, notification)

            Log.d(TAG, "Location notification shown for ${locationData.userId}: $title")

            // 그룹 요약 알림 표시 (여러 알림이 있을 때)
            showSummaryNotification()

        } catch (e: Exception) {
            Log.e(TAG, "Error showing location notification", e)
        }
    }

    /**
     * 그룹 요약 알림 표시
     */
    private fun showSummaryNotification() {
        try {
            val summaryNotification = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_menu_mylocation)
                .setContentTitle("위치 추적 알림")
                .setContentText("사용자들의 위치 업데이트가 있습니다")
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setGroup(NOTIFICATION_GROUP)
                .setGroupSummary(true)
                .setAutoCancel(true)
                .build()

            notificationManager.notify(SUMMARY_NOTIFICATION_ID, summaryNotification)

        } catch (e: Exception) {
            Log.e(TAG, "Error showing summary notification", e)
        }
    }

    /**
     * 현재 추적중인 사용자 목록 반환
     */
    fun getTrackedUsers(): Set<String> {
        return userListeners.keys.toSet()
    }
}