package ai.sharemate.app

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import kotlin.math.abs

/*
 * The car knows when and where something happened but not what it cost; the card knows what it cost but not which
 * trip it belongs to. Neither Pleos SDK exposes a payment event, so this file joins the two records:
 *
 *   vehicle evidence   energy stops (charge state, battery or fuel rising), toll gates passed, route toll fare,
 *                      arrival place and the parked gap between trips
 *   card evidence      approval notifications on the phone and card statements
 *
 * Every candidate gets a score. A clear winner is written to the trip with nobody touching anything; an ambiguous one
 * waits for a single tap. All of this is pure so it can run in unit tests without a vehicle.
 */

enum class CostCategory(val label: String, val expense: ExpenseType) {
    FUEL("주유", ExpenseType.FUEL), CHARGE("충전", ExpenseType.FUEL), TOLL("통행료", ExpenseType.TOLL), PARKING("주차", ExpenseType.PARKING),
}
enum class CardSource(val label: String) { NOTIFICATION("승인 알림"), STATEMENT("카드 명세서") }
enum class ChargeState(val label: String) { WAITING("차량 기록 대기"), MATCHED("자동 반영"), REVIEW("확인 필요"), EXCLUDED("회사 비용 아님"), CANCELED("승인 취소") }

/** One approval as it leaves the phone: no card number beyond the last four digits, and never the raw notification text. */
data class CardApproval(
    val id: String,
    val at: Long,
    // A statement line may carry only a date; `at` is then local noon of that day.
    val timeKnown: Boolean,
    val amountWon: Long,
    val merchant: String,
    val last4: String?,
    val source: CardSource,
    val cancel: Boolean = false,
)

data class CardCharge(
    val id: String,
    val at: Long,
    val timeKnown: Boolean,
    val amountWon: Long,
    val merchant: String,
    val last4: String?,
    val source: CardSource,
    val category: CostCategory?,
    val state: ChargeState = ChargeState.WAITING,
    val tripId: String? = null,
    val stopId: String? = null,
    val score: Int = 0,
    val reason: String = "",
    // Set when a statement line confirmed an approval that a notification already delivered.
    val statementId: String? = null,
    val demo: Boolean = false,
)

internal const val AUTO_MATCH_SCORE = 70
internal const val MATCH_LEAD = 15
internal const val REVIEW_MATCH_SCORE = 40
internal const val MAX_CARD_CHARGES = 3_000
internal const val MAX_ENERGY_STOPS = 500
internal const val MIN_REFILL_PERCENT = 3
internal const val AUTO_PURPOSE_MIN = 3

private const val MINUTE = 60_000L
private const val HOUR = 60 * MINUTE
private const val WAITING_LIMIT = 6 * HOUR
private const val DUPLICATE_WINDOW = 3 * MINUTE

private val FUEL_WORDS = listOf("주유", "SK에너지", "에스케이에너지", "GS칼텍스", "지에스칼텍스", "S-OIL", "SOIL", "에스오일", "오일뱅크", "현대오일")
private val CHARGE_WORDS = listOf("충전", "차지비", "CHARGEV", "채비", "CHAEVI", "에버온", "EVERON", "일렉링크", "이피트", "E-PIT", "EPIT", "파워큐브", "플러그링크", "스타코프", "타이드플로")
private val TOLL_WORDS = listOf("도로공사", "하이패스", "HIPASS", "통행료", "톨게이트", "고속도로", "하이웨이", "순환도로", "민자도로")
private val PARKING_WORDS = listOf("주차", "파킹", "PARKING", "AJ파크", "GS파크")
// These contain a trigger word but are not vehicle costs: stored-value top-ups and highway rest stops.
private val NOT_VEHICLE_WORDS = listOf("교통카드", "티머니", "캐시비", "페이충전", "포인트충전", "휴게소")

private fun squeeze(text: String) = text.uppercase().replace(Regex("\\s+"), "")

