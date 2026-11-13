package ai.sharemate.app

/*
 * The words of the header bar and 홈 (2.13, docs/design/DESIGN_2_13_SPEC.md §2 and §3.6), in one place so HomeCopyTest can
 * hold every line to the car's one-line rule: 해요체, one line at the normal font within the place it is drawn, no final
 * period, no space before a particle. A line that says less than the old sentence keeps that sentence as its `SPOKEN_*`
 * form, which TalkBack reads instead; a `SPOKEN_*` is always the longer of the two.
 */

/** The header's search bar (§2) and its neighbour, 시연 체험. */
internal object HeaderCopy {
    /** What the bar shows from 280 dp (PHRASE): 12 units at most, so it shows whole there. */
    const val BAR_PHRASE = "운행 비서에게 물어보세요"
    /** The example after it from 496 dp (EXAMPLE), ellipsised before the phrase ever is. */
    const val BAR_EXAMPLE = "예: 주차비 4,500원 추가해줘"
    /** The bar as a header tool with its label (LABEL, 160 dp up). */
    const val BAR_LABEL = "운행 비서"
    /** What TalkBack says for the bar in every mode: the visible words first (WCAG 2.5.3), then the example. */
    const val SPOKEN_BAR = "$BAR_PHRASE, $BAR_EXAMPLE"
    /** The bar's click label. */
    const val BAR_CLICK = "운행 비서 열기"
    const val TUTORIAL = "시연 체험"
}

/**
 * Every static line of 홈 (§3): the status line, the dock, the demo strip and the attention cards, with their spoken forms.
 * 2.14 (docs/design/DESIGN_2_14_HOME.md) adds the brand line, the tall dock's header, the idle card and the setup tiles.
 */
internal object HomeCopy {
    // ───── the brand line over the greeting (2.12's, back in 2.14; decorative for TalkBack)
    const val BRAND = "SHAREMATE  ·  FLEET JOURNAL"

    // ───── the status line under the greeting (36 units, the 720-dp reading measure)
    const val STATUS_READY = "출발하면 자동으로 기록해요"
    const val SPOKEN_STATUS_READY = "출발하면 자동으로 기록하고, 주차하면 목적과 비용을 정리해 드려요"
    const val STATUS_DEMO = "시연 주행을 15배속으로 기록해요"
    const val SPOKEN_STATUS_DEMO = "시연 주행은 15배속으로 기록되고 ‘시연 데이터’로 구분돼요"
    const val STATUS_DENIED = "차량 정보 권한이 있어야 기록해요"
    const val SPOKEN_STATUS_DENIED = "계기판 누적거리를 읽으려면 차량 정보 권한이 필요해요"
    const val STATUS_STARTING = "차량과 연결하고 있어요"
    const val STATUS_NO_SIGNAL = "차량 데이터를 받을 수 없어요 · 연결을 확인해 주세요"
    const val SPOKEN_STATUS_NO_SIGNAL = "차량 데이터를 받을 수 없어요, 연결 상태를 확인해 주세요"
    /** The tidy line: nothing left, a trip done today, the signal ready ([allDoneText] with the signal there). */
    const val STATUS_TIDY = "오늘 정리 끝 · 다음 운행도 자동 기록"
    const val SPOKEN_STATUS_TIDY = "오늘 정리 끝, 다음 운행도 출발하면 자동으로 기록해요"
    /** What TalkBack adds after a recording trip's "09:12 출발 · 3.2 km". */
    const val SPOKEN_RECORDING_TAIL = "주차하면 목적과 비용을 정리해 드려요"
    /** The queue's line starts "확인할 일 3건" ([attentionQueueLine]). */
    const val QUEUE_HEAD = "확인할 일"

    // ───── the incident dock and the demo strip
    const val DOCK_TITLE = "사고·특이사항"
    /** The tall dock's caption after the title (2.12's words), and on its right, muted, while the phone link is on. */
    const val DOCK_CAPTION = "한 번 누르면 차량 정보가 바로 기록돼요"
    const val DOCK_SHARED = "휴대폰에도 공유"
    /** The compact dock's lead line (13 units, its 224-dp lead), with the phone link on and off. */
    const val DOCK_LINE = "바로 기록 · 휴대폰에도 공유"
    const val DOCK_LINE_OFF = "누르면 바로 기록돼요"
    const val SPOKEN_DOCK = "$DOCK_TITLE, $DOCK_CAPTION"
    const val DEMO_TAG = "시연 모드"
    const val DESTINATION_CLICK = "열기"

