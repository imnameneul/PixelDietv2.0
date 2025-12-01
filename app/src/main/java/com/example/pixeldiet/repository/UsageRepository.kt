package com.example.pixeldiet.repository

import android.app.usage.UsageStats
import android.app.usage.UsageStatsManager
import android.content.Context
import android.graphics.drawable.Drawable
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.example.pixeldiet.model.AppUsage
import com.example.pixeldiet.model.DailyUsage
import com.example.pixeldiet.model.NotificationSettings
import java.text.SimpleDateFormat
import java.util.*

object UsageRepository {

    // ⭐️ SharedPreferences 인스턴스를 저장할 변수 (알림 설정용)
    private var prefs: NotificationPrefs? = null

    private val _appUsageList = MutableLiveData<List<AppUsage>>()
    val appUsageList: LiveData<List<AppUsage>> = _appUsageList

    private val _dailyUsageList = MutableLiveData<List<DailyUsage>>()
    val dailyUsageList: LiveData<List<DailyUsage>> = _dailyUsageList

    private val _notificationSettings = MutableLiveData<NotificationSettings>()
    val notificationSettings: LiveData<NotificationSettings> = _notificationSettings

    // ⭐️ 목표 시간: key = packageName
    private val currentGoals = mutableMapOf<String, Int>()

    // ⭐️ init: 처음에는 빈 리스트로 시작
    init {
        _appUsageList.postValue(emptyList())
    }

    // ---------------- 알림 설정 ----------------

    // SharedPreferences 초기화
    private fun getPrefs(context: Context): NotificationPrefs {
        if (prefs == null) {
            prefs = NotificationPrefs(context.applicationContext)
            // 초기화 시, SharedPreferences에서 설정을 불러와 LiveData에 반영
            _notificationSettings.postValue(prefs!!.loadNotificationSettings())
        }
        return prefs!!
    }

    // 알림 설정 저장 + LiveData 반영
    fun updateNotificationSettings(settings: NotificationSettings, context: Context) {
        getPrefs(context).saveNotificationSettings(settings)
        _notificationSettings.postValue(settings)
    }

    // ---------------- 목표 시간 업데이트 ----------------

    // goals: key = packageName
    fun updateGoalTimes(goals: Map<String, Int>) {
        currentGoals.clear()
        currentGoals.putAll(goals)

        val currentList = _appUsageList.value ?: emptyList()
        val newList = currentList.map { usage ->
            usage.copy(goalTime = currentGoals[usage.packageName] ?: 0)
        }
        _appUsageList.postValue(newList)
    }

    // ---------------- 실제 데이터 로딩 ----------------

    suspend fun loadRealData(context: Context) {
        val prefs = getPrefs(context)

        val usageStatsManager =
            context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val packageManager = context.packageManager

        // 1. 현재 기본 런처(홈 화면) 패키지명 알아내기
        val intent = android.content.Intent(android.content.Intent.ACTION_MAIN)
        intent.addCategory(android.content.Intent.CATEGORY_HOME)
        val resolveInfo = packageManager.resolveActivity(intent, android.content.pm.PackageManager.MATCH_DEFAULT_ONLY)
        val launcherPackage = resolveInfo?.activityInfo?.packageName

        val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.KOREAN)
        val calendar = Calendar.getInstance()

// --- 1. 오늘 사용량 계산 ---
        calendar.set(Calendar.HOUR_OF_DAY, 0)
        calendar.set(Calendar.MINUTE, 0)
        calendar.set(Calendar.SECOND, 0)
        calendar.set(Calendar.MILLISECOND, 0)
        val startTime = calendar.timeInMillis
        val endTime = System.currentTimeMillis()

        val myPackage = context.packageName

// [수정됨] 기존 queryAndAggregateUsageStats 대신, 새로 만든 정밀 계산 함수 호출
        val preciseUsageMap = calculatePreciseUsage(context, startTime, endTime)