/** Vehicle-related merchant category from the printed name, or null when the name says nothing about a car. */
internal fun categorize(merchant: String): CostCategory? {
    val name = squeeze(merchant)
    fun has(words: List<String>) = words.any { name.contains(it.uppercase()) }
    return when {
        has(FUEL_WORDS) -> CostCategory.FUEL
        has(NOT_VEHICLE_WORDS) -> null
        has(CHARGE_WORDS) -> CostCategory.CHARGE
        has(TOLL_WORDS) -> CostCategory.TOLL
        has(PARKING_WORDS) -> CostCategory.PARKING
        else -> null
    }
}

/** Stable across re-imports and repeated notifications: [occurrence] separates identical lines in one statement. */
internal fun cardApprovalId(at: Long, timeKnown: Boolean, amountWon: Long, merchant: String, last4: String?, cancel: Boolean, occurrence: Int = 0): String {
    val moment = if (timeKnown) at / MINUTE else at / (24 * HOUR)
    return UUID.nameUUIDFromBytes("$moment|$timeKnown|$amountWon|${squeeze(merchant)}|$last4|$cancel|$occurrence".toByteArray(Charsets.UTF_8)).toString()
}

private val BRAND_WORDS = Regex("SK에너지|에스케이에너지|GS칼텍스|지에스칼텍스|S-OIL|에스오일|현대오일뱅크|오일뱅크|한국도로공사|아이파킹|하이파킹|모두의주차장|차지비|에버온|채비|파워큐브|\\(주\\)|㈜|주식회사")
private val PLACE_SUFFIX = Regex("(셀프)?(주유소|충전소|충전기|주차장|영업소|톨게이트|지점|점)$")

/** True when what remains of the merchant name after the brand appears in the place the car recorded. */
internal fun sharesPlace(merchant: String, place: String?): Boolean {
    if (place.isNullOrBlank()) return false
    val target = squeeze(place)
    return merchant.uppercase().replace(BRAND_WORDS, " ").split(' ', '-', '_', '·', '/', '(', ')').any { part ->
        val core = part.replace(PLACE_SUFFIX, "")
        core.length >= 2 && target.contains(core)
    }
}

internal data class MatchCandidate(val tripId: String, val stopId: String?, val score: Int, val reason: String)

private fun day(at: Long, zone: ZoneId): LocalDate = Instant.ofEpochMilli(at).atZone(zone).toLocalDate()
private fun Trip.place() = listOfNotNull(destination, endAddress).joinToString(" ").ifBlank { null }

/** Trip an energy stop belongs to: the one that arrived there, or the first trip if the car had not moved yet. */
private fun tripForStop(stop: ChargeSession, trips: List<Trip>): Trip? =
    trips.filter { it.startAt <= stop.endAt }.maxByOrNull { it.startAt } ?: trips.minByOrNull { it.startAt }

