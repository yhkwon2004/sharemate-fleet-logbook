package ai.sharemate.app

/*
 * The words of what the car lays over its pages (2.13, docs/design/DESIGN_2_13_SPEC.md §8): the follow-up bar, the driver
 * picker, the shell's dialogs, the assistant sheet and the 시연 체험 walkthrough. The 2.12 rule holds: one fact per line in
 * 해요체, no final period, no space before a particle, written to fit where it is drawn (a dialog line DIALOG_LINE_UNITS at
 * 600 dp; a picker, sheet or walkthrough line a card's 26 units; the bar's message 32 units beside one pill, 24 beside two).
 * What does not fit goes into an ⓘ row's `more` or into the SPOKEN_ form TalkBack reads in place of the line. OverlayCopyTest
 * holds every line here to its budget. Words the restyle keeps as they were (닫기, 확인, 보내기, 고치기, 다시 생성, 고쳐 묻기,
 * 다시 시도, the six walkthrough titles and its buttons) stay where they are drawn.
 */
internal object OverlayCopy {
    // ───────────────────────── follow-up bar ─────────────────────────

    /** The one word for taking a change back, on the bar and in every bubble. */
    const val UNDO = "실행 취소"

    /** A trip classified, with nothing else to offer: "일반 업무로 기록했어요". */
    fun classified(purpose: TripPurpose) = "${withRo(purpose.label)} 기록했어요"

    /** A trip classified while [others] trips of that day still wait: "일반 업무로 기록 · 같은 날 미분류 3건". */
    fun classifiedWithOthers(purpose: TripPurpose, others: Int) = "${withRo(purpose.label)} 기록 · 같은 날 미분류 ${others}건"

    /** The bar's second action beside the undo: apply the same purpose to the [others]. */
    fun sameForOthers(others: Int) = "${others}건도 같게"

    /** The answer to [sameForOthers]: "3건을 일반 업무로 기록했어요". */
    fun classifiedMany(purpose: TripPurpose, count: Int) = "${count}건을 ${withRo(purpose.label)} 기록했어요"

    /** The bar's message for an assistant change too long for its one line ([assistantFollowUp]). */
    const val ASSISTANT_CHANGED = "운행 비서가 기록을 바꿨어요"

    // ───────────────────────── 운전자 선택 ─────────────────────────

    const val PICKER_NOW_TITLE = "지금 운전하는 직원"
    const val PICKER_TRIP_TITLE = "이 운행의 운전자"
    /** [pickerLead] while a trip records: the pick reaches it too. */
    const val PICKER_RECORDING = "새 운행과 기록 중인 운행에 바로 반영돼요"
    /** [pickerLead] while parked. */
    const val PICKER_NEXT = "새 운행부터 적용돼요"
    const val PICKER_EMPTY = "등록된 직원이 없어요"
    const val PICKER_SEARCH = "이름이나 부서로 찾기"
    const val PICKER_NO_MATCH = "맞는 직원이 없어요"
    /** The ⓘ under the cards when the pick changes a recorded trip. */
    const val PICKER_TRIP_NOTE = "④ 사용자 칸이 바뀌고 수정 이력에 남아요"
    const val SPOKEN_PICKER_TRIP_NOTE = "운행기록부 ④ 사용자 칸이 바뀌고 수정 이력에 남아요"
    const val PICKER_MANAGE = "직원 추가·관리"
    const val PICKER_ADD = "직원 추가"

    // ───────────────────────── dialogs ─────────────────────────

    const val ERROR_TITLE = "확인이 필요해요"
    const val SAVED_TITLE = "저장 위치"
    /** Over the saved file's path when the car has no file picker. */
    const val SAVED_LINE = "파일 선택기가 없어 앱 보관 폴더에 저장했어요"
    /** The toast when every permission the app asks for is already decided (MainActivity). */
    const val NO_MORE_PERMISSIONS = "더 요청할 권한이 없어요"

    // ───────────────────────── 운행 비서 ─────────────────────────

    const val ASSISTANT_ASK = "무엇을 도와드릴까요?"
    const val ASSISTANT_ASK_LINE = "아래 제안을 누르거나 짧게 적어 주세요"
    /** The intro's ⓘ: a change is shown before it is made. */
    const val ASSISTANT_HOW = "바꾸기 전에 이해한 내용을 먼저 보여 드려요"
    val ASSISTANT_HOW_MORE = listOf("누른 뒤에만 기록해요", "할 수 있는 일: 목적 기록 · 비용 추가 · 요약 · 차량 기한 · 월 마감 확인")

