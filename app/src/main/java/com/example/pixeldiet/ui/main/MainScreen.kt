package com.example.pixeldiet.ui.main

import com.example.pixeldiet.model.AppUsage

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.example.pixeldiet.viewmodel.SharedViewModel
import java.text.SimpleDateFormat
import java.util.*

@Composable
fun MainScreen(viewModel: SharedViewModel = viewModel(),
               onAppSelectionClick: () -> Unit = {}   // ⭐ 추가
               ) {
    val appList by viewModel.appUsageList.observeAsState(emptyList())
    val totalUsage by viewModel.totalUsageData.observeAsState(Pair(0, 0))
    val trackedPackages by viewModel.trackedPackages.observeAsState(emptySet())
    var showGoalDialog by remember { mutableStateOf(false) }

    // ⭐ 실제로 화면에 보여줄 앱 목록 (추적앱만)
    val displayAppList = remember(appList, trackedPackages) {
        if (trackedPackages.isEmpty()) {
            // 🔹 아직 추적할 앱을 선택하지 않았을 때 → 아무 카드도 표시하지 않음
            emptyList()
        } else {
            // 🔹 선택된 앱들만 카드로 표시
            appList
                .filter { it.icon != null && it.packageName in trackedPackages }
                .sortedByDescending { it.currentUsage }   // ⭐ 여기 한 줄 추가
        }
    }


    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // 오늘 날짜
        item {
            val dateFormat = SimpleDateFormat("yyyy년 M월 d일", Locale.KOREAN)
            Text(text = dateFormat.format(Date()), fontSize = 16.sp, color = Color.Gray)
        }

        // ⭐ 앱 선택 화면으로 이동하는 버튼 (목표 시간 설정 버튼 위)
        item {
            Button(
                onClick = { onAppSelectionClick() },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                )
            ) {
                Text("추적할 앱 선택하기")
            }
        }

        // 목표 시간 설정 버튼
        item {
            Button(
                onClick = { showGoalDialog = true },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("목표 시간 설정")
            }
        }

        // 시각화 거품 뷰
        item {
            VisualNotification(
                displayAppList.sortedByDescending { it.currentUsage }  // ⭐ 사용시간 내림차순 정렬
            )
        }

        // 전체 사용 시간/목표 프로그레스
        item {
            TotalProgress(totalUsage.first, totalUsage.second)
        }

        // 개별 앱 카드 리스트 → displayAppList 사용
        items(
            displayAppList,
            key = { it.packageName }
        ) { app ->
            AppUsageCard(app)
        }
    }

    if (showGoalDialog) {
        GoalSettingDialog(
            appList = displayAppList,     // ⭐ 추적앱 기준으로만 목표 설정
            onDismiss = { showGoalDialog = false },
            onSave = { newGoals: Map<String, Int> ->
                viewModel.setGoalTimes(newGoals)
                showGoalDialog = false
            }
        )
    }
}

@Composable
fun VisualNotification(appList: List<AppUsage>) {
    val appsWithUsage = appList.filter { it.currentUsage > 0 }
    val maxUsage = appsWithUsage.maxOfOrNull { it.currentUsage }?.toFloat() ?: 1f

    if (appsWithUsage.isEmpty()) return

    Card(elevation = CardDefaults.cardElevation(2.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(200.dp)
                .horizontalScroll(rememberScrollState())
                .padding(24.dp),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            appsWithUsage.forEach { app ->
                val size = (40 + (app.currentUsage / maxUsage) * 100).dp

                if (app.icon != null) {
                    // 앱 아이콘을 거품 크기만큼 표시
                    AsyncImage(
                        model = app.icon,
                        contentDescription = app.appLabel,
                        modifier = Modifier.size(size)
                    )
                } else {
                    // 아이콘 없으면 단색 박스 폴백
                    Box(
                        modifier = Modifier
                            .size(size)
                            .background(Color.Gray)
                    )
                }
            }
        }
    }
}

@Composable
fun TotalProgress(totalUsage: Int, totalGoal: Int) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(2.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("총 사용시간", fontSize = 14.sp, color = Color.Gray)
                Row {
                    Text(
                        formatTime(totalUsage),
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(end = 16.dp)
                    )
                    Text(
                        "목표 ${formatTime(totalGoal)}",
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            val progress =
                if (totalGoal > 0) (totalUsage.toFloat() / totalGoal).coerceAtMost(1f) else 0f
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(12.dp)
            )
        }
    }
}

@Composable
fun GoalSettingDialog(
    appList: List<AppUsage>,
    onDismiss: () -> Unit,
    onSave: (Map<String, Int>) -> Unit    // ✅ key = packageName
) {
    // app.packageName -> (시간, 분) 문자열 상태
    val goalStates = remember(appList) {
        mutableStateMapOf<String, Pair<String, String>>().apply {
            appList.forEach { app ->
                val currentMinutes = app.goalTime
                val hours = (currentMinutes / 60).toString()
                val minutes = (currentMinutes % 60).toString()
                put(app.packageName, hours to minutes)
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("목표 시간 설정") },
        text = {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(appList, key = { it.packageName }) { app ->
                    val pkg = app.packageName
                    val (hours, minutes) = goalStates[pkg] ?: ("0" to "0")

                    Text(
                        app.appLabel,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(top = 8.dp)
                    )

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = hours,
                            onValueChange = { new ->
                                goalStates[pkg] = new.filter { it.isDigit() } to minutes
                            },
                            label = { Text("시간") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.weight(1f)
                        )
                        OutlinedTextField(
                            value = minutes,
                            onValueChange = { new ->
                                goalStates[pkg] = hours to new.filter { it.isDigit() }
                            },
                            label = { Text("분") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                val newGoals: Map<String, Int> = goalStates.mapValues { (_, hm) ->
                    val h = hm.first.toIntOrNull() ?: 0
                    val m = hm.second.toIntOrNull() ?: 0
                    h * 60 + m
                }
                onSave(newGoals)
            }) {
                Text("저장")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("취소")
            }
        }
    )
}

private fun formatTime(minutes: Int): String {
    val hours = minutes / 60
    val mins = minutes % 60
    return String.format("%d시간 %02d분", hours, mins)
}
