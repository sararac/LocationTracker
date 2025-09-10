package com.penguin.locationtracker

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import kotlin.math.*

class LocationStabilizer(private val context: Context) {

    companion object {
        private const val TAG = "LocationStabilizer"
    }

    // SharedPreferences에서 설정값 읽기
    private val prefs: SharedPreferences = context.getSharedPreferences("UserPrefs", Context.MODE_PRIVATE)

    // 동적으로 설정값 읽어오는 프로퍼티들
    private val epsilonMeters: Double
        get() = prefs.getInt("dbscan_epsilon", 15).toDouble()

    private val minPoints: Int
        get() = prefs.getInt("dbscan_min_points", 3)

    private val minSamplesForStabilization: Int
        get() = prefs.getInt("stabilization_samples", 5)

    private val stableLocationThreshold: Double
        get() = prefs.getInt("stable_location_threshold", 10).toDouble()

    private val maxBufferSize: Int
        get() = prefs.getInt("location_buffer_size", 20)

    private val isDBSCANEnabled: Boolean
        get() = prefs.getBoolean("enable_dbscan", true)

    // 위치 데이터 클래스
    data class LocationPoint(
        val latitude: Double,
        val longitude: Double,
        val timestamp: Long,
        val accuracy: Float,
        var clusterId: Int = -1 // -1은 노이즈, 0 이상은 클러스터 ID
    )

    // WiFi별 위치 안정화 상태
    data class WiFiLocationState(
        val ssid: String,
        val locationBuffer: MutableList<LocationPoint> = mutableListOf(),
        var stableLocation: LocationPoint? = null,
        var isLocationStabilized: Boolean = false,
        var firstDetectionTime: Long = System.currentTimeMillis(),
        var lastUpdateTime: Long = System.currentTimeMillis(),
        var stabilizationAttempts: Int = 0,
        var totalNoiseRatio: Double = 0.0
    )

    // WiFi별 상태 관리
    private val wifiLocationStates = mutableMapOf<String, WiFiLocationState>()

    /**
     * WiFi 상태가 변경될 때 호출
     */
    fun onWiFiStateChanged(currentSSID: String?, previousSSID: String?) {
        // 이전 WiFi에서 연결 해제
        if (previousSSID != null && previousSSID != currentSSID) {
            Log.d(TAG, "🏠 WiFi disconnected from: $previousSSID")
            wifiLocationStates[previousSSID]?.let { state ->
                Log.d(TAG, "📊 Final stats for $previousSSID - Samples: ${state.locationBuffer.size}, Stabilized: ${state.isLocationStabilized}, Attempts: ${state.stabilizationAttempts}")
            }
        }

        // 새로운 WiFi에 연결
        if (currentSSID != null && currentSSID != previousSSID) {
            Log.d(TAG, "🏠 WiFi connected to: $currentSSID")

            // 기존 상태가 없으면 새로 생성
            if (!wifiLocationStates.containsKey(currentSSID)) {
                wifiLocationStates[currentSSID] = WiFiLocationState(currentSSID)
                Log.d(TAG, "📍 New WiFi location state created for: $currentSSID")
            } else {
                val existingState = wifiLocationStates[currentSSID]!!
                Log.d(TAG, "📍 Resuming existing WiFi state for: $currentSSID (${existingState.locationBuffer.size} samples, ${if(existingState.isLocationStabilized) "stabilized" else "not stabilized"})")
            }
        }
    }

