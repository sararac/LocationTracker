package com.penguin.locationtracker

data class LocationData(
    val userId: String = "",
    val latitude: Double = 0.0,
    val longitude: Double = 0.0,
    val timestamp: Long = 0L,
    val address: String = "", // 주소 정보 (선택사항)
    val stayDuration: Long = 0L, // 해당 위치에 머문 시간 (밀리초) - 새로 추가
    val lastUpdateTime: Long = 0L // 마지막 업데이트 시간 - 새로 추가
) {
    // Firebase용 빈 생성자 (필수)
    constructor() : this("", 0.0, 0.0, 0L, "", 0L, 0L)

    // 생성 시간을 포맷팅해서 반환하는 유틸리티 함수
    fun getFormattedTime(): String {
        val sdf = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault())
        return sdf.format(java.util.Date(timestamp))
    }

    // 위치 정보 요약
    fun getLocationSummary(): String {
        return "위도: %.6f, 경도: %.6f".format(latitude, longitude)
    }

    // 머문 시간을 포맷팅해서 반환 - 새로 추가
    fun getFormattedStayDuration(): String {
        if (stayDuration <= 0) return ""

        val totalMinutes = stayDuration / (1000 * 60)
        val hours = totalMinutes / 60
        val minutes = totalMinutes % 60

        return when {
            hours > 0 -> "${hours}시간 ${minutes}분"
            minutes > 0 -> "${minutes}분"
            else -> "1분 미만"
        }
    }

    // 위치 정보와 머문 시간을 함께 표시 - 새로 추가
    fun getLocationWithDuration(): String {
        val duration = getFormattedStayDuration()
        return if (duration.isNotEmpty()) {
            "${getLocationSummary()} (머문시간: $duration)"
        } else {
            getLocationSummary()
        }
    }
}