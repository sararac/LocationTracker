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
import androidx.compose.foundation.background
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.ValueEventListener
import com.google.firebase.database.ktx.database
import com.google.firebase.ktx.Firebase

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UserLocationHistoryScreen(
    userId: String,
    onBackToMap: () -> Unit,
    onViewOnMap: (String) -> Unit, // 새로 추가된 매개변수
    modifier: Modifier = Modifier
) {
    var locationHistory by remember { mutableStateOf(listOf<LocationData>()) }
    var isLoading by remember { mutableStateOf(true) }
    var statusMessage by remember { mutableStateOf("위치 이력을 불러오는 중...") }

    val database = remember { Firebase.database }
    val userLocationsRef = remember { database.getReference("locations").child(userId) }

    // 사용자의 모든 위치 데이터 읽기
    LaunchedEffect(userId) {
        Log.d("LocationHistory", "Loading history for user: $userId")

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

                    // 시간순 정렬 (최신 순)
                    locationHistory = locations.sortedByDescending { it.timestamp }
                    isLoading = false

                    statusMessage = if (locations.isEmpty()) {
                        "$userId 사용자의 위치 이력이 없습니다"
                    } else {
                        "${locations.size}개의 위치 이력을 찾았습니다"
                    }

                    Log.d("LocationHistory", "Loaded ${locations.size} locations for $userId")
                } catch (e: Exception) {
                    Log.e("LocationHistory", "Error processing location data for $userId", e)
                    isLoading = false
                    statusMessage = "데이터 처리 오류: ${e.message}"
                }
            }

            override fun onCancelled(error: DatabaseError) {
                isLoading = false
                statusMessage = "데이터 읽기 오류: ${error.message}"
                Log.e("LocationHistory", "Error loading history for $userId", error.toException())
            }
        })
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = "$userId 위치 이력",
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )

        Card(
            colors = CardDefaults.cardColors(
                containerColor = when {
                    isLoading -> MaterialTheme.colorScheme.secondaryContainer
                    locationHistory.isNotEmpty() -> MaterialTheme.colorScheme.primaryContainer
                    else -> MaterialTheme.colorScheme.errorContainer
                }
            )
        ) {
            Row(
                modifier = Modifier.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                }
                Text(
                    text = statusMessage,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        }

        if (locationHistory.isNotEmpty()) {
            Card {
                Column(
                    modifier = Modifier.padding(16.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "위치 이력 (${locationHistory.size}개)",
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold
                        )

                        Text(
                            text = getMarkerColorEmoji(userId),
                            fontSize = 20.sp
                        )
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    LazyColumn(
                        modifier = Modifier.heightIn(max = 400.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(locationHistory.withIndex().toList()) { (index, location) ->
                            LocationHistoryItem(
                                index = index + 1,
                                location = location,
                                isLatest = index == 0,
                                userId = userId
                            )
                        }
                    }
                }
            }
        } else if (!isLoading) {
            // 데이터가 없을 때 안내
            Card {
                Column(
                    modifier = Modifier.padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "📍",
                        fontSize = 48.sp
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "위치 이력이 없습니다",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "$userId 사용자의 위치 데이터가 저장되지 않았습니다",
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        Spacer(modifier = Modifier.weight(1f))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedButton(
                onClick = {
                    Log.d("LocationHistory", "Back to map button clicked")
                    onBackToMap()
                },
                modifier = Modifier.weight(1f)
            ) {
                Text("지도로 돌아가기")
            }

            // 지도에서 경로 보기 버튼 추가
            if (locationHistory.isNotEmpty()) {
                Button(
                    onClick = {
                        Log.d("LocationHistory", "View on map button clicked for $userId")
                        onViewOnMap(userId)
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Text("지도보기")
                }
            }

            if (locationHistory.isNotEmpty()) {
                OutlinedButton(
                    onClick = {
                        // 이력 새로고침
                        isLoading = true
                        statusMessage = "위치 이력을 새로고침하는 중..."
                        Log.d("LocationHistory", "Refreshing history for $userId")
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Text("새로고침")
                }
            }
        }

        Text(
            text = "📍 7단계: 위치 변경 이력 조회",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.secondary
        )
    }
}

@Composable
private fun LocationHistoryItem(
    index: Int,
    location: LocationData,
    isLatest: Boolean,
    userId: String
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (isLatest)
                MaterialTheme.colorScheme.primaryContainer
            else
                MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(
            modifier = Modifier.padding(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Column(
                    modifier = Modifier.weight(1f)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "#$index",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )

                        if (isLatest) {
                            Spacer(modifier = Modifier.width(8.dp))
                            Surface(
                                color = MaterialTheme.colorScheme.primary,
                                shape = MaterialTheme.shapes.small
                            ) {
                                Text(
                                    text = "최신",
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onPrimary,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    Text(
                        text = location.getFormattedTime(),
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium
                    )

                    Spacer(modifier = Modifier.height(6.dp))

                    Text(
                        text = "위도: %.6f".format(location.latitude),
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Text(
                        text = "경도: %.6f".format(location.longitude),
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Column(
                    horizontalAlignment = Alignment.End
                ) {
                    Text(
                        text = getMarkerColorEmoji(userId),
                        fontSize = 16.sp
                    )

                    if (index > 1) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "이동",
                            fontSize = 10.sp,
                            color = MaterialTheme.colorScheme.secondary
                        )
                    }
                }
            }
        }
    }
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