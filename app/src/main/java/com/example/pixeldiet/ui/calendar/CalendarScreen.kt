package com.example.pixeldiet.ui.calendar

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.pixeldiet.model.AppUsage
import com.example.pixeldiet.ui.common.WrappedBarChart
import com.example.pixeldiet.ui.common.WrappedMaterialCalendar
import com.example.pixeldiet.viewmodel.SharedViewModel

@Composable
fun CalendarScreen(viewModel: SharedViewModel = viewModel()) {

    val decoratorData by viewModel.calendarDecoratorData.observeAsState(emptyList())
    val statsText by viewModel.calendarStatsText.observeAsState("")
    val streakText by viewModel.streakText.observeAsState("")
    val chartData by viewModel.chartData.observeAsState(emptyList())

    // 🔹 캘린더 필터용: 앱 목록 + 추적앱 목록
    val appList by viewModel.appUsageList.observeAsState(emptyList())
    val trackedPackages by viewModel.trackedPackages.observeAsState(emptySet())

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // 1. 스피너 (사용자가 선택한 앱 목록 기반)
        item {
            FilterSpinner(
                appList = appList,
                trackedPackages = trackedPackages,
                onFilterSelected = { pkgOrNull ->
                    viewModel.setCalendarFilter(pkgOrNull)
                }
            )
        }

        // 2. 캘린더
        item {
            Card(elevation = CardDefaults.cardElevation(2.dp)) {
                WrappedMaterialCalendar(
                    modifier = Modifier.fillMaxWidth(),
                    decoratorData = decoratorData
                )
            }
        }

        // 3. 안내 문구
        item {
            Text(
                statsText,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center,
                fontSize = 16.sp,
                color = MaterialTheme.colorScheme.secondary
            )
            Text(
                streakText,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center,
                fontSize = 16.sp,
                color = MaterialTheme.colorScheme.primary
            )
        }

        // 4. 그래프
        item {
            Card(
                Modifier
                    .fillMaxWidth()
                    .height(300.dp),
                elevation = CardDefaults.cardElevation(2.dp)
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text(
                        "이번 달 사용 시간",
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = TextAlign.Center,
                        fontSize = 16.sp
                    )
                    Spacer(Modifier.height(16.dp))

                    WrappedBarChart(
                        modifier = Modifier.fillMaxSize(),
                        chartData = chartData
                    )
                }
            }
        }
    }
}


// ----------------------
// FilterSpinner (이 부분은 동일)
// ----------------------
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FilterSpinner(
    appList: List<AppUsage>,
    trackedPackages: Set<String>,
    onFilterSelected: (String?) -> Unit      // null = 전체
) {
    // 🔹 스피너에 보여줄 앱들: 사용자가 선택한 추적앱만
    val trackedApps = remember(appList, trackedPackages) {
        if (trackedPackages.isEmpty()) {
            emptyList<AppUsage>()
        } else {
            appList.filter { it.packageName in trackedPackages }
                .sortedBy { it.appLabel.lowercase() }
        }
    }

    // (null, "전체") + (packageName, appLabel) 리스트
    val options: List<Pair<String?, String>> = remember(trackedApps) {
        buildList {
            add(null to "전체")
            trackedApps.forEach { app ->
                add(app.packageName to app.appLabel)
            }
        }
    }

    var expanded by remember { mutableStateOf(false) }
    var selectedText by remember { mutableStateOf(options.firstOrNull()?.second ?: "전체") }

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = !expanded }
    ) {
        OutlinedTextField(
            value = selectedText,
            onValueChange = {},
            readOnly = true,
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor()
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            options.forEach { (pkg, label) ->
                DropdownMenuItem(
                    text = { Text(label) },
                    onClick = {
                        selectedText = label
                        expanded = false
                        onFilterSelected(pkg)   // 🔹 null = 전체, 그 외 = packageName
                    }
                )
            }
        }
    }
}