        // key = packageName, value = 사용 시간(분)
        val todayUsageMap: Map<String, Int> =
            preciseUsageMap
                .filterKeys {
                    it != myPackage && it != launcherPackage // 내 앱과 런처 제외
                }
                // calculatePreciseUsage가 이미 (분) 단위로 변환해서 주므로 mapValues 불필요
                .filterValues { it > 0 } // 0분 초과인 앱만 필터링

        // --- 2. 지난 30일 사용량 계산 ---
        calendar.add(Calendar.DAY_OF_MONTH, -30)
        val thirtyDaysAgo = calendar.timeInMillis
        val dailyStats = usageStatsManager.queryUsageStats(
            UsageStatsManager.INTERVAL_DAILY,
            thirtyDaysAgo,
            endTime
        )

        // date("yyyy-MM-dd") -> (packageName -> usageMinutes)
        val dailyUsageMap = mutableMapOf<String, MutableMap<String, Int>>()

        for (stat in dailyStats) {
            val usageInMinutes = (stat.totalTimeInForeground / (1000 * 60)).toInt()
            if (usageInMinutes <= 0) continue

            val pkg = stat.packageName
            val date = sdf.format(Date(stat.firstTimeStamp))
            val dayMap = dailyUsageMap.getOrPut(date) { mutableMapOf() }
            dayMap[pkg] = (dayMap[pkg] ?: 0) + usageInMinutes
        }

        val newDailyList = dailyUsageMap.map { (date, usages) ->
            DailyUsage(
                date = date,
                appUsages = usages.toMap() // Map<String, Int>
            )
        }.sortedBy { it.date }
        _dailyUsageList.postValue(newDailyList)

        // --- 3. 어제까지의 스트릭 계산 ---
        val streakMap = calculateStreaks(newDailyList, currentGoals)

        // ⭐ 추적 중인 앱 패키지들도 가져와서 포함시킨다
        val trackedPrefs =
            context.getSharedPreferences("tracked_apps_prefs", Context.MODE_PRIVATE)
        val trackedPackages: Set<String> =
            trackedPrefs.getStringSet("tracked_packages", emptySet()) ?: emptySet()

        // --- 4. 최종 _appUsageList 생성 ---
        // 오늘 사용한 앱 + 목표가 설정된 앱 + 스트릭이 있는 앱들을 모두 포함
        val packageNames = mutableSetOf<String>()
        packageNames.addAll(trackedPackages)
        packageNames.addAll(todayUsageMap.keys)
        packageNames.addAll(currentGoals.keys)
        packageNames.addAll(streakMap.keys)

        val newAppUsageList = packageNames.map { pkg ->
            val todayUsage = todayUsageMap[pkg] ?: 0
            val goal = currentGoals[pkg] ?: 0
            val pastStreak = streakMap[pkg] ?: 0
            var finalStreak = pastStreak

            // 오늘 목표 초과 시 실패 스트릭 조정
            if (goal > 0 && todayUsage > goal) {
                finalStreak = if (pastStreak < 0) pastStreak - 1 else -1
            }

            // 앱 라벨(이름)
            val label = try {
                val appInfo = packageManager.getApplicationInfo(pkg, 0)
                packageManager.getApplicationLabel(appInfo).toString()
            } catch (e: Exception) {
                pkg // 실패하면 패키지명 그대로 표시
            }

            // 앱 아이콘
            val icon: Drawable? = try {
                packageManager.getApplicationIcon(pkg)
            } catch (e: Exception) {
                null
            }

            AppUsage(
                packageName = pkg,
                appLabel = label,
                icon = icon,
                currentUsage = todayUsage,
                goalTime = goal,
                streak = finalStreak
            )
        }.sortedBy { it.appLabel.lowercase() }

