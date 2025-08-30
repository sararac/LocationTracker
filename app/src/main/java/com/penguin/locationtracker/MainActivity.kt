package com.penguin.locationtracker

import android.content.Context
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
}

@Composable
fun LocationTrackerApp(modifier: Modifier = Modifier) {
    var currentScreen by remember { mutableStateOf("main") }
    var selectedUserId by remember { mutableStateOf("") }
    var selectedLatitude by remember { mutableStateOf<Double?>(null) }
    var selectedLongitude by remember { mutableStateOf<Double?>(null) }
    var showGeofenceDialog by remember { mutableStateOf(false) } // 추가

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
                showGeofenceDialog = true // 다이얼로그 표시
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
            autoShowDialog = showGeofenceDialog, // 추가
            onDialogShown = { showGeofenceDialog = false }, // 추가
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
    onNavigateToGeofence: () -> Unit, // 새로 추가
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("UserPrefs", Context.MODE_PRIVATE) }

    var savedUserId by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        savedUserId = prefs.getString("user_id", "") ?: ""
        Log.d("MainActivity", "Loaded user ID: $savedUserId")
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

        if (savedUserId.isNotEmpty()) {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer
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

        // 새로 추가되는 버튼
        Button(
            onClick = onNavigateToGeofence,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("위치 알림 설정", fontSize = 16.sp)
        }

        Spacer(modifier = Modifier.height(32.dp))

        Text(
            text = "🔔 9단계: 지정 장소 도착/출발 알림 추가",
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
            onNavigateToGeofence = {} // 새로 추가
        )
    }
}