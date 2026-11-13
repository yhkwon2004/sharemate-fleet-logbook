package ai.sharemate.app

import java.time.LocalDate
import java.time.YearMonth

/*
 * The words of 운행기록 on the car (2.13, docs/design/DESIGN_2_13_SPEC.md §7), kept in one place so that LogCopyTest can hold
 * every line to the one-line rule: one fact in 해요체, no final period, inside the width the line gets at the normal font
 * (CarCopyRules). A line that says more than fits keeps its long form for TalkBack (SPOKEN_*). Lines built from a trip (its
 * row, its issue line, the month's totals, an answer on the follow-up bar) come from the pure helpers below, which never
 * touch the phone: its list keeps routeText and its own rows.
 */

/** 운행기록: the header, the three lists, the empty hero and 운행 상세. */
internal object LogCopy {
    // The header (LogPane)
    const val ALL_EMPLOYEES = "전체 직원"
    /** What TalkBack calls the employee chip, before its label: "직원 필터, 전체 직원". */
    const val SPOKEN_EMPLOYEE_FILTER = "직원 필터"
    /** The short window's one chip, which holds the three filters and the employee. */
    const val SPOKEN_LIST_FILTER = "목록 필터"
    /** Every trip on screen shares the demo flag, so the page says it once, in a tag. */
    const val DEMO = "시연 데이터"

    // A trip row (TripRow) and the detail's person
    const val NO_DRIVER = "사용자 미지정"
    /** A trip changed after its month was closed ([editedAfterClose]). */
    const val EDITED = "마감 후 수정"
    const val ROW_ACTION = "운행 상세 보기"
    const val ROW_FLAGGED = "점검 필요"

    // 점검 필요 (IssueList): the two kinds of issue and the cards' buttons
    /** 목적 미분류 and 사용자 미지정: a month cannot be closed with them ([issueRequired]). */
    const val REQUIRED = "마감 전 필수"
    const val REFERENCE = "참고"
    const val ASSIGN_DRIVER = "사용자 지정"
    const val DELETE_RECORD = "기록 삭제"
    const val FILL_GAP = "누락 구간을 운행으로 추가"
    /** The detail's pill for the same action; TalkBack hears [FILL_GAP]. */
    const val FILL_GAP_SHORT = "구간 추가"
    // The issue line of each check ([issueLine]); the long form TalkBack hears is IssueType.detail, which the phone shares.
    const val ISSUE_ZERO = "시동만 켠 기록이면 삭제해도 돼요"
    const val ISSUE_GAP = "앞 운행과 계기판 거리가 이어지지 않아요"
    const val ISSUE_OVERLAP = "출발 계기판이 앞 운행 도착보다 작아요"
    const val ISSUE_SPEED = "평균 시속 200 km를 넘어요"
    const val ISSUE_RECOVERED = "앱이 멈춰 마지막 수신 시각으로 끝냈어요"
    const val ISSUE_CARD_ON_PERSONAL = "회사 규정에 따라 처리해 주세요"

