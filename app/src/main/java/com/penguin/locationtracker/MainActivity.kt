package com.penguin.locationtracker

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.firebase.FirebaseApp
import com.google.firebase.database.ktx.database
import com.google.firebase.ktx.Firebase
import com.penguin.locationtracker.ui.theme.LocationTrackerTheme

// MainActivity.kt의 onCreate() 메서드 업데이트
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Firebase 초기화 확인
        try {
            val firebaseApp = FirebaseApp.getInstance()
            Log.d("MainActivity", "Firebase App initialized: ${firebaseApp.name}")
            Log.d("MainActivity", "Database URL: ${firebaseApp.options.databaseUrl}")
        } catch (e: Exception) {
            Log.e("MainActivity", "Firebase initialization error", e)
        }

        // 알림에서 실행된 경우 처리
        val selectedUserId = intent.getStringExtra("selected_user_id")
        val notificationType = intent.getStringExtra("notification_type")
        val showNotificationHistory = intent.getBooleanExtra("show_notification_history", false) // 🆕 추가

        Log.d("MainActivity", "Intent extras - selectedUserId: $selectedUserId, notificationType: $notificationType, showNotificationHistory: $showNotificationHistory")

        enableEdgeToEdge()
        setContent {
            LocationTrackerTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    LocationTrackerAppWithPermissions(
                        selectedUserId = selectedUserId,
                        notificationType = notificationType,
                        showNotificationHistory = showNotificationHistory, // 🆕 추가
                        modifier = Modifier.padding(innerPadding)
                    )
                }
            }
        }
    }

    // 설정에 따라 위치 추적 서비스 자동 시작
    internal fun startLocationServiceIfEnabled() {
        val prefs = getSharedPreferences("UserPrefs", Context.MODE_PRIVATE)
        val userId = prefs.getString("user_id", "") ?: ""
        val autoStart = prefs.getBoolean("auto_start_service", true)

        Log.d("MainActivity", "Auto start check - User ID: $userId, Auto start: $autoStart")

        // 권한이 있고, 사용자 ID가 설정되어 있고, 자동 시작이 활성화된 경우에만 서비스 시작
        if (userId.isNotEmpty() && autoStart && PermissionManager.hasLocationPermission(this)) {
            val serviceIntent = Intent(this, LocationTrackingService::class.java)
            serviceIntent.action = LocationTrackingService.ACTION_START_TRACKING

            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(serviceIntent)
                } else {
                    startService(serviceIntent)
                }
                Log.d("MainActivity", "Location service started automatically")
            } catch (e: Exception) {
                Log.e("MainActivity", "Failed to start location service", e)
            }
        } else {
            Log.d("MainActivity", "Location service not started - conditions not met")
        }
    }

    override fun onResume() {
        super.onResume()
        // 앱이 포그라운드로 올라올 때마다 권한 확인 후 서비스 시작
        if (PermissionManager.hasAllRequiredPermissions(this)) {
            startLocationServiceIfEnabled()
        }
    }
}

@Composable
fun LocationTrackerAppWithPermissions(
    selectedUserId: String? = null,
    notificationType: String? = null,
    showNotificationHistory: Boolean = false, // 🆕 추가
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var hasRequiredPermissions by remember { mutableStateOf(false) }
    var shouldCheckPermissions by remember { mutableStateOf(true) }

    // 권한 확인
    LaunchedEffect(shouldCheckPermissions) {
        if (shouldCheckPermissions) {
            hasRequiredPermissions = PermissionManager.hasAllRequiredPermissions(context)
            Log.d("LocationTrackerApp", "Permission check - hasRequiredPermissions: $hasRequiredPermissions")
        }
    }

    // 권한 상태에 따라 다른 화면 표시
    if (!hasRequiredPermissions) {
        PermissionRequestScreen(
            onPermissionsGranted = {
                Log.d("LocationTrackerApp", "All permissions granted!")
                hasRequiredPermissions = true

                // 권한이 허용되면 위치 서비스 시작
                if (context is MainActivity) {
                    context.startLocationServiceIfEnabled()
                }
            },
            onSkip = {
                Log.d("LocationTrackerApp", "Permission request skipped")
                hasRequiredPermissions = true // 건너뛰기 허용
            },
            showSkipButton = true, // 건너뛰기 버튼 표시
            modifier = modifier
        )
    } else {
        LocationTrackerApp(
            selectedUserId = selectedUserId,
            notificationType = notificationType,
            showNotificationHistory = showNotificationHistory, // 🆕 추가
            modifier = modifier
        )
    }
}

// MainActivity.kt에 추가할 부분들

