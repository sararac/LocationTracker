package com.penguin.locationtracker

data class GeofenceNotificationData(
    val id: String = "",
    val geofenceId: String = "",
    val geofenceName: String = "",
    val targetUserId: String = "",
    val notifyUserId: String = "",
    val transitionType: String = "", // "ENTER" 또는 "EXIT"
    val latitude: Double = 0.0,
    val longitude: Double = 0.0,
    val timestamp: Long = System.currentTimeMillis(),
    val isRead: Boolean = false,
    val address: String = ""
) {
    // Firebase용 빈 생성자
    constructor() : this("", "", "", "", "", "", 0.0, 0.0, 0L, false, "")

    // 알림 시간 포맷팅
    fun getFormattedTime(): String {
        val sdf = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault())
        return sdf.format(java.util.Date(timestamp))
    }

    // 간단한 시간 표시 (오늘이면 시:분만)
    fun getSimpleTime(): String {
        val now = java.util.Calendar.getInstance()
        val notificationTime = java.util.Calendar.getInstance().apply {
            timeInMillis = timestamp
        }

        return if (now.get(java.util.Calendar.DAY_OF_YEAR) == notificationTime.get(java.util.Calendar.DAY_OF_YEAR) &&
            now.get(java.util.Calendar.YEAR) == notificationTime.get(java.util.Calendar.YEAR)) {
            // 오늘이면 시:분만
            java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date(timestamp))
        } else {
            // 다른 날이면 월.일 시:분
            java.text.SimpleDateFormat("MM.dd HH:mm", java.util.Locale.getDefault()).format(java.util.Date(timestamp))
        }
    }

    // 알림 제목 생성
    fun getNotificationTitle(): String {
        val action = if (transitionType == "ENTER") "도착" else "출발"
        return "$targetUserId $geofenceName $action"
    }

    // 알림 내용 생성
    fun getNotificationMessage(): String {
        val action = if (transitionType == "ENTER") "도착했습니다" else "출발했습니다"
        val time = java.text.SimpleDateFormat("HH시mm분", java.util.Locale.KOREAN).format(java.util.Date(timestamp))
        return "${targetUserId}이(가) ${geofenceName}에 ${time}에 $action"
    }

    // 위치 요약
    fun getLocationSummary(): String {
        return if (address.isNotEmpty()) {
            address
        } else {
            "위도: %.6f, 경도: %.6f".format(latitude, longitude)
        }
    }

    // 알림 타입 아이콘
    fun getTransitionIcon(): String {
        return if (transitionType == "ENTER") "📍" else "🚶‍♂️"
    }

    // 며칠 전인지 계산
    fun getDaysAgo(): String {
        val now = System.currentTimeMillis()
        val diffInMillis = now - timestamp
        val days = diffInMillis / (24 * 60 * 60 * 1000)

        return when {
            days == 0L -> "오늘"
            days == 1L -> "어제"
            days < 7L -> "${days}일 전"
            days < 30L -> "${days / 7}주 전"
            else -> "${days / 30}개월 전"
        }
    }
}