    // An empty list (2.15, CarEmptyHero, docs/design/DESIGN_2_15.md §2.2): the title on the scene, at most one line under it,
    // the tiles and the ways on ([logEmptyHero]). A title that leaves out the month or the employee keeps them for TalkBack.
    const val HERO_FIRST = "아직 운행이 없어요"
    /** The line under FIRST and under this month's MONTH title. */
    const val HERO_START = "출발하면 자동으로 기록해요"
    const val EMPTY_ISSUES = "점검할 운행이 없어요"
    const val EMPTY_ISSUES_LINE = "계기판 누적거리가 끊김 없이 이어져요"
    const val EMPTY_MATCHED = "자동으로 들어간 카드 결제가 없어요"
    /** FIRST's three steps, each a title over its line. */
    val HERO_FIRST_STEPS = listOf("D로 출발" to "기록이 시작돼요", "P로 주차" to "거리와 시간이 저장돼요", "목적 확인" to "홈에서 한 번 눌러요")
    /** MATCHED's three steps: how a card payment reaches a trip by itself. */
    val HERO_CARD_STEPS = listOf("카드 등록" to "끝 4자리만 적어요", "결제 알림" to "휴대폰 앱이 읽어요", "자동 반영" to "운행 비용에 들어가요")
    // ISSUES's three facts of the month: the figure is the tile's title, these its line.
    const val FACT_TRIPS = "운행"
    const val FACT_DISTANCE = "총주행"
    const val FACT_RATIO = "업무사용비율"
    /** A month tile's tag while that month is closed. */
    const val HERO_CLOSED = "마감됨"
    /** What a month tile does, for TalkBack's click label. */
    const val HERO_OPEN_MONTH = "이 달 운행 보기"
    const val PREVIOUS_MONTH = "이전 달 보기"
    const val CLEAR_FILTER = "필터 해제"
    const val SHOW_ALL = "전체 보기"
    /** FIRST's way on: the header's 시연 체험 walkthrough. */
    const val HERO_TUTORIAL = "시연 체험 보기"
    /** FIRST's way to the demo switch, which the hero never flips itself: on a real car it stops real recording. */
    const val HERO_DEMO_SETTING = "시연 모드 켜러 가기"
    /** FIRST in demo mode: the demo drive starts on 홈's strip. */
    const val HERO_DEMO_DRIVE = "홈에서 시연 출발"
    const val HERO_CARD_SETTING = "카드 등록하러 가기"

    /**
     * The quiet tag of a page that follows the trips and stands on an earlier month (2.15 §2.1): "11월은 아직 운행이 없어요".
     * [month] is this month; 월 always takes 은.
     */
    fun landingTag(month: YearMonth): String = "${month.monthValue}월은 아직 운행이 없어요"

    /** MONTH's title: "11월 운행이 아직 없어요" for this month, "9월 운행 기록이 없어요" for a past one (the year when it differs). */
    fun emptyMonthTitle(month: YearMonth, today: LocalDate): String =
        if (month == YearMonth.from(today)) "${monthLabel(month, today)} 운행이 아직 없어요" else "${monthLabel(month, today)} 운행 기록이 없어요"

    /** EMPLOYEE's title: "Kim Jiwoo 님의 운행이 없어요", or, for a filter whose employee is gone, "선택한 직원의 운행이 없어요". */
    fun employeeEmptyTitle(name: String?): String = if (name != null) "$name 님의 운행이 없어요" else "선택한 직원의 운행이 없어요"

    /** ISSUES's way to the close of a month that is ready: "10월 마감하러 가기". */
    fun closeMonthAction(month: YearMonth, today: LocalDate): String = "${monthLabel(month, today)} 마감하러 가기"

    /** A month tile's line: "9건 · 54.0 km · 업무 80%". */
    fun monthTileLine(totals: LogbookTotals): String = "${totals.trips}건 · ${kmText(totals.totalKm)} · 업무 ${ratioText(totals.ratio)}"

    /** A step tile as TalkBack hears it, the way 월 마감's steps say theirs: "1단계, D로 출발, 기록이 시작돼요". */
    fun heroStepSpoken(step: Int, title: String, line: String): String = "${step}단계, $title, $line"

    // 자동 반영 (MatchedTripList)
    const val NOT_COMPANY = "회사 비용 아님"

