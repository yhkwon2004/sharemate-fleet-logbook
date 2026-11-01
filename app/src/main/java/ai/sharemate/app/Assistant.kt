package ai.sharemate.app

import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.util.Locale

/*
 * The local assistant: the same three actions Gleo will call, plus questions the log can answer, understood from
 * short Korean sentences without any network. Gleo itself is still awaiting approval, so this is what the voice
 * integration will feel like, running against the real log today. Parsing and answering are pure; AssistantRunner
 * applies the mutations through the repository so every change is attributed and undoable like any other edit.
 */

sealed interface AssistantIntent {
    data class SetPurpose(val purpose: TripPurpose, val scope: TripScope) : AssistantIntent
    data class AddExpense(val type: ExpenseType, val amountWon: Long) : AssistantIntent
    data class Summary(val from: LocalDate, val to: LocalDate, val label: String) : AssistantIntent
    data object Care : AssistantIntent
    data class MonthClose(val month: YearMonth) : AssistantIntent
    data object Unknown : AssistantIntent
}

/** Which trips a purpose applies to: the latest one, or every unclassified trip on a day. */
sealed interface TripScope {
    data object Latest : TripScope
    data class Day(val date: LocalDate) : TripScope
}

data class AssistantStep(val label: String, val detail: String = "")
data class AssistantCitation(val tripId: String, val label: String)

/** What the panel shows: the sentence, the steps the assistant took, and the trips it touched or read. */
data class AssistantAnswer(
    val text: String,
    val steps: List<AssistantStep> = emptyList(),
    val citations: List<AssistantCitation> = emptyList(),
    val intent: AssistantIntent = AssistantIntent.Unknown,
    val changed: Boolean = false,
    // Set when the change can be taken back: the trips and the purpose they had before.
    val undoPurposes: Map<String, Pair<TripPurpose, EditSource?>> = emptyMap(),
    val undoExpense: Pair<String, Int>? = null,
)

internal const val ASSISTANT_MAX_INPUT = 120

private val AMOUNT = Regex("([0-9]{1,3}(?:,[0-9]{3})+|[0-9]+(?:\\.[0-9]+)?)\\s*(만\\s*원|만|원)")

internal fun parseAmount(text: String): Long? {
    val match = AMOUNT.find(text) ?: return null
    val number = match.groupValues[1].replace(",", "").toDoubleOrNull() ?: return null
    val won = if (match.groupValues[2].startsWith("만")) number * 10_000 else number
    return won.takeIf { it.isFinite() && it in 1.0..10_000_000.0 && it % 1.0 == 0.0 }?.toLong()
}

private fun String.hasAny(vararg words: String) = words.any { contains(it) }

