package com.penguin.locationtracker

import android.util.Log
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.naver.maps.geometry.LatLng
import com.naver.maps.map.CameraPosition
import com.naver.maps.map.MapView
import com.naver.maps.map.NaverMap
import com.naver.maps.map.OnMapReadyCallback

@Composable
fun NaverMapScreen(
    onBackToMain: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var isMapReady by remember { mutableStateOf(false) }
    var mapStatusMessage by remember { mutableStateOf("지도를 초기화하는 중...") }
    var naverMapInstance by remember { mutableStateOf<NaverMap?>(null) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = "네이버 지도 테스트",
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )

        Card(
            colors = CardDefaults.cardColors(
                containerColor = if (isMapReady)
                    MaterialTheme.colorScheme.primaryContainer
                else
                    MaterialTheme.colorScheme.surfaceVariant
            )
        ) {
            Text(
                text = mapStatusMessage,
                modifier = Modifier.padding(16.dp),
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium
            )
        }

        // 네이버 지도 표시
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .height(400.dp)
        ) {
            AndroidView(
                factory = { context ->
                    MapView(context).apply {
                        getMapAsync(object : OnMapReadyCallback {
                            override fun onMapReady(naverMap: NaverMap) {
                                Log.d("NaverMap", "Map is ready")
                                naverMapInstance = naverMap
                                isMapReady = true
                                mapStatusMessage = "✅ 네이버 지도 로드 완료!"

                                // 서울 중심으로 카메라 설정
                                val cameraPosition = CameraPosition(
                                    LatLng(37.5666805, 126.9784147), // 서울 시청
                                    15.0 // 줌 레벨
                                )
                                naverMap.cameraPosition = cameraPosition

                                // 지도 타입 설정
                                naverMap.mapType = NaverMap.MapType.Basic

                                Log.d("NaverMap", "Camera position set to Seoul")
                            }
                        })
                    }
                },
                modifier = Modifier.fillMaxSize()
            ) { mapView ->
                // 컴포지션이 업데이트될 때마다 호출되는 부분
                // 필요시 지도 설정 업데이트
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = {
                    naverMapInstance?.let { map ->
                        // 부산으로 이동
                        val busanPosition = CameraPosition(
                            LatLng(35.1796, 129.0756), // 부산
                            13.0
                        )
                        map.cameraPosition = busanPosition
                        mapStatusMessage = "카메라가 부산으로 이동했습니다"
                    }
                },
                enabled = isMapReady,
                modifier = Modifier.weight(1f)
            ) {
                Text("부산", fontSize = 14.sp)
            }

            Button(
                onClick = {
                    naverMapInstance?.let { map ->
                        // 서울로 이동
                        val seoulPosition = CameraPosition(
                            LatLng(37.5666805, 126.9784147), // 서울
                            15.0
                        )
                        map.cameraPosition = seoulPosition
                        mapStatusMessage = "카메라가 서울로 이동했습니다"
                    }
                },
                enabled = isMapReady,
                modifier = Modifier.weight(1f)
            ) {
                Text("서울", fontSize = 14.sp)
            }

            Button(
                onClick = {
                    naverMapInstance?.let { map ->
                        // 지도 타입 변경
                        map.mapType = when (map.mapType) {
                            NaverMap.MapType.Basic -> NaverMap.MapType.Satellite
                            NaverMap.MapType.Satellite -> NaverMap.MapType.Hybrid
                            else -> NaverMap.MapType.Basic
                        }
                        val typeName = when (map.mapType) {
                            NaverMap.MapType.Basic -> "일반"
                            NaverMap.MapType.Satellite -> "위성"
                            NaverMap.MapType.Hybrid -> "하이브리드"
                            else -> "알 수 없음"
                        }
                        mapStatusMessage = "지도 타입: $typeName"
                    }
                },
                enabled = isMapReady,
                modifier = Modifier.weight(1f)
            ) {
                Text("타입", fontSize = 14.sp)
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
            text = "🗺️ 5단계: 네이버 지도 연동 및 테스트",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.secondary
        )
    }
}