package com.penguin.locationtracker

import android.content.Context
import android.location.Geocoder
import android.util.Log
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.ValueEventListener
import com.google.firebase.database.ktx.database
import com.google.firebase.ktx.Firebase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.*

class GeofenceNotificationHistoryManager(private val context: Context) {

    companion object {
        private const val TAG = "GeofenceNotificationHistory"
        private const val MAX_HISTORY_DAYS = 30 // 최대 30일간 이력 보관
    }

    private val database = Firebase.database
    private val notificationHistoryRef = database.getReference("notification_history")
    private val geocoder = Geocoder(context, Locale.KOREAN)

    /**
     * 지오펜스 알림 이력 저장
     */
    fun saveNotificationHistory(
        geofenceData: GeofenceData,
        transitionType: String, // "ENTER" or "EXIT"
        latitude: Double,
        longitude: Double,
        callback: ((Boolean) -> Unit)? = null
    ) {
        try {
            val notificationId = "notification_${System.currentTimeMillis()}"

            // 주소 정보를 비동기로 가져온 후 저장
            getAddressFromCoordinates(latitude, longitude) { address ->
                val notificationData = GeofenceNotificationData(
                    id = notificationId,
                    geofenceId = geofenceData.id,
                    geofenceName = geofenceData.name,
                    targetUserId = geofenceData.targetUserId,
                    notifyUserId = geofenceData.notifyUserId,
                    transitionType = transitionType,
                    latitude = latitude,
                    longitude = longitude,
                    timestamp = System.currentTimeMillis(),
                    isRead = false,
                    address = address
                )

                // Firebase에 저장
                notificationHistoryRef.child(notificationId).setValue(notificationData)
                    .addOnSuccessListener {
                        Log.d(TAG, "Notification history saved: ${notificationData.getNotificationTitle()}")
                        callback?.invoke(true)

                        // 오래된 이력 정리
                        cleanupOldHistory()
                    }
                    .addOnFailureListener { e ->
                        Log.e(TAG, "Failed to save notification history", e)
                        callback?.invoke(false)
                    }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error saving notification history", e)
            callback?.invoke(false)
        }
    }

    /**
     * 특정 사용자의 알림 이력 조회
     */
    fun getNotificationHistory(
        userId: String,
        callback: (List<GeofenceNotificationData>) -> Unit
    ) {
        try {
            Log.d(TAG, "Getting notification history for user: $userId")

            notificationHistoryRef.orderByChild("timestamp")
                .addValueEventListener(object : ValueEventListener {
                    override fun onDataChange(snapshot: DataSnapshot) {
                        val notifications = mutableListOf<GeofenceNotificationData>()

                        for (notificationSnapshot in snapshot.children) {
                            val notification = notificationSnapshot.getValue(GeofenceNotificationData::class.java)

                            // 해당 사용자와 관련된 알림만 필터링
                            if (notification != null &&
                                (notification.targetUserId == userId || notification.notifyUserId == userId)) {
                                notifications.add(notification)
                            }
                        }

                        // 최신 순으로 정렬
                        val sortedNotifications = notifications.sortedByDescending { it.timestamp }

                        Log.d(TAG, "Found ${sortedNotifications.size} notifications for $userId")
                        callback(sortedNotifications)
                    }

                    override fun onCancelled(error: DatabaseError) {
                        Log.e(TAG, "Error getting notification history for $userId", error.toException())
                        callback(emptyList())
                    }
                })
        } catch (e: Exception) {
            Log.e(TAG, "Error getting notification history", e)
            callback(emptyList())
        }
    }

    /**
     * 모든 알림 이력 조회 (관리자용)
     */
    fun getAllNotificationHistory(callback: (List<GeofenceNotificationData>) -> Unit) {
        try {
            notificationHistoryRef.orderByChild("timestamp")
                .addValueEventListener(object : ValueEventListener {
                    override fun onDataChange(snapshot: DataSnapshot) {
                        val notifications = mutableListOf<GeofenceNotificationData>()

                        for (notificationSnapshot in snapshot.children) {
                            val notification = notificationSnapshot.getValue(GeofenceNotificationData::class.java)
                            if (notification != null) {
                                notifications.add(notification)
                            }
                        }

                        // 최신 순으로 정렬
                        val sortedNotifications = notifications.sortedByDescending { it.timestamp }
                        callback(sortedNotifications)
                    }

                    override fun onCancelled(error: DatabaseError) {
                        Log.e(TAG, "Error getting all notification history", error.toException())
                        callback(emptyList())
                    }
                })
        } catch (e: Exception) {
            Log.e(TAG, "Error getting all notification history", e)
            callback(emptyList())
        }
    }

    /**
     * 알림을 읽음으로 표시
     */
    fun markAsRead(notificationId: String) {
        notificationHistoryRef.child(notificationId).child("isRead").setValue(true)
            .addOnSuccessListener {
                Log.d(TAG, "Notification marked as read: $notificationId")
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "Failed to mark notification as read: $notificationId", e)
            }
    }

