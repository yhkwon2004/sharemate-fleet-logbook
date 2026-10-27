# SHAREMATE Gleo Interface

2026-10-28 공식 문서 기준. 앱의 `assets/schema.json`과 처리 코드가 준비되어 있습니다. **실제 Gleo 음성 인식·호출 연결은 승인 및 호출자 검증 전이며, 시연의 버튼 실행은 음성 인식 결과가 아닙니다.**

## 제공하는 세 가지 동작

공식 App Schema는 actionCalls를 최대 3개로 제한합니다. 음성으로 반복하기 좋은 아래 기능에 집중합니다.

| 동작 | 사용 예 | 처리 범위 |
|---|---|---|
| `SET_TRIP_PURPOSE` | “운행일지, 이번 운행을 업무로 기록해줘” | 현재 운행 또는 12시간 이내 최근 완료 운행의 출·퇴근 / 업무 / 개인 분류. 추가 메모는 200자, 전체 메모는 300자까지이며 목적·메모를 한 번에 저장합니다. |
| `ADD_TRIP_EXPENSE` | “운행일지, 주차비 3,600원 기록해줘” | 주유·충전 / 통행료 / 주차 / 기타와 1원~1,000만원의 정수 금액. 실제 결제는 실행하지 않습니다. |
| `GET_DRIVING_LOG_SUMMARY` | “운행일지, 이번 달 업무 거리와 유류비 알려줘” | 오늘 / 이번 달 / 올해의 거리·업무비율·등록 경비와 종류별 합계·직원 수·확인 필요 건수. 설정된 km당 단가가 있을 때만 정산 참고액을 제공합니다. |

직원을 센서나 음성의 발화자만으로 판별하지 않습니다. 화면에서 선택한 직원이 운행 사용자로 기록됩니다. 요약 callback은 직원 실명·부서·주소·메모를 포함하지 않습니다. 실제/시연 기록도 합산하지 않습니다.

## 공식 연동과 남은 단계

- App Schema는 `app/src/main/assets/schema.json`에 포함합니다. 영문 설명, 고유 대문자 function 이름, 지원 parameter 타입, 필수 항목을 사용합니다.
- 공식 `component: service`는 Android Service 실행이 아니라 `sendBroadcast()` 전달입니다. 대응 action filter가 기존 `GleoActionReceiver`에 선언되어 있습니다.
- Receiver는 `goAsync()`와 5초 timeout을 사용합니다. 공식 제한 7초 안에 `APP_ACTION_RESPONSE`로 영어 `message`, `success/asking/fail`, JSON 객체 배열 문자열 `data`를 돌려줍니다. 자체 TTS나 마이크 접근은 추가하지 않습니다.
- 잘못된 JSON이 다른 extras로 조용히 전환되어 기록을 변경하지 않게 처리하고, 알 수 없는 비용 종류·소수 원 금액은 재질문합니다. 목적+메모는 같은 저장 트랜잭션에서 처리합니다.
- 공식 문서는 schema 검토·승인 후 활성화를 안내합니다. 또한 Android 외부 호출을 열기 위한 신뢰 발신자 package/signature/permission 계약은 해당 공개 문서에 명시되어 있지 않습니다. 확인 전까지 Receiver의 **`android:exported="false"`를 유지**합니다. 임의 앱이 직원 운행·비용을 바꾸게 열지 않습니다.
- 승인된 Gleo 발신자와 callback 수신자 계약을 확보한 뒤 보호 permission 또는 검증 가능한 발신자 인증을 적용하고 외부 수신을 활성화해야 합니다. 실제 기기의 Gleo 호출·중복 요청 처리·callback 수신까지 통합 검증해야 연결 완료로 표시할 수 있습니다.

## 시연 및 개발 검증

`gleoDataSummaryReply(data, period, today, zone)`는 저장·방송 없이 전달한 운행 사본의 요약을 생성합니다. 튜토리얼은 이 함수를 가상 데이터에 적용할 수 있습니다. 음성 명령 버튼은 “명령 동작 시연”으로 표시하며 실제 Gleo 연결 성공으로 표시하지 않습니다.

JUnit의 `GleoIntegrationTest`는 정수 금액, 손상 입력, 개인정보 없는 실제/시연 분리 요약, 읽기 전용 동작, 공식 3개 제한과 버전 일치를 검사합니다. `gradlew.bat testDebugUnitTest --tests ai.sharemate.app.GleoIntegrationTest`로 실행합니다.

## 공식 근거

- [Gleo Interface 개념](https://document.pleos.ai/docs/pleos-only/app-integration-guide/guide/core-concepts)
- [App Schema 작성: 최대 3개 동작·영문 설명·지원 타입](https://document.pleos.ai/docs/pleos-only/app-integration-guide/guide/app-capability)
- [Callback 작성: 응답 형식·7초 제한](https://document.pleos.ai/docs/pleos-only/app-integration-guide/guide/action_callback)
- [Android Intent·Broadcast 예제](https://document.pleos.ai/docs/pleos-only/app-integration-guide/guide/examples)
- [Schema 위치·검토·승인·활성화](https://document.pleos.ai/docs/pleos-only/app-integration-guide/guide/deliver-schema)
