package ai.sharemate.app

/*
 * The words of 월간·Excel and 차량 on the car (2.12, docs/design/DESIGN_2_12_SPEC.md §7–§9), kept in one place so that
 * ReportCopyTest can hold every line to the one-line rule: one fact in 해요체, no final period, inside the width the line
 * gets at the normal font (CarCopyRules). A line that needs a second fact folds it behind an ⓘ row (its *_MORE, which may
 * wrap) or into what TalkBack reads (SPOKEN_*). Lines built from data (a count, an amount, the period on screen) come from
 * the pure helpers beside their cards: settlementCopy, taxBasis, taxCardBlocker, taxDifferenceLine, exportConfirmLines,
 * reportRangeLabel, monthCloseNote, vehicleHeroCopy, phoneLinkTag, carReservationsEmptyText and carReservationsOverflow.
 * Money (2.13, DESIGN_2_13_SPEC §5) is never written here as a figure: an amount is a CarAmount beside its label, a rate
 * reads "km당 150원", and no line holds the ₩ sign or 원/km.
 */

/** 월간·Excel: the month close, 운행 현황, 경비·정산, the Excel view and 검토. */
internal object ReportCopy {
    // 월 마감 (ReportCloseCard): what a close means, said once the month is closed or ready to close.
    const val CLOSE_NOTE = "마감 후에도 운행은 고칠 수 있어요"
    const val CLOSE_NOTE_MORE = "고친 운행엔 ‘마감 후 수정’이 붙어요"
    /** A past month without trips: nothing waits for a close. */
    const val CLOSE_NOTHING = "운행이 없는 달은 마감하지 않아도 돼요"
    /**
     * This month while it has no trips (2.13): the card shows no steps and no close button that cannot enable, only what the
     * close waits for, beside 이전 달 보기.
     */
    const val CLOSE_WAITING = "운행이 기록되면 여기서 마감해요"

    /** The range tag's second tag in demo mode, beside [reportRangeLabel]: the figures under it are demo data. */
    const val RANGE_DEMO = "시연 데이터"

    // 운행 현황
    const val RATIO_TITLE = "업무사용비율"
    /** The form's own line numbers: ⑬ is ⑫ (business km) over ⑪ (total km). */
    const val RATIO_FORMULA = "⑬ = ⑫ ÷ ⑪"
    const val SPOKEN_RATIO_FORMULA = "업무 거리 나누기 총주행 거리"
    const val USAGE_TITLE = "직원별 운행"
    const val USAGE_EMPTY = "완료된 운행이 없어요"
    const val USAGE_NOTE = "지난 운행의 직원은 운행 상세에서 고쳐요"

    // 경비·정산
    const val EXPENSE_TITLE = "등록 경비"
    /** 2.13: a period without registered expenses shows this one line and no total (a list of money never draws a 0원 sum). */
    const val EXPENSE_EMPTY = "등록한 경비가 없어요"
    /** The label of the total row under the expense types. */
    const val EXPENSE_TOTAL = "경비 합계"
    const val EXPENSE_NOTE = "직접 등록한 금액이에요"
    val EXPENSE_NOTE_MORE = listOf("영수증 인증이나 지급 완료를 뜻하지 않아요")
    const val SETTLEMENT_TITLE = "유류비 정산"
    /** Without a km rate the card has no figure: it says what would give it one. */
    const val SETTLEMENT_ASK = "km당 단가를 입력하면 직원별로 계산해요"
    /** 2.13: one word for a trip without its person everywhere, 사용자 미지정 (the issue's own label). */
    const val SETTLEMENT_NOTE = "사용자 미지정 운행은 정산을 보류해요"
    val SETTLEMENT_NOTE_MORE = listOf("등록 경비는 이 금액에 자동으로 더하지 않아요")

