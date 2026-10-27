# 1.3.0 UI 개선 및 Pleos 시연

- Android Compose 네이티브 UI: 네이비/청록 테마, Pretendard 글꼴, 명확한 선택 메뉴, 차량 도식 배경, 반납 시간 강조. CSS 웹 화면이 아닙니다.
- 차량/휴대폰에 공통 테마 적용. 차량 홈의 그림 높이를 줄여 거리·요금 정보를 더 빨리 확인할 수 있게 했습니다.
- 공식 가이드: https://document.pleos.ai/docs/connect/guide/getting-started/application-development/use-vehicle-emulator
- 실행 중인 Pleos 2.1.0 emulator-5560에 1.3.0 설치. LauncherActivity로 실행.
- adb -s emulator-5560 forward tcp:8088 tcp:8088 후 http://127.0.0.1:8088/ 연결. DDS ONLINE 확인.
- Virtual Console > Emulation Controls > Energy > Battery SOC: 100→99 변경 시 실제 앱 표시 99% 확인, 이후 100% 복원.
- 기존 테스트 이용 기록의 반납시각 초과를 숨기지 않았습니다. 충전 정보는 권한 확인 필요 상태입니다.
- 자동 테스트 37개 통과, testDebugUnitTest/lintDebug/assembleDebug 성공.
- 차량 화면과 SHAREMATE가 함께 표시되는 20초 MP4 저장. 휴대폰 UI 실기기/화면 크기별 재검증, 실차 검증, AI 판독 검증은 이번 변경에서 수행하지 않았습니다.

## 다시 시연하기
1. Pleos 에뮬레이터에서 SHAREMATE 실행, 기존 본인 테스트 이용은 이어가기 선택.
2. 위 adb forward 명령 후 Virtual Console 열기. P 유지.
3. Energy > Battery SOC 슬라이더 변경. SHAREMATE 홈 배터리 값 확인.
4. 테스트 후 값을 원래대로 복원. 시연 데이터 모드와 Pleos 연결을 구별합니다.