/** Turns one short Korean request into an intent. Unknown is a valid answer: the panel then shows the chips again. */
internal fun parseIntent(raw: String, today: LocalDate): AssistantIntent {
    val text = raw.trim().take(ASSISTANT_MAX_INPUT).replace(Regex("\\s+"), " ")
    if (text.isEmpty()) return AssistantIntent.Unknown
    val lower = text.lowercase(Locale.KOREA)
    if (lower.hasAny("마감")) {
        val month = when {
            lower.hasAny("지난달", "지난 달", "전월") -> YearMonth.from(today).minusMonths(1)
            else -> YearMonth.from(today)
        }
        return AssistantIntent.MonthClose(month)
    }
    if (lower.hasAny("검사", "보험", "리스", "렌트", "정비 주기", "차량 관리", "차량관리", "만기")) return AssistantIntent.Care
    val amount = parseAmount(text)
    if (amount != null && lower.hasAny("추가", "등록", "넣어", "기록", "결제", "냈", "썼", "비용", "경비", "료", "주차", "주유", "충전", "정비")) {
        val type = when {
            lower.hasAny("통행", "톨", "하이패스", "고속도로") -> ExpenseType.TOLL
            lower.hasAny("주차") -> ExpenseType.PARKING
            lower.hasAny("주유", "충전", "기름", "연료") -> ExpenseType.FUEL
            lower.hasAny("정비", "수리", "타이어", "세차", "오일") -> ExpenseType.MAINTENANCE
            else -> ExpenseType.OTHER
        }
        return AssistantIntent.AddExpense(type, amount)
    }
    val purpose = when {
        lower.hasAny("출퇴근", "출·퇴근", "출 퇴근", "퇴근", "출근") -> TripPurpose.COMMUTE
        lower.hasAny("개인") -> TripPurpose.PERSONAL
        lower.hasAny("업무", "출장", "거래처", "회사 일") -> TripPurpose.BUSINESS
        else -> null
    }
    if (purpose != null && lower.hasAny("기록", "분류", "해줘", "해 줘", "으로", "로 ", "운행", "처리", "바꿔", "변경")) {
        val scope = when {
            lower.hasAny("어제") -> TripScope.Day(today.minusDays(1))
            lower.hasAny("오늘 운행", "오늘 전부", "오늘 모두", "오늘 다") -> TripScope.Day(today)
            else -> TripScope.Latest
        }
        return AssistantIntent.SetPurpose(purpose, scope)
    }
    if (lower.hasAny("요약", "얼마나", "몇 건", "몇건", "거리", "통계", "현황", "얼마", "비율", "알려", "보여", "정리")) {
        val (from, to, label) = when {
            lower.hasAny("오늘") -> Triple(today, today, "오늘")
            lower.hasAny("어제") -> Triple(today.minusDays(1), today.minusDays(1), "어제")
            lower.hasAny("이번 주", "이번주", "금주") -> Triple(today.minusDays((today.dayOfWeek.value - 1).toLong()), today, "이번 주")
            lower.hasAny("지난달", "지난 달", "전월") -> YearMonth.from(today).minusMonths(1).let { Triple(it.atDay(1), it.atEndOfMonth(), "${it.monthValue}월") }
            lower.hasAny("올해", "금년", "연간") -> Triple(today.withDayOfYear(1), today, "올해")
            else -> Triple(today.withDayOfMonth(1), today, "이번 달")
        }
        return AssistantIntent.Summary(from, to, label)
    }
    return AssistantIntent.Unknown
}

private fun clock(at: Long, zone: ZoneId) = Instant.ofEpochMilli(at).atZone(zone).let { "%02d:%02d".format(it.hour, it.minute) }
private fun tripLabel(trip: Trip, zone: ZoneId) = "${clock(trip.startAt, zone)} 출발 · ${"%.1f".format(Locale.KOREA, trip.distanceKm)} km · ${trip.destination ?: trip.endAddress ?: "주소 미기록"}"
private fun won(value: Long) = formatWon(value)

/** Trips a purpose request refers to, newest first; the recording trip counts as the latest. */
internal fun purposeTargets(data: TripLogData, scope: TripScope, now: Long, zone: ZoneId): List<Trip> {
    val demo = data.profile.demoMode
    return when (scope) {
        TripScope.Latest -> listOfNotNull(data.open?.takeIf { it.demo == demo }
            ?: data.trips.filter { it.demo == demo && now - (it.endAt ?: it.updatedAt) in 0..12 * 3_600_000L }.maxByOrNull { it.endAt ?: it.updatedAt })
        is TripScope.Day -> tripsIn(data.trips.filter { it.demo == demo && it.purpose == TripPurpose.UNCLASSIFIED }, scope.date, scope.date, zone).reversed()
    }
}

