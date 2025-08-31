package com.penguin.locationtracker

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

class BootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "BootReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        Log.d(TAG, "Boot completed: ${intent.action}")

        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            "android.intent.action.QUICKBOOT_POWERON",
            Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_PACKAGE_REPLACED -> {

                val prefs = context.getSharedPreferences("UserPrefs", Context.MODE_PRIVATE)
                val userId = prefs.getString("user_id", "") ?: ""
                val autoStart = prefs.getBoolean("auto_start_service", true)

                Log.d(TAG, "User ID: $userId, Auto start: $autoStart")

                if (userId.isNotEmpty() && autoStart) {
                    val serviceIntent = Intent(context, LocationTrackingService::class.java)
                    serviceIntent.action = LocationTrackingService.ACTION_START_TRACKING

                    try {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            context.startForegroundService(serviceIntent)
                        } else {
                            context.startService(serviceIntent)
                        }
                        Log.d(TAG, "Location service started after boot")
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to start service after boot", e)
                    }
                } else {
                    Log.d(TAG, "Service not started - User ID empty or auto start disabled")
                }
            }
        }
    }
}