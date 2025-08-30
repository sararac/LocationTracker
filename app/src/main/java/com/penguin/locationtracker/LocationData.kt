package com.penguin.locationtracker

data class LocationData(
    val userId: String = "",
    val latitude: Double = 0.0,
    val longitude: Double = 0.0,
    val timestamp: Long = 0L,
    val address: String = "" // 주소 정보 (선택사항)
) {
    // Firebase용 빈 생성자 (필수)
    constructor() : this("", 0.0, 0.0, 0L, "")

    // 생성 시간을 포맷팅해서 반환하는 유틸리티 함수
    fun getFormattedTime(): String {
        val sdf = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault())
        return sdf.format(java.util.Date(timestamp))
    }

    // 위치 정보 요약
    fun getLocationSummary(): String {
        return "위도: %.6f, 경도: %.6f".format(latitude, longitude)
    }
}