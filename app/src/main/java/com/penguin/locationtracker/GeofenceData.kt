package com.penguin.locationtracker

data class GeofenceData(
    val id: String = "",
    val name: String = "",
    val latitude: Double = 0.0,
    val longitude: Double = 0.0,
    val radius: Float = 100f,
    val targetUserId: String = "", // 대상 사용자 A (이 사용자가 출입시 알림)
    val notifyUserId: String = "", // 알림 받을 사용자 B
    val isActive: Boolean = true,
    val createdTime: Long = System.currentTimeMillis()
) {
    constructor() : this("", "", 0.0, 0.0, 100f, "", "", true, 0L)

    fun getFormattedCreatedTime(): String {
        val sdf = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault())
        return sdf.format(java.util.Date(createdTime))
    }

    fun getLocationSummary(): String {
        return "위도: %.6f, 경도: %.6f (반경: ${radius.toInt()}m)".format(latitude, longitude)
    }

    fun getDescription(): String {
        return "$targetUserId → $notifyUserId: $name"
    }
}