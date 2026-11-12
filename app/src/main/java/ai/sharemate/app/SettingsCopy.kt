package ai.sharemate.app

/*
 * 설정's words on the car (2.12, docs/design/DESIGN_2_12_SPEC.md §6 and §9). A supporting line is one fact in 해요체 with no
 * final period, written to fit one line where it is drawn: beside a switch (368 dp at FULL, 306 on the narrowest two-up page),
 * across a half card (496) or after an ⓘ mark (466). What does not fit goes into an ⓘ row's `more`, or into the SPOKEN_ form
 * that TalkBack reads in place of the line. Titles are at most 8 syllables. SettingsCopyTest holds every line here to its
 * budget. Lines that follow the state are built beside their cards by pure helpers ([sceneStateLine], [careOdometerLine],
 * [cardRegistryLine], [driverListLine]), tested there and held to the same budgets. Words the restyle keeps as they were
 * (field labels and hints, 새 직원, 직원 추가, 수정 저장, 취소, 카드 저장, 운행 비서 열기) stay where they are.
 */
internal object SettingsCopy {
    /** The sub-tabs, as before. */
    val SECTIONS = listOf("법인·차량", "직원", "정산·세무", "기록")

    // ───────────────────────── 법인·차량 ─────────────────────────

    const val PROFILE_TITLE = "법인·차량 정보"
    /** True to the file: the profile fills the logbook's header cells (LogbookExcel.kt). */
    const val PROFILE_LINE = "저장하면 운행기록부 Excel에 들어가요"
    const val PROFILE_SAVE = "정보 저장"
    const val PROFILE_SAVED = "저장된 정보"
    const val PROFILE_DONE = "법인·차량 정보를 저장했어요"

    const val CARE_TITLE = "차량 관리 기한"
    const val CARE_LINE = "입력한 날짜 30일 전부터 홈·차량에 알려요"
    const val SPOKEN_CARE = "등록된 날짜 기준으로만 알려요 · 자동 조회는 하지 않아요 · 30일 전부터 홈과 차량 탭에 알림이 떠요"
    /** [careOdometerLine] before the car has reported an odometer. */
    const val CARE_ODOMETER_WAITING = "계기판 값은 차량 신호를 받으면 넣을 수 있어요"
    /** What TalkBack hears after the odometer line: an oil-change record moves the last-service value by itself. */
    const val SPOKEN_CARE_SERVICE = "엔진오일 정비 기록을 추가하면 마지막 정비 값이 저절로 바뀌어요"

    // ───────────────────────── 직원 ─────────────────────────

    /** The list's title, 0명 included. */
    fun driversTitle(count: Int) = "직원 ${count}명"
    /** A tag beside the title (2.13), as 정비 기록 carries its count: a fact, never a control. */
    const val DRIVERS_CAP = "최대 50명"
    /**
     * The pill at the end of the list's title row (2.13, as 정비 기록's 추가): it opens an empty editor. 2.12 had 새 직원 as an
     * outlined button under the rows, an orphan at the foot of a long list. TalkBack hears [SPOKEN_ADD_DRIVER].
     */
    const val ADD_DRIVER = "추가"
    const val SPOKEN_ADD_DRIVER = "새 직원 추가"
    /** Under the title while nobody is registered. */
    const val DRIVERS_EMPTY = "운행기록부 ④ 사용자 칸에 부서와 성명이 들어가요"
    const val DRIVERS_FULL = "직원은 50명까지 등록할 수 있어요"
    const val CONFIRM_TITLE = "출발 전 운전자 확인"
    const val CONFIRM_LINE = "직원 2명부터 하루 한 번 물어요"
    const val SPOKEN_CONFIRM = "직원이 2명 이상이면 하루 한 번 지금 운전하는 직원이 맞는지 물어요 · 기본 켜짐"
    /** The tag on the person driving now, in the list and in the editor. */
    const val NOW_DRIVING = "지금 운전"
    const val MAKE_DRIVER = "운전자로 선택"
    /** What TalkBack hears for [MAKE_DRIVER]: who, and what the choice means. */
    fun makeDriverSpoken(name: String) = "$name 님을 지금 운전하는 직원으로 선택"
    const val EDIT_NOTE = "지난 운행의 ④ 사용자 칸도 함께 바뀌어요"
    const val DELETE_DRIVER = "직원 삭제"
    const val KEEP_DRIVER = "운행 기록이 있어 삭제할 수 없어요"
    const val SPOKEN_KEEP_DRIVER = "운행 기록이 있어 삭제할 수 없어요 · 부서와 성명은 고칠 수 있어요"

    // ───────────────────────── 정산·세무 ─────────────────────────