    /**
     * 새로운 위치 데이터 처리
     * @return 안정화된 위치 (null이면 위치 업데이트 안함, 머문시간만 증가)
     */
    fun processLocationForWiFi(
        currentSSID: String?,
        latitude: Double,
        longitude: Double,
        accuracy: Float,
        timestamp: Long
    ): LocationPoint? {

        if (currentSSID == null) {
            Log.d(TAG, "❌ No WiFi connected - location not processed")
            return null
        }

        // 이동형 WiFi는 처리하지 않음
        if (isMobileWiFi(currentSSID)) {
            Log.d(TAG, "🚄 Mobile WiFi detected ($currentSSID) - skipping stabilization")
            return null
        }

        // DBSCAN이 비활성화된 경우 일반 처리
        if (!isDBSCANEnabled) {
            Log.d(TAG, "⚠️ DBSCAN disabled - using simple WiFi detection")
            return LocationPoint(latitude, longitude, timestamp, accuracy)
        }

        val wifiState = wifiLocationStates[currentSSID] ?: run {
            Log.d(TAG, "⚠️ WiFi state not found for: $currentSSID")
            return null
        }

        val newPoint = LocationPoint(latitude, longitude, timestamp, accuracy)

        // 버퍼에 추가
        wifiState.locationBuffer.add(newPoint)
        wifiState.lastUpdateTime = timestamp

        // 버퍼 크기 제한
        if (wifiState.locationBuffer.size > maxBufferSize) {
            wifiState.locationBuffer.removeAt(0)
        }

        Log.d(TAG, "📍 Added location to buffer for $currentSSID (${wifiState.locationBuffer.size}/$maxBufferSize, accuracy: ${accuracy}m)")

        // 안정화된 위치가 있는 경우 - 변화 감지
        if (wifiState.isLocationStabilized && wifiState.stableLocation != null) {
            val distanceFromStable = calculateDistance(
                latitude, longitude,
                wifiState.stableLocation!!.latitude, wifiState.stableLocation!!.longitude
            )

            Log.d(TAG, "🏠 WiFi location stabilized - distance from stable: ${String.format("%.1f", distanceFromStable)}m (threshold: ${String.format("%.1f", stableLocationThreshold)}m)")

            // 안정된 위치에서 크게 벗어나지 않았으면 머문시간만 증가
            if (distanceFromStable <= stableLocationThreshold) {
                Log.d(TAG, "⏰ Location stable - only updating stay duration")
                return null // 위치 업데이트 없이 머문시간만 증가
            } else {
                Log.d(TAG, "📍 Significant movement detected - re-analyzing location")
                // 큰 이동이 감지되면 재분석
                wifiState.isLocationStabilized = false
                wifiState.stableLocation = null
                wifiState.stabilizationAttempts = 0
            }
        }

        // DBSCAN 클러스터링 수행 (충분한 데이터가 있을 때)
        if (wifiState.locationBuffer.size >= minSamplesForStabilization) {
            val stabilizedLocation = performDBSCANAndStabilize(wifiState)

            if (stabilizedLocation != null) {
                Log.d(TAG, "✅ Location stabilized for WiFi: $currentSSID after ${wifiState.stabilizationAttempts + 1} attempts")
                wifiState.stableLocation = stabilizedLocation
                wifiState.isLocationStabilized = true
                wifiState.stabilizationAttempts++
                return stabilizedLocation
            } else {
                wifiState.stabilizationAttempts++
                Log.d(TAG, "🔄 Stabilization attempt ${wifiState.stabilizationAttempts} failed for: $currentSSID")
            }
        }

        // 아직 안정화되지 않았으면 현재 위치 반환
        Log.d(TAG, "📍 Location not yet stabilized (${wifiState.locationBuffer.size}/${minSamplesForStabilization} samples) - using current location")
        return newPoint
    }

