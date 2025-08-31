package com.penguin.locationtracker

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun PermissionRequestScreen(
    onPermissionsGranted: () -> Unit,
    onSkip: () -> Unit = {},
    showSkipButton: Boolean = false,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    var deniedPermissions by remember { mutableStateOf(PermissionManager.getDeniedPermissions(context)) }
    var permissionStatus by remember { mutableStateOf("권한을 확인하는 중...") }
    var hasCheckedInitially by remember { mutableStateOf(false) }
    var backgroundLocationRequested by remember { mutableStateOf(false) }

    // 권한 상태 업데이트
    fun updatePermissionStatus() {
        val denied = PermissionManager.getDeniedPermissions(context)
        deniedPermissions = denied

        if (denied.isEmpty()) {
            permissionStatus = "✅ 모든 권한이 허용되었습니다!"
            onPermissionsGranted()
        } else {
            permissionStatus = "${denied.size}개의 권한이 필요합니다"
        }
    }

    // 백그라운드 위치 권한 요청 런처 (Android 10+)
    val backgroundLocationLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        Log.d("PermissionRequest", "Background location permission result: $isGranted")
        updatePermissionStatus()
    }


    // 일반 권한 요청 런처 (위치 권한)
    val locationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        Log.d("PermissionRequest", "Location permissions result: $permissions")

        // 위치 권한 확인
        val locationGranted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
                permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true

        if (locationGranted && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            !PermissionManager.hasBackgroundLocationPermission(context) &&
            !backgroundLocationRequested) {

            // 위치 권한은 허용되었지만 백그라운드 권한이 아직 없는 경우
            backgroundLocationRequested = true
            backgroundLocationLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        } else {
            updatePermissionStatus()
        }
    }

    // 알림 권한 요청 런처 (Android 13+)
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        Log.d("PermissionRequest", "Notification permission result: $isGranted")
        updatePermissionStatus()
    }

    // 초기 권한 상태 확인
    LaunchedEffect(Unit) {
        if (!hasCheckedInitially) {
            updatePermissionStatus()
            hasCheckedInitially = true
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp)
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        // 앱 아이콘 또는 이미지
        Text(
            text = "📍",
            fontSize = 72.sp,
            modifier = Modifier.padding(bottom = 24.dp)
        )

        Text(
            text = "Location Tracker",
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
            textAlign = TextAlign.Center
        )

        Text(
            text = "위치 추적을 위한 권한이 필요합니다",
            fontSize = 16.sp,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 8.dp, bottom = 32.dp)
        )

        // 상태 메시지
        Card(
            colors = CardDefaults.cardColors(
                containerColor = if (deniedPermissions.isEmpty()) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    MaterialTheme.colorScheme.errorContainer
                }
            ),
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 24.dp)
        ) {
            Text(
                text = permissionStatus,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(16.dp)
            )
        }

        // 필요한 권한 목록
        if (deniedPermissions.isNotEmpty()) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 24.dp)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp)
                ) {
                    Text(
                        text = "필요한 권한",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(bottom = 12.dp)
                    )

                    LazyColumn(
                        modifier = Modifier.heightIn(max = 200.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(deniedPermissions) { permission ->
                            PermissionItem(
                                permission = permission,
                                isGranted = false
                            )
                        }
                    }
                }
            }
        }

        // 권한 요청 버튼들
        if (deniedPermissions.isNotEmpty()) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {

                // 위치 권한 요청 버튼
                if (!PermissionManager.hasLocationPermission(context)) {
                    Button(
                        onClick = {
                            Log.d("PermissionRequest", "Requesting location permissions")
                            locationPermissionLauncher.launch(
                                arrayOf(
                                    Manifest.permission.ACCESS_FINE_LOCATION,
                                    Manifest.permission.ACCESS_COARSE_LOCATION
                                )
                            )
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("위치 권한 허용", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    }
                }

                // 백그라운드 위치 권한 요청 버튼 (이미 위치 권한이 있는 경우)
                if (PermissionManager.hasLocationPermission(context) &&
                    !PermissionManager.hasBackgroundLocationPermission(context)) {
                    Button(
                        onClick = {
                            Log.d("PermissionRequest", "Requesting background location permission")
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                                backgroundLocationLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("백그라운드 위치 권한 허용", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    }
                }

                // 알림 권한 요청 버튼
                if (!PermissionManager.hasNotificationPermission(context)) {
                    Button(
                        onClick = {
                            Log.d("PermissionRequest", "Requesting notification permission")
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("알림 권한 허용", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    }
                }

                // 모든 권한 한번에 요청 버튼
                Button(
                    onClick = {
                        Log.d("PermissionRequest", "Requesting all permissions")
                        val permissionsToRequest = mutableListOf<String>()

                        if (!PermissionManager.hasLocationPermission(context)) {
                            permissionsToRequest.add(Manifest.permission.ACCESS_FINE_LOCATION)
                            permissionsToRequest.add(Manifest.permission.ACCESS_COARSE_LOCATION)
                        }

                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                            !PermissionManager.hasNotificationPermission(context)) {
                            permissionsToRequest.add(Manifest.permission.POST_NOTIFICATIONS)
                        }

                        if (permissionsToRequest.isNotEmpty()) {
                            locationPermissionLauncher.launch(permissionsToRequest.toTypedArray())
                        }
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("모든 권한 허용", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                }

                // 설정으로 이동 버튼
                OutlinedButton(
                    onClick = {
                        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                            data = Uri.fromParts("package", context.packageName, null)
                        }
                        context.startActivity(intent)
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("설정에서 권한 허용", fontSize = 14.sp)
                }
            }
        }

        // 건너뛰기 버튼 (선택사항)
        if (showSkipButton) {
            Spacer(modifier = Modifier.height(24.dp))

            TextButton(
                onClick = {
                    Log.d("PermissionRequest", "Skip button clicked")
                    onSkip()
                }
            ) {
                Text(
                    text = "나중에 설정하기",
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // 앱 정보
        Spacer(modifier = Modifier.height(32.dp))

        Text(
            text = "이 앱은 사용자 간 실시간 위치 공유를 위해 위치 정보를 사용합니다.",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            lineHeight = 16.sp
        )
    }
}

@Composable
private fun PermissionItem(
    permission: String,
    isGranted: Boolean
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (isGranted) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            }
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = PermissionManager.getPermissionDisplayName(permission),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = PermissionManager.getPermissionDescription(permission),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    lineHeight = 16.sp
                )
            }

            Text(
                text = if (isGranted) "✅" else "❌",
                fontSize = 16.sp,
                modifier = Modifier.padding(start = 8.dp)
            )
        }
    }
}