    const val COST_TITLE = "정산·세무 기준"
    /** The 연간 차량 관련 비용 field's hint while it is empty: what the sum covers (the paragraph under the form in 2.11). */
    const val COST_HINT = "감가상각·리스·렌트·유류·보험·자동차세 등 합계"
    const val COST_SAVE = "기준 저장"
    const val COST_SAVED = "저장된 기준"
    const val COST_DONE = "정산·세무 기준을 저장했어요"
    /** The span 설정's settlement card covers, which the page around it does not say. */
    const val SETTLEMENT_PERIOD = "이번 달"

    // ───────────────────────── 기록 ─────────────────────────

    const val SCENE_TITLE = "화면 밝기"
    const val PRIVACY_TITLE = "기록 및 개인정보"
    const val TUTORIAL_LINK = "시연 체험 다시보기"
    const val ADDRESS_TITLE = "출발·도착 주소 저장"
    const val ADDRESS_LINE = "끄면 계기판 거리와 시각만 저장해요"
    const val SPOKEN_ADDRESS = "끄면 계기판 거리와 시각만 남기고 내비 주소는 저장하지 않아요"
    const val AUTOPILOT_TITLE = "자동 완성 · 기본 켜짐"
    const val AUTOPILOT_LINE = "반복 도착지의 목적과 카드 결제를 자동 입력해요"
    const val SPOKEN_AUTOPILOT = "반복 도착지의 목적과 카드 결제 비용을 사람 손 없이 채워요 · 자동으로 들어간 항목은 ‘자동 분류’·‘카드 자동 매칭’으로 " +
        "표시되고 언제든 되돌릴 수 있어요 · 끄면 정차 후 직접 확인해요"
    const val PHONE_SYNC_TITLE = "휴대폰 실시간 연동"
    const val PHONE_SYNC_LINE = "휴대폰과 운행 정보를 실시간 공유해요"
    const val SPOKEN_PHONE_SYNC = "같은 Wi-Fi·핫스팟의 휴대폰 SHAREMATE와 암호화 연결로 운행·경비·특이사항을 실시간 공유해요 · " +
        "휴대폰에서 바꾼 내용은 ‘휴대폰’ 출처로 차량에 기록돼요"
    const val DEMO_TITLE = "시연 모드"
    const val DEMO_LINE = "실제 차량 대신 15배속 시연 주행으로 기록해요"
    const val SPOKEN_DEMO = "$DEMO_LINE · 시연 기록은 따로 표시되고 실제 기록과 섞이지 않아요"
    /** The first ⓘ under the switches: where the records live; unfolded, when the navigation address is read. */
    const val STORAGE_INFO = "기록은 이 차량에만 두고 백업·외부 서버로 보내지 않아요"
    val STORAGE_MORE = listOf("내비 주소는 초기 연결, 출발·주차 전환과 운행 중에 조회해요", "주소 저장 여부는 위 설정을 따라요")
    /** The second ⓘ: the facts of the PLEOS SDK card of 2.11, which 2.12 removes, as in the mock. */
    const val SIGNALS_INFO = "차량 신호는 기록에 필요한 것만 읽어요"
    val SIGNALS_MORE = listOf("Vehicle SDK로 거리·배터리·VIN을, AAOS로 기어와 충전 상태를 읽어요", "내비 주소는 받은 경우에만 기록해요",
        "권한·미지원·수신 지연은 ‘확인 필요’로 표시해요", "차량 제어와 내비 목적지 변경은 하지 않아요")

    const val AUTOFILL_TITLE = "자동 완성"
    /** What TalkBack hears in place of [cardRegistryLine]: the two paragraphs of 2.11's card, in 해요체. */
    const val SPOKEN_CARDS = "반복 도착지는 목적을 스스로 채우고, 휴대폰이 받은 카드 승인을 차량 기록과 맞춰 비용에 넣어요 · " +
        "자동으로 들어간 항목은 ‘자동 분류’·‘카드 자동 매칭’으로 표시되고 언제든 고칠 수 있어요 · " +
        "등록하면 다른 카드의 알림은 무시하고, 가맹점 이름만으로 알 수 없는 결제도 차량 기록과 맞춰 봐요 · " +
        "등록하지 않으면 주유·충전·통행료·주차로 확인되는 결제만 받아요"
    const val CARDS_ERROR = "끝 4자리 숫자를 쉼표로 구분해 입력해 주세요"

    /** No "음성으로": the assistant has no microphone ([ASSISTANT_NOTE]). */
    const val ASSISTANT_LINE = "말하듯 적으면 목적·비용을 기록하고 이달을 요약해요"
    /** What a phrase chip does, for TalkBack's action. */
    const val ASSISTANT_CHIP_ACTION = "운행 비서에서 열기"
    /** The assistant card's ⓘ ([ASSISTANT_NOTE]) unfolded: the Gleo note of 2.11's privacy card. */
    val ASSISTANT_MORE = listOf("Gleo 음성 연동은 심사·호출자 검증 후 제공 예정이에요", "승인되면 같은 세 동작이 음성으로 이어져요",
        "운행 목적과 비용은 정차 후 화면에서 기록해 주세요")
}