/** Chips that fit the moment: the log decides what is worth asking, so the first suggestion is usually the one to tap. */
internal fun suggestedPrompts(data: TripLogData, now: Long, zone: ZoneId): List<String> {
    val demo = data.profile.demoMode
    val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    val out = mutableListOf<String>()
    val latest = purposeTargets(data, TripScope.Latest, now, zone).firstOrNull()
    if (latest != null && latest.purpose == TripPurpose.UNCLASSIFIED) {
        val guess = autoPurpose(latest, data.trips) ?: suggestPurpose(latest, data.trips)?.purpose
        out += if (guess != null) "방금 운행 ${guess.label}로 기록해줘" else "방금 운행 업무로 기록해줘"
    }
    val yesterday = purposeTargets(data, TripScope.Day(today.minusDays(1)), now, zone)
    if (yesterday.size >= 2) out += "어제 운행 전부 업무로 기록해줘"
    data.pendingCharge?.takeIf { it.demo == demo }?.let { out += "충전 비용 등록해줘" }
    if (careAlerts(data.care, data.vehicle.odometerKm, today).any { it.level != CareLevel.INFO }) out += "차량 관리 알려줘"
    val status = monthStatus(data, YearMonth.from(today), zone)
    if (today.dayOfMonth >= 25 && status.trips > 0 && status.closed == null) out += "이번 달 마감 상태 알려줘"
    out += "이번 달 운행 요약해줘"
    if (out.size < 4) out += "통행료 3,600원 추가해줘"
    return out.distinct().take(4)
}

/** Answers that only read the log. Mutating intents are answered by [AssistantRunner]; this gives them their preview text. */
internal fun answerReadOnly(data: TripLogData, intent: AssistantIntent, now: Long, zone: ZoneId): AssistantAnswer {
    val demo = data.profile.demoMode
    val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    return when (intent) {
        is AssistantIntent.Summary -> {
            val trips = tripsIn(data.trips.filter { it.demo == demo }, intent.from, intent.to, zone)
            val totals = logbookTotals(trips)
            val unclassified = trips.count { it.purpose == TripPurpose.UNCLASSIFIED }
            val ratio = totals.ratio?.let { "${(it * 100).toInt()}%" }
            val text = buildString {
                append("${intent.label} ${totals.trips}건, ${"%.1f".format(Locale.KOREA, totals.totalKm)} km 운행했어요. ")
                if (ratio != null) append("업무 ${"%.1f".format(Locale.KOREA, totals.businessKm)} km로 업무 비율 $ratio. ")
                if (totals.expenseWon > 0) append("등록 비용 ${won(totals.expenseWon)}. ")
                if (unclassified > 0) append("목적 확인이 남은 운행이 ${unclassified}건 있어요.") else if (totals.trips > 0) append("목적은 모두 확인됐어요.")
            }.trim()
            val cited = trips.sortedByDescending { it.startAt }.take(3)
            AssistantAnswer(text, listOf(AssistantStep("기간 조회", "${intent.from} ~ ${intent.to}"), AssistantStep("운행 ${totals.trips}건 집계")),
                cited.map { AssistantCitation(it.id, tripLabel(it, zone)) }, intent)
        }
        AssistantIntent.Care -> {
            val alerts = careAlerts(data.care, data.vehicle.odometerKm, today)
            val text = if (alerts.isEmpty()) "설정된 차량 기한이 없어요. 차량 탭에서 정기검사·보험·리스 만기와 정비 주기를 등록하면 미리 알려 드려요."
                else alerts.joinToString(" ") { "${it.title} (${it.detail})." }
            AssistantAnswer(text, listOf(AssistantStep("차량 기한 확인", "${alerts.size}건")), intent = intent)
        }
        is AssistantIntent.MonthClose -> {
            val status = monthStatus(data, intent.month, zone)
            val text = when {
                status.closed != null -> "${intent.month.monthValue}월은 이미 마감했어요. ${status.closed.trips}건, ${"%.1f".format(Locale.KOREA, status.closed.totalKm)} km."
                status.trips == 0 -> "${intent.month.monthValue}월에는 운행 기록이 없어요."
                status.issues == 0 -> "${intent.month.monthValue}월 ${status.trips}건 모두 확인됐어요. 월간·Excel 탭에서 마감할 수 있어요."
                else -> "${intent.month.monthValue}월 마감까지 목적·사용자 확인 ${status.unfinished}건, 카드 확인 ${status.reviews}건이 남았어요."
            }
            AssistantAnswer(text, listOf(AssistantStep("마감 상태 확인", "${status.percent ?: 0}% 완성")), intent = intent)
        }
        is AssistantIntent.SetPurpose -> {
            val targets = purposeTargets(data, intent.scope, now, zone)
            AssistantAnswer(if (targets.isEmpty()) "바꿀 운행을 찾지 못했어요." else "${targets.size}건을 ${intent.purpose.label}로 기록할게요.",
                citations = targets.take(3).map { AssistantCitation(it.id, tripLabel(it, zone)) }, intent = intent)
        }
        is AssistantIntent.AddExpense -> {
            val target = purposeTargets(data, TripScope.Latest, now, zone).firstOrNull()
            AssistantAnswer(if (target == null) "비용을 연결할 최근 운행이 없어요." else "${intent.type.label} ${won(intent.amountWon)}을 최근 운행에 추가할게요.",
                citations = listOfNotNull(target?.let { AssistantCitation(it.id, tripLabel(it, zone)) }), intent = intent)
        }
        AssistantIntent.Unknown -> AssistantAnswer("아직 알아듣지 못했어요. 아래 예시처럼 말씀해 주세요.", intent = intent)
    }
}

