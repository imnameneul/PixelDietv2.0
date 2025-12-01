package com.example.pixeldiet.viewmodel

import android.app.Application
import android.content.Context
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MediatorLiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.map
import androidx.lifecycle.viewModelScope
import com.example.pixeldiet.model.AppUsage
import com.example.pixeldiet.model.CalendarDecoratorData
import com.example.pixeldiet.model.DailyUsage
import com.example.pixeldiet.model.DayStatus
import com.example.pixeldiet.model.NotificationSettings
import com.example.pixeldiet.repository.UsageRepository
import com.github.mikephil.charting.data.Entry
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.GoogleAuthProvider
import com.prolificinteractive.materialcalendarview.CalendarDay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.text.SimpleDateFormat
import java.util.*

class SharedViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = UsageRepository
    val appUsageList: LiveData<List<AppUsage>> = repository.appUsageList
    private val dailyUsageList: LiveData<List<DailyUsage>> = repository.dailyUsageList
    val notificationSettings: LiveData<NotificationSettings> = repository.notificationSettings

    // ❗ AppName 대신 packageName(String?)으로 필터 (null = 전체)
    private val _selectedFilter = MutableLiveData<String?>(null)

    // ----------------------- 추적 앱 목록 -----------------------

    // SharedPreferences (어떤 앱을 추적 중인지 저장)
    private val trackedPrefs = application
        .getSharedPreferences("tracked_apps_prefs", Context.MODE_PRIVATE)

    // 현재 추적 중인 앱들의 packageName 집합
    private val _trackedPackages = MutableLiveData<Set<String>>(emptySet())
    val trackedPackages: LiveData<Set<String>> = _trackedPackages

    private fun loadTrackedPackages() {
        val saved = trackedPrefs.getStringSet("tracked_packages", emptySet()) ?: emptySet()
        _trackedPackages.value = saved
    }

    fun updateTrackedPackages(newSet: Set<String>) {
        _trackedPackages.value = newSet
        trackedPrefs.edit().putStringSet("tracked_packages", newSet).apply()
    }

    // ----------------------- Firebase Auth -----------------------

    private val auth = FirebaseAuth.getInstance()
    private val _userName = MutableStateFlow(getUserName())
    val userName: StateFlow<String> = _userName

    val isGoogleUser = MutableStateFlow(isGoogleLogin())
    private val authListener = FirebaseAuth.AuthStateListener { _ ->
        _userName.value = getUserName()
        isGoogleUser.value = isGoogleLogin()
    }

    init {
        auth.addAuthStateListener(authListener)
        loadTrackedPackages()   // ⭐ 저장돼 있던 추적앱 목록 로드
        refreshData()           // ⭐ 사용시간 데이터 로드
    }

    override fun onCleared() {
        super.onCleared()
        auth.removeAuthStateListener(authListener)
    }

    fun onGoogleLoginSuccess(idToken: String) {
        viewModelScope.launch {
            try {
                val credential = GoogleAuthProvider.getCredential(idToken, null)
                auth.signInWithCredential(credential).await()
            } catch (e: Exception) {
                Log.e("GoogleLogin", "Firebase sign in failed: $e")
            }
        }
    }

    fun logout() {
        auth.signOut()
    }

    private fun getUserName(): String {
        val user = auth.currentUser
        return if (user != null && !user.isAnonymous) {
            "${user.displayName ?: "사용자"}님 환영합니다"
        } else {
            "게스트 로그인 중입니다"
        }
    }

    private fun isGoogleLogin(): Boolean {
        val user = auth.currentUser
        return user != null && !user.isAnonymous
    }

    // ----------------------- 메인 통계 -----------------------

    // 🔁 기존 코드 지우고 이걸로 교체
    val totalUsageData: LiveData<Pair<Int, Int>> =
        MediatorLiveData<Pair<Int, Int>>().apply {

            fun update() {
                val list = appUsageList.value ?: emptyList()
                val tracked = trackedPackages.value ?: emptySet()

                // 🔹 추적앱이 없으면 총 사용시간/목표 0으로
                val filtered = if (tracked.isEmpty()) {
                    emptyList<com.example.pixeldiet.model.AppUsage>()
                } else {
                    // 🔹 선택한 앱들만 합산
                    list.filter { it.packageName in tracked }
                }

                val totalUsage = filtered.sumOf { it.currentUsage }
                val totalGoal = filtered.sumOf { it.goalTime }

                value = totalUsage to totalGoal
            }

            addSource(appUsageList) { update() }
            addSource(trackedPackages) { update() }
        }


    private val filteredGoalTime: LiveData<Int> = MediatorLiveData<Int>().apply {
        addSource(appUsageList) { goals ->
            val filterPkg = _selectedFilter.value
            value = if (filterPkg == null) {
                goals.sumOf { it.goalTime }
            } else {
                goals.find { it.packageName == filterPkg }?.goalTime ?: 0
            }
        }
        addSource(_selectedFilter) { filterPkg ->
            val goals = appUsageList.value ?: return@addSource
            value = if (filterPkg == null) {
                goals.sumOf { it.goalTime }
            } else {
                goals.find { it.packageName == filterPkg }?.goalTime ?: 0
            }
        }
    }

    val calendarDecoratorData: LiveData<List<CalendarDecoratorData>> =
        MediatorLiveData<List<CalendarDecoratorData>>().apply {
            fun updateDecorators() {
                val goals = appUsageList.value ?: return
                val dailies = dailyUsageList.value ?: return
                val filterPkg = _selectedFilter.value
                val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.KOREAN)
                val decorators = mutableListOf<CalendarDecoratorData>()

                for (daily in dailies) {
                    val date = sdf.parse(daily.date) ?: continue
                    val cal = Calendar.getInstance(); cal.time = date
                    val calDay = CalendarDay.from(
                        cal.get(Calendar.YEAR),
                        cal.get(Calendar.MONTH) + 1,
                        cal.get(Calendar.DAY_OF_MONTH)
                    )

                    val (usage, goal) = if (filterPkg == null) {
                        Pair(
                            daily.appUsages.values.sum(),
                            goals.sumOf { it.goalTime }
                        )
                    } else {
                        Pair(
                            daily.appUsages[filterPkg] ?: 0,
                            goals.find { it.packageName == filterPkg }?.goalTime ?: 0
                        )
                    }

                    if (goal == 0) continue

                    val status = when {
                        usage > goal -> DayStatus.FAIL
                        usage > goal * 0.7 -> DayStatus.WARNING
                        else -> DayStatus.SUCCESS
                    }
                    decorators.add(CalendarDecoratorData(calDay, status))
                }
                value = decorators
            }

            addSource(dailyUsageList) { updateDecorators() }
            addSource(filteredGoalTime) { updateDecorators() }
            addSource(_selectedFilter) { updateDecorators() }
        }

    val calendarStatsText: LiveData<String> = MediatorLiveData<String>().apply {
        addSource(calendarDecoratorData) { decorators ->
            val currentMonth = Calendar.getInstance().get(Calendar.MONTH) + 1
            val successDays = decorators.count {
                it.date.month == currentMonth &&
                        (it.status == DayStatus.SUCCESS || it.status == DayStatus.WARNING)
            }

            val filterPkg = _selectedFilter.value
            val filterName = if (filterPkg == null) {
                "전체"
            } else {
                appUsageList.value
                    ?.find { it.packageName == filterPkg }
                    ?.appLabel ?: "전체"
            }

            value = "이번달 $filterName 목표 성공일: 총 ${successDays}일!"
        }
    }

    val streakText: LiveData<String> = MediatorLiveData<String>().apply {
        fun updateStreak() {
            val filterPkg = _selectedFilter.value
            val appList = appUsageList.value ?: return

            val streak = if (filterPkg == null) {
                appList.firstOrNull()?.streak ?: 0
            } else {
                appList.find { it.packageName == filterPkg }?.streak ?: 0
            }

            val days = kotlin.math.abs(streak)
            val status = if (streak >= 0) "달성" else "실패"
            value = "${days}일 연속 목표 $status 중!"
        }

        addSource(appUsageList) { updateStreak() }
        addSource(_selectedFilter) { updateStreak() }
    }

    val chartData: LiveData<List<Entry>> = MediatorLiveData<List<Entry>>().apply {
        fun updateChart() {
            val dailies = dailyUsageList.value ?: emptyList()
            val filterPkg = _selectedFilter.value
            val currentMonth = Calendar.getInstance().get(Calendar.MONTH) + 1
            val entries = mutableListOf<Entry>()

            dailies
                .filter { it.date.substring(5, 7).toInt() == currentMonth }
                .forEach { daily ->
                    val dayOfMonth = daily.date.substring(8, 10).toFloat()
                    val usage = if (filterPkg == null) {
                        daily.appUsages.values.sum()
                    } else {
                        daily.appUsages[filterPkg] ?: 0
                    }
                    entries.add(Entry(dayOfMonth, usage.toFloat()))
                }
            value = entries
        }

        addSource(dailyUsageList) { updateChart() }
        addSource(_selectedFilter) { updateChart() }
    }

    // ----------------------- 데이터 로딩/설정 저장 -----------------------

    fun refreshData() {
        viewModelScope.launch(Dispatchers.IO) {
            repository.loadRealData(getApplication())
        }
    }

    fun setGoalTimes(goals: Map<String, Int>) = viewModelScope.launch(Dispatchers.IO) {
        repository.updateGoalTimes(goals)
    }

    fun setCalendarFilter(packageName: String?) {
        _selectedFilter.value = packageName
    }

    fun saveNotificationSettings(settings: NotificationSettings) = viewModelScope.launch {
        repository.updateNotificationSettings(settings, getApplication())
    }
}
