## 최신 UI 빌드: 1.4.0

검정 배경·초록 강조색의 다크 테마로 바꾸고, 차량 홈을 배터리·반납 시간 게이지와 단계별 주요 버튼 하나로 정리했습니다. 메뉴는 내 차·맞춤·안내 3개입니다. 인수 체크와 상세 정보는 대화상자로 옮겼습니다. 자세한 변경과 검증은 [UI_DEMO_1.4.0.md](UI_DEMO_1.4.0.md)를 참고하세요.

이전 1.3.0(네이비·청록 디자인, Virtual Console 연결 확인)은 [UI_DEMO_1.3.0.md](UI_DEMO_1.3.0.md)에 있습니다. 아래는 이전 버전의 기록입니다.

# SHAREMATE 1.2.1 — 연결 상태 표시 개선 (2026-10-27)

휴대폰에서 오래된 거리·요금·배터리를 **마지막 수신 기록**으로 구분합니다. 앱 복귀 시 이전 연결 상태를 지우고 최신 수신을 기다립니다. 누락·미래 시각·종료된 연결을 최신 상태로 인정하지 않습니다. 차량의 서버 접속 표시는 휴대폰의 실제 접속 여부와 구분합니다.

빌드: 1.2.1 / versionCode 4. Android 테스트 37개 통과, Lint 오류 0개·경고 11개. 이번 추가 휴대폰·서버 실행 검증은 실행 정책 차단으로 수행하지 못했습니다. 아래 1.2 기능은 유지되며 실제 AI 키는 미연결 상태입니다. Android Studio 직접 열기·동기화·빌드·설치는 후속 작업에서 확인했습니다.

# SHAREMATE 1.2 — 휴대폰 촬영과 AI 확인 (2026-10-26)

같은 APK를 Pleos에 설치하면 차량 화면, 일반 Android 휴대폰에 설치하면 휴대폰 화면으로 실행됩니다. 탑승 전·후 네 방향 사진과 둘레 영상, 수동 사고·긁힘 기록, 기기 내 사진 비교, 차량·휴대폰 간 요약 공유와 예상 요금을 추가했습니다. Pretendard 폰트는 앱에 포함됩니다.

**1.2에는 사용자가 선택한 사진을 OpenAI에 보내 손상 의심 부위를 확인하는 요청 경로를 구현했습니다.** 사진 확인 화면에서 전송에 동의하면 현재 사진을 분석하고, 이용 후 사진에는 같은 면의 탑승 전 사진을 참조로 함께 보냅니다. 결과는 원본과 연결해 휴대폰에 보관하고, 연결된 차량에도 요약을 표시합니다. 서버에 키가 없거나 API 요청이 실패하면 원본을 유지하고 실패를 안내합니다. 현재 키 생성·실제 API 호출·실차 손상 정확도 검증은 완료되지 않았습니다.

기존 **기기 내 사진 비교**는 학습형 AI가 아닌 실험적 영상 처리이며 AI 결과와 구분합니다. 두 기능 모두 흠집·사고·책임·안전성을 확정하지 않습니다. 둘레 영상은 증빙으로 보관하며 자동 분석하지 않습니다. 차량 카메라 연계는 개발 예정입니다.

