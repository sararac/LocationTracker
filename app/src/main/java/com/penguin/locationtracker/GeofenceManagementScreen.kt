package com.penguin.locationtracker

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
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
import com.google.android.gms.location.*
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.ValueEventListener
import com.google.firebase.database.ktx.database
import com.google.firebase.ktx.Firebase
import android.app.NotificationChannel
import android.app.NotificationManager
import androidx.core.app.NotificationCompat
import android.content.ClipboardManager
import android.content.ClipData
import android.widget.Toast
import android.location.Geocoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GeofenceManagementScreen(
    onBackToMain: () -> Unit,
    selectedLatitude: Double? = null,
    selectedLongitude: Double? = null,
    autoShowDialog: Boolean = false,
    onDialogShown: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("UserPrefs", Context.MODE_PRIVATE) }
    val currentUserId = remember { prefs.getString("user_id", "") ?: "" }

    var geofenceList by remember { mutableStateOf(listOf<GeofenceData>()) }
    var hasLocationPermission by remember { mutableStateOf(false) }
    var hasNotificationPermission by remember { mutableStateOf(true) }
    var statusMessage by remember { mutableStateOf("지오펜스 목록을 불러오는 중...") }

    // 새 지오펜스 입력 상태
    var showAddDialog by remember { mutableStateOf(false) }
    var newLocationName by remember { mutableStateOf("") }
    var newTargetUserId by remember { mutableStateOf("") }
    var newNotifyUserId by remember { mutableStateOf("") }
    var newLatitude by remember { mutableStateOf(selectedLatitude?.toString() ?: "") }
    var newLongitude by remember { mutableStateOf(selectedLongitude?.toString() ?: "") }
    var newRadius by remember { mutableStateOf("100") }

    val database = remember { Firebase.database }
    val geofencesRef = remember { database.getReference("geofences") }
    val geofencingClient = remember { LocationServices.getGeofencingClient(context) }

    // 자동 다이얼로그 표시
    LaunchedEffect(autoShowDialog) {
        if (autoShowDialog) {
            showAddDialog = true
            onDialogShown() // 다이얼로그가 표시되었음을 알림
        }
    }

    // 권한 확인
    LaunchedEffect(Unit) {
        hasLocationPermission = PermissionManager.hasLocationPermission(context)
        hasNotificationPermission = PermissionManager.hasNotificationPermission(context)
    }

    // 권한 요청 런처들
    val locationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        hasLocationPermission = permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
                permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true

        val backgroundGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            permissions[Manifest.permission.ACCESS_BACKGROUND_LOCATION] == true
        } else {
            true
        }

        if (hasLocationPermission && backgroundGranted) {
            statusMessage = "모든 위치 권한이 허용되었습니다."
        } else if (hasLocationPermission) {
            statusMessage = "위치 권한은 허용되었지만 백그라운드 권한이 필요합니다."
        } else {
            statusMessage = "위치 권한이 필요합니다."
        }
    }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        hasNotificationPermission = isGranted
        if (isGranted) {
            statusMessage = "알림 권한이 허용되었습니다."
        } else {
            statusMessage = "알림 권한이 거부되었습니다. 설정에서 수동으로 허용해주세요."
        }
    }

    // Firebase에서 지오펜스 데이터 읽기
    LaunchedEffect(Unit) {
        geofencesRef.addValueEventListener(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val geofences = mutableListOf<GeofenceData>()
                for (geofenceSnapshot in snapshot.children) {
                    geofenceSnapshot.getValue(GeofenceData::class.java)?.let { geofence ->
                        geofences.add(geofence)
                    }
                }
                geofenceList = geofences.sortedByDescending { it.createdTime }
                statusMessage = if (geofences.isEmpty()) {
                    "등록된 지오펜스가 없습니다"
                } else {
                    "${geofences.size}개의 지오펜스가 등록되어 있습니다"
                }
            }

            override fun onCancelled(error: DatabaseError) {
                statusMessage = "데이터 읽기 오류: ${error.message}"
            }
        })
    }

    // 지오펜스 추가 함수
    fun addGeofence(geofenceData: GeofenceData) {
        if (!hasLocationPermission) {
            statusMessage = "위치 권한이 필요합니다"
            return
        }

        try {
            // Firebase에 저장
            val geofenceRef = geofencesRef.child(geofenceData.id)
            geofenceRef.setValue(geofenceData)
                .addOnSuccessListener {
                    Log.d("Geofence", "Geofence saved to Firebase: ${geofenceData.name}")
                }
                .addOnFailureListener { e ->
                    Log.e("Geofence", "Failed to save geofence to Firebase", e)
                }

            // Google Play Services에 지오펜스 등록 (대상 사용자만)
            if (geofenceData.targetUserId == currentUserId) {
                addGeofenceToPlayServices(geofencingClient, geofenceData, context) { success ->
                    if (success) {
                        statusMessage = "${geofenceData.name} 지오펜스가 등록되었습니다"
                    } else {
                        statusMessage = "지오펜스 등록에 실패했습니다"
                    }
                }
            } else {
                statusMessage = "${geofenceData.name} 지오펜스가 Firebase에 저장되었습니다 (다른 사용자 대상)"
            }

        } catch (e: Exception) {
            Log.e("Geofence", "Error adding geofence", e)
            statusMessage = "지오펜스 추가 오류: ${e.message}"
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
            text = "위치 알림 설정",
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )

        if (currentUserId.isEmpty()) {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer
                )
            ) {
                Text(
                    text = "사용자 ID를 먼저 설정해주세요",
                    modifier = Modifier.padding(16.dp),
                    fontSize = 16.sp
                )
            }
        } else {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer
                )
            ) {
                Text(
                    text = "현재 사용자: $currentUserId",
                    modifier = Modifier.padding(16.dp),
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        }

        Card {
            Text(
                text = statusMessage,
                modifier = Modifier.padding(16.dp),
                fontSize = 14.sp
            )
        }

        // 권한 체크 및 요청
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (!hasLocationPermission) {
                Button(
                    onClick = {
                        locationPermissionLauncher.launch(PermissionManager.LOCATION_PERMISSIONS)
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Text("위치 권한 요청")
                }
            }

            if (!hasNotificationPermission && PermissionManager.NOTIFICATION_PERMISSION.isNotEmpty()) {
                Button(
                    onClick = {
                        notificationPermissionLauncher.launch(PermissionManager.NOTIFICATION_PERMISSION[0])
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Text("알림 권한 요청")
                }
            }
        }

        // 권한 상태 표시
        if (!PermissionManager.hasAllRequiredPermissions(context)) {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer
                )
            ) {
                Column(
                    modifier = Modifier.padding(16.dp)
                ) {
                    Text(
                        text = "지오펜스 기능을 사용하려면 다음 권한이 필요합니다:",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold
                    )

                    val deniedPermissions = PermissionManager.getDeniedPermissions(context)
                    deniedPermissions.forEach { permission ->
                        Row(
                            modifier = Modifier.padding(vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "❌",
                                fontSize = 12.sp,
                                modifier = Modifier.padding(end = 4.dp)
                            )
                            Text(
                                text = PermissionManager.getPermissionDisplayName(permission),
                                fontSize = 12.sp
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    Text(
                        text = "모든 권한을 허용하면 백그라운드에서 자동으로 위치 기반 알림을 받을 수 있습니다.",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        style = androidx.compose.ui.text.TextStyle(lineHeight = 14.sp)
                    )
                }
            }
        }

        // 테스트 및 추가 버튼들
        if (hasLocationPermission && hasNotificationPermission && currentUserId.isNotEmpty()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = { showAddDialog = true },
                    modifier = Modifier.weight(1f)
                ) {
                    Text("새 위치 알림 추가")
                }

                // 테스트 알림 버튼
                OutlinedButton(
                    onClick = {
                        Log.d("GeofenceTest", "Test notification button clicked")

                        // 직접 알림 표시 테스트
                        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

                        // Android 8.0+ 알림 채널 생성
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            val channel = NotificationChannel(
                                "geofence_notifications",
                                "위치 알림",
                                NotificationManager.IMPORTANCE_HIGH
                            ).apply {
                                description = "지정된 장소 도착/출발 알림"
                                enableVibration(true)
                                enableLights(true)
                                setShowBadge(true)
                            }
                            notificationManager.createNotificationChannel(channel)
                        }

                        // 앱 실행 인텐트 생성
                        val appIntent = context.packageManager.getLaunchIntentForPackage(context.packageName)
                        val pendingIntent = PendingIntent.getActivity(
                            context,
                            0,
                            appIntent,
                            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                        )

                        // 테스트 알림 생성
                        val notification = NotificationCompat.Builder(context, "geofence_notifications")
                            .setSmallIcon(android.R.drawable.ic_dialog_map)
                            .setContentTitle("$currentUserId 테스트장소 도착")
                            .setContentText("$currentUserId 가 테스트장소에 14시30분에 도착했습니다.")
                            .setStyle(NotificationCompat.BigTextStyle().bigText("$currentUserId 가 테스트장소에 14시30분에 도착했습니다."))
                            .setPriority(NotificationCompat.PRIORITY_HIGH)
                            .setDefaults(NotificationCompat.DEFAULT_ALL)
                            .setAutoCancel(true)
                            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                            .setContentIntent(pendingIntent)
                            .build()

                        val notificationId = System.currentTimeMillis().toInt()
                        notificationManager.notify(notificationId, notification)

                        statusMessage = "테스트 알림이 표시되었습니다"
                        Log.d("GeofenceTest", "Test notification displayed")
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Text("알림 테스트")
                }
            }
        }

        // 지오펜스 목록
        if (geofenceList.isNotEmpty()) {
            Card {
                Column(
                    modifier = Modifier.padding(16.dp)
                ) {
                    Text(
                        text = "등록된 위치 알림 (${geofenceList.size}개)",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold
                    )

                    LazyColumn(
                        modifier = Modifier.heightIn(max = 300.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(geofenceList) { geofence ->
                            GeofenceItem(
                                geofence = geofence,
                                onRemove = { geofenceId ->
                                    // 지오펜스 제거 로직
                                    geofencesRef.child(geofenceId).removeValue()
                                    removeGeofenceFromPlayServices(geofencingClient, geofenceId)
                                }
                            )
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
            text = "위치 알림 설정",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.secondary
        )
    }

    // 지오펜스 추가 다이얼로그
    if (showAddDialog) {
        AlertDialog(
            onDismissRequest = {
                showAddDialog = false
                // 다이얼로그를 닫을 때 선택된 좌표 초기화
                newLatitude = ""
                newLongitude = ""
            },
            title = { Text("새 위치 알림 추가") },
            text = {
                Column(
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = newLocationName,
                        onValueChange = { newLocationName = it },
                        label = { Text("장소 이름") },
                        placeholder = { Text("예: 집, 회사, 학교") }
                    )

                    OutlinedTextField(
                        value = newTargetUserId,
                        onValueChange = { newTargetUserId = it },
                        label = { Text("대상 사용자 (출입하는 사용자)") },
                        placeholder = { Text(currentUserId.takeIf { it.isNotEmpty() } ?: "사용자 ID") }
                    )

                    OutlinedTextField(
                        value = newNotifyUserId,
                        onValueChange = { newNotifyUserId = it },
                        label = { Text("알림받을 사용자") },
                        placeholder = { Text("사용자 ID") }
                    )

                    OutlinedTextField(
                        value = newLatitude,
                        onValueChange = { newLatitude = it },
                        label = { Text("위도") },
                        placeholder = { Text("37.5665") },
                        readOnly = selectedLatitude != null // 지도에서 선택했으면 읽기 전용
                    )

                    OutlinedTextField(
                        value = newLongitude,
                        onValueChange = { newLongitude = it },
                        label = { Text("경도") },
                        placeholder = { Text("126.9780") },
                        readOnly = selectedLongitude != null // 지도에서 선택했으면 읽기 전용
                    )

                    OutlinedTextField(
                        value = newRadius,
                        onValueChange = { newRadius = it },
                        label = { Text("반경 (미터)") },
                        placeholder = { Text("100") }
                    )

                    // 지도에서 선택한 좌표인 경우 안내 메시지
                    if (selectedLatitude != null && selectedLongitude != null) {
                        Text(
                            text = "지도에서 선택한 위치가 자동으로 입력되었습니다.",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (newLocationName.isNotBlank() &&
                            newTargetUserId.isNotBlank() &&
                            newNotifyUserId.isNotBlank() &&
                            newLatitude.isNotBlank() &&
                            newLongitude.isNotBlank()) {

                            try {
                                val geofenceData = GeofenceData(
                                    id = "geofence_${System.currentTimeMillis()}",
                                    name = newLocationName.trim(),
                                    latitude = newLatitude.toDouble(),
                                    longitude = newLongitude.toDouble(),
                                    radius = newRadius.toFloatOrNull() ?: 100f,
                                    targetUserId = newTargetUserId.trim(),
                                    notifyUserId = newNotifyUserId.trim()
                                )

                                addGeofence(geofenceData)

                                // 입력 필드 초기화
                                newLocationName = ""
                                newTargetUserId = ""
                                newNotifyUserId = ""
                                newLatitude = ""
                                newLongitude = ""
                                newRadius = "100"
                                showAddDialog = false

                            } catch (e: Exception) {
                                statusMessage = "입력값 오류: ${e.message}"
                            }
                        }
                    }
                ) {
                    Text("추가")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showAddDialog = false
                        // 필드 초기화
                        newLocationName = ""
                        newTargetUserId = ""
                        newNotifyUserId = ""
                        newLatitude = ""
                        newLongitude = ""
                        newRadius = "100"
                    }
                ) {
                    Text("취소")
                }
            }
        )
    }
}

@Composable
private fun GeofenceItem(
    geofence: GeofenceData,
    onRemove: (String) -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top
        ) {
            Column(
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = geofence.name,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = geofence.getDescription(), // "대상사용자 → 알림받을사용자: 장소명"
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = geofence.getLocationSummary(),
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = geofence.getFormattedCreatedTime(),
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            TextButton(
                onClick = { onRemove(geofence.id) }
            ) {
                Text("제거", fontSize = 12.sp)
            }
        }
    }
}

// Google Play Services에 지오펜스 등록
@SuppressLint("MissingPermission")
private fun addGeofenceToPlayServices(
    geofencingClient: GeofencingClient,
    geofenceData: GeofenceData,
    context: Context,
    callback: (Boolean) -> Unit
) {
    // 위치 권한 재확인
    if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
        != PackageManager.PERMISSION_GRANTED) {
        Log.e("Geofence", "Location permission not granted")
        callback(false)
        return
    }

    val geofence = Geofence.Builder()
        .setRequestId(geofenceData.id)
        .setCircularRegion(geofenceData.latitude, geofenceData.longitude, geofenceData.radius)
        .setExpirationDuration(Geofence.NEVER_EXPIRE)
        .setTransitionTypes(Geofence.GEOFENCE_TRANSITION_ENTER or Geofence.GEOFENCE_TRANSITION_EXIT)
        .setLoiteringDelay(1000)
        .build()

    val geofencingRequest = GeofencingRequest.Builder()
        .setInitialTrigger(0)
        .addGeofence(geofence)
        .build()

    val geofencePendingIntent = PendingIntent.getBroadcast(
        context,
        geofenceData.id.hashCode(),
        Intent(context, GeofenceBroadcastReceiver::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    Log.d("Geofence", "Attempting to add geofence: ${geofenceData.name}")

    geofencingClient.addGeofences(geofencingRequest, geofencePendingIntent)
        .addOnSuccessListener {
            Log.d("Geofence", "Geofence added successfully: ${geofenceData.name}")
            callback(true)
        }
        .addOnFailureListener { e ->
            Log.e("Geofence", "Failed to add geofence: ${geofenceData.name}", e)
            callback(false)
        }
}

// Google Play Services에서 지오펜스 제거
private fun removeGeofenceFromPlayServices(
    geofencingClient: GeofencingClient,
    geofenceId: String
) {
    geofencingClient.removeGeofences(listOf(geofenceId))
        .addOnSuccessListener {
            Log.d("Geofence", "Geofence removed successfully: $geofenceId")
        }
        .addOnFailureListener { e ->
            Log.e("Geofence", "Failed to remove geofence: $geofenceId", e)
        }
}