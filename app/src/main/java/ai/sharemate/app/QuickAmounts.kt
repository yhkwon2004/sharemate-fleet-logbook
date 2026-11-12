package ai.sharemate.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/*
 * Quick amounts under 운행 비용's amount field (2.15, docs/design/DESIGN_2_15.md §5.1): every expense amount needed the
 * on-screen keyboard. One row of chips now sits under the field: up to two amounts this log already paid for the chosen type
 * (a tap sets the field), then +1,000 · +5,000 · +10,000 (a tap adds to what the field holds, never past its limit). The chips
 * are 홈's 충전 card's ([CarExampleChip], 46 dp in a 56-dp target, amounts written as [formatWon] writes them). The field
 * stays the one place an amount is entered: a chip only fills it, and 추가 still adds the cost, so nothing is saved by a
 * stray tap. The phone never draws any of this.
 */

/** 운행 비용's amount field's upper limit, the repository's own range (1원~1,000만 원, LogCopy.AMOUNT_HINT). */
internal const val EXPENSE_LIMIT_WON = 10_000_000L

/** What the adding chips add, in order: +1,000, +5,000, +10,000 (spec §5.1). */
internal val QUICK_ADD_STEPS = listOf(1_000L, 5_000L, 10_000L)

/**
 * The most recent distinct amounts of [type] in [trips] (pure, unit-tested), newest first, [limit] at most: the costs are read
 * newest first by when they were recorded (a tie by the trip's start, the later trip first), other types are ignored, and an
 * amount the field could not hold (outside 1..[EXPENSE_LIMIT_WON]) is never offered, so a chip never fills the field with an
 * error. The caller passes the trips of one data set (a demo trip's costs are never offered for a real trip).
 */
internal fun recentAmounts(trips: List<Trip>, type: ExpenseType, limit: Int = 2): List<Long> {
    if (limit <= 0) return emptyList()
    return trips.flatMap { trip -> trip.expenses.filter { it.type == type && it.amountWon in 1..EXPENSE_LIMIT_WON }.map { Triple(it.at, trip.startAt, it.amountWon) } }
        .sortedWith(compareByDescending<Triple<Long, Long, Long>> { it.first }.thenByDescending { it.second })
        .map { it.third }.distinct().take(limit)
}

/**
 * The field's digits after an adding chip (pure, unit-tested): what [current] holds plus [add], never past [limit]. The field
 * keeps raw digits ([moneyDigits]), but anything else in [current] is ignored all the same (a pasted "4,500원" counts as
 * 4,500); empty counts as 0. A figure already at or past [limit] (one typed too long) comes back as [limit], which the field
 * accepts.
 */
internal fun quickAmount(current: String, add: Long, limit: Long): String {
    val digits = current.filter { it in '0'..'9' }.trimStart('0')
    // Digits too many for a Long are past any limit.
    val now = if (digits.isEmpty()) 0L else digits.toLongOrNull() ?: limit
    val step = add.coerceAtLeast(0)
    return (if (now >= limit || step >= limit - now) limit else now + step).toString()
}

/**
 * The quick amounts under 운행 비용's amount field (spec §5.1): a FlowRow, 8 dp apart and wrapping onto a second row where the
 * pane is narrow, of [CarExampleChip]s, which settle to .98 under the press fill. First the two most recent distinct amounts of
 * [type] in this data set ([recentAmounts] of [trips] with the trip's [demo] flag), "4,500원", each setting [digits] to it;
 * then +1,000, +5,000 and +10,000 ([quickAddText]), each adding to [digits] ([quickAmount]) and off once the field holds the
 * limit. They follow the type chosen above them. TalkBack hears each as one button: the amount and what the tap does
 * (LogCopy.QUICK_SET_ACTION), or "1,000원 더하기" ([quickAddSpoken]) for the symbolic +1,000.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable internal fun QuickAmounts(trips: List<Trip>, demo: Boolean, type: ExpenseType, digits: String, onDigits: (String) -> Unit,
    modifier: Modifier = Modifier, limit: Long = EXPENSE_LIMIT_WON) {
    val recent = remember(trips, demo, type) { recentAmounts(trips.filter { it.demo == demo }, type) }
    FlowRow(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        recent.forEach { won -> CarExampleChip(formatWon(won), { onDigits(won.toString()) }, actionLabel = LogCopy.QUICK_SET_ACTION) }
        QUICK_ADD_STEPS.forEach { add ->
            val next = quickAmount(digits, add, limit)
            val spoken = quickAddSpoken(add)
            CarExampleChip(quickAddText(add), { onDigits(next) }, Modifier.semantics { contentDescription = spoken }, enabled = next != digits,
                actionLabel = LogCopy.QUICK_ADD_ACTION)
        }
    }
}