    /**
     * DBSCAN 클러스터링 수행 후 안정화된 위치 반환
     */
    private fun performDBSCANAndStabilize(wifiState: WiFiLocationState): LocationPoint? {
        val points = wifiState.locationBuffer.toList()

        Log.d(TAG, "🔍 Starting DBSCAN analysis with ${points.size} points (ε=${String.format("%.1f", epsilonMeters)}m, minPts=$minPoints)")

        // DBSCAN 클러스터링 수행
        val clusters = performDBSCAN(points, epsilonMeters, minPoints)

        if (clusters.isEmpty()) {
            Log.d(TAG, "❌ No clusters found - all points considered noise")
            wifiState.totalNoiseRatio = 1.0
            return null
        }

        // 가장 큰 클러스터 찾기
        val largestCluster = clusters.maxByOrNull { it.size }
        if (largestCluster == null || largestCluster.size < minPoints) {
            Log.d(TAG, "❌ Largest cluster too small: ${largestCluster?.size ?: 0} < $minPoints")
            return null
        }

        Log.d(TAG, "✅ Found ${clusters.size} clusters, largest: ${largestCluster.size} points")

        // 클러스터 품질 검사
        val clusterQuality = assessClusterQuality(largestCluster)
        if (clusterQuality < 0.6) { // 60% 미만의 품질이면 안정화하지 않음
            Log.d(TAG, "⚠️ Cluster quality too low: ${String.format("%.2f", clusterQuality)} < 0.60")
            return null
        }

        // 가장 큰 클러스터의 가중 평균 계산 (정확도 기반)
        val stabilizedLocation = calculateWeightedCenter(largestCluster)

        // 노이즈 비율 계산 및 저장
        val totalPoints = points.size
        val clusterPoints = clusters.sumOf { it.size }
        val noisePoints = totalPoints - clusterPoints
        val noiseRatio = noisePoints.toDouble() / totalPoints
        wifiState.totalNoiseRatio = noiseRatio

        Log.d(TAG, "📍 Stabilized location: ${String.format("%.6f, %.6f", stabilizedLocation.latitude, stabilizedLocation.longitude)}")
        Log.d(TAG, "📊 DBSCAN Stats - Total: $totalPoints, Clustered: $clusterPoints, Noise: $noisePoints (${String.format("%.1f", noiseRatio * 100)}%)")
        Log.d(TAG, "🎯 Cluster quality: ${String.format("%.2f", clusterQuality)}, Final accuracy: ${String.format("%.1f", stabilizedLocation.accuracy)}m")

        return stabilizedLocation
    }

    /**
     * 클러스터 품질 평가
     */
    private fun assessClusterQuality(cluster: List<LocationPoint>): Double {
        if (cluster.size < 3) return 0.0

        // 1. 클러스터 내 점들의 평균 정확도 (정확도가 좋을수록 높은 점수)
        val avgAccuracy = cluster.map { it.accuracy.toDouble() }.average()
        val accuracyScore = 1.0 / (1.0 + avgAccuracy / 20.0) // 20m 기준으로 정규화

        // 2. 클러스터 밀도 (점들이 얼마나 가깝게 분포되어 있는지)
        val center = calculateSimpleCenter(cluster)
        val distances = cluster.map { point ->
            calculateDistance(
                point.latitude, point.longitude,
                center.latitude, center.longitude
            )
        }
        val avgDistance = distances.average()
        val densityScore = 1.0 / (1.0 + avgDistance / epsilonMeters)

        // 3. 클러스터 크기 점수 (더 많은 점이 있을수록 신뢰성 높음)
        val sizeScore = minOf(1.0, cluster.size.toDouble() / (minSamplesForStabilization * 2))

        // 4. 시간적 분포 (시간이 고르게 분포되어 있는지)
        val timeSpan = cluster.maxOf { it.timestamp } - cluster.minOf { it.timestamp }
        val temporalScore = if (timeSpan > 60000) 1.0 else timeSpan / 60000.0 // 1분 기준

        val totalScore = (accuracyScore * 0.3 + densityScore * 0.4 + sizeScore * 0.2 + temporalScore * 0.1)

        return totalScore
    }

