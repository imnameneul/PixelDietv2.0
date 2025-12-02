# 1. Repository 역할 개요

UsageRepository는 현재 앱에서 “사용시간/일별 사용기록/알림 설정” 을 관리하는 데이터 레이어의 허브다.

## Repository의 책임

### 1. 오늘의 앱 사용시간 수집

- UsageStatsManager + UsageEvents를 이용하여 오늘 0시~현재까지 각 패키지별 사용시간(분)을 계산

### 2. 과거 N일(현재는 30일) 일별 사용기록 관리

- DailyUsage(date, appUsages: Map<String, Int>) 리스트로 보관

### 3. 목표시간(goalTime) & 스트릭(streak) 계산

- currentGoals: Map<String, Int>를 기준으로 streak 맵 계산

### 4. 알림 설정(NotificationSettings) 관리

- NotificationPrefs(SharedPreferences 래퍼)에 저장/로드

### 5. UI/ViewModel에 전달할 도메인 모델 제공

- LiveData<List<AppUsage>>

- LiveData<List<DailyUsage>>

- LiveData<NotificationSettings>

---

# 2. 현재 UsageRepository 인터페이스 정리

코드는 object UsageRepository 형태로 구현되어 있고, 외부에 공개되는 프로퍼티/함수는 다음과 같다.

## 2.1 공개 프로퍼티 (LiveData)
```
val appUsageList: LiveData<List<AppUsage>>
val dailyUsageList: LiveData<List<DailyUsage>>
val notificationSettings: LiveData<NotificationSettings>
```
appUsageList

- 타입: LiveData<List<AppUsage>>

- 내용:

  - 오늘 날짜 기준, 화면에 표시할 개별 앱 데이터 목록

  - 각 원소:

    - packageName: String

    - appLabel: String

    - icon: Drawable?

    - currentUsage: Int (오늘 사용시간, 분 단위)

    - goalTime: Int (분 단위 목표시간)

    - streak: Int (양수=연속 성공일, 음수=연속 실패일)

- DB 도입 후 요구사항:

  - 오늘 사용시간은 여전히 UsageStats 기반으로 계산하되,

  - goalTime, streak 계산 시 DB에 저장된 목표/히스토리를 활용할 수 있음.

  - ViewModel/UI에서 observe 방식은 그대로 유지.

dailyUsageList

- 타입: LiveData<List<DailyUsage>>

- 내용:

  - 과거 일별 전체 앱 사용 통계

  - 각 DailyUsage:

    - date: String ("yyyy-MM-dd")

    - appUsages: Map<String, Int> (key: packageName, value: usageMinutes)

- 현재:

    - UsageStatsManager.queryUsageStats(INTERVAL_DAILY, last30days, now) 결과를 파싱해서 메모리상에 구성.

    - 오늘 날짜에 대해서는 preciseUsageMap(정밀 계산 결과)을 덮어써서 사용.

- DB 도입 후 요구사항:

  - Room/Firestore에 일별 사용량을 영속 저장하고,

  - dailyUsageList는 DB에서 불러온 결과를 도메인 모델로 변환하여 제공.

  - 조회 범위(최근 30일, n개월 등)는 향후 확장 가능해야 함.
---
notificationSettings

- 타입: LiveData<NotificationSettings>

- 내용:

  - 개별 앱/전체 사용에 대해 50/70/100% 알림 여부

  - 100% 초과시 반복 알림 간격 등

- 현재:

  - NotificationPrefs(SharedPreferences 래퍼)에 저장/로드하고,

  - 초기화 시 loadNotificationSettings() 값을 LiveData에 반영.

- DB 도입 후 요구사항:

  - 알림 설정은 SharedPreferences 그대로 써도 OK (DB에 억지로 넣을 필요는 없음).

  - 혹은 Room/Firestore로 이관할 경우, 인터페이스는 그대로 유지:

    - notificationSettings 는 항상 최신 설정을 방출해야 함.
---
## 2.2 공개 메서드
① fun updateNotificationSettings(settings: NotificationSettings, context: Context)

- 역할:

  - 알림 설정 변경 시 호출

  - 현재 구현:

    - NotificationPrefs.saveNotificationSettings() 로 SharedPreferences에 저장

    - _notificationSettings.postValue(settings) 로 LiveData 업데이트

- DB 도입 후 요구사항:

- 알림 설정을 어디에 저장하든(SharedPrefs/Room/Firestore),
이 함수는 “설정 저장 + LiveData 최신화”라는 의미를 유지.

---

② fun updateGoalTimes(goals: Map<String, Int>)

- 파라미터:

  - goals: key = packageName, value = 목표시간(분)

- 현재 역할:

  - currentGoals 맵을 갱신

  - 기존 _appUsageList에 대해 goalTime 값을 재매핑하여 postValue()

- 한계:

  - 메모리 상에만 존재 → 앱 재시작 시 유실

  - 과거 목표 히스토리 없음 → 과거 캘린더/그래프가 “현재 목표 기준”으로만 재해석됨

- DB 도입 후 요구사항:

  - 이 함수는 **“현재 시점의 목표 설정 요청”**으로 취급

  - Repository 내부에서:

    - Room/Firestore에 목표 히스토리/현재 목표를 기록

    - 필요 시, “effectiveDate = 오늘” 같은 형태로 GoalHistory 테이블에 insert