/** Every plausible home for [charge], best first. Scores add up evidence; see the thresholds above for what they trigger. */
internal fun matchCandidates(charge: CardCharge, data: TripLogData, zone: ZoneId): List<MatchCandidate> {
    val trips = (data.trips + listOfNotNull(data.open)).filter { it.demo == charge.demo }.sortedBy { it.startAt }
    val registered = charge.last4 != null && charge.last4 in data.profile.cards
    val trust = if (registered) 15 else 0
    val known = if (charge.category != null) 15 else 0
    val out = mutableListOf<MatchCandidate>()
    fun sameDay(at: Long) = day(at, zone) == day(charge.at, zone)

    fun fromStops() {
        data.stops.filter { it.demo == charge.demo && it.chargeId == null && it.id.isNotEmpty() }.filter { stop ->
            when (charge.category) { CostCategory.FUEL -> stop.fuel; CostCategory.CHARGE -> !stop.fuel; null -> true; else -> false }
        }.forEach { stop ->
            val inWindow = if (charge.timeKnown) charge.at in (stop.startAt - 10 * MINUTE)..(stop.endAt + 30 * MINUTE) else sameDay(stop.endAt)
            val trip = tripForStop(stop, trips)
            if (inWindow && trip != null) {
                val near = sharesPlace(charge.merchant, stop.place) || sharesPlace(charge.merchant, trip.place())
                val base = if (charge.timeKnown) 60 else 50
                out += MatchCandidate(trip.id, stop.id, base + known + trust + (if (near) 25 else 0),
                    "${if (stop.fuel) "연료 증가" else "충전"} ${stop.socFrom ?: "?"}→${stop.socTo ?: "?"}%" + if (near) " · 위치 일치" else "")
            }
        }
    }

    fun fromTrips() {
        trips.forEach { trip ->
            val end = trip.endAt ?: Long.MAX_VALUE
            val inWindow = if (charge.timeKnown) charge.at in (trip.startAt - 5 * MINUTE)..(if (end == Long.MAX_VALUE) end else end + 30 * MINUTE)
                else sameDay(trip.startAt) || sameDay(trip.endAt ?: trip.updatedAt)
            if (inWindow) {
                val near = sharesPlace(charge.merchant, trip.place()) || sharesPlace(charge.merchant, trip.startAddress)
                val base = if (charge.timeKnown) 40 else 30
                out += MatchCandidate(trip.id, null, base + known + trust + (if (near) 25 else 0), if (near) "운행 경로 일치" else "운행 시간대 일치")
            }
        }
    }

    fun tolls() {
        trips.forEach { trip ->
            val end = trip.endAt ?: Long.MAX_VALUE
            val inWindow = if (charge.timeKnown) charge.at in (trip.startAt - 5 * MINUTE)..(if (end == Long.MAX_VALUE) end else end + 10 * MINUTE) else sameDay(trip.startAt)
            if (inWindow) {
                val fare = trip.tollFareWon
                val exact = fare != null && fare > 0 && abs(fare - charge.amountWon) <= maxOf(100, fare / 10)
                val gate = if (charge.timeKnown) trip.tollPasses.any { abs(it.at - charge.at) <= 15 * MINUTE } else trip.tollPasses.isNotEmpty()
                val base = if (charge.timeKnown) 60 else 40
                val score = base + known + trust + (if (gate) 20 else 0) + (if (exact) 20 else if (fare != null && fare > 0) 5 else 0)
                out += MatchCandidate(trip.id, null, score, listOfNotNull("운행 중 통행", if (gate) "톨게이트 통과" else null,
                    if (exact) "경로 예상요금 ${formatWon(fare!!)} 일치" else null).joinToString(" · "))
            }
        }
    }

    fun parking() {
        trips.filter { !it.open }.forEachIndexed { index, trip ->
            val next = trips.drop(index + 1).firstOrNull { it.startAt >= (trip.endAt ?: 0) }
            val start = trip.endAt ?: return@forEachIndexed
            // Exit gates charge just after the next trip begins, so the window runs a little past departure.
            val end = next?.let { it.startAt + 15 * MINUTE } ?: (start + 24 * HOUR)
            val inWindow = if (charge.timeKnown) charge.at in (start - MINUTE)..end else sameDay(start)
            if (inWindow) {
                val near = sharesPlace(charge.merchant, trip.place())
                val base = if (charge.timeKnown) 60 else 40
                out += MatchCandidate(trip.id, null, base + known + trust + (if (near) 25 else 0), if (near) "주차 위치 일치" else "주차 시간대 일치")
            }
        }
    }

    when (charge.category) {
        CostCategory.TOLL -> tolls()
        CostCategory.PARKING -> parking()
        CostCategory.FUEL, CostCategory.CHARGE -> { fromStops(); if (out.isEmpty()) fromTrips() }
        null -> fromStops()
    }
    // A lone candidate on a date-only line is evidence too: nothing else that day could own the charge.
    val ranked = out.groupBy { it.tripId to it.stopId }.values.map { group -> group.maxBy { it.score } }.sortedByDescending { it.score }
    return if (!charge.timeKnown && ranked.size == 1) listOf(ranked[0].copy(score = ranked[0].score + 10)) else ranked
}

private fun List<MatchCandidate>.decisive(): MatchCandidate? {
    val best = firstOrNull() ?: return null
    val runnerUp = getOrNull(1)?.score ?: 0
    return best.takeIf { it.score >= AUTO_MATCH_SCORE && it.score - runnerUp >= MATCH_LEAD }
}

private fun TripLogData.withTrip(id: String, edit: (Trip) -> Trip): TripLogData = when {
    open?.id == id -> copy(open = edit(open))
    else -> copy(trips = trips.map { if (it.id == id) edit(it) else it })
}
private fun TripLogData.findTrip(id: String?) = (trips + listOfNotNull(open)).firstOrNull { it.id == id }