    /**
     * DBSCAN 클러스터링 알고리즘
     */
    private fun performDBSCAN(points: List<LocationPoint>, epsilon: Double, minPoints: Int): List<List<LocationPoint>> {
        val clusters = mutableListOf<MutableList<LocationPoint>>()
        val visited = mutableSetOf<LocationPoint>()
        val clustered = mutableSetOf<LocationPoint>()

        for (point in points) {
            if (point in visited) continue
            visited.add(point)

            val neighbors = getNeighbors(point, points, epsilon)

            if (neighbors.size < minPoints) {
                // 노이즈 포인트
                point.clusterId = -1
            } else {
                // 새 클러스터 생성
                val cluster = mutableListOf<LocationPoint>()
                expandCluster(point, neighbors, cluster, visited, clustered, points, epsilon, minPoints)

                if (cluster.isNotEmpty()) {
                    clusters.add(cluster)

                    // 클러스터 ID 할당
                    val clusterId = clusters.size - 1
                    cluster.forEach { it.clusterId = clusterId }
                }
            }
        }

        return clusters
    }

    private fun expandCluster(
        point: LocationPoint,
        neighbors: MutableList<LocationPoint>,
        cluster: MutableList<LocationPoint>,
        visited: MutableSet<LocationPoint>,
        clustered: MutableSet<LocationPoint>,
        allPoints: List<LocationPoint>,
        epsilon: Double,
        minPoints: Int
    ) {
        cluster.add(point)
        clustered.add(point)

        val neighborQueue = ArrayDeque(neighbors)

        while (neighborQueue.isNotEmpty()) {
            val neighbor = neighborQueue.removeFirst()

            if (neighbor !in visited) {
                visited.add(neighbor)
                val neighborNeighbors = getNeighbors(neighbor, allPoints, epsilon)

                if (neighborNeighbors.size >= minPoints) {
                    neighborQueue.addAll(neighborNeighbors.filter { it !in visited })
                }
            }

            if (neighbor !in clustered) {
                cluster.add(neighbor)
                clustered.add(neighbor)
            }
        }
    }

    private fun getNeighbors(point: LocationPoint, points: List<LocationPoint>, epsilon: Double): MutableList<LocationPoint> {
        val neighbors = mutableListOf<LocationPoint>()

        for (otherPoint in points) {
            if (point != otherPoint) {
                val distance = calculateDistance(
                    point.latitude, point.longitude,
                    otherPoint.latitude, otherPoint.longitude
                )

                if (distance <= epsilon) {
                    neighbors.add(otherPoint)
                }
            }
        }

        return neighbors
    }

    /**
     * 클러스터의 가중 평균 위치 계산 (정확도 기반)
     */
    private fun calculateWeightedCenter(cluster: List<LocationPoint>): LocationPoint {
        var totalWeight = 0.0
        var weightedLat = 0.0
        var weightedLng = 0.0
        var bestAccuracy = Float.MAX_VALUE
        var latestTimestamp = 0L

        for (point in cluster) {
            // 정확도가 좋을수록 + 최근일수록 높은 가중치
            val accuracyWeight = 1.0 / (point.accuracy + 1.0)
            val timeWeight = 1.0 + (point.timestamp - cluster.minOf { it.timestamp }) / 60000.0 // 시간차(분)를 가중치에 반영
            val weight = accuracyWeight * timeWeight

            totalWeight += weight
            weightedLat += point.latitude * weight
            weightedLng += point.longitude * weight

            if (point.accuracy < bestAccuracy) {
                bestAccuracy = point.accuracy
            }

            if (point.timestamp > latestTimestamp) {
                latestTimestamp = point.timestamp
            }
        }

        return LocationPoint(
            latitude = weightedLat / totalWeight,
            longitude = weightedLng / totalWeight,
            timestamp = latestTimestamp,
            accuracy = bestAccuracy * 0.6f, // 클러스터링으로 정확도 크게 향상 반영
            clusterId = cluster.firstOrNull()?.clusterId ?: 0
        )
    }