    // ───── 최근 운행 (2.14: the idle card, while nothing waits; 2.15: three rows, or how a trip is recorded)
    const val IDLE_TITLE = "최근 운행"
    const val IDLE_TAG = "확인할 일 없음"
    const val SPOKEN_IDLE_TAG = "지금 확인할 일이 없어요"
    const val IDLE_ACTION = "운행기록 보기"
    const val IDLE_EMPTY = "아직 운행이 없어요"
    /**
     * Without any trip (2.15 §4): the three steps of 운행기록's first-run hero (§2.2's FIRST case), as tiles, then the walkthrough.
     * The hero reads its title, steps and way on from here ([LogCopy.HERO_FIRST], [LogCopy.HERO_FIRST_STEPS], [LogCopy.HERO_TUTORIAL]).
     */
    const val IDLE_STEP_DRIVE = "D로 출발"
    const val IDLE_STEP_DRIVE_LINE = "기록이 시작돼요"
    const val SPOKEN_IDLE_STEP_DRIVE_LINE = "기어를 P에서 D로 바꾸면 운행 기록이 자동으로 시작돼요"
    const val IDLE_STEP_PARK = "P로 주차"
    const val IDLE_STEP_PARK_LINE = "거리와 시간이 저장돼요"
    const val SPOKEN_IDLE_STEP_PARK_LINE = "P로 주차하면 계기판 거리와 운행 시간이 저장돼요"
    const val IDLE_STEP_PURPOSE = "목적 확인"
    const val IDLE_STEP_PURPOSE_LINE = "홈에서 한 번 눌러요"
    const val SPOKEN_IDLE_STEP_PURPOSE_LINE = "주차하면 홈에 뜨는 운행 목적을 한 번 눌러 확인해요"
    const val IDLE_DEMO = "시연 체험 보기"
    val IDLE_STEPS = listOf(IdleStep(IDLE_STEP_DRIVE, IDLE_STEP_DRIVE_LINE, SPOKEN_IDLE_STEP_DRIVE_LINE),
        IdleStep(IDLE_STEP_PARK, IDLE_STEP_PARK_LINE, SPOKEN_IDLE_STEP_PARK_LINE), IdleStep(IDLE_STEP_PURPOSE, IDLE_STEP_PURPOSE_LINE, SPOKEN_IDLE_STEP_PURPOSE_LINE))

    /**
     * What TalkBack hears for a step tile, its one stop: "1단계, D로 출발, 기어를 P에서 D로 바꾸면 …", in the hero's form
     * ([LogCopy.heroStepSpoken]), so the same step sounds the same on 홈 and in 운행기록.
     */
    fun spokenIdleStep(number: Int, step: IdleStep) = LogCopy.heroStepSpoken(number, step.title, step.spokenLine)

    // ───── the pager
    const val PAGER_PREVIOUS = "이전 할 일"
    const val PAGER_NEXT = "다음 할 일"
    const val PAGER_FIRST = "이전 할 일 없음"
    const val PAGER_LAST = "다음 할 일 없음"

    // ───── 특이사항 대응
    const val INCIDENT_HEADLINE = "지금 할 일"
    const val INCIDENT_PHOTOS_SAVED = "휴대폰에서 찍어 차량에 저장했어요"
    const val INCIDENT_PHONE = "사진과 메모는 휴대폰 앱에서 덧붙여요"
    const val SPOKEN_INCIDENT_PHONE = "휴대폰 SHAREMATE에 같은 기록이 떠 있어요, 현장 사진과 메모는 휴대폰에서 덧붙이세요"
    const val INCIDENT_SYNC_OFF = "사진·메모는 휴대폰 연동을 켜면 덧붙일 수 있어요"
    const val SPOKEN_INCIDENT_SYNC_OFF = "사진·메모는 휴대폰 연동을 켜면 덧붙일 수 있어요, 설정 › 기록에서 휴대폰 실시간 연동을 켜 주세요"
    const val INCIDENT_DELETE = "기록 삭제"
    const val INCIDENT_DONE = "처리 완료"
    const val PHOTO_DIALOG_TITLE = "사진을 지울까요?"
    const val PHOTO_DIALOG_LINE = "지운 사진은 복구되지 않아요"
    const val DELETE_DIALOG_LINE = "기록은 되돌릴 수 있지만 사진은 복구되지 않아요"
    const val CANCEL = "취소"
    const val DELETE = "삭제"
    const val UNDO = "실행 취소"

