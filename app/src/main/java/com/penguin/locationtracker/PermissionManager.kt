package com.penguin.locationtracker

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

object PermissionManager {

    // 위치 권한들
    val LOCATION_PERMISSIONS = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        arrayOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.ACCESS_BACKGROUND_LOCATION
        )
    } else {
        arrayOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
    }

    // 알림 권한
    val NOTIFICATION_PERMISSION = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        arrayOf(Manifest.permission.POST_NOTIFICATIONS)
    } else {
        emptyArray()
    }

    // 모든 필요한 권한
    val ALL_REQUIRED_PERMISSIONS = LOCATION_PERMISSIONS + NOTIFICATION_PERMISSION

    /**
     * 위치 권한이 허용되어 있는지 확인
     */
    fun hasLocationPermission(context: Context): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                ) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * 백그라운드 위치 권한이 허용되어 있는지 확인 (Android 10+)
     */
    fun hasBackgroundLocationPermission(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_BACKGROUND_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true // Android 10 미만에서는 별도 백그라운드 권한 불필요
        }
    }

    /**
     * 알림 권한이 허용되어 있는지 확인 (Android 13+)
     */
    fun hasNotificationPermission(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true // Android 13 미만에서는 알림 권한 불필요
        }
    }

    /**
     * 앱 실행에 필요한 모든 권한이 허용되어 있는지 확인
     */
    fun hasAllRequiredPermissions(context: Context): Boolean {
        return hasLocationPermission(context) &&
                hasBackgroundLocationPermission(context) &&
                hasNotificationPermission(context)
    }

    /**
     * 거부된 권한 목록 반환
     */
    fun getDeniedPermissions(context: Context): List<String> {
        val deniedPermissions = mutableListOf<String>()

        // 위치 권한 확인
        if (!hasLocationPermission(context)) {
            deniedPermissions.add(Manifest.permission.ACCESS_FINE_LOCATION)
            deniedPermissions.add(Manifest.permission.ACCESS_COARSE_LOCATION)
        }

        // 백그라운드 위치 권한 확인
        if (!hasBackgroundLocationPermission(context)) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                deniedPermissions.add(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
            }
        }

        // 알림 권한 확인
        if (!hasNotificationPermission(context)) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                deniedPermissions.add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        return deniedPermissions
    }

    /**
     * 권한 이름을 사용자 친화적인 텍스트로 변환
     */
    fun getPermissionDisplayName(permission: String): String {
        return when (permission) {
            Manifest.permission.ACCESS_FINE_LOCATION -> "정확한 위치"
            Manifest.permission.ACCESS_COARSE_LOCATION -> "대략적인 위치"
            Manifest.permission.ACCESS_BACKGROUND_LOCATION -> "백그라운드 위치"
            Manifest.permission.POST_NOTIFICATIONS -> "알림"
            else -> permission
        }
    }

    /**
     * 권한 설명 텍스트 반환
     */
    fun getPermissionDescription(permission: String): String {
        return when (permission) {
            Manifest.permission.ACCESS_FINE_LOCATION ->
                "GPS를 사용하여 정확한 위치를 추적하기 위해 필요합니다"
            Manifest.permission.ACCESS_COARSE_LOCATION ->
                "네트워크를 사용하여 대략적인 위치를 파악하기 위해 필요합니다"
            Manifest.permission.ACCESS_BACKGROUND_LOCATION ->
                "앱이 백그라운드에서 실행될 때도 위치를 추적하기 위해 필요합니다"
            Manifest.permission.POST_NOTIFICATIONS ->
                "위치 알림을 표시하기 위해 필요합니다"
            else -> "앱 기능 동작을 위해 필요한 권한입니다"
        }
    }
}