        _appUsageList.postValue(newAppUsageList)
    }

    // ---------------- 보조 함수들 ----------------

    // UsageStats 리스트 -> packageName 기준 사용시간(분) 맵
    private fun parseUsageStats(stats: List<UsageStats>): Map<String, Int> {
        val usageMap = mutableMapOf<String, Int>()
        for (stat in stats) {
            val usageInMinutes = (stat.totalTimeInForeground / (1000 * 60)).toInt()
            if (usageInMinutes <= 0) continue
            val pkg = stat.packageName
            usageMap[pkg] = (usageMap[pkg] ?: 0) + usageInMinutes
        }
        return usageMap
    }

    // 어제까지의 스트릭 계산
    // goals: key = packageName, value = 목표시간(분)
    private fun calculateStreaks(
        dailyList: List<DailyUsage>,
        goals: Map<String, Int>
    ): Map<String, Int> {
        val streakMap = mutableMapOf<String, Int>()
        val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.KOREAN)
        val todayStr = sdf.format(Date())

        val pastDays = dailyList
            .filter { it.date != todayStr }
            .sortedByDescending { it.date }

        if (pastDays.isEmpty()) {
            goals.keys.forEach { streakMap[it] = 0 }
            return streakMap
        }

        // 목표가 설정된 앱들만 스트릭 계산
        for ((pkg, goal) in goals) {
            if (goal == 0) {
                streakMap[pkg] = 0
                continue
            }

            val firstDayUsage = pastDays.first().appUsages[pkg] ?: 0
            val wasSuccess = firstDayUsage <= goal
            var streak = 0

            for (day in pastDays) {
                val usage = day.appUsages[pkg] ?: 0
                if ((usage <= goal) == wasSuccess) {
                    streak++
                } else {
                    break
                }
            }

            streakMap[pkg] = if (wasSuccess) streak else -streak
        }

        return streakMap
    }

    // ---------------- 정밀 시간 계산 함수 (화면 꺼짐 처리 포함 수정본) ----------------
    private fun calculatePreciseUsage(context: Context, startTime: Long, endTime: Long): Map<String, Int> {
        val usageStatsManager = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val usageEvents = usageStatsManager.queryEvents(startTime, endTime)
        val event = android.app.usage.UsageEvents.Event()

        val appUsageMap = mutableMapOf<String, Long>()
        val startMap = mutableMapOf<String, Long>()

        while (usageEvents.hasNextEvent()) {
            usageEvents.getNextEvent(event)
            val pkg = event.packageName

            when (event.eventType) {
                // 1. 앱이 켜짐 (화면 진입)
                android.app.usage.UsageEvents.Event.MOVE_TO_FOREGROUND,
                android.app.usage.UsageEvents.Event.ACTIVITY_RESUMED -> {
                    startMap[pkg] = event.timeStamp
                }

                // 2. 앱이 꺼짐 (화면 이탈)
                android.app.usage.UsageEvents.Event.MOVE_TO_BACKGROUND,
                android.app.usage.UsageEvents.Event.ACTIVITY_PAUSED -> {
                    startMap[pkg]?.let { startTime ->
                        val duration = event.timeStamp - startTime
                        if (duration > 0) {
                            appUsageMap[pkg] = (appUsageMap[pkg] ?: 0L) + duration
                        }
                        startMap.remove(pkg)
                    }
                }

                // ⭐️ 3. 화면 자체가 꺼짐 (이 부분이 추가되어야 유튜브 20시간 오류가 해결됨!)
                android.app.usage.UsageEvents.Event.SCREEN_NON_INTERACTIVE -> {
                    // 화면이 꺼지면 현재 측정 중이던 모든 앱의 시간을 정산하고 리스트를 비움
                    startMap.forEach { (p, sTime) ->
                        val duration = event.timeStamp - sTime
                        if (duration > 0) {
                            appUsageMap[p] = (appUsageMap[p] ?: 0L) + duration
                        }
                    }
                    startMap.clear()
                }
            }
        }

        // 4. 마지막까지 켜져 있는 앱 처리 (현재 보고 있는 앱)
        startMap.forEach { (pkg, startTime) ->
            val duration = endTime - startTime
            if (duration > 0) {
                appUsageMap[pkg] = (appUsageMap[pkg] ?: 0L) + duration
            }
        }

        // 밀리초 -> 분(Int)으로 변환하여 반환
        return appUsageMap.mapValues { (_, millis) -> (millis / (1000 * 60)).toInt() }
    }
}