    // 운행 상세 (TripDetail)
    /** The side sheet's pane title. */
    const val DETAIL = "운행 상세"
    const val CLOSE = "닫기"
    const val BEFORE = "⑤ 주행 전"
    const val SPOKEN_BEFORE = "⑤ 주행 전 계기판"
    const val AFTER = "⑥ 주행 후"
    const val SPOKEN_AFTER = "⑥ 주행 후 계기판"
    const val DURATION = "주행 시간"
    const val ODOMETER_READ = "계기판에서 읽은 값이라 고칠 수 없어요"
    const val ODOMETER_FILLED = "앞뒤 운행의 계기판 값으로 채운 구간이에요"
    const val FROM = "출발"
    const val TO = "도착"
    const val NO_ADDRESS = "주소 미기록"
    const val RECORDING = "기록 중"
    const val PURPOSE = "운행 목적"
    const val DRIVER = "④ 사용자"
    const val DRIVER_EMPTY = "④ 사용자 칸이 비어요"
    const val CHANGE = "바꾸기"
    const val ASSIGN = "지정"
    const val SPOKEN_CHANGE = "이 운행의 사용자 바꾸기"
    const val SPOKEN_ASSIGN = "이 운행의 사용자 지정"
    const val COSTS = "운행 비용"
    const val ADD = "추가"
    const val COSTS_EMPTY = "등록한 비용이 없어요"
    const val SPOKEN_ADD_COST = "운행 비용 추가"
    const val SPOKEN_CLOSE_COST = "운행 비용 입력 닫기"
    const val FOLDED = "접힘"
    const val UNFOLDED = "펼침"
    const val AMOUNT = "금액"
    const val AMOUNT_HINT = "1원~1,000만 원"
    /** After the toll chip's fare ([tollChipText]): the navigation's estimate, not a receipt. */
    const val TOLL_GUESS = "내비 예상"
    const val SPOKEN_TOLL_ACTION = "통행료 추가"
    const val DELETE = "삭제"
    const val NOTE = "⑩ 비고"
    const val NOTE_FIELD = "방문처·업무 내용"
    const val SAVE = "저장"
    const val NOTE_SAVED = "비고를 저장했어요"
    const val DELETE_TRIP = "이 기록 삭제"
    const val DELETE_TITLE = "운행 기록을 삭제할까요?"
    /** The delete dialog's two facts, one a line (DIALOG_LINE_UNITS). */
    val DELETE_LINES = listOf("계기판 거리가 끊겨 ‘기록되지 않은 주행’으로 표시될 수 있어요", "시동만 켠 기록이나 시연 기록만 지우길 권해요")
    const val CANCEL = "취소"
    const val SPOKEN_DELETE = "운행 기록 삭제"
    const val UNDO = "실행 취소"
}

/** The trip's places in order, the start and the destination (over the end address); none without any address. */
private fun tripPlaces(trip: Trip): List<String> =
    listOfNotNull(trip.startAddress, trip.destination?.takeIf { it.isNotBlank() } ?: trip.endAddress).filter { it.isNotBlank() }

/** The trip's places as a route, "수정구 시흥동 → 판교", the destination over the end address; null without any address. */
private fun tripRoute(trip: Trip): String? = tripPlaces(trip).joinToString(" → ").ifBlank { null }

/** "8.0 km · 1분": what tells two trips without an address apart. */
private fun distanceAndTime(trip: Trip) = "${kmText(trip.distanceKm)} · ${durationText(trip.durationMillis)}"

/**
 * A trip row's title (pure): its route ("수정구 시흥동 → 판교", the destination winning over the end address), or, without
 * any address, its distance and time ("8.0 km · 1분"), so rows without addresses no longer read alike.
 */
internal fun tripRowTitle(trip: Trip): String = tripRoute(trip) ?: distanceAndTime(trip)

/**
 * Whether [tripRowTitle] is the trip's route (pure, unit-tested); without one the title is already its distance and time, so
 * whatever shows the title beside the distance (홈's 최근 운행) leaves the distance out rather than say it twice.
 */
internal fun tripHasRoute(trip: Trip): Boolean = tripPlaces(trip).isNotEmpty()

/**
 * What TalkBack says for [tripRowTitle] (pure, unit-tested): the places one by one, "수정구 시흥동, 판교", as [tripRowSpoken]
 * reads them (no arrow), or "8.0 km, 1분" without an address.
 */
internal fun tripRowTitleSpoken(trip: Trip): String =
    tripPlaces(trip).ifEmpty { listOf(kmText(trip.distanceKm), durationText(trip.durationMillis)) }.joinToString(", ")

/**
 * The line under a trip row's title (pure): [마감 후 수정 ·] [8.0 km · 1분 ·] the person, or 사용자 미지정 [· 4,500원]. The
 * distance and time only under a route title (a title without an address is already that), [driverName] is the name alone
 * (the department is the detail's), a cost only when the trip has one, and never 시연: the page says that once, in a tag.
 */