/** Writes the charge onto the trip. A typed expense of about the same size is the same payment, so the card amount corrects it. */
private fun TripLogData.attach(charge: CardCharge, candidate: MatchCandidate, now: Long): TripLogData {
    val type = (charge.category?.expense) ?: stops.firstOrNull { it.id == candidate.stopId }?.let { ExpenseType.FUEL } ?: ExpenseType.OTHER
    val trip = findTrip(candidate.tripId) ?: return this
    val label = "${charge.merchant} ${formatWon(charge.amountWon)}"
    val updated = when {
        trip.expenses.any { it.cardChargeId == charge.id } -> trip
        else -> {
            val typed = trip.expenses.indexOfFirst { it.type == type && it.cardChargeId == null &&
                abs(it.amountWon - charge.amountWon) <= maxOf(1_000, charge.amountWon / 5) }
            if (typed >= 0) {
                val before = trip.expenses[typed]
                val expenses = trip.expenses.toMutableList().also { it[typed] = before.copy(amountWon = charge.amountWon, cardChargeId = charge.id, merchant = charge.merchant) }
                withHistory(trip.copy(expenses = expenses), "입력 ${formatWon(before.amountWon)} → 카드 $label (${charge.source.label})", now)
            } else {
                if (trip.expenses.size >= 30) return copy(charges = charges.map { if (it.id == charge.id) it.copy(state = ChargeState.REVIEW, reason = "운행 비용 30건 초과") else it })
                withHistory(trip.copy(expenses = trip.expenses + TripExpense(type, charge.amountWon, now, EditSource.CARD, charge.id, charge.merchant)),
                    "${type.label} $label 카드 매칭 · ${candidate.reason} (${charge.source.label})", now)
            }
        }
    }
    return withTrip(trip.id) { updated }.copy(
        charges = charges.map { if (it.id == charge.id) it.copy(state = ChargeState.MATCHED, tripId = trip.id, stopId = candidate.stopId, score = candidate.score, reason = candidate.reason) else it },
        stops = stops.map { if (it.id == candidate.stopId) it.copy(chargeId = charge.id) else it },
        pendingCharge = pendingCharge?.takeUnless { it.id.isNotEmpty() && it.id == candidate.stopId },
    )
}

/** Takes a charge back off its trip, used for cancellations and for the driver saying it was not a company cost. */
private fun TripLogData.detach(charge: CardCharge, note: String, now: Long): TripLogData {
    val trip = findTrip(charge.tripId)
    val edited = if (trip != null && trip.expenses.any { it.cardChargeId == charge.id }) withTrip(trip.id) {
        withHistory(it.copy(expenses = it.expenses.filterNot { e -> e.cardChargeId == charge.id }), "${charge.merchant} ${formatWon(charge.amountWon)} $note", now)
    } else this
    return edited.copy(stops = edited.stops.map { if (it.chargeId == charge.id) it.copy(chargeId = null) else it })
}

/** Re-runs matching for everything undecided. Called when a charge arrives and whenever the car adds evidence. */
internal fun settleCharges(data: TripLogData, now: Long, zone: ZoneId): TripLogData {
    if (!data.profile.autopilot) return data
    var next = data
    data.charges.sortedBy { it.at }.forEach { snapshot ->
        val charge = next.charges.firstOrNull { it.id == snapshot.id } ?: return@forEach
        if (charge.state == ChargeState.MATCHED && next.findTrip(charge.tripId) == null) {
            // The trip was deleted; the charge goes back to looking for a home instead of pointing at nothing.
            next = next.copy(charges = next.charges.map { if (it.id == charge.id) it.copy(state = ChargeState.WAITING, tripId = null, stopId = null, score = 0, reason = "") else it })
        }
        val current = next.charges.first { it.id == charge.id }
        if (current.state != ChargeState.WAITING && current.state != ChargeState.REVIEW) return@forEach
        val candidates = matchCandidates(current, next, zone)
        val winner = candidates.decisive()
        val best = candidates.firstOrNull()?.takeIf { it.score >= REVIEW_MATCH_SCORE }
        val waitedOut = !current.timeKnown || now - current.at > WAITING_LIMIT
        next = when {
            winner != null -> next.attach(current, winner, now)
            best != null -> next.copy(charges = next.charges.map { if (it.id == current.id) it.copy(state = ChargeState.REVIEW, tripId = best.tripId, stopId = best.stopId, score = best.score, reason = best.reason) else it })
            // No evidence after hours and nothing in the name about a car: this was never a vehicle cost, so it is not kept.
            current.category == null && waitedOut -> next.copy(charges = next.charges.filterNot { it.id == current.id })
            waitedOut -> next.copy(charges = next.charges.map { if (it.id == current.id) it.copy(state = ChargeState.REVIEW, tripId = null, stopId = null, score = 0, reason = "맞는 운행을 찾지 못했어요") else it })
            else -> next
        }
    }
    return next
}