    /** The line under a write the assistant waits to make; [cost] names its button 추가, otherwise 기록. */
    fun assistantConfirm(cost: Boolean) = "맞으면 ${if (cost) "추가를" else "기록을"} 눌러 주세요 · 바로 되돌릴 수 있어요"

    /** What TalkBack hears for [assistantConfirm]: the long form of 2.12, with the particle right. */
    fun spokenAssistantConfirm(cost: Boolean) = "이렇게 이해했어요, 맞으면 ${if (cost) "추가를" else "기록을"} 눌러 주세요, 기록 뒤에도 바로 되돌릴 수 있어요"

    const val ASSISTANT_UNDONE = "되돌렸어요 · 기록은 이전 상태예요"
    /** What a suggestion chip does, for TalkBack. */
    const val ASSISTANT_CHIP_ACTION = "운행 비서에 보내기"

    // ───────────────────────── 시연 체험 ─────────────────────────

    const val TUTORIAL_TITLE = "시연 체험"
    fun tutorialStep(step: Int) = "$step / 6"
    fun spokenTutorialStep(step: Int) = "6단계 중 ${step}단계"
    const val SPOKEN_TUTORIAL_CLOSE = "시연 닫기"
    const val TUTORIAL_DATA = "시연 데이터 · 실제 기록과 설정은 바꾸지 않아요"
    const val SPOKEN_TUTORIAL_DATA = "시연 데이터 · 실제 직원·운행기록과 차량 설정은 바꾸지 않아요"

    /** The story card's line of each step; a step with [TUTORIAL_STORY_MORE] draws it as an ⓘ row. */
    val TUTORIAL_STORY = listOf(
        "선택한 직원이 이번 운행에 기록돼요",
        "시연 신호로 실제와 같은 기록 로직을 돌려요",
        "운행 목적은 운전자가 확인해요",
        "입력한 비용만 보관해요 · 영수증 인증·자동 결제 없음",
        "정산 참고액과 실제 등록 경비를 따로 보여요",
        "국세청 항목을 참고한 검토용 파일이에요",
    )
    /** What an ⓘ story line unfolds, per step (empty: a plain line). */
    val TUTORIAL_STORY_MORE = listOf(
        emptyList(),
        listOf("실제 SDK 연결은 Virtual Console에서 검증해요"),
        listOf("센서가 업무 여부를 정하지 않아요"),
        emptyList(),
        emptyList(),
        listOf("세무 신고·제출은 자동으로 하지 않아요"),
    )
    /** What TalkBack hears for step 5's line (index 4): what the reference sum is made of. */
    const val SPOKEN_TUTORIAL_OVERVIEW = "업무 거리 × 회사 단가의 참고액과 실제 등록 경비를 구분해요"

    const val TUTORIAL_DRIVER = "운전자"
    const val TUTORIAL_DRIVER_LINE = "시연용 직원이에요 · 실제 직원은 설정에서 관리해요"
    const val TUTORIAL_RECORDING = "기록 중"
    const val TUTORIAL_RECORDING_LINE = "출발·주차 신호와 누적거리로 기록해요"
    const val TUTORIAL_PURPOSE_LINE = "목적을 고른 뒤 ‘목적 확인’을 눌러요"
    const val TUTORIAL_EXPENSE = "주유·충전"
    const val TUTORIAL_EXPENSE_NOTE = "시연은 금액을 미리 채워요"
    val TUTORIAL_EXPENSE_MORE = listOf("실제 경비는 운행 상세에서 종류와 금액만 입력해요")

    /** The walkthrough's rate and reference sum: "km당 200원 · 정산 참고 2,480원". */
    fun tutorialRate(perKmWon: Long, referenceWon: Long) = "km당 ${formatWon(perKmWon)} · 정산 참고 ${formatWon(referenceWon)}"

    const val TUTORIAL_VOICE = "음성 요약 체험"
    const val TUTORIAL_VOICE_CHIP = "“오늘 운행 요약해 줘”"
    const val TUTORIAL_VOICE_ACTION = "요약 실행"
    const val TUTORIAL_VOICE_NOTE = "앱 안의 요약 처리만 체험해요"
    val TUTORIAL_VOICE_MORE = listOf("실제 마이크·Gleo 연결은 스키마 등록과 승인 후 쓸 수 있어요")
    /** The last step's button while the file is made (its label stays 시연 Excel 저장). */
    const val TUTORIAL_SAVING = "만드는 중…"
}