internal fun tripRowLine(trip: Trip, driverName: String?, edited: Boolean): String = listOfNotNull(
    LogCopy.EDITED.takeIf { edited },
    distanceAndTime(trip).takeIf { tripHasRoute(trip) },
    driverName?.takeIf { it.isNotBlank() } ?: LogCopy.NO_DRIVER,
    formatWon(trip.expenseWon).takeIf { trip.expenseWon > 0 },
).joinToString(" · ")

/**
 * What TalkBack says for a trip row, its one stop (pure): "02:26 출발, 수정구 시흥동, 판교, 8.0 km, 1분, 일반 업무, Sales ·
 * Kim Jiwoo, 경비 4,500원, 마감 후 수정", then 시연 데이터 for a demo trip. The places are read one by one (no arrow), the
 * person with the department, an unclassified trip as 목적 미분류.
 */
internal fun tripRowSpoken(trip: Trip, driver: Driver?, edited: Boolean): String = buildList {
    add("${hm(trip.startAt)} 출발")
    addAll(tripPlaces(trip))
    add(kmText(trip.distanceKm))
    add(durationText(trip.durationMillis))
    add(if (trip.purpose == TripPurpose.UNCLASSIFIED) IssueType.UNCLASSIFIED.label else trip.purpose.label)
    add(driver?.label?.takeIf { it.isNotBlank() } ?: LogCopy.NO_DRIVER)
    if (trip.expenseWon > 0) add("경비 ${formatWon(trip.expenseWon)}")
    if (edited) add(LogCopy.EDITED)
    if (trip.demo) add(LogCopy.DEMO)
}.joinToString(", ")

/**
 * 운행 상세's title (pure): the day, the start and the end, "10월 29일 (목) 02:26 – 02:27", or "– 기록 중" while the trip
 * still records.
 */
internal fun tripDetailTitle(trip: Trip): String =
    "${dayLabel(localDate(trip.startAt))} ${hm(trip.startAt)} – ${trip.endAt?.let(::hm) ?: LogCopy.RECORDING}"

/**
 * The trip the detail shows when nothing is tapped, the first row of the list under [filter] (pure): 0 전체, the newest of
 * [trips] (given oldest first, as tripsIn returns them; the list shows them newest first); 1 점검 필요, the trip of the first
 * of [issues] (in the list's order, [issueOrder]); 2 자동 반영, the first of [matched]. Null for an empty list.
 */
internal fun logTopId(filter: Int, trips: List<Trip>, issues: List<TripIssue>, matched: List<Pair<Trip, List<TripExpense>>>): String? = when (filter) {
    0 -> trips.lastOrNull()?.id
    1 -> issues.firstOrNull()?.tripId
    2 -> matched.firstOrNull()?.first?.id
    else -> null
}

/**
 * The trip 운행 상세 shows (pure), derived and never stored: the row the driver tapped ([selectedId]) while it still exists
 * ([known]); otherwise, where the page holds the list beside the detail ([twoPane]), the list's first row ([topId]), so the
 * detail follows the list until a row is tapped. In a side-sheet layout nothing opens by itself: null.
 */
internal fun logSelection(selectedId: String?, known: Set<String>, topId: String?, twoPane: Boolean): String? =
    selectedId?.takeIf { it in known } ?: topId.takeIf { twoPane }

/** The month's totals as one tag (pure): "총 54.0 km · 업무 80% · 9건". */
internal fun logTotalsLine(totals: LogbookTotals): String = "총 ${kmText(totals.totalKm)} · 업무 ${ratioText(totals.ratio)} · ${totals.trips}건"

/** Whether an issue keeps the month from closing (pure): 목적 미분류 and 사용자 미지정, the ④ and ⑧–⑨ columns of the form. */
internal fun issueRequired(type: IssueType): Boolean = type == IssueType.UNCLASSIFIED || type == IssueType.NO_DRIVER

/** An issue's title (pure): its label, with the distance for a gap or an overlap ("기록되지 않은 주행 · 12.0 km"). */
internal fun issueTitle(issue: TripIssue): String = issue.type.label + (issue.gapKm?.let { " · ${kmText(it)}" } ?: "")