    // ───── 차량 권한
    const val PERMISSION_TITLE = "차량 권한"
    const val PERMISSION_TAG = "권한 필요"
    const val PERMISSION_HEADLINE = "차량 정보 권한이 필요해요"
    const val PERMISSION_LINE = "계기판 누적거리를 읽어 자동으로 기록해요"
    const val PERMISSION_ACTION = "차량 정보 권한 허용"

    // ───── 목적 확인 · 목적 바꾸기
    const val PURPOSE_TITLE = "목적 확인"
    const val RECLASSIFY_TITLE = "목적 바꾸기"
    const val PURPOSE_HEADLINE = "운행 목적만 확인해 주세요"
    const val RECLASSIFY_HEADLINE = "어떤 목적이었나요?"
    const val PURPOSE_OTHER = "다른 목적"
    const val SPOKEN_PURPOSE_OTHER = "다른 목적 선택지 펼치기"
    const val PURPOSE_KEEP = "그대로 두기"
    const val NO_DRIVER = "사용자 미지정"
    const val SPOKEN_DRIVER_CHANGE = "이 운행의 운전자 바꾸기"
    const val SPOKEN_DRIVER_SET = "이 운행의 운전자 지정"

    // ───── 카드 확인
    const val REVIEW_TITLE = "카드 확인"
    const val REVIEW_MATCHED = "이 운행의 비용이 맞나요?"
    const val REVIEW_NEAREST = "가장 가까운 운행의 비용이 맞나요?"
    const val REVIEW_NO_TRIP = "어느 운행의 비용인지 찾지 못했어요"
    const val REVIEW_YES = "맞아요"
    const val NOT_COMPANY = "회사 비용 아님"
    const val REVIEW_ONE_BY_ONE = "한 건씩"

    // ───── 충전 비용
    const val CHARGE_NONE = "비용 없음"
    const val CHARGE_FIELD = "직접 입력"
    const val CHARGE_REGISTER = "등록"
    const val SPOKEN_CHARGE_REGISTER = "등록, 최근 운행의 주유·충전 경비로 들어가요"
    const val CHARGE_WAIT = "카드 승인 기다리기"
    const val CHARGE_CHIP_ACTION = "이 금액으로 등록"
    const val CHARGE_DETECTED = "감지"

    // ───── 방금 운행
    const val FINISHED_TITLE = "방금 운행"
    const val FINISHED_CONFIRM = "확인"
    const val FINISHED_ADD_COST = "비용 추가"
    const val SPOKEN_FINISHED_ADD_COST = "이 운행에 비용 추가"

    // ───── 카드 반영
    const val MATCHED_TITLE = "카드 반영"
    const val MATCHED_LINE = "내역에서 한 건씩 되돌릴 수 있어요"
    const val MATCHED_SHOW = "내역 보기"
    const val CLOSE = "닫기"
    const val SPOKEN_MATCHED_CLOSE = "자동 반영 알림 닫기"

    // ───── 시작 설정 (2.14: three tiles, each its step's button; the line is what the step is for, then what it holds)
    const val SETUP_TITLE = "시작 설정"
    const val SETUP_HEADLINE = "운행기록부에 들어갈 정보를 등록해 주세요"
    const val SETUP_COMPANY = "법인·차량 정보"
    const val SETUP_DRIVERS = "직원 등록"
    const val SETUP_PICK = "운전자 선택"
    const val SETUP_COMPANY_LINE = "운행기록부 머리말에 들어가요"
    const val SETUP_DRIVERS_LINE = "④ 사용자 칸에 들어가요"
    const val SPOKEN_SETUP_DRIVERS_LINE = "운행기록부 ④ 사용자 칸에 부서와 성명이 들어가요"
    const val SETUP_PICK_LINE = "오늘 운전하는 직원을 골라요"
    /** The 운전자 선택 tile while nobody is registered: it is disabled and says what it waits for. */
    const val SETUP_PICK_WAITING = "직원을 등록하면 고를 수 있어요"
    const val SETUP_STATE_DONE = "완료"
    const val SETUP_STATE_NEXT = "다음 단계"
    const val SETUP_STATE_WAITING = "직원 등록 뒤"