private fun sameMerchant(a: String, b: String): Boolean {
    val left = squeeze(a); val right = squeeze(b)
    return left == right || left.contains(right) || right.contains(left) || (left.length >= 4 && right.length >= 4 && left.take(4) == right.take(4))
}

/** Adds incoming card records, dropping other people's cards, repeats, and anything with no link to a vehicle. */
internal fun mergeCardEvents(data: TripLogData, events: List<CardApproval>, now: Long, zone: ZoneId = ZoneId.systemDefault()): TripLogData {
    val demo = data.profile.demoMode
    var next = data
    events.sortedBy { it.at }.forEach { event ->
        if (event.cancel) { next = cancelCharge(next, event, now); return@forEach }
        val cards = next.profile.cards
        val registered = event.last4 != null && event.last4 in cards
        if (event.last4 != null && cards.isNotEmpty() && !registered) return@forEach
        val category = categorize(event.merchant)
        if (category == null && !registered) return@forEach
        if (next.charges.any { it.id == event.id || it.statementId == event.id }) return@forEach
        val twin = next.charges.firstOrNull { other ->
            other.amountWon == event.amountWon && other.state != ChargeState.CANCELED && sameMerchant(other.merchant, event.merchant) && when (event.source) {
                CardSource.STATEMENT -> other.statementId == null && day(other.at, zone) == day(event.at, zone)
                CardSource.NOTIFICATION -> other.source == CardSource.NOTIFICATION && abs(other.at - event.at) <= DUPLICATE_WINDOW
            }
        }
        next = when {
            twin != null && event.source == CardSource.STATEMENT -> next.copy(charges = next.charges.map { if (it.id == twin.id) it.copy(statementId = event.id) else it })
            twin != null -> next
            else -> next.copy(charges = (next.charges + CardCharge(event.id, event.at, event.timeKnown, event.amountWon, event.merchant.take(60), event.last4,
                event.source, category, demo = demo)).takeLast(MAX_CARD_CHARGES))
        }
    }
    return next
}

/** Fuel stations pre-authorise a round sum, cancel it, then charge the real amount; the cancel must erase the first line. */
private fun cancelCharge(data: TripLogData, event: CardApproval, now: Long): TripLogData {
    val original = data.charges.filter { it.amountWon == event.amountWon && it.state != ChargeState.CANCELED && it.at <= event.at + HOUR &&
        (sameMerchant(it.merchant, event.merchant) || (it.last4 != null && it.last4 == event.last4)) }.maxByOrNull { it.at } ?: return data
    return data.detach(original, "승인 취소", now).let { edited ->
        edited.copy(charges = edited.charges.map { if (it.id == original.id) it.copy(state = ChargeState.CANCELED, reason = "카드 승인이 취소됐어요") else it })
    }
}

/** The driver's answer to a review card; a chosen trip wins over every score. */
internal fun confirmCharge(data: TripLogData, id: String, tripId: String, now: Long): TripLogData {
    val charge = data.charges.firstOrNull { it.id == id } ?: throw IllegalArgumentException("카드 결제를 찾지 못했어요.")
    require(data.findTrip(tripId) != null) { "운행 기록을 찾지 못했어요." }
    val stop = data.stops.firstOrNull { it.id == charge.stopId }?.id
    return data.attach(charge, MatchCandidate(tripId, stop, 100, "직접 확인"), now)
}

