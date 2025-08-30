package com.penguin.locationtracker

import android.content.Context
import android.content.Intent
import android.location.Geocoder
import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.*

@Composable
fun MainMapScreen(
    onNavigateToSettings: () -> Unit,
    onShowUserHistory: (String) -> Unit,
    onNavigateToGeofence: (Double?, Double?) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("UserPrefs", Context.MODE_PRIVATE) }
    val currentUserId = remember { prefs.getString("user_id", "") ?: "" }

    var isMapReady by remember { mutableStateOf(false) }
    var naverMapInstance by remember { mutableStateOf<NaverMap?>(null) }
    var userLocations by remember { mutableStateOf(mapOf<String, LocationData>()) }
    var activeMarkers by remember { mutableStateOf(mutableMapOf<String, Marker>()) }
    var isRefreshing by remember { mutableStateOf(false) }
    var viewMode by remember { mutableStateOf("현재위치") }

    // 길게 누르기 관련 상태
    var showContextMenu by remember { mutableStateOf(false) }
    var contextMenuLocation by remember { mutableStateOf<LatLng?>(null) }
    var locationAddress by remember { mutableStateOf("주소를 가져오는 중...") }

    val database = remember { Firebase.database }
    val locationsRef = remember { database.getReference("locations") }
    val geocoder = remember { Geocoder(context, Locale.KOREAN) }

    // 주소 가져오기 함수 (디버깅 포함)
    suspend fun getAddressFromCoordinates(latitude: Double, longitude: Double): String {
        return try {
            withContext(Dispatchers.IO) {
                Log.d("MainMap", "Getting address for: $latitude, $longitude")
                val addresses = geocoder.getFromLocation(latitude, longitude, 1)

                if (!addresses.isNullOrEmpty()) {
                    val address = addresses[0]
                    Log.d("MainMap", "Address object: $address")

                    // 모든 주소 정보 로깅
                    Log.d("MainMap", "AdminArea: ${address.adminArea}")
                    Log.d("MainMap", "SubAdminArea: ${address.subAdminArea}")
                    Log.d("MainMap", "Locality: ${address.locality}")
                    Log.d("MainMap", "SubLocality: ${address.subLocality}")
                    Log.d("MainMap", "Thoroughfare: ${address.thoroughfare}")
                    Log.d("MainMap", "SubThoroughfare: ${address.subThoroughfare}")
                    Log.d("MainMap", "FeatureName: ${address.featureName}")
                    Log.d("MainMap", "AddressLine[0]: ${address.getAddressLine(0)}")

                    // 가장 완전한 주소 라인 사용
                    val fullAddress = address.getAddressLine(0)
                    if (!fullAddress.isNullOrEmpty()) {
                        // 한국 주소에서 국가명과 우편번호 제거
                        val cleanAddress = fullAddress
                            .replace("대한민국", "")
                            .replace("South Korea", "")
                            .replace(Regex("\\d{5}"), "") // 우편번호 제거
                            .trim()
                            .replace(Regex("\\s+"), " ") // 여러 공백을 하나로

                        if (cleanAddress.isNotEmpty()) {
                            cleanAddress
                        } else {
                            "%.6f, %.6f".format(latitude, longitude)
                        }
                    } else {
                        // fallback 방법
                        val parts = mutableListOf<String>()

                        address.subAdminArea?.let { if (!it.endsWith("도")) parts.add(it) }
                        address.locality?.let { parts.add(it) }
                        address.subLocality?.let { parts.add(it) }
                        address.thoroughfare?.let { parts.add(it) }
                        address.subThoroughfare?.let { parts.add(it) }

                        if (parts.isNotEmpty()) {
                            parts.joinToString(" ")
                        } else {
                            "%.6f, %.6f".format(latitude, longitude)
                        }
                    }
                } else {
                    Log.w("MainMap", "No addresses found for: $latitude, $longitude")
                    "%.6f, %.6f".format(latitude, longitude)
                }
            }
        } catch (e: Exception) {
            Log.e("MainMap", "Error getting address for $latitude, $longitude", e)
            "%.6f, %.6f".format(latitude, longitude)
        }
    }

    // 자동 위치 추적 서비스 시작
    LaunchedEffect(Unit) {
        val intent = Intent(context, LocationTrackingService::class.java)
        intent.action = LocationTrackingService.ACTION_START_TRACKING
        context.startService(intent)
    }

    // Firebase 데이터 읽기
    LaunchedEffect(Unit) {
        try {
            Log.d("MainMap", "Starting Firebase data loading...")

            locationsRef.addValueEventListener(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    try {
                        Log.d("MainMap", "Firebase data received")
                        val latestLocations = mutableMapOf<String, LocationData>()

                        for (userSnapshot in snapshot.children) {
                            val userId = userSnapshot.key ?: continue

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
                            }
                        }

                        userLocations = latestLocations
                        isRefreshing = false

                        naverMapInstance?.let { map ->
                            updateMarkersOnMap(map, latestLocations, activeMarkers)

                            // 현재 사용자의 위치로 카메라 이동 (처음 로딩시에만)
                            if (!isMapReady && currentUserId.isNotEmpty() && latestLocations.containsKey(currentUserId)) {
                                val userLocation = latestLocations[currentUserId]!!
                                val cameraPosition = CameraPosition(
                                    LatLng(userLocation.latitude, userLocation.longitude),
                                    15.0
                                )
                                map.cameraPosition = cameraPosition
                                Log.d("MainMap", "Centered map on current user: $currentUserId")
                            }
                        }

                    } catch (e: Exception) {
                        Log.e("MainMap", "Error processing Firebase data", e)
                    }
                }

                override fun onCancelled(error: DatabaseError) {
                    isRefreshing = false
                    Log.e("MainMap", "Database error: ${error.message}")
                }
            })
        } catch (e: Exception) {
            Log.e("MainMap", "Error setting up Firebase listener", e)
        }
    }

    // 주소 가져오기 (별도 LaunchedEffect)
    LaunchedEffect(contextMenuLocation) {
        contextMenuLocation?.let { location ->
            locationAddress = "주소를 가져오는 중..."
            locationAddress = getAddressFromCoordinates(location.latitude, location.longitude)
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.fillMaxSize()
        ) {
            // 상단 4개 버튼을 1줄로 배치
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Button(
                    onClick = onNavigateToSettings,
                    modifier = Modifier
                        .weight(1f)
                        .height(32.dp),
                    contentPadding = PaddingValues(2.dp)
                ) {
                    Text("설정", fontSize = 12.sp)
                }

                Button(
                    onClick = {
                        naverMapInstance?.let { map ->
                            try {
                                val centerPosition = if (userLocations.isNotEmpty()) {
                                    if (currentUserId.isNotEmpty() && userLocations.containsKey(currentUserId)) {
                                        val userLocation = userLocations[currentUserId]!!
                                        CameraPosition(
                                            LatLng(userLocation.latitude, userLocation.longitude),
                                            16.0
                                        )
                                    } else {
                                        val firstUser = userLocations.values.first()
                                        CameraPosition(
                                            LatLng(firstUser.latitude, firstUser.longitude),
                                            7.0
                                        )
                                    }
                                } else {
                                    CameraPosition(
                                        LatLng(37.5665, 126.9780),
                                        7.0
                                    )
                                }
                                map.cameraPosition = centerPosition
                            } catch (e: Exception) {
                                Log.e("MainMap", "Error in full view", e)
                            }
                        }
                    },
                    enabled = isMapReady,
                    modifier = Modifier
                        .weight(1f)
                        .height(32.dp),
                    contentPadding = PaddingValues(2.dp)
                ) {
                    Text("전체보기", fontSize = 12.sp)
                }

                Button(
                    onClick = {
                        try {
                            isRefreshing = true
                            locationsRef.get().addOnSuccessListener { snapshot ->
                                Log.d("MainMap", "Manual refresh completed")
                            }.addOnFailureListener { error ->
                                isRefreshing = false
                                Log.e("MainMap", "Manual refresh failed", error)
                            }
                        } catch (e: Exception) {
                            Log.e("MainMap", "Error in refresh", e)
                            isRefreshing = false
                        }
                    },
                    enabled = isMapReady && !isRefreshing,
                    modifier = Modifier
                        .weight(1f)
                        .height(32.dp),
                    contentPadding = PaddingValues(2.dp)
                ) {
                    Text("새로고침", fontSize = 12.sp)
                }

                Button(
                    onClick = {
                        viewMode = if (viewMode == "현재위치") "이력보기" else "현재위치"
                    },
                    modifier = Modifier
                        .weight(1f)
                        .height(32.dp),
                    contentPadding = PaddingValues(2.dp)
                ) {
                    Text(viewMode, fontSize = 12.sp)
                }
            }

            // 활성 사용자 정보 - 현재 사용자를 맨 앞에 표시
            if (userLocations.isNotEmpty()) {
                LazyRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    val sortedUsers = userLocations.toList().sortedWith { (userIdA, _), (userIdB, _) ->
                        when {
                            userIdA == currentUserId -> -1
                            userIdB == currentUserId -> 1
                            else -> userIdA.compareTo(userIdB)
                        }
                    }

                    items(sortedUsers) { (userId, location) ->
                        Row(
                            modifier = Modifier
                                .background(
                                    if (userId == currentUserId) {
                                        MaterialTheme.colorScheme.primaryContainer
                                    } else {
                                        MaterialTheme.colorScheme.surfaceVariant
                                    },
                                    MaterialTheme.shapes.small
                                )
                                .clickable {
                                    if (viewMode == "이력보기") {
                                        onShowUserHistory(userId)
                                    } else {
                                        naverMapInstance?.let { map ->
                                            try {
                                                val cameraPosition = CameraPosition(
                                                    LatLng(location.latitude, location.longitude),
                                                    15.0
                                                )
                                                map.cameraPosition = cameraPosition
                                            } catch (e: Exception) {
                                                Log.e("MainMap", "Error moving camera", e)
                                            }
                                        }
                                    }
                                }
                                .padding(horizontal = 8.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = getMarkerColorEmoji(userId),
                                fontSize = 14.sp
                            )

                            Spacer(modifier = Modifier.width(4.dp))

                            Text(
                                text = if (userId == currentUserId) "$userId (나)" else userId,
                                fontSize = 12.sp,
                                fontWeight = if (userId == currentUserId) FontWeight.Bold else FontWeight.Medium
                            )
                        }
                    }
                }
            }

            // 네이버 지도
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight()
                    .padding(horizontal = 8.dp, vertical = 0.dp)
            ) {
                AndroidView(
                    factory = { context ->
                        Log.d("MainMap", "Creating MapView...")
                        MapView(context).apply {
                            getMapAsync(object : OnMapReadyCallback {
                                override fun onMapReady(naverMap: NaverMap) {
                                    try {
                                        Log.d("MainMap", "Map is ready")
                                        naverMapInstance = naverMap
                                        isMapReady = true

                                        // 길게 누르기 이벤트 리스너 설정 (수정된 버전)
                                        naverMap.setOnMapLongClickListener { pointF, latLng ->
                                            contextMenuLocation = latLng
                                            showContextMenu = true
                                            Log.d("MainMap", "Long click at: ${latLng.latitude}, ${latLng.longitude}")
                                        }

                                        // 초기 위치 설정
                                        val initialPosition = if (currentUserId.isNotEmpty() && userLocations.containsKey(currentUserId)) {
                                            val userLocation = userLocations[currentUserId]!!
                                            CameraPosition(
                                                LatLng(userLocation.latitude, userLocation.longitude),
                                                15.0
                                            )
                                        } else if (userLocations.isNotEmpty()) {
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
                                        naverMap.cameraPosition = initialPosition

                                        updateMarkersOnMap(naverMap, userLocations, activeMarkers)

                                    } catch (e: Exception) {
                                        Log.e("MainMap", "Error in onMapReady", e)
                                    }
                                }
                            })
                        }
                    },
                    modifier = Modifier.fillMaxSize()
                )
            }
        }

        // 컨텍스트 메뉴 표시
        if (showContextMenu && contextMenuLocation != null) {
            Card(
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface
                ),
                elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "선택된 위치",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold
                    )

                    Text(
                        text = locationAddress,
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Text(
                        text = "%.6f, %.6f".format(
                            contextMenuLocation!!.latitude,
                            contextMenuLocation!!.longitude
                        ),
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    HorizontalDivider()

                    // 현재 위치 알림 설정 버튼만 유지
                    Button(
                        onClick = {
                            showContextMenu = false
                            onNavigateToGeofence(
                                contextMenuLocation!!.latitude,
                                contextMenuLocation!!.longitude
                            )
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("현재 위치 알림 설정")
                    }

                    // 취소 버튼
                    OutlinedButton(
                        onClick = { showContextMenu = false },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("취소")
                    }
                }
            }
        }
    }
}

// 지도에 마커 업데이트하는 함수
private fun updateMarkersOnMap(
    naverMap: NaverMap,
    userLocations: Map<String, LocationData>,
    activeMarkers: MutableMap<String, Marker>
) {
    activeMarkers.values.forEach { marker ->
        marker.map = null
    }
    activeMarkers.clear()

    userLocations.forEach { (userId, location) ->
        val colorIndex = Math.abs(userId.hashCode()) % 8

        val marker = Marker().apply {
            position = LatLng(location.latitude, location.longitude)
            captionText = userId
            captionTextSize = 12f

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
    }
}

private fun getMarkerColorEmoji(userId: String): String {
    val colorIndex = Math.abs(userId.hashCode()) % 8
    return when (colorIndex) {
        0 -> "🔴"
        1 -> "🔵"
        2 -> "🟢"
        3 -> "⚫"
        4 -> "🟣"
        5 -> "🟡"
        6 -> "🔵"
        else -> "🔴"
    }
}