package com.penguin.locationtracker

import android.location.Geocoder
import android.util.Log
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
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
import com.naver.maps.map.overlay.PathOverlay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.*
import androidx.compose.material3.LocalTextStyle

@Composable
fun UserLocationHistoryMapScreen(
    userId: String,
    onBackToHistory: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var isMapReady by remember { mutableStateOf(false) }
    var naverMapInstance by remember { mutableStateOf<NaverMap?>(null) }
    var locationHistory by remember { mutableStateOf(listOf<LocationData>()) }
    var activeMarkers by remember { mutableStateOf(mutableListOf<Marker>()) }
    var pathOverlay by remember { mutableStateOf<PathOverlay?>(null) }
    var isLoading by remember { mutableStateOf(true) }
    var locationAddresses by remember { mutableStateOf(mapOf<Long, String>()) }
    var selectedLocationIndex by remember { mutableStateOf<Int?>(null) }
    var isHistoryExpanded by remember { mutableStateOf(false) }
    var selectedHours by remember { mutableStateOf("6") }
    var filteredLocationHistory by remember { mutableStateOf(listOf<LocationData>()) }

    val database = remember { Firebase.database }
    val userLocationsRef = remember { database.getReference("locations").child(userId) }
    val geocoder = remember { Geocoder(context, Locale.KOREAN) }

    // 날짜 포맷팅 함수
    fun formatDateTime(timestamp: Long): String {
        val sdf = SimpleDateFormat("MM.dd HH:mm", Locale.KOREAN)
        return sdf.format(Date(timestamp))
    }

    // 주소 가져오기 함수
    suspend fun getAddress(latitude: Double, longitude: Double): String {
        return try {
            withContext(Dispatchers.IO) {
                Log.d("LocationHistoryMap", "Getting address for: $latitude, $longitude")
                val addresses = geocoder.getFromLocation(latitude, longitude, 1)

                if (!addresses.isNullOrEmpty()) {
                    val address = addresses[0]
                    Log.d("LocationHistoryMap", "Address object found")

                    val fullAddress = address.getAddressLine(0)
                    if (!fullAddress.isNullOrEmpty()) {
                        val cleanAddress = fullAddress
                            .replace("대한민국", "")
                            .replace("South Korea", "")
                            .replace(Regex("\\d{5}"), "")
                            .trim()
                            .replace(Regex("\\s+"), " ")

                        if (cleanAddress.isNotEmpty()) {
                            cleanAddress
                        } else {
                            "주소 불명"
                        }
                    } else {
                        val parts = mutableListOf<String>()

                        address.subAdminArea?.let { if (!it.endsWith("도")) parts.add(it) }
                        address.locality?.let { parts.add(it) }
                        address.subLocality?.let { parts.add(it) }
                        address.thoroughfare?.let { parts.add(it) }
                        address.subThoroughfare?.let { parts.add(it) }

                        if (parts.isNotEmpty()) {
                            parts.joinToString(" ")
                        } else {
                            "주소 불명"
                        }
                    }
                } else {
                    Log.w("LocationHistoryMap", "No addresses found for: $latitude, $longitude")
                    "주소 불명"
                }
            }
        } catch (e: Exception) {
            Log.e("LocationHistoryMap", "Error getting address for $latitude, $longitude", e)
            "주소 불명"
        }
    }

    // 시간 필터링 함수
    fun filterLocationsByHours(hours: Int) {
        val cutoffTime = System.currentTimeMillis() - (hours * 60 * 60 * 1000L)
        filteredLocationHistory = locationHistory.filter { it.timestamp >= cutoffTime }
    }

    // 사용자의 모든 위치 데이터 읽기
    LaunchedEffect(userId) {
        Log.d("LocationHistoryMap", "Loading history for user: $userId")

        userLocationsRef.addValueEventListener(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                try {
                    val locations = mutableListOf<LocationData>()

                    for (locationSnapshot in snapshot.children) {
                        val location = locationSnapshot.getValue(LocationData::class.java)
                        if (location != null) {
                            locations.add(location)
                        }
                    }

                    locationHistory = locations.sortedBy { it.timestamp }
                    isLoading = false

                    Log.d("LocationHistoryMap", "Loaded ${locations.size} locations for $userId")
                } catch (e: Exception) {
                    Log.e("LocationHistoryMap", "Error processing location data for $userId", e)
                    isLoading = false
                }
            }

            override fun onCancelled(error: DatabaseError) {
                isLoading = false
                Log.e("LocationHistoryMap", "Error loading history for $userId", error.toException())
            }
        })
    }

    // 위치 데이터가 업데이트될 때마다 필터링
    LaunchedEffect(locationHistory, selectedHours) {
        val hours = selectedHours.toIntOrNull() ?: 6
        filterLocationsByHours(hours)

        // 지도 마커 업데이트
        naverMapInstance?.let { map ->
            updateLocationHistoryOnMap(map, filteredLocationHistory, activeMarkers, pathOverlay, userId, selectedLocationIndex) { newPath ->
                pathOverlay = newPath
            }
        }
    }

    // 주소 정보 가져오기
    LaunchedEffect(filteredLocationHistory) {
        if (filteredLocationHistory.isNotEmpty()) {
            val addressMap = mutableMapOf<Long, String>()
            filteredLocationHistory.forEach { location ->
                try {
                    val address = getAddress(location.latitude, location.longitude)
                    addressMap[location.timestamp] = address
                } catch (e: Exception) {
                    Log.e("LocationHistoryMap", "Error getting address for location", e)
                }
            }
            locationAddresses = addressMap
        }
    }

    Column(
        modifier = modifier.fillMaxSize()
    ) {
        // 상단 버튼들
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Button(
                onClick = {
                    Log.d("LocationHistoryMap", "Back to main button clicked")
                    onBackToHistory()
                },
                modifier = Modifier
                    .weight(1f)
                    .height(32.dp),
                contentPadding = PaddingValues(2.dp)
            ) {
                Text("메인화면", fontSize = 12.sp)
            }

            Button(
                onClick = {
                    naverMapInstance?.let { map ->
                        try {
                            if (filteredLocationHistory.isNotEmpty()) {
                                val latitudes = filteredLocationHistory.map { it.latitude }
                                val longitudes = filteredLocationHistory.map { it.longitude }

                                val centerLat = (latitudes.minOrNull()!! + latitudes.maxOrNull()!!) / 2
                                val centerLng = (longitudes.minOrNull()!! + longitudes.maxOrNull()!!) / 2

                                val cameraPosition = CameraPosition(
                                    LatLng(centerLat, centerLng),
                                    10.0
                                )
                                map.cameraPosition = cameraPosition
                            }
                        } catch (e: Exception) {
                            Log.e("LocationHistoryMap", "Error in fit bounds", e)
                        }
                    }
                },
                enabled = isMapReady && filteredLocationHistory.isNotEmpty(),
                modifier = Modifier
                    .weight(1f)
                    .height(32.dp),
                contentPadding = PaddingValues(2.dp)
            ) {
                Text("전체보기", fontSize = 12.sp)
            }

            // 시간 입력 필드 (수정된 버전)
            OutlinedTextField(
                value = selectedHours,
                onValueChange = { newValue ->
                    selectedHours = newValue.filter { char -> char.isDigit() }
                },
                modifier = Modifier
                    .weight(1f)
                    .height(32.dp),
                textStyle = LocalTextStyle.current.copy(fontSize = 12.sp),
                placeholder = { Text("6", fontSize = 12.sp) },
                suffix = { Text("시간", fontSize = 10.sp) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
            )

            Button(
                onClick = {
                    isLoading = true
                    userLocationsRef.get().addOnSuccessListener {
                        Log.d("LocationHistoryMap", "Manual refresh completed")
                    }.addOnFailureListener {
                        isLoading = false
                        Log.e("LocationHistoryMap", "Manual refresh failed")
                    }
                },
                enabled = !isLoading,
                modifier = Modifier
                    .weight(1f)
                    .height(32.dp),
                contentPadding = PaddingValues(2.dp)
            ) {
                Text("새로고침", fontSize = 12.sp)
            }
        }

        // 필터링된 데이터로 표시
        if (filteredLocationHistory.isNotEmpty()) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 2.dp)
            ) {
                Column(
                    modifier = Modifier.padding(8.dp)
                ) {
                    Text(
                        text = "최근 ${selectedHours.toIntOrNull() ?: 6}시간 이력 (${filteredLocationHistory.size}개)",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(bottom = 4.dp)
                    )

                    LazyColumn(
                        modifier = if (isHistoryExpanded) {
                            Modifier.heightIn(max = 300.dp)
                        } else {
                            Modifier.heightIn(max = 120.dp)
                        },
                        verticalArrangement = Arrangement.spacedBy(1.dp)
                    ) {
                        val sortedHistory = filteredLocationHistory.sortedByDescending { it.timestamp }
                        val itemsToShow = if (isHistoryExpanded) sortedHistory else sortedHistory.take(10)

                        items(itemsToShow.withIndex().toList()) { (displayIndex, location) ->
                            val originalIndex = locationHistory.indexOf(location)
                            val isFirst = displayIndex == 0

                            Card(
                                colors = CardDefaults.cardColors(
                                    containerColor = if (isFirst) {
                                        MaterialTheme.colorScheme.primaryContainer
                                    } else {
                                        MaterialTheme.colorScheme.surface
                                    }
                                ),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        selectedLocationIndex = originalIndex

                                        naverMapInstance?.let { map ->
                                            updateLocationHistoryOnMap(map, filteredLocationHistory, activeMarkers, pathOverlay, userId, originalIndex) { newPath ->
                                                pathOverlay = newPath
                                            }

                                            try {
                                                val currentZoom = map.cameraPosition.zoom
                                                val cameraPosition = CameraPosition(
                                                    LatLng(location.latitude, location.longitude),
                                                    currentZoom
                                                )
                                                map.cameraPosition = cameraPosition
                                            } catch (e: Exception) {
                                                Log.e("LocationHistoryMap", "Error moving camera to location", e)
                                            }
                                        }
                                    }
                            ) {
                                Text(
                                    text = "#${originalIndex + 1} ${formatDateTime(location.timestamp)} ${locationAddresses[location.timestamp] ?: ""}",
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Medium,
                                    modifier = Modifier.padding(6.dp),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }

                        if (!isHistoryExpanded && filteredLocationHistory.size > 10) {
                            item {
                                Card(
                                    colors = CardDefaults.cardColors(
                                        containerColor = MaterialTheme.colorScheme.primaryContainer
                                    ),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            isHistoryExpanded = true
                                        }
                                ) {
                                    Text(
                                        text = "더 보려면 여기를 눌러주세요 (총 ${filteredLocationHistory.size}개)",
                                        fontSize = 11.sp,
                                        color = MaterialTheme.colorScheme.primary,
                                        fontWeight = FontWeight.Medium,
                                        modifier = Modifier.padding(8.dp)
                                    )
                                }
                            }
                        }

                        if (isHistoryExpanded) {
                            item {
                                Card(
                                    colors = CardDefaults.cardColors(
                                        containerColor = MaterialTheme.colorScheme.secondaryContainer
                                    ),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            isHistoryExpanded = false
                                        }
                                ) {
                                    Text(
                                        text = "접기",
                                        fontSize = 11.sp,
                                        color = MaterialTheme.colorScheme.secondary,
                                        fontWeight = FontWeight.Medium,
                                        modifier = Modifier.padding(8.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // 지도 표시
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight()
                .padding(horizontal = 8.dp, vertical = 2.dp)
        ) {
            AndroidView(
                factory = { context ->
                    Log.d("LocationHistoryMap", "Creating MapView...")
                    MapView(context).apply {
                        getMapAsync(object : OnMapReadyCallback {
                            override fun onMapReady(naverMap: NaverMap) {
                                try {
                                    Log.d("LocationHistoryMap", "Map is ready")
                                    naverMapInstance = naverMap
                                    isMapReady = true

                                    val initialPosition = if (filteredLocationHistory.isNotEmpty()) {
                                        val firstLocation = filteredLocationHistory.first()
                                        CameraPosition(
                                            LatLng(firstLocation.latitude, firstLocation.longitude),
                                            12.0
                                        )
                                    } else {
                                        CameraPosition(
                                            LatLng(37.5665, 126.9780),
                                            12.0
                                        )
                                    }
                                    naverMap.cameraPosition = initialPosition

                                    if (filteredLocationHistory.isNotEmpty()) {
                                        updateLocationHistoryOnMap(naverMap, filteredLocationHistory, activeMarkers, pathOverlay, userId, selectedLocationIndex) { newPath ->
                                            pathOverlay = newPath
                                        }
                                    }

                                } catch (e: Exception) {
                                    Log.e("LocationHistoryMap", "Error in onMapReady", e)
                                }
                            }
                        })
                    }
                },
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}

private fun updateLocationHistoryOnMap(
    naverMap: NaverMap,
    locationHistory: List<LocationData>,
    activeMarkers: MutableList<Marker>,
    currentPath: PathOverlay?,
    userId: String,
    selectedIndex: Int? = null,
    onPathUpdated: (PathOverlay?) -> Unit
) {
    try {
        activeMarkers.forEach { marker ->
            marker.map = null
        }
        activeMarkers.clear()

        currentPath?.map = null

        if (locationHistory.isEmpty()) {
            onPathUpdated(null)
            return
        }

        if (locationHistory.size > 1) {
            val pathOverlay = PathOverlay()
            val coords = locationHistory.map { location ->
                LatLng(location.latitude, location.longitude)
            }

            pathOverlay.coords = coords
            pathOverlay.color = getPathColor(userId)
            pathOverlay.width = 8
            pathOverlay.map = naverMap

            onPathUpdated(pathOverlay)
        }

        locationHistory.forEachIndexed { index, location ->
            val shouldShowMarker = when {
                index == 0 -> true
                index == locationHistory.size - 1 -> true
                selectedIndex == index -> true
                else -> false
            }

            if (shouldShowMarker) {
                val marker = Marker().apply {
                    position = LatLng(location.latitude, location.longitude)
                    captionText = "${index + 1}: ${getTimeOnly(location.timestamp)}"
                    captionTextSize = 11f

                    icon = when {
                        index == 0 -> OverlayImage.fromResource(com.naver.maps.map.R.drawable.navermap_default_marker_icon_green)
                        index == locationHistory.size - 1 -> OverlayImage.fromResource(com.naver.maps.map.R.drawable.navermap_default_marker_icon_red)
                        selectedIndex == index -> OverlayImage.fromResource(com.naver.maps.map.R.drawable.navermap_default_marker_icon_blue)
                        else -> OverlayImage.fromResource(com.naver.maps.map.R.drawable.navermap_default_marker_icon_blue)
                    }

                    map = naverMap
                }

                activeMarkers.add(marker)
            }
        }

    } catch (e: Exception) {
        Log.e("LocationHistoryMap", "Error updating location history on map", e)
    }
}

private fun getPathColor(userId: String): Int {
    val colorIndex = Math.abs(userId.hashCode()) % 6
    return when (colorIndex) {
        0 -> Color.Red.toArgb()
        1 -> Color.Blue.toArgb()
        2 -> Color.Green.toArgb()
        3 -> Color.Magenta.toArgb()
        4 -> Color.Cyan.toArgb()
        else -> Color(0xFF6200EA).toArgb()
    }
}

private fun getTimeOnly(timestamp: Long): String {
    val sdf = SimpleDateFormat("HH:mm", Locale.KOREAN)
    return sdf.format(Date(timestamp))
}