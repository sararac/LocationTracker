package com.penguin.locationtracker

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun SettingsMenuScreen(
    onBackToMain: () -> Unit,
    modifier: Modifier = Modifier
) {
    var currentMenu by remember { mutableStateOf("menu") }
    var selectedUserId by remember { mutableStateOf("") }

    when (currentMenu) {
        "menu" -> MainSettingsMenu(
            onNavigateToUserSettings = { currentMenu = "user" },
            onNavigateToFirebaseTest = { currentMenu = "firebase" },
            onNavigateToLocationTest = { currentMenu = "location" },
            onNavigateToMapTest = { currentMenu = "map" },
            onNavigateToGeofence = { currentMenu = "geofence" },
            onBackToMain = onBackToMain,
            modifier = modifier
        )
        "user" -> SettingsScreen(
            onBackToMain = { currentMenu = "menu" },
            modifier = modifier
        )
        "firebase" -> FirebaseTestScreen(
            onBackToMain = { currentMenu = "menu" },
            modifier = modifier
        )
        "location" -> LocationScreen(
            onBackToMain = { currentMenu = "menu" },
            modifier = modifier
        )
        "map" -> NaverMapScreen(
            onBackToMain = { currentMenu = "menu" },
            modifier = modifier
        )
        "geofence" -> GeofenceManagementScreen(
            onBackToMain = { currentMenu = "menu" },
            modifier = modifier
        )
    }
}

@Composable
private fun MainSettingsMenu(
    onNavigateToUserSettings: () -> Unit,
    onNavigateToFirebaseTest: () -> Unit,
    onNavigateToLocationTest: () -> Unit,
    onNavigateToMapTest: () -> Unit,
    onNavigateToGeofence: () -> Unit,
    onBackToMain: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Spacer(modifier = Modifier.height(32.dp))

        Text(
            text = "설정 및 테스트",
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )

        Spacer(modifier = Modifier.height(32.dp))

        Button(
            onClick = onNavigateToUserSettings,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("사용자 설정", fontSize = 16.sp)
        }

        Button(
            onClick = onNavigateToGeofence,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("위치 알림 설정", fontSize = 16.sp)
        }

        Divider()

        Text(
            text = "테스트 메뉴",
            fontSize = 18.sp,
            fontWeight = FontWeight.Medium
        )

        Button(
            onClick = onNavigateToFirebaseTest,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Firebase 연결 테스트", fontSize = 16.sp)
        }

        Button(
            onClick = onNavigateToLocationTest,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("위치 추적 테스트", fontSize = 16.sp)
        }

        Button(
            onClick = onNavigateToMapTest,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("네이버 지도 테스트", fontSize = 16.sp)
        }

        Spacer(modifier = Modifier.weight(1f))

        OutlinedButton(
            onClick = onBackToMain,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("메인 화면으로 돌아가기")
        }

        Text(
            text = "Location Tracker v1.0",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.secondary
        )
    }
}