/** Runs an intent against the repository, so purposes and expenses land with the same checks and history as any tap. */
class AssistantRunner(private val repository: TripLogRepository) {
    suspend fun run(text: String, now: Long = System.currentTimeMillis(), zone: ZoneId = ZoneId.systemDefault()): AssistantAnswer {
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val intent = parseIntent(text, today)
        val data = repository.ensureLoaded()
        return when (intent) {
            is AssistantIntent.SetPurpose -> {
                val targets = purposeTargets(data, intent.scope, now, zone)
                if (targets.isEmpty()) return answerReadOnly(data, intent, now, zone)
                val before = targets.associate { it.id to (it.purpose to it.purposeSource) }
                if (targets.size == 1) repository.classify(targets[0].id, intent.purpose, EditSource.ASSISTANT)
                else repository.classifyMany(targets.map { it.id }, intent.purpose, EditSource.ASSISTANT)
                AssistantAnswer("${targets.size}건을 ${intent.purpose.label}로 기록했어요. 휴대폰에도 반영됐어요.",
                    listOf(AssistantStep("운행 찾기", targets.first().let { tripLabel(it, zone) } + if (targets.size > 1) " 외 ${targets.size - 1}건" else ""),
                        AssistantStep("목적 기록", "${intent.purpose.label} · 화면 비서 출처"), AssistantStep("저장 완료")),
                    targets.take(3).map { AssistantCitation(it.id, tripLabel(it, zone)) }, intent, changed = true, undoPurposes = before)
            }
            is AssistantIntent.AddExpense -> {
                val target = purposeTargets(data, TripScope.Latest, now, zone).firstOrNull() ?: return answerReadOnly(data, intent, now, zone)
                repository.addExpense(target.id, intent.type, intent.amountWon, EditSource.ASSISTANT)
                val index = repository.ensureLoaded().let { (it.trips + listOfNotNull(it.open)).first { t -> t.id == target.id } }.expenses.lastIndex
                AssistantAnswer("${intent.type.label} ${won(intent.amountWon)}을 ${tripLabel(target, zone)} 운행에 추가했어요.",
                    listOf(AssistantStep("운행 찾기", tripLabel(target, zone)), AssistantStep("비용 추가", "${intent.type.label} ${won(intent.amountWon)}"), AssistantStep("저장 완료")),
                    listOf(AssistantCitation(target.id, tripLabel(target, zone))), intent, changed = true, undoExpense = target.id to index)
            }
            else -> answerReadOnly(data, intent, now, zone)
        }
    }

    /** Puts back what [run] changed; purposes return to their previous source so later suggestions stay honest. */
    suspend fun undo(answer: AssistantAnswer) {
        answer.undoPurposes.forEach { (id, previous) -> repository.classify(id, previous.first, previous.second ?: EditSource.DRIVER) }
        answer.undoExpense?.let { (tripId, index) -> repository.removeExpense(tripId, index) }
    }
}