- _appUsageList의 goalTime 필드도 DB 기준으로 재계산해서 LiveData 업데이트.

---

③ suspend fun loadRealData(context: Context)

⚠️ 현재 구조에서 가장 중요한 로직.

- 역할 (현재):

1. 오늘 0시~현재까지의 정밀 사용시간 계산

  - calculatePreciseUsage(context, startTime, endTime)

2. 최근 30일 UsageStats로 dailyUsageMap 생성

3. 오늘 데이터(todayUsageMap)를 dailyUsageMap에 덮어씀

4. DailyUsage 리스트로 변환 후 _dailyUsageList.postValue(...)

5. calculateStreaks(newDailyList, currentGoals) 로 어제까지 streak 계산

6. 추적 앱 목록(tracked_packages SharedPreferences)을 읽어와서

  - 오늘 사용 앱

  - 목표가 설정된 앱

  - 스트릭이 있는 앱
→ 이들을 모두 합쳐 newAppUsageList 구성 후 _appUsageList.postValue(...)
<br>
- DB 도입 후 추천 방향:

함수 이름/시그니처는 그대로 두되, 내부 로직을 다음처럼 역할 분리:

1. 로우 데이터 수집

  - 오늘 UsageEvents / UsageStats → 오늘 사용량

2. DB 갱신

  - Room/Firestore의 DailyUsage 테이블/컬렉션을 업데이트

3. 도메인 모델 구성

  - DB에서 DailyUsage를 읽어서 streak 계산

  - 목표 히스토리/추적앱 히스토리를 고려해 AppUsage 리스트 구성

4. LiveData 업데이트

  - _dailyUsageList, _appUsageList 에 최종 리스트 반영

이 함수는 여전히 “오늘 기준 데이터 새로 로드/동기화”를 의미해야 함.

---

## 2.3 내부 보조 함수 (참고용)

DB/사용량 로직에 중요한 의미가 있어서, DB 담당이 구현을 이해할 때 참고하면 좋음.

calculateStreaks(dailyList: List<DailyUsage>, goals: Map<String, Int>): Map<String, Int>

- 역할:

  - “어제까지의” 스트릭(연속 성공/실패 일수)을 앱별로 계산

- 주요 로직:

  - 오늘(todayStr)을 제외한 날짜들(pastDays)을 **내림차순(가장 최근 → 과거)**으로 순회

  - 각 앱별로:

    - 목표(goal)가 0이면 streak=0

    - 첫 날 기준으로 성공/실패 여부 결정(wasSuccess)

    - 연속해서 성공/실패가 이어지는 날 수 카운트

    - 최종적으로 성공이면 +n, 실패이면 -n

DB 도입 후:

- 과거 목표 시간이 “히스토리 기반”이 되면,
이 함수는 “각 날짜에서 유효한 goal”을 DB에서 가져와야 함.

- streak 계산 자체는 Repository/Domain 레벨에 남겨두는 게 좋다.

---
calculatePreciseUsage(context, startTime, endTime): Map<String, Int>

- 역할:

  - UsageEvents를 순회하면서:

    - MOVE_TO_FOREGROUND / ACTIVITY_RESUMED 시점 기억

    - MOVE_TO_BACKGROUND / ACTIVITY_PAUSED 시점에 duration 누적

    - SCREEN_NON_INTERACTIVE(화면 꺼짐) 시 모든 app 정산

  - 마지막까지 foreground인 앱은 endTime 기준으로 duration 정산

  - 최종 결과: Map<packageName, minutes>

- DB 도입과는 직접 관계없지만,
“오늘 사용시간이 디지털 웰빙과 최대한 비슷하게 나오게 하는 핵심 로직”이라
DB 담당도 전체 동작 이해용으로 한 번 읽어두면 좋음.

---

# 3. Room / Firestore 담당자에게 강조하고 싶은 점

## 1. UI / ViewModel 인터페이스 유지

- UsageRepository.appUsageList, dailyUsageList, notificationSettings 는 그대로 남기고,

- 가능하면 함수 이름도 유지 (loadRealData, updateGoalTimes, updateNotificationSettings).

## 2. 도메인 모델 vs DB 모델 분리

- AppUsage, DailyUsage는 UI/도메인용 모델로 유지

- Room/Firestore 쪽에는 별도의 Entity/DTO를 둔 후
Repository에서 변환해서 LiveData로 노출

## 3. 과거 통계/캘린더/스트릭을 위한 히스토리 구조

- 추가로 설계해야 할 테이블/컬렉션 (요구사항 수준):

- GoalHistory

  - effectiveDate, packageName?, goalMinutes

- TrackingHistory

  - effectiveDate, trackedPackages (혹은 N:M 구조)

- 캘린더/그래프/스트릭 계산 시:

  - 각 날짜에 대해 “그 날짜에 유효한 goal + trackedApps” 를 계산할 수 있어야 함.

## 4. Worker와의 연동

- UsageCheckWorker는 현재 Repository/SharedPreferences에 의존해서
사용시간/목표/알림 상태를 결정

- 나중에 DB에서 “오늘까지의 사용/목표 히스토리”를 읽어야 할 수도 있으므로,
Worker에서 사용할 수 있는 Repository API도 같이 고려.