- [휴대폰 사용·촬영·연결 안내](PHONE_AND_INSPECTION.md)
- [HTTPS 연결 서버 실행](relay/README.md)
- Android Studio: 이 폴더를 **Open** → Gradle Sync → `app` → 휴대폰 또는 Pleos 기기 선택 → Run. JDK 17, Android SDK 35를 사용합니다. 현재 프로젝트의 AGP 8.13.2 / Gradle 8.13 설정으로 검증했습니다.
- 명령행 검증: `gradlew.bat testDebugUnitTest lintDebug assembleDebug`
- 빌드 APK: `app/build/outputs/apk/debug/app-debug.apk` (1.2.0, versionCode 3)
- AI 연결: OpenAI Platform의 보안 키 설정 흐름에서 로컬 저장 위치를 승인한 뒤 연결 서버를 재시작합니다. 키는 서버의 `OPENAI_API_KEY` 또는 프로젝트 루트 `.env.local`에서만 읽으며 소스·APK·배포 압축본에 포함하지 않습니다. [설정·데이터 범위](relay/README.md#ai-사진-확인)

이번 작업에서 Android Studio 설치는 확인했지만, Computer Use의 Android Studio 앱 접근이 거부되어 IDE 화면에서의 편집·빌드 조작은 수행하지 못했습니다. 저장된 프로젝트의 실제 빌드는 Gradle로 수행했습니다.

아래 내용은 **기존 차량용 1.0 구현 기록**입니다. 휴대폰 촬영·기기 연결·예상 요금·AI의 최신 범위는 위 1.2 안내와 연결 문서가 우선합니다. 기존 `SHAREMATE-demo.mp4`는 1.0 시연 영상으로 휴대폰 촬영이나 AI 기능을 보여주지 않습니다.

# 기존 차량용 1.0 안내

공유차를 이번 이동에 맞게 이용하는 Pleos 차량용 Android 앱입니다. 인수 점검부터 출퇴근·여행·휴식·업무 맞춤 안내, 가족·반려동물 동반 체크, 충전·주차 확인, 반납 준비까지 지원합니다.

차량 설정을 변경하지 않는 조회 중심 프로토타입입니다. 예약·결제·운영사 반납 승인, 생성형 AI, 카메라 촬영은 포함하지 않습니다. 카메라 아이디어 3개는 ‘개발 예정’으로만 표시합니다.

## 실행 환경

| 항목 | 버전 |
|---|---|
| JDK | 17 |
| Gradle / Android Gradle Plugin | 8.11.1 / 8.9.2 |
| Kotlin | 2.1.20 |
| compileSdk / targetSdk / minSdk | 35 / 34 / 28 |
| Pleos Connect Emulator | 2.1.0, Android API 34 |
| Vehicle SDK | 2.3.2 |
| NaviHelper | 3.0.5 |

SDK 버전은 재현 가능한 빌드를 위해 고정했습니다. 최신 Android 버전으로 무조건 올리지 않습니다. Pleos SDK는 공식 Maven 저장소에서 다운로드하며 별도 클라이언트 시크릿을 요구하는 기능은 사용하지 않습니다.

## 빌드

Android Studio에서 이 디렉터리를 프로젝트로 열고 JDK 17, Android SDK Platform 35와 Build Tools 35.0.0을 준비합니다. Android SDK 경로는 `ANDROID_HOME` 또는 개인 `local.properties`의 `sdk.dir`로 설정합니다.

```powershell
.\gradlew.bat testDebugUnitTest lintDebug assembleDebug
```

Windows에서는 JDK·SDK를 직접 지정할 수 있습니다.

```powershell
.\build.ps1 -JavaHome 'C:\path\to\jdk-17' -AndroidSdk 'C:\path\to\Android\Sdk'
```

이 작업 폴더에서 빌드할 때는 `build.ps1`이 `../../work/toolchain`에 설치된 도구도 탐색합니다. 해당 도구는 소스 패키지에 포함하지 않습니다. 다른 PC에서는 자신의 JDK와 SDK를 사용하세요.

결과 APK: `app/build/outputs/apk/debug/app-debug.apk`.

## 에뮬레이터에 설치

공식 [Pleos 설치 가이드](https://document.pleos.ai/docs/connect/guide/getting-started/application-development/setup-connect-sdk-emulator)에 따라 API 34 Pleos 2.1.0 AVD를 준비합니다. 권장 초기 설정은 RAM 4GB, Cold boot입니다. 2.1.0 이후에는 CRN 입력이 필요하지 않습니다. 과거의 VHAL FIFO 쓰기 명령을 앱 설치 절차에 사용하지 않습니다.

에뮬레이터가 하나 연결된 상태에서 실행합니다. 여러 장치가 있으면 `adb -s <serial>`로 대상을 지정하세요.

```powershell
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n ai.sharemate.app/.MainActivity
```

Debug 키로 서명한 검증용 APK입니다. App Market 제출용 서명키·심사 설정은 별도 준비해야 합니다. 프로젝트 ID·클라이언트 ID·클라이언트 시크릿을 소스에 넣지 마세요.

## 사용 흐름

1. 상단 **Pleos 에뮬레이터** 버튼에서 연결 상태를 확인합니다. 요청 가능한 차량 데이터 권한은 **차량 정보 권한 확인**에서 승인합니다.
2. 예약한 반납 장소·일시와 이용 목적을 입력합니다. 가족·반려동물 동반 여부는 직접 선택합니다.
3. 홈의 인수 체크 3개를 직접 확인하고 **점검 완료 · 이용 시작**을 누릅니다.
4. **이용 맞춤**에서 목적별 체크 항목, SOC 목표, 휴식·업무 종료 목표, 주차 메모를 사용합니다. 메모를 바꾼 뒤 **저장**을 누릅니다.
5. **반납 준비**에서 현재 내비 목적지가 반납 장소와 같은지 직접 확인한 뒤 연결합니다. 경로가 없거나 바뀌거나 오래되면 복귀 여유시간을 계산하지 않습니다.
6. 반납 체크 4개를 확인하고 **SHAREMATE 이용 종료**를 누르면 이번 이용 정보가 삭제됩니다. 실제 계약의 반납은 운영사 절차에서 완료해야 합니다.

실행 중이던 앱이 다시 시작되면 **내 이용 이어가기** 또는 **이전 정보 삭제 · 새 이용**을 선택합니다. 이전 이용자의 상세 장소와 메모는 이 선택 화면에서 보여주지 않습니다.

## 시연 데이터

상단 데이터 연결에서 가족 여행·충전, 반려동물·반납, 출퇴근, 휴식·업무, 확인 불가 시나리오를 선택할 수 있습니다. 주황/파랑 경고나 배터리 숫자가 실제 값인 것처럼 자동 대체하지 않습니다. 시연 중에는 **시연용 가상 차량 상태입니다** 배너가 항상 표시됩니다.

차량 데이터 시나리오를 고르는 것과 사용자의 이용 목적·가족·반려동물 조건 선택은 별개입니다. 앱이 센서로 가족이나 반려동물 동반 여부를 추정하지 않습니다.

일반 Android 기기에서는 SDK 연결이 지원되지 않으므로 명시적으로 시연 데이터를 선택해야 합니다. 실제 Automotive 환경에서는 시연 모드라도 플랫폼의 주행 제한을 우회하지 못합니다.

## 구현과 데이터 경계

- Compose 화면 4개, 단일 `ViewModel`과 `StateFlow`, SDK/시연 데이터 소스 두 개로 구성합니다.
- 현재 세션 하나만 `noBackupFilesDir`에 `AtomicFile`로 저장합니다. 서버·계정·사용자 분석 전송은 없습니다. 백업과 기기 간 복원에서도 제외합니다.
- 설정 저장 실패는 성공으로 표시하지 않습니다. 손상된 기록은 조용히 덮어쓰지 않고 삭제 후 다시 시작하도록 안내합니다.
- 반납 여유시간 = 반납일시 − 현재시각 − 현재 반납 경로 소요시간 − 15분. 수신 후 30초가 지난 경로는 사용하지 않습니다.
- SDK 값은 capability·권한·지원 영역 확인 후 읽습니다. 비어 있거나 잘못되거나 오래된 값을 0 또는 정상으로 바꾸지 않습니다.
- 차량 정보 수집과 화면 시계는 앱이 전경에 있을 때만 실행합니다. 앱을 나갔다 돌아오면 현재시각과 최신 상태로 다시 계산합니다.
- 타이머는 화면 내 안내입니다. 백그라운드 정시 알림은 구현하지 않았습니다.
- 차량 UX 제한은 `CarUxRestrictions`를 따릅니다. 상태를 확인할 수 없을 때도 상세 조작을 잠급니다. 주행 중 허용을 임의 선언하지 않습니다.
- 차종 전용 설명서는 포함하지 않았습니다. 확인한 모델명과 공통 조작·점검 안내만 제공합니다.

## 조회 항목

| 항목 | 현재 구현 |
|---|---|
| 배터리 SOC | Vehicle capability 확인 후 조회·변화 구독 |
| 도어·잠금·창문·안전벨트 | 지원 영역별 capability와 값 확인 |
| 공조 | 지원되는 설정 온도 표시, 실내 측정온도와 구분 |
| 출력 제한 경고 | 공개 Safety API로 읽은 경고만 표시, 종합 진단 아님 |
| 주차·충전 상태·표시 가능거리·모델명 | 지원되는 읽기 전용 AAOS property와 권한 확인 |
| 예상 충전 완료시간 | 최신 차량 제공 잔여시간이 있고 충전 중일 때만 표시. 앱의 목표 SOC 도달시간을 추정하지 않음 |
| 타이어 현재 압력 | 공개 권한으로 제공되지 않는 API를 호출하지 않고 확인 불가 표시 |
| 반납 ETA | NaviHelper의 실제 안내 상태·목적지 정보 확인 후 사용자 연결 |

승인된 권한과 각 이미지·차종의 지원 범위에 따라 ‘확인 불가’가 정상 결과일 수 있습니다. 타이어 저압 기준값을 현재 압력으로 표시하지 않습니다. 충전 스위치를 실제 충전 진행 상태로 추정하지 않습니다.

## 검증

```powershell
.\gradlew.bat testDebugUnitTest lintDebug assembleDebug
```

자동 테스트는 반납 목적지 확인 경쟁 상태, 만료·누락 경로, 자정 경과와 음수 여유시간, 세션 입력 검증, 빠른 독립 설정 편집, SOC 누락·범위, 음수 환기 창문 위치, 목적지 변경 시 기존 연결 무효화, 충전 잔여시간의 유효성과 만료를 확인합니다. 실제 실행 결과와 미검증 항목은 함께 제공하는 `검증결과.md`를 확인하세요. API와 권한별 범위는 `기능지원표.md`에 정리했습니다.

공식 참고: [SDK 시작](https://document.pleos.ai/docs/pleos-only/getting-started), [Vehicle](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/intro), [NaviHelper](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/NaviHelper/destinations), [주행 제한](https://document.pleos.ai/docs/pleos-only/vehicle-app-developer-guide/driving-restriction-development-guide).
