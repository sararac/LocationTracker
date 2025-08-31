package com.penguin.locationtracker

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.ValueEventListener
import com.google.firebase.database.ktx.database
import com.google.firebase.ktx.Firebase

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LocationScreen(
    onBackToMain: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("UserPrefs", Context.MODE_PRIVATE) }
    val currentUserId = remember { prefs.getString("user_id", "") ?: "" }

    var hasLocationPermission by remember { mutableStateOf(false) }
    var isLocationLoading by remember { mutableStateOf(false) }
    var locationMessage by remember { mutableStateOf("위치 서비스를 시작하려면 권한이 필요합니다.") }
    var userLocations by remember { mutableStateOf(listOf<LocationData>()) }

    val database = remember { Firebase.database }
    val locationsRef = remember { database.getReference("locations") }

    // 권한 요청 런처
    val locationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        hasLocationPermission = permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
                permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true

        if (hasLocationPermission) {
            locationMessage = "위치 권한이 허용되었습니다."
        } else {
            locationMessage = "위치 권한이 거부되었습니다. 설정에서 권한을 허용해주세요."
        }
    }

    // 권한 확인
    LaunchedEffect(Unit) {
        hasLocationPermission = PermissionManager.hasLocationPermission(context)
    }

    // 사용자 위치 데이터 실시간 읽기
    LaunchedEffect(currentUserId) {
        if (currentUserId.isNotEmpty()) {
            locationsRef.child(currentUserId).addValueEventListener(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    val locations = mutableListOf<LocationData>()
                    for (locationSnapshot in snapshot.children) {
                        locationSnapshot.getValue(LocationData::class.java)?.let { location ->
                            locations.add(location)
                        }
                    }
                    // 시간순으로 정렬 (최신 순)
                    userLocations = locations.sortedByDescending { it.timestamp }
                }

                override fun onCancelled(error: DatabaseError) {
                    locationMessage = "데이터 읽기 오류: ${error.message}"
                }
            })
        }
    }

    // 위치 수집 함수
    @SuppressLint("MissingPermission")
    fun getCurrentLocation() {
        if (!hasLocationPermission) {
            locationMessage = "위치 권한이 필요합니다."
            return
        }

        if (currentUserId.isEmpty()) {
            locationMessage = "사용자 ID를 먼저 설정해주세요."
            return
        }

        isLocationLoading = true
        locationMessage = "현재 위치를 가져오는 중..."

        val fusedLocationClient = LocationServices.getFusedLocationProviderClient(context)
        val cancellationTokenSource = CancellationTokenSource()

        fusedLocationClient.getCurrentLocation(
            Priority.PRIORITY_HIGH_ACCURACY,
            cancellationTokenSource.token
        ).addOnSuccessListener { location ->
            isLocationLoading = false

            if (location != null) {
                val locationData = LocationData(
                    userId = currentUserId,
                    latitude = location.latitude,
                    longitude = location.longitude,
                    timestamp = System.currentTimeMillis(),
                    address = "위도: %.6f, 경도: %.6f".format(location.latitude, location.longitude),
                    stayDuration = 0L, // 수동 저장은 머문시간 0으로 시작
                    lastUpdateTime = System.currentTimeMillis()
                )

                // Firebase에 저장
                locationsRef.child(currentUserId).push().setValue(locationData)
                    .addOnSuccessListener {
                        locationMessage = "✅ 위치가 성공적으로 저장되었습니다!"
                        Log.d("Location", "Location saved: ${locationData.getLocationSummary()}")
                    }
                    .addOnFailureListener { e ->
                        locationMessage = "❌ 위치 저장에 실패했습니다: ${e.message}"
                        Log.e("Location", "Failed to save location", e)
                    }
            } else {
                locationMessage = "❌ 위치를 가져올 수 없습니다. GPS를 확인해주세요."
            }
        }.addOnFailureListener { e ->
            isLocationLoading = false
            locationMessage = "❌ 위치 수집 실패: ${e.message}"
            Log.e("Location", "Location collection failed", e)
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
            text = "위치 추적",
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )

        if (currentUserId.isNotEmpty()) {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer
                )
            ) {
                Text(
                    text = "사용자: $currentUserId",
                    modifier = Modifier.padding(16.dp),
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        } else {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer
                )
            ) {
                Text(
                    text = "⚠️ 사용자 ID를 먼저 설정해주세요",
                    modifier = Modifier.padding(16.dp),
                    fontSize = 16.sp
                )
            }
        }

        Card {
            Text(
                text = locationMessage,
                modifier = Modifier.padding(16.dp),
                fontSize = 14.sp
            )
        }

        if (!hasLocationPermission) {
            Button(
                onClick = {
                    // PermissionManager 사용하여 권한 요청
                    locationPermissionLauncher.launch(PermissionManager.LOCATION_PERMISSIONS)
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("위치 권한 요청")
            }

            // 추가 정보 표시
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer
                )
            ) {
                Column(
                    modifier = Modifier.padding(16.dp)
                ) {
                    Text(
                        text = "위치 추적을 위해 다음 권한이 필요합니다:",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold
                    )

                    val deniedPermissions = PermissionManager.getDeniedPermissions(context)
                    deniedPermissions.forEach { permission ->
                        Text(
                            text = "• ${PermissionManager.getPermissionDisplayName(permission)}",
                            fontSize = 12.sp,
                            modifier = Modifier.padding(start = 8.dp, top = 2.dp)
                        )
                    }
                }
            }
        } else {
            Button(
                onClick = { getCurrentLocation() },
                modifier = Modifier.fillMaxWidth(),
                enabled = !isLocationLoading && currentUserId.isNotEmpty()
            ) {
                if (isLocationLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                }
                Text("현재 위치 저장")
            }
        }

        if (userLocations.isNotEmpty()) {
            Card {
                Column(
                    modifier = Modifier.padding(16.dp)
                ) {
                    Text(
                        text = "저장된 위치 (${userLocations.size}개)",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )

                    LazyColumn(
                        modifier = Modifier.heightIn(max = 300.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(userLocations) { location ->
                            LocationItem(location = location)
                        }
                    }
                }
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
            text = "📍 위치 저장 및 머문시간 추적",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.secondary
        )
    }
}

@Composable
private fun LocationItem(location: LocationData) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (location.stayDuration > 0) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            }
        )
    ) {
        Column(
            modifier = Modifier.padding(12.dp)
        ) {
            // 시간 정보
            Text(
                text = location.getFormattedTime(),
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )

            Spacer(modifier = Modifier.height(4.dp))

            // 위치 정보
            Text(
                text = location.getLocationSummary(),
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            // 머문시간 표시 (0보다 클 때만)
            if (location.stayDuration > 0) {
                Spacer(modifier = Modifier.height(4.dp))

                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.secondary
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "머문시간: ${location.getFormattedStayDuration()}",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSecondary,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }

                // 마지막 업데이트 시간
                if (location.lastUpdateTime > location.timestamp) {
                    Text(
                        text = "마지막 업데이트: ${java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date(location.lastUpdateTime))}",
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }
            }
        }
    }
}