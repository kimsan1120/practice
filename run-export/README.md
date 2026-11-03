# 러닝 기록 내보내기 (RunExport)

갤럭시 워치 → 삼성 헬스 → **Health Connect** 에 저장된 운동 기록을 날짜별로 골라서
CSV / GPX 파일로 내보내는 안드로이드 앱입니다.

```
갤럭시 워치 5 ──동기화──▶ 삼성 헬스 ──동기화──▶ Health Connect ──읽기──▶ 이 앱 ──▶ CSV / GPX
```

## 할 수 있는 것

- 날짜 선택(◀ ▶, 오늘) → 그날의 **러닝만** (또는 모든 운동) 목록
- 기록별 요약: 거리, 운동 시간, 평균 페이스, 평균/최고 속도, 평균/최고/최저 심박,
  총/활동 칼로리, 걸음 수, 케이던스, 상승 고도
- 기록을 누르면 상세 화면
  - **1km 구간(스플릿)** 페이스 + 구간 평균 심박
  - 심박·속도 원본 샘플
  - **GPS 경로** (기록마다 한 번 허락 필요) → 심박이 포함된 **GPX** (Strava 등에 업로드 가능)
- 내보내기: **공유**(카톡, 드라이브, 메일 등) 또는 **다운로드/RunExport 폴더에 저장**

| 파일 | 내용 |
|---|---|
| `runs_YYYYMMDD_summary.csv` | 그날 기록들의 요약 (한 줄 = 운동 1회) |
| `run_YYYYMMDD_HHMM_summary.csv` | 선택한 기록의 요약 |
| `run_..._splits.csv` | 1km 구간 기록 |
| `run_..._samples.csv` | 시각별 심박 / 속도 / 위도·경도·고도 |
| `run_..._laps.csv` | 워치 랩 기록 (있을 때만) |
| `run_....gpx` | GPS 경로 + 심박 (경로를 불러왔을 때만) |

CSV 는 UTF-8(BOM) 이라 엑셀에서 바로 열어도 한글이 깨지지 않습니다.

## 설치 방법 (Android Studio 없이)

1. 이 저장소의 **Releases → `apk-latest`** 에서 `RunExport.apk` 를 휴대폰으로 다운로드
   (GitHub Actions 가 코드를 올릴 때마다 자동으로 빌드해서 올립니다.
   Actions 탭의 실행 결과 → Artifacts 에서도 받을 수 있어요.)
2. 다운로드한 파일을 누르고 "출처를 알 수 없는 앱 설치"를 허용 → 설치
3. 앱 실행 → **권한 허용하기** → 운동/심박/거리 등 모두 허용

> 디버그 빌드라서 새 버전이 서명 문제로 덮어쓰기 설치가 안 되면, 기존 앱을 지우고 다시 설치하세요.

## 휴대폰 사전 설정 (한 번만)

1. **삼성 헬스 → 설정(⋮) → Health Connect** → 삼성 헬스가 Health Connect 에
   **운동, 심박수, 거리, 속도, 걸음, 칼로리** 등을 *쓰도록(공유)* 허용
2. 러닝 후 워치 → 삼성 헬스 동기화가 끝났는지 확인 (삼성 헬스 앱에서 기록이 보이면 OK)
3. 삼성 헬스가 Health Connect 로 보내는 데 시간이 조금 걸릴 수 있어요. 앱에 안 보이면
   삼성 헬스를 한 번 열었다가 다시 와보세요.

## 알아둘 점

- Health Connect 에 넘어오는 항목만 읽을 수 있어요. **러닝 다이내믹스(지면 접촉 시간,
  수직 진폭 등), VO2max, 땀 손실** 같은 삼성 전용 지표는 보통 넘어오지 않습니다.
- 1km 구간은 GPS 경로 → 거리 기록 → 속도 기록 순으로 가능한 데이터로 계산하고,
  세션 총 거리에 맞춰 보정합니다. 구간 시간에는 일시정지가 포함될 수 있어요.
- 기본적으로 **권한을 처음 준 날 기준 30일 이전** 기록은 읽을 수 없어요.
  기기가 지원하면 앱이 "과거 데이터 읽기" 권한도 함께 요청합니다.
- 데이터는 휴대폰 밖으로 나가지 않아요 (인터넷 권한 없음).

## 직접 빌드

```bash
cd run-export
./gradlew assembleDebug        # app/build/outputs/apk/debug/app-debug.apk
./gradlew testDebugUnitTest    # 구간 계산 단위 테스트
```

JDK 17+, Android SDK (compileSdk 36) 필요. Android Studio 로 `run-export` 폴더를 열어도 됩니다.

## 코드 구조

| 파일 | 역할 |
|---|---|
| `HealthRepository.kt` | Health Connect 읽기 (세션 목록, 집계, 심박/속도/거리/경로) |
| `SplitCalculator.kt` | 1km 구간 계산 |
| `Exporter.kt` | CSV / GPX 생성, 공유, 다운로드 저장 |
| `MainViewModel.kt` | 화면 상태 |
| `MainActivity.kt` | 화면 (Jetpack Compose) |
| `PermissionsRationaleActivity.kt` | Health Connect 권한 안내 화면 |