    // ───── 휴대폰 연결
    const val PAIRING_PREPARING = "휴대폰 연결을 준비하고 있어요"

    // ───── 월 마감
    const val CLOSE_TITLE = "월 마감"
    const val CLOSE_OPEN_REPORT = "월간에서 보기"
    const val CLOSE_TIDY = "정리하기"
    const val CLOSE_REVIEW = "확인하기"
    const val CLOSE_REMINDER_TAG = "마감 전"
    const val CLOSE_REMINDER_CLOSE = "마감하기"
    const val SPOKEN_REMINDER_CLOSE = "이달 마감 안내 닫기"

    // ───── 차량 관리
    const val CARE_TITLE = "차량 관리"
    const val CARE_SNOOZE = "7일 뒤 다시"
    const val CARE_SNOOZED = "7일 뒤 다시 알려요"
    const val CARE_NEW_DUE = "새 기한 입력"

    // ───── 충전 비용 (카드 승인 대기)
    const val WAITING_TAG = "카드 승인 대기"
    const val WAITING_HEADLINE = "카드 승인을 기다리고 있어요"
    const val WAITING_NOW = "지금 입력"
    const val WAITING_MONTH = "이달 완성"
    const val WAITING_AUTO_PURPOSE = "목적 자동 분류"
    const val WAITING_AUTO_CARD = "카드 자동 반영"

    /** Every card title (8 syllables at most), for the copy test. */
    val TITLES = listOf(DOCK_TITLE, PERMISSION_TITLE, PURPOSE_TITLE, RECLASSIFY_TITLE, REVIEW_TITLE, FINISHED_TITLE, MATCHED_TITLE, SETUP_TITLE,
        CLOSE_TITLE, CARE_TITLE, VehicleCopy.PHONE_TITLE, IDLE_TITLE, SETUP_COMPANY, SETUP_DRIVERS, SETUP_PICK) + IncidentKind.entries.map { it.label } +
        IDLE_STEPS.map { it.title }

    /** The status lines with what TalkBack says for them. */
    val STATUS_LINES = listOf(STATUS_READY to SPOKEN_STATUS_READY, STATUS_DEMO to SPOKEN_STATUS_DEMO, STATUS_DENIED to SPOKEN_STATUS_DENIED,
        STATUS_NO_SIGNAL to SPOKEN_STATUS_NO_SIGNAL, STATUS_TIDY to SPOKEN_STATUS_TIDY, STATUS_STARTING to STATUS_STARTING)

    /** The card lines (headlines and supporting lines) with what TalkBack says for them. */
    val CARD_LINES = listOf(INCIDENT_PHONE to SPOKEN_INCIDENT_PHONE, INCIDENT_SYNC_OFF to SPOKEN_INCIDENT_SYNC_OFF, PERMISSION_HEADLINE to PERMISSION_HEADLINE,
        PERMISSION_LINE to PERMISSION_LINE, PURPOSE_HEADLINE to PURPOSE_HEADLINE, REVIEW_NEAREST to REVIEW_NEAREST, REVIEW_NO_TRIP to REVIEW_NO_TRIP,
        MATCHED_LINE to MATCHED_LINE, SETUP_HEADLINE to SETUP_HEADLINE, WAITING_HEADLINE to WAITING_HEADLINE, PHOTO_DIALOG_LINE to PHOTO_DIALOG_LINE,
        DELETE_DIALOG_LINE to DELETE_DIALOG_LINE, IDLE_EMPTY to IDLE_EMPTY)

    /**
     * The setup tiles' lines (2.14) with what TalkBack says for them; each fits a third of the card beside its disc. 2.15's idle
     * step tiles take the same look and place.
     */
    val SETUP_LINES = listOf(SETUP_COMPANY_LINE to SETUP_COMPANY_LINE, SETUP_DRIVERS_LINE to SPOKEN_SETUP_DRIVERS_LINE, SETUP_PICK_LINE to SETUP_PICK_LINE,
        SETUP_PICK_WAITING to SETUP_PICK_WAITING) + IDLE_STEPS.map { it.line to it.spokenLine }
}