    /**
     * 특정 알림 삭제
     */
    fun deleteNotification(notificationId: String, callback: ((Boolean) -> Unit)? = null) {
        notificationHistoryRef.child(notificationId).removeValue()
            .addOnSuccessListener {
                Log.d(TAG, "Notification deleted: $notificationId")
                callback?.invoke(true)
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "Failed to delete notification: $notificationId", e)
                callback?.invoke(false)
            }
    }

    /**
     * 오래된 알림 이력 정리 (30일 이상)
     */
    private fun cleanupOldHistory() {
        val cutoffTime = System.currentTimeMillis() - (MAX_HISTORY_DAYS * 24 * 60 * 60 * 1000L)

        notificationHistoryRef.orderByChild("timestamp").endAt(cutoffTime.toDouble())
            .addListenerForSingleValueEvent(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    var deletedCount = 0
                    for (childSnapshot in snapshot.children) {
                        childSnapshot.ref.removeValue()
                        deletedCount++
                    }
                    if (deletedCount > 0) {
                        Log.d(TAG, "Cleaned up $deletedCount old notification records")
                    }
                }

                override fun onCancelled(error: DatabaseError) {
                    Log.e(TAG, "Failed to cleanup old notification history", error.toException())
                }
            })
    }

    /**
     * 좌표로부터 주소 정보 가져오기
     */
    private fun getAddressFromCoordinates(
        latitude: Double,
        longitude: Double,
        callback: (String) -> Unit
    ) {
        try {
            Thread {
                try {
                    val addresses = geocoder.getFromLocation(latitude, longitude, 1)

                    val address = if (!addresses.isNullOrEmpty()) {
                        val addr = addresses[0]
                        val fullAddress = addr.getAddressLine(0)

                        if (!fullAddress.isNullOrEmpty()) {
                            fullAddress
                                .replace("대한민국", "")
                                .replace("South Korea", "")
                                .replace(Regex("\\d{5}"), "")
                                .trim()
                                .replace(Regex("\\s+"), " ")
                        } else {
                            ""
                        }
                    } else {
                        ""
                    }

                    callback(address)
                } catch (e: Exception) {
                    Log.e(TAG, "Error getting address", e)
                    callback("")
                }
            }.start()
        } catch (e: Exception) {
            Log.e(TAG, "Error starting address lookup", e)
            callback("")
        }
    }

    /**
     * 읽지 않은 알림 개수 조회
     */
    fun getUnreadCount(userId: String, callback: (Int) -> Unit) {
        try {
            notificationHistoryRef.orderByChild("isRead").equalTo(false)
                .addListenerForSingleValueEvent(object : ValueEventListener {
                    override fun onDataChange(snapshot: DataSnapshot) {
                        var unreadCount = 0

                        for (notificationSnapshot in snapshot.children) {
                            val notification = notificationSnapshot.getValue(GeofenceNotificationData::class.java)

                            // 해당 사용자와 관련된 읽지 않은 알림만 카운트
                            if (notification != null &&
                                (notification.targetUserId == userId || notification.notifyUserId == userId)) {
                                unreadCount++
                            }
                        }

                        callback(unreadCount)
                    }

                    override fun onCancelled(error: DatabaseError) {
                        Log.e(TAG, "Error getting unread count", error.toException())
                        callback(0)
                    }
                })
        } catch (e: Exception) {
            Log.e(TAG, "Error getting unread count", e)
            callback(0)
        }
    }
}