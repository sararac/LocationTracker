package com.penguin.locationtracker

import android.content.Context
import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.SimpleDateFormat
import java.util.*
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GeofenceNotificationHistoryScreen(
    onBackToMain: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("UserPrefs", Context.MODE_PRIVATE) }
    val currentUserId = remember { prefs.getString("user_id", "") ?: "" }
    val coroutineScope = rememberCoroutineScope()

    var notificationHistory by remember { mutableStateOf(listOf<GeofenceNotificationData>()) }
    var isLoading by remember { mutableStateOf(true) }
    var statusMessage by remember { mutableStateOf("알림 이력을 불러오는 중...") }
    var filterType by remember { mutableStateOf("all") } // "all", "enter", "exit", "unread"
    var selectedNotification by remember { mutableStateOf<GeofenceNotificationData?>(null) }
    var showDetailDialog by remember { mutableStateOf(false) }
    var unreadCount by remember { mutableStateOf(0) }

    val historyManager = remember { GeofenceNotificationHistoryManager(context) }
    val listState = rememberLazyListState()

    // 필터링된 알림 목록
    val filteredNotifications = remember(notificationHistory, filterType) {
        when (filterType) {
            "enter" -> notificationHistory.filter { it.transitionType == "ENTER" }
            "exit" -> notificationHistory.filter { it.transitionType == "EXIT" }
            "unread" -> notificationHistory.filter { !it.isRead }
            else -> notificationHistory
        }
    }

    // 알림 이력 로드
    LaunchedEffect(currentUserId) {
        if (currentUserId.isNotEmpty()) {
            Log.d("NotificationHistory", "Loading notification history for: $currentUserId")

            historyManager.getNotificationHistory(currentUserId) { notifications ->
                notificationHistory = notifications
                isLoading = false
                statusMessage = if (notifications.isEmpty()) {
                    "알림 이력이 없습니다"
                } else {
                    "${notifications.size}개의 알림 이력"
                }
            }

            // 읽지 않은 알림 개수 조회
            historyManager.getUnreadCount(currentUserId) { count ->
                unreadCount = count
            }
        } else {
            statusMessage = "사용자 ID를 먼저 설정해주세요"
            isLoading = false
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // 헤더
        Text(
            text = "위치 알림 이력",
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )

        // 현재 사용자 표시
        if (currentUserId.isNotEmpty()) {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer
                )
            ) {
                Text(
                    text = "사용자: $currentUserId ${if (unreadCount > 0) "• 읽지 않음: ${unreadCount}개" else ""}",
                    modifier = Modifier.padding(12.dp),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        }

        // 상태 메시지
        Card(
            colors = CardDefaults.cardColors(
                containerColor = when {
                    isLoading -> MaterialTheme.colorScheme.surfaceVariant
                    filteredNotifications.isNotEmpty() -> MaterialTheme.colorScheme.primaryContainer
                    else -> MaterialTheme.colorScheme.errorContainer
                }
            )
        ) {
            Row(
                modifier = Modifier.padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                }
                Text(
                    text = statusMessage,
                    fontSize = 13.sp
                )
            }
        }

        // 필터 버튼들
        if (notificationHistory.isNotEmpty()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                FilterButton(
                    text = "전체 (${notificationHistory.size})",
                    isSelected = filterType == "all",
                    onClick = { filterType = "all" },
                    modifier = Modifier.weight(1f)
                )

                FilterButton(
                    text = "도착 (${notificationHistory.count { it.transitionType == "ENTER" }})",
                    isSelected = filterType == "enter",
                    onClick = { filterType = "enter" },
                    modifier = Modifier.weight(1f)
                )

                FilterButton(
                    text = "출발 (${notificationHistory.count { it.transitionType == "EXIT" }})",
                    isSelected = filterType == "exit",
                    onClick = { filterType = "exit" },
                    modifier = Modifier.weight(1f)
                )

                FilterButton(
                    text = "미읽음 ($unreadCount)",
                    isSelected = filterType == "unread",
                    onClick = { filterType = "unread" },
                    modifier = Modifier.weight(1f)
                )
            }
        }

        // 알림 이력 리스트
        if (filteredNotifications.isNotEmpty()) {
            Card {
                Column(
                    modifier = Modifier.padding(8.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "필터 결과: ${filteredNotifications.size}개",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold
                        )

                        if (unreadCount > 0) {
                            TextButton(
                                onClick = {
                                    // 모든 알림을 읽음으로 표시
                                    filteredNotifications.filter { !it.isRead }.forEach { notification ->
                                        historyManager.markAsRead(notification.id)
                                    }
                                }
                            ) {
                                Text("모두 읽음", fontSize = 11.sp)
                            }
                        }
                    }

                    LazyColumn(
                        state = listState,
                        modifier = Modifier.heightIn(max = 400.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        items(filteredNotifications) { notification ->
                            NotificationHistoryItem(
                                notification = notification,
                                onClick = {
                                    selectedNotification = notification
                                    showDetailDialog = true

                                    // 읽지 않은 알림이면 읽음으로 표시
                                    if (!notification.isRead) {
                                        historyManager.markAsRead(notification.id)
                                        // 읽지 않은 개수 다시 조회
                                        historyManager.getUnreadCount(currentUserId) { count ->
                                            unreadCount = count
                                        }
                                    }
                                }
                            )
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.weight(1f))

        // 하단 버튼들
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedButton(
                onClick = onBackToMain,
                modifier = Modifier.weight(1f)
            ) {
                Text("메인으로")
            }

            if (filteredNotifications.isNotEmpty()) {
                Button(
                    onClick = {
                        // 리스트 맨 위로 이동
                        coroutineScope.launch {
                            listState.animateScrollToItem(0)
                        }
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Text("최신으로")
                }
            }
        }

        Text(
            text = "📍 지오펜스 알림 이력",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.secondary
        )
    }

    // 상세 정보 다이얼로그
    if (showDetailDialog && selectedNotification != null) {
        NotificationDetailDialog(
            notification = selectedNotification!!,
            onDismiss = {
                showDetailDialog = false
                selectedNotification = null
            },
            onDelete = { notification ->
                historyManager.deleteNotification(notification.id) { success ->
                    if (success) {
                        showDetailDialog = false
                        selectedNotification = null
                        // 목록 새로고침
                        historyManager.getNotificationHistory(currentUserId) { notifications ->
                            notificationHistory = notifications
                        }
                    }
                }
            }
        )
    }
}

@Composable
private fun FilterButton(
    text: String,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (isSelected) {
        Button(
            onClick = onClick,
            modifier = modifier.height(32.dp),
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
        ) {
            Text(text, fontSize = 10.sp, maxLines = 1)
        }
    } else {
        OutlinedButton(
            onClick = onClick,
            modifier = modifier.height(32.dp),
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
        ) {
            Text(text, fontSize = 10.sp, maxLines = 1)
        }
    }
}

@Composable
private fun NotificationHistoryItem(
    notification: GeofenceNotificationData,
    onClick: () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (!notification.isRead) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            }
        ),
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f)
            ) {
                // 읽지 않은 알림 표시
                if (!notification.isRead) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                }

                // 알림 타입 아이콘
                Text(
                    text = notification.getTransitionIcon(),
                    fontSize = 16.sp
                )

                Spacer(modifier = Modifier.width(8.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = notification.getNotificationTitle(),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )

                    Spacer(modifier = Modifier.height(2.dp))

                    Text(
                        text = notification.getLocationSummary(),
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Column(
                horizontalAlignment = Alignment.End
            ) {
                Text(
                    text = notification.getSimpleTime(),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.primary
                )

                Text(
                    text = notification.getDaysAgo(),
                    fontSize = 9.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun NotificationDetailDialog(
    notification: GeofenceNotificationData,
    onDismiss: () -> Unit,
    onDelete: (GeofenceNotificationData) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = notification.getTransitionIcon(),
                    fontSize = 20.sp
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = notification.getNotificationTitle(),
                    fontSize = 16.sp
                )
            }
        },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = notification.getNotificationMessage(),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium
                )

                HorizontalDivider()

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "시간",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = notification.getFormattedTime(),
                        fontSize = 12.sp
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "장소",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = notification.geofenceName,
                        fontSize = 12.sp
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "사용자",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "${notification.targetUserId} → ${notification.notifyUserId}",
                        fontSize = 12.sp
                    )
                }

                if (notification.address.isNotEmpty()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "주소",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = notification.address,
                            fontSize = 12.sp,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "위치",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "%.6f, %.6f".format(notification.latitude, notification.longitude),
                        fontSize = 12.sp
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "상태",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = if (notification.isRead) "읽음" else "읽지 않음",
                        fontSize = 12.sp,
                        color = if (notification.isRead)
                            MaterialTheme.colorScheme.onSurfaceVariant
                        else
                            MaterialTheme.colorScheme.primary
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("확인")
            }
        },
        dismissButton = {
            TextButton(
                onClick = { onDelete(notification) },
                colors = ButtonDefaults.textButtonColors(
                    contentColor = MaterialTheme.colorScheme.error
                )
            ) {
                Text("삭제")
            }
        }
    )
}