    /**
     * 단순 중심점 계산 (품질 평가용)
     */
    private fun calculateSimpleCenter(cluster: List<LocationPoint>): LocationPoint {
        val avgLat = cluster.map { it.latitude }.average()
        val avgLng = cluster.map { it.longitude }.average()
        val avgTimestamp = cluster.map { it.timestamp }.average().toLong()
        val avgAccuracy = cluster.map { it.accuracy }.average().toFloat()

        return LocationPoint(avgLat, avgLng, avgTimestamp, avgAccuracy)
    }

    private fun calculateDistance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val earthRadius = 6371000.0 // 지구 반지름 (미터)
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2).pow(2) + cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return earthRadius * c
    }

    private fun isMobileWiFi(ssid: String): Boolean {
        val mobilePatterns = listOf(
            "KTX", "SRT", "ITX", "KorailWiFi", "PublicWiFi@BUS",
            "T wifi zone_Secure", "_Free_U+zone", "korail", "KORAIL",
            "olleh_WiFi_Bus", "KT_WiFi_Bus", "LG_WiFi_Bus"
        )

        return mobilePatterns.any { pattern ->
            ssid.contains(pattern, ignoreCase = true)
        }
    }

    /**
     * 현재 WiFi의 안정화 상태 및 통계 반환
     */
    fun getWiFiLocationState(ssid: String): WiFiLocationState? {
        return wifiLocationStates[ssid]
    }

    /**
     * WiFi 상태 개수 반환
     */
    fun getWiFiStateCount(): Int {
        return wifiLocationStates.size
    }

    /**
     * 현재 설정값들을 로그로 출력 (디버깅용)
     */
    fun logCurrentSettings() {
        Log.d(TAG, "📋 Current DBSCAN Settings:")
        Log.d(TAG, "  ├─ DBSCAN Enabled: $isDBSCANEnabled")
        Log.d(TAG, "  ├─ Epsilon (cluster radius): ${String.format("%.1f", epsilonMeters)}m")
        Log.d(TAG, "  ├─ Min Points: $minPoints")
        Log.d(TAG, "  ├─ Min Samples for Stabilization: $minSamplesForStabilization")
        Log.d(TAG, "  ├─ Stable Location Threshold: ${String.format("%.1f", stableLocationThreshold)}m")
        Log.d(TAG, "  └─ Max Buffer Size: $maxBufferSize")
    }

    /**
     * 오래된 WiFi 상태 정리 (메모리 관리)
     */
    fun cleanupOldWiFiStates() {
        val cutoffTime = System.currentTimeMillis() - (24 * 60 * 60 * 1000L) // 24시간

        val toRemove = wifiLocationStates.filter { (_, state) ->
            state.lastUpdateTime < cutoffTime
        }.keys

        toRemove.forEach { ssid ->
            val state = wifiLocationStates[ssid]
            Log.d(TAG, "🧹 Removing old WiFi state: $ssid (${state?.locationBuffer?.size ?: 0} samples, stabilized: ${state?.isLocationStabilized ?: false})")
            wifiLocationStates.remove(ssid)
        }

        if (toRemove.isNotEmpty()) {
            Log.d(TAG, "🧹 Cleaned up ${toRemove.size} old WiFi states")
        }
    }

    /**
     * 전체 통계 요약
     */
    fun getSummaryStats(): String {
        val totalWiFis = wifiLocationStates.size
        val stabilizedWiFis = wifiLocationStates.count { it.value.isLocationStabilized }
        val totalSamples = wifiLocationStates.values.sumOf { it.locationBuffer.size }
        val avgNoiseRatio = if (wifiLocationStates.isNotEmpty()) {
            wifiLocationStates.values.map { it.totalNoiseRatio }.average()
        } else 0.0

        return "WiFi States: $totalWiFis, Stabilized: $stabilizedWiFis, Total Samples: $totalSamples, Avg Noise: ${String.format("%.1f", avgNoiseRatio * 100)}%"
    }
}