@Composable
fun LocationTrackerApp(
    selectedUserId: String? = null,
    notificationType: String? = null,
    showNotificationHistory: Boolean = false, // 🆕 추가
    modifier: Modifier = Modifier
) {
    var currentScreen by remember { mutableStateOf("main") }
    var selectedUserIdState by remember { mutableStateOf("") }
    var selectedLatitude by remember { mutableStateOf<Double?>(null) }
    var selectedLongitude by remember { mutableStateOf<Double?>(null) }
    var showGeofenceDialog by remember { mutableStateOf(false) }

    // 🆕 알림 이력 화면 자동 표시
    LaunchedEffect(showNotificationHistory) {
        if (showNotificationHistory) {
            currentScreen = "notification_history"
            Log.d("LocationTrackerApp", "Auto-showing notification history from notification")
        }
    }

    // 알림에서 온 경우 해당 사용자 자동 선택
    LaunchedEffect(selectedUserId, notificationType) {
        if (!selectedUserId.isNullOrEmpty() && notificationType == "location_tracking") {
            selectedUserIdState = selectedUserId
            currentScreen = "userhistorymap"
            Log.d("LocationTrackerApp", "Auto-selected user from notification: $selectedUserId")
        }
    }

    when (currentScreen) {
        "main" -> MainMapScreen(
            onNavigateToSettings = { currentScreen = "settings" },
            onShowUserHistory = { userId ->
                selectedUserIdState = userId
                currentScreen = "userhistorymap"
            },
            onNavigateToGeofence = { latitude, longitude ->
                selectedLatitude = latitude
                selectedLongitude = longitude
                showGeofenceDialog = true
                currentScreen = "geofence"
            },
            onNavigateToNotificationHistory = { currentScreen = "notification_history" }, // 🆕 추가
            modifier = modifier
        )
        "settings" -> SettingsMenuScreen(
            onBackToMain = { currentScreen = "main" },
            modifier = modifier
        )
        "userhistorymap" -> UserLocationHistoryMapScreen(
            userId = selectedUserIdState,
            onBackToHistory = { currentScreen = "main" },
            modifier = modifier
        )
        "geofence" -> GeofenceManagementScreen(
            onBackToMain = { currentScreen = "main" },
            selectedLatitude = selectedLatitude,
            selectedLongitude = selectedLongitude,
            autoShowDialog = showGeofenceDialog,
            onDialogShown = { showGeofenceDialog = false },
            modifier = modifier
        )
        // 🆕 알림 이력 화면 추가
        "notification_history" -> GeofenceNotificationHistoryScreen(
            onBackToMain = { currentScreen = "main" },
            modifier = modifier
        )
    }
}

@Composable
fun MainScreen(
    onNavigateToSettings: () -> Unit,
    onNavigateToFirebaseTest: () -> Unit,
    onNavigateToLocation: () -> Unit,
    onNavigateToMap: () -> Unit,
    onNavigateToLocationMap: () -> Unit,
    onNavigateToGeofence: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("UserPrefs", Context.MODE_PRIVATE) }

    var savedUserId by remember { mutableStateOf("") }
    var isServiceRunning by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        savedUserId = prefs.getString("user_id", "") ?: ""
        Log.d("MainActivity", "Loaded user ID: $savedUserId")

        // 서비스 실행 상태 확인 (간접적)
        val autoStart = prefs.getBoolean("auto_start_service", true)
        isServiceRunning = savedUserId.isNotEmpty() && autoStart && PermissionManager.hasLocationPermission(context)
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "Location Tracker App",
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
            textAlign = TextAlign.Center
        )

        Text(
            text = "Penguin Location Tracker",
            fontSize = 16.sp,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 8.dp)
        )

        Spacer(modifier = Modifier.height(32.dp))

        // 권한 상태 표시
        Card(
            colors = CardDefaults.cardColors(
                containerColor = if (PermissionManager.hasAllRequiredPermissions(context))
                    MaterialTheme.colorScheme.primaryContainer
                else
                    MaterialTheme.colorScheme.errorContainer
            )
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "권한 상태",
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSecondaryContainer
                )
                Text(
                    text = if (PermissionManager.hasAllRequiredPermissions(context))
                        "모든 권한 허용됨"
                    else
                        "권한 확인 필요",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSecondaryContainer
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // 사용자 정보 및 서비스 상태 표시
        if (savedUserId.isNotEmpty()) {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = if (isServiceRunning)
                        MaterialTheme.colorScheme.primaryContainer
                    else
                        MaterialTheme.colorScheme.secondaryContainer
                )
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "현재 사용자",
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                    Text(
                        text = savedUserId,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    Text(
                        text = if (isServiceRunning) "백그라운드 추적 활성" else "추적 대기 중",
                        fontSize = 12.sp,
                        color = if (isServiceRunning)
                            MaterialTheme.colorScheme.primary
                        else
                            MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
        }

        Button(
            onClick = {
                Log.d("MainActivity", "Settings button clicked")
                onNavigateToSettings()
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = if (savedUserId.isEmpty()) "사용자 설정하기" else "사용자 설정 변경",
                fontSize = 16.sp
            )
        }

        Button(
            onClick = {
                Log.d("MainActivity", "Firebase test button clicked")
                onNavigateToFirebaseTest()
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Firebase 연결 테스트", fontSize = 16.sp)
        }

        Button(
            onClick = {
                Log.d("MainActivity", "Location button clicked")
                onNavigateToLocation()
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("위치 추적", fontSize = 16.sp)
        }

        Button(
            onClick = {
                Log.d("MainActivity", "Map button clicked")
                onNavigateToMap()
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("네이버 지도 테스트", fontSize = 16.sp)
        }

        Button(
            onClick = {
                Log.d("MainActivity", "Location map button clicked")
                onNavigateToLocationMap()
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("실시간 사용자 위치 지도", fontSize = 16.sp)
        }

        Button(
            onClick = onNavigateToGeofence,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("위치 알림 설정", fontSize = 16.sp)
        }

        Spacer(modifier = Modifier.height(32.dp))

        Text(
            text = "백그라운드 위치 추적 기능 및 권한 관리",
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.secondary,
            textAlign = TextAlign.Center
        )
    }
}

@Preview(showBackground = true)
@Composable
fun MainScreenPreview() {
    LocationTrackerTheme {
        MainScreen(
            onNavigateToSettings = {},
            onNavigateToFirebaseTest = {},
            onNavigateToLocation = {},
            onNavigateToMap = {},
            onNavigateToLocationMap = {},
            onNavigateToGeofence = {}
        )
    }
}