internal fun excludeCharge(data: TripLogData, id: String, now: Long): TripLogData {
    if (data.charges.firstOrNull { it.id == id }?.state == ChargeState.CANCELED) return data
    val charge = data.charges.firstOrNull { it.id == id } ?: throw IllegalArgumentException("카드 결제를 찾지 못했어요.")
    return data.detach(charge, "회사 비용 아님으로 제외", now).let { edited ->
        edited.copy(charges = edited.charges.map { if (it.id == id) it.copy(state = ChargeState.EXCLUDED, tripId = null, stopId = null, reason = "") else it })
    }
}

private fun Trip.placeKey() = destination?.takeIf { it.isNotBlank() } ?: endAddress?.takeIf { it.isNotBlank() }

/** A destination the driver has labelled the same way several times labels itself. Earlier automatic guesses do not count as evidence. */
internal fun autoPurpose(trip: Trip, history: List<Trip>): TripPurpose? {
    val key = trip.placeKey() ?: return null
    val same = history.filter { it.id != trip.id && !it.open && it.demo == trip.demo && it.placeKey() == key &&
        it.purpose != TripPurpose.UNCLASSIFIED && it.purposeSource != EditSource.AUTO }.sortedByDescending { it.startAt }.take(5)
    if (same.size < AUTO_PURPOSE_MIN) return null
    return same.map { it.purpose }.distinct().singleOrNull()
}

internal data class AutopilotSummary(val trips: Int, val complete: Int, val autoPurpose: Int, val cardMatched: Int, val cardWon: Long, val review: Int, val waiting: Int, val left: Int) {
    val percent get() = if (trips == 0) null else complete * 100 / trips
}

/** How much of the period closed itself, for the home card and the month-end view. */
internal fun autopilotSummary(data: TripLogData, from: LocalDate, to: LocalDate, zone: ZoneId): AutopilotSummary {
    val demo = data.profile.demoMode
    val trips = tripsIn(data.trips.filter { it.demo == demo }, from, to, zone)
    val charges = data.charges.filter { it.demo == demo && day(it.at, zone) in from..to }
    val reviewTrips = charges.filter { it.state == ChargeState.REVIEW }.mapNotNull { it.tripId }.toSet()
    val matched = charges.filter { it.state == ChargeState.MATCHED }
    return AutopilotSummary(trips.size, trips.count { it.purpose != TripPurpose.UNCLASSIFIED && it.driverId != null && it.id !in reviewTrips },
        trips.count { it.purposeSource == EditSource.AUTO }, matched.size, matched.sumOf { it.amountWon },
        charges.count { it.state == ChargeState.REVIEW }, charges.count { it.state == ChargeState.WAITING },
        trips.count { it.purpose == TripPurpose.UNCLASSIFIED || it.driverId == null } + charges.count { it.state == ChargeState.REVIEW })
}

/** One card record for the presentation build, shaped to what the car just saw so the demo needs no real card. */
internal fun demoCardApproval(data: TripLogData, now: Long): CardApproval {
    val latest = data.trips.filter { it.demo }.maxByOrNull { it.startAt }
    val stop = data.stops.lastOrNull { it.demo && it.chargeId == null }
    val (merchant, amount) = when {
        stop != null -> "차지비 ${(stop.place ?: latest?.destination).orEmpty()}".trim() to 18_400L
        latest != null && latest.tollPasses.isNotEmpty() && latest.expenses.none { it.type == ExpenseType.TOLL } -> "한국도로공사" to (latest.tollFareWon ?: 3_600L)
        else -> "아이파킹 ${latest?.destination.orEmpty()}".trim() to 4_500L
    }
    return CardApproval(cardApprovalId(now, true, amount, merchant, null, false), now, true, amount, merchant, null, CardSource.NOTIFICATION)
}

/** The trip a review card should offer: the matcher's pick, else the trip closest in time to the payment. */
internal fun suggestedTrip(charge: CardCharge, data: TripLogData): Trip? = data.findTrip(charge.tripId)
    ?: (data.trips + listOfNotNull(data.open)).filter { it.demo == charge.demo }.minByOrNull { abs(it.startAt - charge.at) }
