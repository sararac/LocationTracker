package com.penguin.locationtracker

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
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

    var userId by remember { mutableStateOf(prefs.getString("user_id", "") ?: "") }
    var trackingInterval by remember { mutableStateOf(prefs.getInt("tracking_interval", 1).toString()) }
    var dataRetentionDays by remember { mutableStateOf(prefs.getInt("data_retention_days", 1).toString()) }
    var showSavedMessage by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Spacer(modifier = Modifier.height(32.dp))

        Text(
            text = "사용자 설정",
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )

        Spacer(modifier = Modifier.height(32.dp))

        // 사용자 ID 설정
        Text(
            text = "사용자 아이디",
            fontSize = 18.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.fillMaxWidth()
        )

        OutlinedTextField(
            value = userId,
            onValueChange = {
                userId = it
                showSavedMessage = false
            },
            label = { Text("아이디를 입력하세요") },
            modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
            singleLine = true
        )

        Spacer(modifier = Modifier.height(16.dp))

        // 위치 추적 주기 설정 부분 수정
        Text(
            text = "위치 추적 주기",
            fontSize = 18.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.fillMaxWidth()
        )

        OutlinedTextField(
            value = trackingInterval,
            onValueChange = {
                trackingInterval = it.filter { char -> char.isDigit() }
                showSavedMessage = false
            },
            label = { Text("초 단위 (최소 10초)") },
            placeholder = { Text("10") },
            modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            singleLine = true,
            suffix = { Text("초") }
        )

        Spacer(modifier = Modifier.height(16.dp))

        // 데이터 보관 기간 설정
        Text(
            text = "위치 데이터 보관 기간",
            fontSize = 18.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.fillMaxWidth()
        )

        OutlinedTextField(
            value = dataRetentionDays,
            onValueChange = {
                dataRetentionDays = it.filter { char -> char.isDigit() }
                showSavedMessage = false
            },
            label = { Text("일 단위 (최소 1일)") },
            placeholder = { Text("1") },
            modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            singleLine = true,
            suffix = { Text("일") }
        )

        Spacer(modifier = Modifier.height(24.dp))

        // 저장 버튼의 검증 로직 수정
        Button(
            onClick = {
                val userIdValue = userId.trim()
                val intervalValue = trackingInterval.toIntOrNull() ?: 10
                val retentionValue = dataRetentionDays.toIntOrNull() ?: 1

                if (userIdValue.isNotEmpty()) {
                    prefs.edit().apply {
                        putString("user_id", userIdValue)
                        putInt("tracking_interval", maxOf(10, intervalValue)) // 최소 10초
                        putInt("data_retention_days", maxOf(1, retentionValue))
                    }.apply()
                    showSavedMessage = true
                }
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("설정 저장", fontSize = 16.sp)
        }

        // 저장 완료 메시지 수정
        if (showSavedMessage) {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
            ) {
                Column(
                    modifier = Modifier.padding(16.dp)
                ) {
                    Text(
                        text = "설정이 저장되었습니다!",
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "위치 추적 주기: ${maxOf(10, trackingInterval.toIntOrNull() ?: 10)}초",
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                    Text(
                        text = "데이터 보관 기간: ${maxOf(1, dataRetentionDays.toIntOrNull() ?: 1)}일",
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }
        }

        Spacer(modifier = Modifier.weight(1f))

        OutlinedButton(
            onClick = onBackToMain,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("돌아가기")
        }

        Text(
            text = "설정 및 자동 위치 추적",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.secondary
        )
    }
}