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

        // 앱 시작 시 백그라운드 서비스 자동 시작
        startLocationServiceIfEnabled()

        enableEdgeToEdge()
        setContent {
            LocationTrackerTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    LocationTrackerApp(
                        modifier = Modifier.padding(innerPadding)
                    )
                }
            }
        }
    }

    // 설정에 따라 위치 추적 서비스 자동 시작
    private fun startLocationServiceIfEnabled() {
        val prefs = getSharedPreferences("UserPrefs", Context.MODE_PRIVATE)
        val userId = prefs.getString("user_id", "") ?: ""
        val autoStart = prefs.getBoolean("auto_start_service", true)

        Log.d("MainActivity", "Auto start check - User ID: $userId, Auto start: $autoStart")

        if (userId.isNotEmpty() && autoStart) {
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
        // 앱이 포그라운드로 올라올 때마다 서비스 상태 확인 및 시작
        startLocationServiceIfEnabled()
    }
}

@Composable
fun LocationTrackerApp(modifier: Modifier = Modifier) {
    var currentScreen by remember { mutableStateOf("main") }
    var selectedUserId by remember { mutableStateOf("") }
    var selectedLatitude by remember { mutableStateOf<Double?>(null) }
    var selectedLongitude by remember { mutableStateOf<Double?>(null) }
    var showGeofenceDialog by remember { mutableStateOf(false) }

    when (currentScreen) {
        "main" -> MainMapScreen(
            onNavigateToSettings = { currentScreen = "settings" },
            onShowUserHistory = { userId ->
                selectedUserId = userId
                currentScreen = "userhistorymap"
            },
            onNavigateToGeofence = { latitude, longitude ->
                selectedLatitude = latitude
                selectedLongitude = longitude
                showGeofenceDialog = true
                currentScreen = "geofence"
            },
            modifier = modifier
        )
        "settings" -> SettingsMenuScreen(
            onBackToMain = { currentScreen = "main" },
            modifier = modifier
        )
        "userhistorymap" -> UserLocationHistoryMapScreen(
            userId = selectedUserId,
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
        isServiceRunning = savedUserId.isNotEmpty() && autoStart
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
            text = "백그라운드 위치 추적 기능 추가 완료",
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