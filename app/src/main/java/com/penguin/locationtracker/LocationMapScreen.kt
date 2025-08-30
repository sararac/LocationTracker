package com.penguin.locationtracker

import android.util.Log
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.platform.LocalContext
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.ValueEventListener
import com.google.firebase.database.ktx.database
import com.google.firebase.ktx.Firebase
import com.naver.maps.geometry.LatLng
import com.naver.maps.map.CameraPosition
import com.naver.maps.map.MapView
import com.naver.maps.map.NaverMap
import com.naver.maps.map.OnMapReadyCallback
import com.naver.maps.map.overlay.Marker
import com.naver.maps.map.overlay.OverlayImage
import kotlinx.coroutines.delay
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable

@Composable
fun LocationMapScreen(
    onBackToMain: () -> Unit,
    onShowUserHistory: (String) -> Unit, // 새로 추가된 매개변수
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var isMapReady by remember { mutableStateOf(false) }
    var naverMapInstance by remember { mutableStateOf<NaverMap?>(null) }
    var userLocations by remember { mutableStateOf(mapOf<String, LocationData>()) }
    var activeMarkers by remember { mutableStateOf(mutableMapOf<String, Marker>()) }
    var statusMessage by remember { mutableStateOf("지도를 초기화하는 중...") }
    var isRefreshing by remember { mutableStateOf(false) }
    var hasError by remember { mutableStateOf(false) }

    val database = remember { Firebase.database }
    val locationsRef = remember { database.getReference("locations") }

    // Firebase 연결 및 데이터 읽기
    LaunchedEffect(Unit) {
        try {
            Log.d("LocationMap", "Starting Firebase data loading...")

            locationsRef.addValueEventListener(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    try {
                        Log.d("LocationMap", "Firebase data received, children count: ${snapshot.childrenCount}")
                        val latestLocations = mutableMapOf<String, LocationData>()

                        for (userSnapshot in snapshot.children) {
                            val userId = userSnapshot.key ?: continue
                            Log.d("LocationMap", "Processing user: $userId")

                            // 각 사용자의 최신 위치 찾기
                            var latestLocation: LocationData? = null
                            var latestTimestamp = 0L

                            for (locationSnapshot in userSnapshot.children) {
                                val location = locationSnapshot.getValue(LocationData::class.java)
                                if (location != null && location.timestamp > latestTimestamp) {
                                    latestLocation = location
                                    latestTimestamp = location.timestamp
                                }
                            }

                            latestLocation?.let { location ->
                                latestLocations[userId] = location
                                Log.d("LocationMap", "User $userId: ${location.latitude}, ${location.longitude}")
                            }
                        }

                        userLocations = latestLocations
                        statusMessage = if (latestLocations.isEmpty()) {
                            "저장된 사용자 위치 데이터가 없습니다"
                        } else {
                            "총 ${latestLocations.size}명의 사용자 위치를 표시 중"
                        }
                        hasError = false

                        // 새로고침 상태 해제
                        if (isRefreshing) {
                            isRefreshing = false
                        }

                        // 지도가 준비되었으면 마커 업데이트
                        naverMapInstance?.let { map ->
                            updateMarkersOnMap(map, latestLocations, activeMarkers)
                        }

                        Log.d("LocationMap", "Updated locations for ${latestLocations.size} users")

                    } catch (e: Exception) {
                        Log.e("LocationMap", "Error processing Firebase data", e)
                        statusMessage = "데이터 처리 오류: ${e.message}"
                        hasError = true
                    }
                }

                override fun onCancelled(error: DatabaseError) {
                    statusMessage = "데이터 읽기 오류: ${error.message}"
                    isRefreshing = false
                    hasError = true
                    Log.e("LocationMap", "Database error: ${error.message}")
                }
            })
        } catch (e: Exception) {
            Log.e("LocationMap", "Error setting up Firebase listener", e)
            statusMessage = "Firebase 연결 오류: ${e.message}"
            hasError = true
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = "실시간 사용자 위치",
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )

        Card(
            colors = CardDefaults.cardColors(
                containerColor = when {
                    hasError -> MaterialTheme.colorScheme.errorContainer
                    isRefreshing -> MaterialTheme.colorScheme.secondaryContainer
                    isMapReady -> MaterialTheme.colorScheme.primaryContainer
                    else -> MaterialTheme.colorScheme.surfaceVariant
                }
            )
        ) {
            Row(
                modifier = Modifier.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (isRefreshing) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                }
                Text(
                    text = statusMessage,
                    fontSize = 14.sp
                )
            }
        }

        // 네이버 지도 표시
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .height(350.dp)
        ) {
            AndroidView(
                factory = { context ->
                    Log.d("LocationMap", "Creating MapView...")
                    MapView(context).apply {
                        getMapAsync(object : OnMapReadyCallback {
                            override fun onMapReady(naverMap: NaverMap) {
                                try {
                                    Log.d("LocationMap", "Map is ready")
                                    naverMapInstance = naverMap
                                    isMapReady = true

                                    // 첫 번째 사용자가 있으면 그 위치를 중심으로, 없으면 한국 중심
                                    val initialPosition = if (userLocations.isNotEmpty()) {
                                        val firstUser = userLocations.values.first()
                                        CameraPosition(
                                            LatLng(firstUser.latitude, firstUser.longitude),
                                            7.0
                                        )
                                    } else {
                                        CameraPosition(
                                            LatLng(37.5665, 126.9780), // 서울 시청
                                            7.0
                                        )
                                    }
                                    naverMap.cameraPosition = initialPosition

                                    // 기존 위치 데이터가 있으면 마커 표시
                                    updateMarkersOnMap(naverMap, userLocations, activeMarkers)

                                    statusMessage = if (userLocations.isEmpty()) {
                                        "지도 로드 완료 - 사용자 위치 데이터를 기다리는 중"
                                    } else {
                                        "총 ${userLocations.size}명의 사용자 위치를 표시 중"
                                    }
                                    hasError = false

                                } catch (e: Exception) {
                                    Log.e("LocationMap", "Error in onMapReady", e)
                                    statusMessage = "지도 초기화 오류: ${e.message}"
                                    hasError = true
                                }
                            }
                        })
                    }
                },
                modifier = Modifier.fillMaxSize()
            )
        }

        // 사용자 목록
        if (userLocations.isNotEmpty()) {
            Card {
                Column(
                    modifier = Modifier.padding(4.dp)
                ) {
                    Text(
                        text = "활성 사용자 (${userLocations.size}명)",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(bottom = 1.dp)
                    )

                    // 사용자 목록 부분에서 클릭 이벤트 수정
                    LazyColumn(
                        modifier = Modifier.heightIn(max = 100.dp),
                        verticalArrangement = Arrangement.spacedBy((-1).dp)
                    ) {
                        items(userLocations.toList().sortedBy { it.first }) { (userId, location) ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(MaterialTheme.colorScheme.surfaceVariant)
                                    .clickable {
                                        Log.d("LocationMap", "User $userId clicked - showing history")
                                        onShowUserHistory(userId) // 이력 화면으로 이동
                                    }
                                    .padding(horizontal = 4.dp, vertical = 1.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = getMarkerColorEmoji(userId),
                                        fontSize = 12.sp,
                                        modifier = Modifier.padding(end = 4.dp)
                                    )

                                    Text(
                                        text = userId,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }

                                Text(
                                    text = location.getFormattedTime(),
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        } else if (!hasError) {
            // 데이터가 없을 때 안내 메시지
            Card {
                Column(
                    modifier = Modifier.padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "위치 데이터를 찾을 수 없습니다",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Medium
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "먼저 '위치 추적' 메뉴에서 위치를 저장해보세요",
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = {
                    naverMapInstance?.let { map ->
                        try {
                            val centerPosition = if (userLocations.isNotEmpty()) {
                                val firstUser = userLocations.values.first()
                                CameraPosition(
                                    LatLng(firstUser.latitude, firstUser.longitude),
                                    7.0
                                )
                            } else {
                                CameraPosition(
                                    LatLng(37.5665, 126.9780),
                                    7.0
                                )
                            }
                            map.cameraPosition = centerPosition
                            statusMessage = "전체 보기로 이동"
                        } catch (e: Exception) {
                            Log.e("LocationMap", "Error in full view", e)
                            statusMessage = "전체 보기 실패: ${e.message}"
                        }
                    }
                },
                enabled = isMapReady,
                modifier = Modifier.weight(1f)
            ) {
                Text("전체 보기", fontSize = 12.sp)
            }

            Button(
                onClick = {
                    try {
                        isRefreshing = true
                        statusMessage = "위치 데이터를 새로고침하는 중..."

                        locationsRef.get().addOnSuccessListener { snapshot ->
                            Log.d("LocationMap", "Manual refresh completed")
                        }.addOnFailureListener { error ->
                            statusMessage = "새로고침 실패: ${error.message}"
                            isRefreshing = false
                            hasError = true
                            Log.e("LocationMap", "Manual refresh failed", error)
                        }
                    } catch (e: Exception) {
                        Log.e("LocationMap", "Error in refresh", e)
                        statusMessage = "새로고침 오류: ${e.message}"
                        isRefreshing = false
                        hasError = true
                    }
                },
                enabled = isMapReady && !isRefreshing,
                modifier = Modifier.weight(1f)
            ) {
                Text("새로고침", fontSize = 12.sp)
            }
        }

        Spacer(modifier = Modifier.weight(1f))

        OutlinedButton(
            onClick = onBackToMain,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("메인 화면으로 돌아가기")
        }

        Text(
            text = "👥 6단계: 실시간 사용자 위치 표시",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.secondary
        )
    }
}

// 지도에 마커 업데이트하는 함수 (오류 수정)
private fun updateMarkersOnMap(
    naverMap: NaverMap,
    userLocations: Map<String, LocationData>,
    activeMarkers: MutableMap<String, Marker>
) {
    // 기존 마커 제거
    activeMarkers.values.forEach { marker ->
        marker.map = null
    }
    activeMarkers.clear()

    // 새 마커 추가
    userLocations.forEach { (userId, location) ->
        // colorIndex를 미리 계산
        val colorIndex = Math.abs(userId.hashCode()) % 8

        val marker = Marker().apply {
            position = LatLng(location.latitude, location.longitude)
            captionText = userId
            captionTextSize = 14f

            // 마커 색상을 더 다양하게 분배
            icon = when (colorIndex) {
                0 -> OverlayImage.fromResource(com.naver.maps.map.R.drawable.navermap_default_marker_icon_red)
                1 -> OverlayImage.fromResource(com.naver.maps.map.R.drawable.navermap_default_marker_icon_blue)
                2 -> OverlayImage.fromResource(com.naver.maps.map.R.drawable.navermap_default_marker_icon_green)
                3 -> OverlayImage.fromResource(com.naver.maps.map.R.drawable.navermap_default_marker_icon_gray)
                4 -> OverlayImage.fromResource(com.naver.maps.map.R.drawable.navermap_default_marker_icon_pink)
                5 -> OverlayImage.fromResource(com.naver.maps.map.R.drawable.navermap_default_marker_icon_yellow)
                6 -> OverlayImage.fromResource(com.naver.maps.map.R.drawable.navermap_default_marker_icon_lightblue)
                else -> OverlayImage.fromResource(com.naver.maps.map.R.drawable.navermap_default_marker_icon_red)
            }
            map = naverMap
        }
        activeMarkers[userId] = marker

        // 이제 colorIndex가 정의된 스코프에서 사용 가능
        Log.d("LocationMap", "Added marker for $userId at ${location.latitude}, ${location.longitude}, color: $colorIndex")
    }

    Log.d("LocationMap", "Updated ${userLocations.size} markers on map")
}

// 마커 색상에 맞는 이모지 반환
private fun getMarkerColorEmoji(userId: String): String {
    val colorIndex = Math.abs(userId.hashCode()) % 8
    return when (colorIndex) {
        0 -> "🔴" // red
        1 -> "🔵" // blue
        2 -> "🟢" // green
        3 -> "⚫" // gray
        4 -> "🟣" // pink
        5 -> "🟡" // yellow
        6 -> "🔵" // lightblue
        else -> "🔴"
    }
}