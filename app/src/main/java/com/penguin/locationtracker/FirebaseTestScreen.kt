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
import com.google.firebase.database.*
import com.google.firebase.database.ktx.database
import com.google.firebase.ktx.Firebase
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType

@Composable
fun FirebaseTestScreen(
    onBackToMain: () -> Unit,
    modifier: Modifier = Modifier
) {
    var testData by remember { mutableStateOf("") }
    var savedData by remember { mutableStateOf(listOf<String>()) }
    var isConnected by remember { mutableStateOf(false) }
    var statusMessage by remember { mutableStateOf("Firebase 연결 확인 중...") }
    var debugInfo by remember { mutableStateOf("") }

    val database = remember {
        try {
            Firebase.database.also {
                Log.d("Firebase", "Database instance created: ${it.reference.database.app.name}")
            }
        } catch (e: Exception) {
            Log.e("Firebase", "Error creating database instance", e)
            null
        }
    }

    val testRef = remember {
        database?.getReference("test")?.also {
            Log.d("Firebase", "Test reference created: ${it.key}")
        }
    }

    // Firebase 연결 상태 확인
    LaunchedEffect(Unit) {
        try {
            database?.let { db ->
                val connectedRef = db.getReference(".info/connected")
                debugInfo = "Database URL: ${db.reference.database.app.options.databaseUrl}\n"

                connectedRef.addValueEventListener(object : ValueEventListener {
                    override fun onDataChange(snapshot: DataSnapshot) {
                        val connected = snapshot.getValue(Boolean::class.java) ?: false
                        isConnected = connected
                        val connectionStatus = if (connected) "연결됨" else "연결 안됨"
                        statusMessage = if (connected) {
                            "✅ Firebase 연결됨"
                        } else {
                            "❌ Firebase 연결 안됨"
                        }
                        debugInfo += "Connection status: $connectionStatus\n"
                        Log.d("Firebase", "Connection status changed: $connected")
                    }

                    override fun onCancelled(error: DatabaseError) {
                        statusMessage = "❌ 연결 오류: ${error.message}"
                        debugInfo += "Connection error: ${error.message}\n"
                        Log.e("Firebase", "Connection cancelled", error.toException())
                    }
                })
            } ?: run {
                statusMessage = "❌ Database 초기화 실패"
                debugInfo += "Database initialization failed\n"
            }
        } catch (e: Exception) {
            statusMessage = "❌ 초기화 오류: ${e.message}"
            debugInfo += "Initialization error: ${e.message}\n"
            Log.e("Firebase", "Firebase initialization error", e)
        }
    }

// 실시간 데이터 읽기 (수정된 버전)
    LaunchedEffect(Unit) {
        testRef?.addValueEventListener(object : ValueEventListener {
            override fun onDataChange(dataSnapshot: DataSnapshot) {
                val dataList = mutableListOf<String>()
                for (snapshot in dataSnapshot.children) {
                    // 한글 처리 개선
                    val value = snapshot.getValue(String::class.java)
                    val key = snapshot.key
                    if (value != null && key != null) {
                        dataList.add("$key: $value")
                        Log.d("Firebase", "Data read - Key: $key, Value: $value")
                    }
                }
                savedData = dataList
                Log.d("Firebase", "Total data loaded: ${dataList.size} items")
            }

            override fun onCancelled(error: DatabaseError) {
                statusMessage = "데이터 읽기 오류: ${error.message}"
                Log.e("Firebase", "Data read cancelled", error.toException())
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
            text = "Firebase 연결 테스트",
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )

        Card(
            colors = CardDefaults.cardColors(
                containerColor = if (isConnected)
                    MaterialTheme.colorScheme.primaryContainer
                else
                    MaterialTheme.colorScheme.errorContainer
            )
        ) {
            Text(
                text = statusMessage,
                modifier = Modifier.padding(16.dp),
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium
            )
        }

        // 디버그 정보 표시 (기존 코드에 추가)
        if (debugInfo.isNotEmpty()) {
            Card {
                Column(
                    modifier = Modifier.padding(16.dp)
                ) {
                    Text(
                        text = "디버그 정보",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = debugInfo,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(top = 8.dp)
                    )

                    // 한글 테스트 추가
                    Text(
                        text = "한글 테스트: 안녕하세요 Firebase! 🇰🇷",
                        fontSize = 12.sp,
                        modifier = Modifier.padding(top = 8.dp),
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }

        OutlinedTextField(
            value = testData,
            onValueChange = { newValue ->
                testData = newValue
                Log.d("Firebase", "Input changed: $newValue") // 디버깅용
            },
            label = { Text("테스트 데이터 입력") },
            placeholder = { Text("한글과 영어를 입력해보세요") },
            modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Text,
                imeAction = ImeAction.Done
            ),
            keyboardActions = KeyboardActions(
                onDone = {
                    // Enter 키 눌렀을 때의 동작 (선택사항)
                }
            ),
            singleLine = false, // 여러 줄 입력 허용
            maxLines = 3
        )

        // 현재 입력 상태 표시 (디버깅용)
        if (testData.isNotEmpty()) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Column(
                    modifier = Modifier.padding(8.dp)
                ) {
                    Text(
                        text = "현재 입력: $testData",
                        fontSize = 12.sp
                    )
                    Text(
                        text = "글자 수: ${testData.length}",
                        fontSize = 12.sp
                    )
                }
            }
        }

        Button(
            onClick = {
                if (testData.trim().isNotEmpty()) {
                    val timestamp = System.currentTimeMillis()
                    val dataToSave = testData.trim()

                    Log.d("Firebase", "Saving data: $dataToSave")

                    testRef?.child("data_$timestamp")?.setValue(dataToSave)
                        ?.addOnSuccessListener {
                            Log.d("Firebase", "Data saved successfully: $dataToSave")
                        }
                        ?.addOnFailureListener { e ->
                            Log.e("Firebase", "Failed to save data: $dataToSave", e)
                        }
                    testData = ""
                }
            },
            modifier = Modifier.fillMaxWidth(),
            enabled = testRef != null && testData.trim().isNotEmpty()
        ) {
            Text("Firebase에 데이터 저장")
        }

        // 간단한 연결 테스트 버튼 추가
        Button(
            onClick = {
                testRef?.child("connection_test")?.setValue("테스트 ${System.currentTimeMillis()}")
                    ?.addOnSuccessListener {
                        Log.d("Firebase", "Connection test successful")
                    }
                    ?.addOnFailureListener { e ->
                        Log.e("Firebase", "Connection test failed", e)
                    }
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("연결 테스트")
        }

        if (savedData.isNotEmpty()) {
            Card {
                Column(
                    modifier = Modifier.padding(16.dp)
                ) {
                    Text(
                        text = "저장된 데이터 (실시간)",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                    LazyColumn(
                        modifier = Modifier.heightIn(max = 200.dp)
                    ) {
                        items(savedData) { data ->
                            Text(
                                text = data,
                                fontSize = 14.sp,
                                modifier = Modifier.padding(vertical = 2.dp)
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
            text = "🔥 3단계: Firebase Realtime Database",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.secondary
        )
    }
}