/**
 * The one line under an issue (pure). 목적 미분류 and 사용자 미지정 show the trip itself, "8.0 km · 수정구 시흥동 → 판교" (or
 * "8.0 km · 1분" without an address), so the purpose can be chosen without opening it; the other checks say what they found
 * or what to do, in a line. The long form is [IssueType.detail], which TalkBack hears.
 */
internal fun issueLine(type: IssueType, trip: Trip): String = when (type) {
    IssueType.UNCLASSIFIED, IssueType.NO_DRIVER -> tripRoute(trip)?.let { "${kmText(trip.distanceKm)} · $it" } ?: distanceAndTime(trip)
    IssueType.ZERO_DISTANCE -> LogCopy.ISSUE_ZERO
    IssueType.ODOMETER_GAP -> LogCopy.ISSUE_GAP
    IssueType.ODOMETER_OVERLAP -> LogCopy.ISSUE_OVERLAP
    IssueType.IMPLAUSIBLE_SPEED -> LogCopy.ISSUE_SPEED
    IssueType.RECOVERED -> LogCopy.ISSUE_RECOVERED
    IssueType.CARD_ON_PERSONAL -> LogCopy.ISSUE_CARD_ON_PERSONAL
}

/**
 * 점검 필요 in the order a month is finished (pure): the two required kinds first ([issueRequired]), then the rest, each part
 * newest first by its trip's start. Issues of one trip keep their order.
 */
internal fun issueOrder(issues: List<TripIssue>, trips: Map<String, Trip>): List<TripIssue> =
    issues.sortedWith(compareBy<TripIssue> { !issueRequired(it.type) }.thenByDescending { trips[it.tripId]?.startAt ?: Long.MIN_VALUE })

/** The 점검 필요 counts as one tag (pure): "필수 2건 · 참고 5건"; a part that is zero is left out, both zero is "". */
internal fun issueCountLine(required: Int, reference: Int): String =
    listOfNotNull("필수 ${required}건".takeIf { required > 0 }, "참고 ${reference}건".takeIf { reference > 0 }).joinToString(" · ")

/** The ⓘ line under the detail's odometer figures (pure): read from the car, or filled in from the trips around it. */
internal fun odometerNote(origin: TripOrigin): String = when (origin) {
    TripOrigin.AUTO -> LogCopy.ODOMETER_READ
    TripOrigin.GAP_FILL -> LogCopy.ODOMETER_FILLED
}

/** A cost's line in the detail (pure): where it came from, "GS칼텍스 · 카드 자동 매칭", or the source alone ("직접 선택"). */
internal fun expenseLine(expense: TripExpense): String = expense.merchant?.takeIf { it.isNotBlank() }?.let { "$it · ${expense.source.label}" } ?: expense.source.label

/** The ⓘ row over a trip's edits (pure): "수정 이력 3건"; the lines themselves unfold under it. */
internal fun historyLine(count: Int): String = "수정 이력 ${count}건"

/**
 * The toll the navigation expected for [trip], to offer as one tap (pure): its fare while the trip passed a gate and has no
 * toll yet; else null. The fare is stored with the route (Trip.tollFareWon) and was read only by the card matcher.
 */
internal fun tollSuggestion(trip: Trip): Long? =
    trip.tollFareWon?.takeIf { it in 1..10_000_000 && trip.tollPasses.isNotEmpty() && trip.expenses.none { e -> e.type == ExpenseType.TOLL } }

/** The toll chip's words (pure): "통행료 3,600원 · 내비 예상". */
internal fun tollChipText(fare: Long): String = "${ExpenseType.TOLL.label} ${formatWon(fare)} · ${LogCopy.TOLL_GUESS}"

/** The answer to a cost added in the detail (pure): "주차 4,500원을 추가했어요". */
internal fun expenseAddedLine(type: ExpenseType, won: Long): String = "${type.label} ${formatWon(won)}을 추가했어요"

/** The answer to 누락 구간을 운행으로 추가 (pure): "누락 구간 12.0 km를 추가했어요". */
internal fun gapAddedLine(km: Double): String = "누락 구간 ${kmText(km)}를 추가했어요"
