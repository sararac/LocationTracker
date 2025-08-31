package com.penguin.locationtracker

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun SettingsScreen(
    onBackToMain: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("UserPrefs", Context.MODE_PRIVATE) }
    val scrollState = rememberScrollState()

    var userId by remember { mutableStateOf(prefs.getString("user_id", "") ?: "") }
    var trackingInterval by remember { mutableStateOf(prefs.getInt("tracking_interval", 10).toString()) }
    var dataRetentionDays by remember { mutableStateOf(prefs.getInt("data_retention_days", 7).toString()) }
    var locationThreshold by remember { mutableStateOf(prefs.getInt("location_threshold", 10).toString()) } // 새로 추가
    var autoStart by remember { mutableStateOf(prefs.getBoolean("auto_start_service", true)) }
    var autoRestart by remember { mutableStateOf(prefs.getBoolean("auto_restart_service", true)) }
    var showSavedMessage by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = "사용자 설정",
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(top = 8.dp, bottom = 12.dp)
        )

        // 사용자 ID 설정
        Text(
            text = "사용자 아이디",
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.fillMaxWidth()
        )

        OutlinedTextField(
            value = userId,
            onValueChange = {
                userId = it
                showSavedMessage = false
            },
            label = { Text("아이디", fontSize = 12.sp) },
            modifier = Modifier.fillMaxWidth(),
            textStyle = androidx.compose.ui.text.TextStyle(fontSize = 14.sp),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
            singleLine = true,
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = MaterialTheme.colorScheme.onSurface,
                unfocusedTextColor = MaterialTheme.colorScheme.onSurface
            )
        )

        Spacer(modifier = Modifier.height(4.dp))

        // 위치 추적 주기 설정
        Text(
            text = "위치 추적 주기",
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.fillMaxWidth()
        )

        OutlinedTextField(
            value = trackingInterval,
            onValueChange = { newValue ->
                trackingInterval = newValue.filter { char -> char.isDigit() }
                showSavedMessage = false
            },
            label = { Text("초 (최소 10초)", fontSize = 12.sp) },
            modifier = Modifier.fillMaxWidth(),
            textStyle = androidx.compose.ui.text.TextStyle(fontSize = 14.sp),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            singleLine = true,
            suffix = { Text("초", fontSize = 12.sp) },
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = MaterialTheme.colorScheme.onSurface,
                unfocusedTextColor = MaterialTheme.colorScheme.onSurface
            )
        )

        Spacer(modifier = Modifier.height(4.dp))

        // 위치 변동 감지 기준 설정 - 새로 추가
        Text(
            text = "위치 변동 감지 기준",
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.fillMaxWidth()
        )

        OutlinedTextField(
            value = locationThreshold,
            onValueChange = { newValue ->
                locationThreshold = newValue.filter { char -> char.isDigit() }
                showSavedMessage = false
            },
            label = { Text("미터 (최소 5미터)", fontSize = 12.sp) },
            modifier = Modifier.fillMaxWidth(),
            textStyle = androidx.compose.ui.text.TextStyle(fontSize = 14.sp),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            singleLine = true,
            suffix = { Text("m", fontSize = 12.sp) },
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = MaterialTheme.colorScheme.onSurface,
                unfocusedTextColor = MaterialTheme.colorScheme.onSurface
            )
        )

        Text(
            text = "이 거리 이내에서는 같은 위치로 판단하여 머문 시간을 누적합니다",
            fontSize = 10.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 8.dp)
        )

        Spacer(modifier = Modifier.height(4.dp))

        // 데이터 보관 기간 설정
        Text(
            text = "위치 데이터 보관 기간",
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.fillMaxWidth()
        )

        OutlinedTextField(
            value = dataRetentionDays,
            onValueChange = { newValue ->
                dataRetentionDays = newValue.filter { char -> char.isDigit() }
                showSavedMessage = false
            },
            label = { Text("일 (최소 1일)", fontSize = 12.sp) },
            modifier = Modifier.fillMaxWidth(),
            textStyle = androidx.compose.ui.text.TextStyle(fontSize = 14.sp),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            singleLine = true,
            suffix = { Text("일", fontSize = 12.sp) },
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = MaterialTheme.colorScheme.onSurface,
                unfocusedTextColor = MaterialTheme.colorScheme.onSurface
            )
        )

        Spacer(modifier = Modifier.height(8.dp))

        // 백그라운드 실행 설정 섹션
        Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer
            )
        ) {
            Column(
                modifier = Modifier.padding(12.dp)
            ) {
                Text(
                    text = "백그라운드 실행 설정",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )

                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "부팅 시 자동 시작",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            text = "폰 켜면 자동으로 위치 추적 시작",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = autoStart,
                        onCheckedChange = {
                            autoStart = it
                            showSavedMessage = false
                        }
                    )
                }

                Spacer(modifier = Modifier.height(6.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "앱 종료 시 자동 재시작",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            text = "앱 종료되어도 백그라운드에서 계속 실행",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = autoRestart,
                        onCheckedChange = {
                            autoRestart = it
                            showSavedMessage = false
                        }
                    )
                }
            }
        }

        // 배터리 최적화 해제 버튼
        Button(
            onClick = {
                requestBatteryOptimizationExemption(context)
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(42.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.secondary
            )
        ) {
            Text("배터리 최적화 해제 요청", fontSize = 13.sp)
        }

        Text(
            text = "배터리 최적화를 해제하면 백그라운드에서 더 안정적으로 실행됩니다",
            fontSize = 10.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 8.dp)
        )

        // 저장 버튼
        Button(
            onClick = {
                val userIdValue = userId.trim()
                val intervalValue = trackingInterval.toIntOrNull() ?: 10
                val retentionValue = dataRetentionDays.toIntOrNull() ?: 7
                val thresholdValue = locationThreshold.toIntOrNull() ?: 10 // 새로 추가

                if (userIdValue.isNotEmpty()) {
                    prefs.edit().apply {
                        putString("user_id", userIdValue)
                        putInt("tracking_interval", maxOf(10, intervalValue))
                        putInt("data_retention_days", maxOf(1, retentionValue))
                        putInt("location_threshold", maxOf(5, thresholdValue)) // 새로 추가
                        putBoolean("auto_start_service", autoStart)
                        putBoolean("auto_restart_service", autoRestart)
                    }.apply()
                    showSavedMessage = true

                    Log.d("Settings", "Settings saved - Location threshold: ${maxOf(5, thresholdValue)}m")
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
        ) {
            Text("설정 저장", fontSize = 14.sp, fontWeight = FontWeight.Bold)
        }

        // 저장 완료 메시지
        if (showSavedMessage) {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
            ) {
                Column(
                    modifier = Modifier.padding(12.dp)
                ) {
                    Text(
                        text = "설정이 저장되었습니다!",
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp
                    )
                    Text(
                        text = "추적 주기: ${maxOf(10, trackingInterval.toIntOrNull() ?: 10)}초",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                    Text(
                        text = "위치 변동 기준: ${maxOf(5, locationThreshold.toIntOrNull() ?: 10)}m", // 새로 추가
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                    Text(
                        text = "보관 기간: ${maxOf(1, dataRetentionDays.toIntOrNull() ?: 7)}일",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                    Text(
                        text = "자동 시작: ${if (autoStart) "활성화" else "비활성화"}",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                    Text(
                        text = "자동 재시작: ${if (autoRestart) "활성화" else "비활성화"}",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }
        }

        OutlinedButton(
            onClick = onBackToMain,
            modifier = Modifier
                .fillMaxWidth()
                .height(42.dp)
        ) {
            Text("돌아가기", fontSize = 13.sp)
        }

        Text(
            text = "설정 및 자동 위치 추적",
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.secondary,
            modifier = Modifier.padding(bottom = 16.dp)
        )
    }
}

// 배터리 최적화 해제 요청 함수
@SuppressLint("BatteryLife")
private fun requestBatteryOptimizationExemption(context: Context) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
        val intent = Intent().apply {
            action = Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS
            data = Uri.parse("package:${context.packageName}")
        }
        try {
            context.startActivity(intent)
            Log.d("Settings", "Battery optimization exemption requested")
        } catch (e: Exception) {
            Log.e("Settings", "Cannot open battery optimization settings", e)

            // 대안: 배터리 설정 화면으로 이동
            try {
                val fallbackIntent = Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS)
                context.startActivity(fallbackIntent)
            } catch (e2: Exception) {
                Log.e("Settings", "Cannot open battery settings", e2)
            }
        }
    }
}