    // Excel
    const val PREVIEW_DEMO = "시연 데이터 · 제출용이 아니에요"
    const val PREVIEW_FAILED = "미리보기를 만들지 못했어요"
    const val PREVIEW_FAILED_LINE = "기록은 그대로예요 · 기간을 다시 골라 주세요"
    /** Under an empty sheet: the saved file still carries the sheet, without rows. */
    const val PREVIEW_EMPTY_LINE = "저장하면 빈 시트로 들어가요"
    const val PREVIEW_NOTE = "Excel 인쇄 배치와 다를 수 있어요"
    val PREVIEW_NOTE_MORE = listOf("실제 저장 데이터로 만든 미리보기예요", "증빙·세무 요건은 제출 전 따로 확인해 주세요")
    /** The save bar's line: the phone file carries the whole log, whatever period is on screen. */
    const val SAVE_BAR_LINE = "휴대폰 앱에서 여는 전체 운행 파일"
    /** Why a save is off, for TalkBack: a range (or a log) without trips has nothing to save. */
    const val NOTHING_TO_SAVE = "저장할 운행이 없어요"

    // 검토
    const val AUDIT_TITLE = "증빙 준비 상태"
    /** The real sheet name of the workbook (LogbookExcel). */
    const val AUDIT_LINE = "세부 항목은 Excel 확인사항 시트에 있어요"
    const val AUDIT_NOTE = "제출 전 검토용이에요"
    val AUDIT_NOTE_MORE = listOf("직원·운행 목적·계기판 거리의 연결을 점검해요", "국세청 운행기록부 항목을 참고했어요", "영수증과 세무 요건은 따로 확인해 주세요")
    const val TAX_TITLE = "비용 참고"
    /** Shown while the annual cost is missing: why entering it matters. */
    const val TAX_CAP_NOTE = "운행기록부가 없으면 연 1,500만 원까지만 인정돼요"
    val TAX_CAP_MORE = listOf("차량 1대당 기준이에요", "연간 비용: 감가상각비·유류비·보험료·자동차세 등")
    const val TAX_COST = "연간 차량 비용"
    const val TAX_WITH_LOG = "업무 비율 적용액"
    const val TAX_WITHOUT_LOG = "기록부 없을 때"
    const val TAX_NOTE = "참고 계산이에요 · 세무사에게 확인해 주세요"
    val TAX_NOTE_MORE = listOf("보험 가입·보유 기간·비용별 한도 등을 반영하지 않은 참고 계산이에요", "실제 인정액과 신고 요건은 담당 세무사에게 확인해 주세요")

    // The confirm dialog before an Excel save with untidy trips (its body is exportConfirmLines).
    const val CONFIRM_TITLE = "정리되지 않은 운행이 있어요"
    const val CONFIRM_SAVE = "그래도 저장"
    const val CONFIRM_TIDY = "정리하러 가기"
}

/** 차량: the car card, 차량 정보, 차량 관리, 정비 기록, 휴대폰 연결, 차량 예약 and 사고·특이사항. */
internal object VehicleCopy {
    // The car card (VehicleHero) and 차량 정보
    const val NO_PLATE = "차량번호 미등록"
    const val ODOMETER = "계기판"
    const val SPOKEN_ODOMETER = "계기판 누적거리"
    const val GEAR = "기어"
    const val SPOKEN_GEAR = "수신 기어"
    /** A value the car cannot vouch for right now (a stale signal, an unknown gear). */
    const val CHECK = "확인 필요"
    const val FOOTER = "차량에서 받은 정보만 보여요"
    const val FOOTER_DEMO = "시연 신호 · 실제 차량 상태가 아니에요"
    const val DETAILS_TITLE = "차량 정보"
    const val SOURCE = "Pleos SDK · AAOS"
    const val SOURCE_DEMO = "시연 데이터"
    const val VIN = "차대번호"
    const val BATTERY_UNKNOWN = "확인 불가"
    /** The caveat of the last stored charge, for TalkBack: the car may have charged or driven since. */
    const val SPOKEN_STORED_CHARGE = "현재 상태와 다를 수 있음"
    const val NO_TRIP_YET = "완료된 운행이 아직 없어요"

