package com.penguin.locationtracker

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingEvent
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.ValueEventListener
import com.google.firebase.database.ktx.database
import com.google.firebase.ktx.Firebase
import java.text.SimpleDateFormat
import java.util.*

class GeofenceBroadcastReceiver : BroadcastReceiver() {

    companion object {
        private const val CHANNEL_ID = "geofence_notifications"
        private const val TAG = "GeofenceReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        Log.d(TAG, "Geofence broadcast received with action: ${intent.action}")

        val geofencingEvent = GeofencingEvent.fromIntent(intent)
        if (geofencingEvent == null) {
            Log.e(TAG, "GeofencingEvent.fromIntent() returned null")
            return
        }

        if (geofencingEvent.hasError()) {
            Log.e(TAG, "Geofencing event has error: ${geofencingEvent.errorCode}")
            return
        }

        val geofenceTransition = geofencingEvent.geofenceTransition
        val triggeringGeofences = geofencingEvent.triggeringGeofences

        if (triggeringGeofences.isNullOrEmpty()) {
            Log.e(TAG, "No triggering geofences found")
            return
        }

        Log.d(TAG, "Number of triggering geofences: ${triggeringGeofences.size}")

        for (geofence in triggeringGeofences) {
            val geofenceId = geofence.requestId
            Log.d(TAG, "Processing geofence: $geofenceId, transition: $geofenceTransition")

            // Firebase에서 지오펜스 정보 조회
            val database = Firebase.database
            val geofencesRef = database.getReference("geofences")

            geofencesRef.child(geofenceId).addListenerForSingleValueEvent(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    val geofenceData = snapshot.getValue(GeofenceData::class.java)
                    if (geofenceData != null) {
                        // 현재 사용자 확인
                        val prefs = context.getSharedPreferences("UserPrefs", Context.MODE_PRIVATE)
                        val currentUserId = prefs.getString("user_id", "") ?: ""

                        // 대상 사용자가 현재 사용자인 경우에만 처리
                        if (geofenceData.targetUserId == currentUserId) {
                            when (geofenceTransition) {
                                Geofence.GEOFENCE_TRANSITION_ENTER -> {
                                    val title = "${geofenceData.targetUserId} ${geofenceData.name} 도착"
                                    val message = "${geofenceData.targetUserId}가 ${geofenceData.name}에 ${getCurrentTime()}에 도착했습니다."
                                    showNotification(context, title, message)
                                    Log.d(TAG, "Entered geofence: ${geofenceData.name}")
                                }
                                Geofence.GEOFENCE_TRANSITION_EXIT -> {
                                    val title = "${geofenceData.targetUserId} ${geofenceData.name} 출발"
                                    val message = "${geofenceData.targetUserId}가 ${geofenceData.name}에서 ${getCurrentTime()}에 출발했습니다."
                                    showNotification(context, title, message)
                                    Log.d(TAG, "Exited geofence: ${geofenceData.name}")
                                }
                                else -> {
                                    Log.w(TAG, "Unknown geofence transition: $geofenceTransition")
                                }
                            }
                        } else {
                            Log.d(TAG, "Geofence not for current user: target=${geofenceData.targetUserId}, current=$currentUserId")
                        }
                    } else {
                        Log.e(TAG, "Geofence data not found for id: $geofenceId")
                    }
                }

                override fun onCancelled(error: DatabaseError) {
                    Log.e(TAG, "Error reading geofence data: ${error.message}")
                }
            })
        }
    }

    private fun getCurrentTime(): String {
        val sdf = SimpleDateFormat("HH시mm분", Locale.KOREAN)
        return sdf.format(Date())
    }

    private fun showNotification(context: Context, title: String, message: String) {
        Log.d(TAG, "Showing notification: $title - $message")

        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        // Android 8.0+ 알림 채널 생성
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val existingChannel = notificationManager.getNotificationChannel(CHANNEL_ID)
            if (existingChannel == null) {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    "위치 알림",
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = "지정된 장소 도착/출발 알림"
                    enableVibration(true)
                    enableLights(true)
                    setShowBadge(true)
                }
                notificationManager.createNotificationChannel(channel)
                Log.d(TAG, "Notification channel created")
            }
        }

        // 앱 실행 인텐트 생성
        val appIntent = context.packageManager.getLaunchIntentForPackage(context.packageName)
        val pendingIntent = PendingIntent.getActivity(
            context,
            0,
            appIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        try {
            val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_map)
                .setContentTitle(title)
                .setContentText(message)
                .setStyle(NotificationCompat.BigTextStyle().bigText(message))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setDefaults(NotificationCompat.DEFAULT_ALL)
                .setAutoCancel(true)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setContentIntent(pendingIntent) // 알림 클릭시 앱 실행
                .build()

            val notificationId = System.currentTimeMillis().toInt()
            notificationManager.notify(notificationId, notification)

            Log.d(TAG, "Notification posted with ID: $notificationId")
        } catch (e: Exception) {
            Log.e(TAG, "Error showing notification", e)
        }
    }
}