    // The 지금 column's 차량 tile (2.15, NowVehicle.kt): the plate, the car's picture and three live figures, one stop for TalkBack.
    const val NOW_TITLE = "차량"
    const val NOW_OPEN = "차량 정보 보기"
    const val NOW_BATTERY = "배터리"
    const val NOW_SIGNAL = "신호"
    /** 신호 in the header pill's terms ([nowVehicleSignal]): the samples arrive, or the link is still starting; otherwise [CHECK]. */
    const val NOW_RECEIVING = "수신 중"
    const val NOW_CONNECTING = "연결 중"
    /** A figure the car does not report right now, and what TalkBack says in its place. */
    const val NOW_NONE = "—"
    const val SPOKEN_NOW_NONE = "정보 없음"
    /** The car drawing's description: a staged picture, never the car's real model (the 차량 page's card says the same). */
    const val DRAWING = "연출용 차량 이미지 · 실제 차종과 다를 수 있음"

    // 차량 관리 (deadlines) and 정비 기록
    const val CARE_TITLE = "차량 관리"
    const val CARE_EMPTY = "등록된 기한이 없어요"
    const val CARE_WAITING = "정비 주기는 계기판 값을 받으면 계산해요"
    const val CARE_LINE = "기한을 입력하면 30일 전부터 알려요"
    const val MAINTENANCE_TITLE = "정비 기록"
    const val MAINTENANCE_EMPTY = "정비 기록이 없어요 · 법인카드 결제는 자동으로 들어와요"
    const val MAINTENANCE_MORE = "최근 5건 · 전체는 Excel 차량관리 시트에 있어요"
    const val MAINTENANCE_KIND = "정비 종류"
    /** Under the form while 엔진오일·소모품 is chosen: that record also moves the last-service odometer. */
    const val MAINTENANCE_OIL = "마지막 정비 계기판 값도 함께 바뀌어요"

    // 휴대폰 연결
    const val PHONE_TITLE = "휴대폰 연결"
    const val PHONE_CODE = "연결 번호"
    const val PHONE_HOW = "같은 Wi-Fi에서 휴대폰 앱에 주소와 번호를 입력해요"
    const val PHONE_ANOTHER = "새 번호로 휴대폰을 더 연결해요"
    const val PHONE_EXPIRED = "연결 번호가 만료됐어요"
    const val PHONE_KEY = "인증키"
    const val SPOKEN_PHONE_KEY = "차량 인증키, 처음 연결할 때 입력"
    const val PHONE_ADDRESS = "주소"
    const val SPOKEN_PHONE_ADDRESS = "차량 주소"
    const val PHONE_ADDRESS_PENDING = "네트워크 주소 확인 중"
    const val PHONE_NOTE = "차량 기록이 원본이에요"
    val PHONE_NOTE_MORE = listOf("휴대폰에서 바꾼 목적·경비·특이사항은 ‘휴대폰’ 출처로 남아요", "휴대폰은 인증키로 차량을 확인한 뒤 번호를 보내요")
    // 2.13: with the link off the card shows the switch itself (SettingsCopy.PHONE_SYNC_*), where 2.12 sent the driver to 설정.
    // 2.15 (DESIGN_2_15 §6.2): the QR leads the pairing; the three typed steps fold behind PHONE_MANUAL.
    /** Beside the QR, in titleSmall (it may take two lines in the narrow column beside the 232-dp tile). */
    const val PHONE_QR = "휴대폰 앱에서 이 QR을 찍으세요"
    /** What TalkBack hears for the QR tile itself. */
    const val SPOKEN_PHONE_QR = "휴대폰 연결 QR"
    /** The fold over the three steps a phone types by hand. */
    const val PHONE_MANUAL = "직접 입력하기"
    /** The pill while no code is live, and while one is. */
    const val PHONE_NEW_CODE = "새 연결 번호"
    const val PHONE_RENEW_CODE = "번호 다시 만들기"

    // 차량 예약 and 사고·특이사항
    const val RESERVATIONS_TITLE = "차량 예약"
    /** The same words as the 지금 column's 예약 tile. */
    const val RESERVATIONS_EMPTY = "현재 잡힌 예약이 없어요"
    const val INCIDENTS_TITLE = "사고·특이사항"
    const val INCIDENTS_EMPTY = "기록이 없어요 · 홈의 버튼으로 바로 남겨요"
}
