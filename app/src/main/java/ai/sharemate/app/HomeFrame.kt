package ai.sharemate.app

import androidx.compose.runtime.Immutable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

/*
 * 홈 without scrolling (2.13, docs/design/DESIGN_2_13_SPEC.md §3), the pure half: what the attention slot holds and in which
 * order ([attentionQueue]), which item the driver is looking at ([attentionIndex]), how tall each card may get
 * ([attentionCardMax]) and how the frame adds up in the full window ([homeFrame]). The composables in HomeAttention.kt and
 * LogbookUi.kt's DrivePane lay themselves out with the constants here, and HomeFrameTest checks every card kind, with and
 * without the pager and the demo strip, against the 1209-dp window: that is why 홈 never needs to scroll there.
 *
 * 2.14 ("채운 홈", docs/design/DESIGN_2_14_HOME.md): 2.13's page read as empty, a small card over a large sky. The blocks are
 * stacked again under the greeting, as 2.12 had them: the destinations in a tall form, the attention card (always one: the
 * idle card holds the slot when the queue is empty), the sky, and the incident dock in a tall form at the foot. Both tall
 * forms have the 2.13 forms as their compact fallback, and [homeForms] picks, for each state, the largest pair that fits.
 * 2.15 (docs/design/DESIGN_2_15.md §4) fills the idle card: up to three rows of the newest trips ([homeIdle], [idleAnatomy]),
 * 334 dp at the most, so the idle frame is 914 dp (1,010 with the demo strip).
 *
 * Heights are one text line as Asta Sans draws it (round(.952 s) + round(.241 s) dp: bodySmall 19, titleSmall and labelLarge
 * 23, bodyLarge 24, titleLarge 29, headlineSmall 31, displaySmall 53), which reproduces the shots to the pixel.
 */

// ───────────────────────── the frame (FULL, 1×, with the 지금 column) ─────────────────────────

/** The full Pleos window's height, the header's one row and the page slot's padding (8 above, 20 below). */
internal val HOME_WINDOW_FULL = 1209.dp
internal val HOME_HEADER_ROW = 84.dp
internal val HOME_PAGE_PADDING = 28.dp

/** The page the frame is budgeted for: the full window less the header row and the page slot's padding (1,097 dp). */
internal val HOME_PAGE_FULL = HOME_WINDOW_FULL - HOME_HEADER_ROW - HOME_PAGE_PADDING

/**
 * The greeting block's minimum beside the 지금 column: the sun rests right of it and needs the band down to 152.7 dp. 2.14
 * puts the brand line back over the greeting (8 + 30 + 12 + 53 + 8 + 24 = 135), which still fits because the greeting keeps
 * to one line there ([HomeGreeting]).
 */
internal val HOME_GREETING_MIN = 160.dp

/**
 * The space between the page's blocks (2.14): under the greeting, under the destinations (the attention card's top), and
 * between the card and the column's tiles where the 지금 column is not there.
 */
internal val HOME_BLOCK_GAP = 24.dp

/** The sky's fixed part; the rest of the page's height is the sky's too (a weighted spacer). */
internal val HOME_SKY_MIN = 24.dp

/** The demo strip (12 + 56 + 12) and the space under it, while demo mode is on. */
internal val HOME_DEMO_STRIP = 80.dp

/** The gap under the demo strip, over the dock. */
internal val HOME_BAND_GAP = 16.dp

/**
 * A destination card's two forms (2.14). TALL, 2.12's look under the greeting: 40 + the 56-dp icon tile + 16 + the title
 * (titleLarge, 29) + 6 + the line (bodyMedium, 21) + 40. COMPACT, 2.13's card: 20 + a 32-dp title + 12 + one 19-dp line +
 * 20 is 103, held at 104 so the budget is exact.
 */
internal val HOME_DESTINATION_TALL = 208.dp
internal val HOME_DESTINATION_COMPACT = 104.dp

/** The tall destination card's icon tile (corner 16, the accent) and its glyph (onAccent). */
internal val DESTINATION_TILE = 56.dp
internal val DESTINATION_GLYPH = 28.dp

/**
 * The incident dock's two forms (2.14). TALL, 2.12's panel: 16 + the 24-dp header line + 12 + the 72-dp buttons (the label
 * over its caption) + 16. COMPACT, 2.13's one row: 12 + the 56-dp buttons + 12.
 */
internal val HOME_DOCK_TALL = 140.dp
internal val HOME_DOCK_COMPACT = 80.dp
internal val DOCK_TALL_PADDING = 16.dp
internal val DOCK_HEADER = 24.dp
internal val DOCK_HEADER_GAP = 12.dp
internal val DOCK_BUTTON_TALL = 72.dp

/** The tallest an attention card is designed to get; every kind stays under it (HomeFrameTest). */
internal val ATTENTION_CARD_CAP = 480.dp

// ───────────────────────── the attention card's anatomy ─────────────────────────

/** The card's padding on every side, as `CarCard(padding = 24.dp)`. */
internal val ATTENTION_PADDING = 24.dp

/** The title row: 32 dp, or 56 with the pager's 56-dp arrows. */
internal val ATTENTION_TITLE = 32.dp
internal val ATTENTION_TITLE_PAGER = 56.dp

/** Between the title row and the headline. */
internal val ATTENTION_TITLE_GAP = 12.dp

/** The headline (headlineSmall, one line), or 53 with a figure at its end (displaySmall). */
internal val ATTENTION_HEADLINE = 31.dp
internal val ATTENTION_FIGURE = 53.dp

/** A supporting line: 4 dp above a one-line [CarLine] (bodySmall, 19 dp). */
internal val ATTENTION_LINE_GAP = 4.dp
internal val CAR_LINE_HEIGHT = 19.dp
internal val ATTENTION_LINE = ATTENTION_LINE_GAP + CAR_LINE_HEIGHT

/** Before the body block and before the action row. */
internal val ATTENTION_BLOCK_GAP = 16.dp

/** The action row: 56 dp, 68 when its start holds the money field, plus 24 while that field shows its error. */
internal val ATTENTION_ROW = 56.dp
internal val ATTENTION_FIELD_ROW = 68.dp
internal val ATTENTION_FIELD_ERROR = 24.dp

/** The incident card's steps: 28-dp discs in 32-dp rows, so four stacked discs never touch; the photo strip at 56. */
internal val INCIDENT_STEP_ROW = 32.dp
internal val INCIDENT_STEP_DISC = 28.dp
internal const val INCIDENT_STEPS_MAX = 4
internal val INCIDENT_PHOTO = 56.dp
internal val INCIDENT_GAP = 8.dp

/** The charge card's amount chips: two rows of 56-dp targets at most, 8 dp apart. */
internal const val CHARGE_CHIP_ROWS = 2
internal val CHARGE_CHIP_ROW = 56.dp
internal val CHARGE_CHIP_GAP = 8.dp

/**
 * The purpose tiles ([PurposeOptions]), the setup steps (2.14: three tiles across, each the button for its step, 96 dp at
 * least: 16 + the 32-dp disc beside a title and a line + 16, with room to spare), the pairing card's two rows, the mini stats.
 */
internal val PURPOSE_TILES = 72.dp
internal val SETUP_TILES = 96.dp
internal val PAIRING_ROWS = 66.dp
internal val MINI_STATS = 46.dp

/** The month-close card's completion bar and the gap over it. */
internal val CLOSE_BAR = 10.dp
internal val CLOSE_BAR_GAP = 12.dp

/**
 * The idle card's rows (2.15, docs/design/DESIGN_2_15.md §4): the newest finished trips, three at most, each a 56-dp row (the
 * car's least touch target) with a 1-dp hairline between two rows.
 */
internal const val IDLE_ROWS_MAX = 3
internal val IDLE_ROW = 56.dp
internal val IDLE_HAIRLINE = 1.dp

/** [count] idle rows with the hairlines between them (pure, unit-tested): 56, 113 and 170 dp, none for none; three at most. */
internal fun idleRowsHeight(count: Int): Dp {
    val rows = count.coerceIn(0, IDLE_ROWS_MAX)
    return if (rows == 0) 0.dp else IDLE_ROW * rows + IDLE_HAIRLINE * (rows - 1)
}

// ───────────────────────── the queue ─────────────────────────

/**
 * What the attention slot can hold, most urgent first, with the word the status line and the pager use. The eight kinds the
 * parked task list shares ([TaskKind]) keep its words, so the driving screen's last line ([drivingTaskLine]) still names
 * what the parked 홈 shows.
 */
internal enum class AttentionKind(val label: String) {
    INCIDENT(TaskKind.INCIDENT.label), PERMISSION("차량 권한"), RECLASSIFY("목적 바꾸기"), CARD_REVIEW(TaskKind.CARD_REVIEW.label),
    CHARGE(TaskKind.CHARGE.label), FINISHED("방금 운행"), MATCHED("카드 반영"), PURPOSE(TaskKind.PURPOSE.label), SETUP(TaskKind.SETUP.label),
    PAIRING(TaskKind.PAIRING.label), MONTH_CLOSE(TaskKind.MONTH_CLOSE.label), CARE(TaskKind.CARE.label), CLOSE_REMINDER("월 마감"),
    CHARGE_WAITING("충전 비용"),
}

/** One thing in the attention slot: its [kind], how many of it wait ([count]) and, for a trip's card, the trip ([id]). */
@Immutable internal data class AttentionItem(val kind: AttentionKind, val count: Int = 1, val id: String? = null)

/** The item the driver paged to ([item]) and the queue's head when they did ([head]); see [attentionIndex]. */
@Immutable internal data class AttentionPin(val item: AttentionItem, val head: AttentionItem)

/**
 * Everything 홈's attention slot will show, most urgent first (pure, unit-tested): the parked task list's order (incident,
 * card question, charge, purpose, setup, pairing, month close, care) with what the home adds folded in. The permission prompt
 * while the vehicle signal is [SignalStatus.DENIED]; the purpose question reopened from the 방금 운행 card ([reclassifyId]),
 * behind an open incident; the 방금 운행 card ([finishedId]), behind card and charge questions instead of dropped; a batch of
 * card payments the car settled at once ([matched]); this month's close reminder ([closeReminder]) only when no month-close
 * task stands; and a charge whose card approval is awaited ([chargeWaiting]) last. [finishedId] and [reclassifyId] come from
 * the home's answers ([ParkedStackAnswers]), already checked against the data.
 */
internal fun attentionQueue(tasks: List<ParkedTask>, signal: SignalStatus, finishedId: String?, reclassifyId: String?, matched: Int,
    closeReminder: Boolean, chargeWaiting: Boolean): List<AttentionItem> {
    fun task(kind: TaskKind, target: AttentionKind) = tasks.firstOrNull { it.kind == kind && it.count > 0 }?.let { AttentionItem(target, it.count) }
    return listOfNotNull(
        task(TaskKind.INCIDENT, AttentionKind.INCIDENT),
        AttentionItem(AttentionKind.PERMISSION).takeIf { signal == SignalStatus.DENIED },
        reclassifyId?.let { AttentionItem(AttentionKind.RECLASSIFY, id = it) },
        task(TaskKind.CARD_REVIEW, AttentionKind.CARD_REVIEW),
        task(TaskKind.CHARGE, AttentionKind.CHARGE),
        finishedId?.let { AttentionItem(AttentionKind.FINISHED, id = it) },
        AttentionItem(AttentionKind.MATCHED, matched).takeIf { matched > 0 },
        task(TaskKind.PURPOSE, AttentionKind.PURPOSE),
        task(TaskKind.SETUP, AttentionKind.SETUP),
        task(TaskKind.PAIRING, AttentionKind.PAIRING),
        task(TaskKind.MONTH_CLOSE, AttentionKind.MONTH_CLOSE),
        task(TaskKind.CARE, AttentionKind.CARE),
        AttentionItem(AttentionKind.CLOSE_REMINDER).takeIf { closeReminder && tasks.none { it.kind == TaskKind.MONTH_CLOSE && it.count > 0 } },
        AttentionItem(AttentionKind.CHARGE_WAITING).takeIf { chargeWaiting },
    )
}

/**
 * Which item of [queue] the slot shows (pure, unit-tested). The queue's head unless the driver paged ([pin]) while the same
 * head stood: then the item of the pinned kind and trip, wherever it moved (a count that changed keeps it). A 방금 운행 pin
 * follows the 목적 바꾸기 question of the same trip that its 다른 목적 opens, so the driver lands on the question just asked
 * for; a 충전 비용 card that was waiting for the card approval follows the amount question that 지금 입력 (or the end of the
 * wait) puts in its place, even when something more urgent stands at the head. A new head (something more urgent arrived,
 * or the head was answered) or a pinned item that has gone shows the head.
 */
internal fun attentionIndex(queue: List<AttentionItem>, pin: AttentionPin?): Int {
    if (pin == null || queue.isEmpty() || queue.first() != pin.head) return 0
    val at = queue.indexOfFirst { it.kind == pin.item.kind && it.id == pin.item.id }
    if (at >= 0) return at
    if (pin.item.kind == AttentionKind.FINISHED) {
        val next = queue.indexOfFirst { it.kind == AttentionKind.RECLASSIFY && it.id == pin.item.id }
        if (next >= 0) return next
    }
    if (pin.item.kind == AttentionKind.CHARGE_WAITING) {
        val next = queue.indexOfFirst { it.kind == AttentionKind.CHARGE }
        if (next >= 0) return next
    }
    return 0
}

/**
 * How a card kind is built, for its height: a figure at the headline's end, the supporting lines, a block under the headline
 * ([under], the month-close bar), the body block, and the action row (none: [row] null, as on the setup card, whose tiles
 * are its buttons). Without a [headline] row (2.15: the idle card's rows) the body follows the title row's 12 dp directly,
 * with neither the headline, the block under it, the lines nor the 16 dp over the body.
 */
@Immutable internal data class AttentionAnatomy(val figure: Boolean, val lines: Int, val under: Dp = 0.dp, val body: Dp? = null, val row: Dp? = ATTENTION_ROW,
    val headline: Boolean = true)

/**
 * The idle card's anatomy with [rows] trips to list (2.15, docs/design/DESIGN_2_15.md §4; pure, unit-tested). With trips: no
 * headline row, the rows right under the title row ([idleRowsHeight], three at most), then 운행기록 보기. Without any: the
 * headline 아직 운행이 없어요, the three steps of how a trip is recorded as tiles in the setup tiles' look ([SETUP_TILES]),
 * then 시연 체험 보기.
 */
internal fun idleAnatomy(rows: Int): AttentionAnatomy =
    if (rows > 0) AttentionAnatomy(figure = false, lines = 0, body = idleRowsHeight(rows), headline = false)
    else AttentionAnatomy(figure = false, lines = 0, body = SETUP_TILES)

/**
 * Each kind's anatomy at its tallest (§3.2's table), and the idle card's for null at its tallest (2.15: 최근 운행 with three
 * rows, 334 dp; without any trip it is 307); the composables draw the same blocks with the same constants.
 */
internal fun attentionAnatomy(kind: AttentionKind?): AttentionAnatomy = when (kind) {
    null -> idleAnatomy(IDLE_ROWS_MAX)
    // 지금 할 일 / the photo hint; four steps, the note and the photo strip, 8 dp apart; the facts line beside two buttons.
    AttentionKind.INCIDENT -> AttentionAnatomy(figure = false, lines = 1,
        body = INCIDENT_STEP_ROW * INCIDENT_STEPS_MAX + INCIDENT_GAP + CAR_LINE_HEIGHT + INCIDENT_GAP + INCIDENT_PHOTO)
    AttentionKind.PERMISSION, AttentionKind.CARE -> AttentionAnatomy(figure = false, lines = 1)
    AttentionKind.RECLASSIFY, AttentionKind.PURPOSE -> AttentionAnatomy(figure = true, lines = 2, body = PURPOSE_TILES)
    AttentionKind.CARD_REVIEW -> AttentionAnatomy(figure = true, lines = 2)
    AttentionKind.CHARGE -> AttentionAnatomy(figure = false, lines = 1, body = CHARGE_CHIP_ROW * CHARGE_CHIP_ROWS + CHARGE_CHIP_GAP * (CHARGE_CHIP_ROWS - 1),
        row = ATTENTION_FIELD_ROW + ATTENTION_FIELD_ERROR)
    AttentionKind.FINISHED, AttentionKind.MATCHED -> AttentionAnatomy(figure = true, lines = 1)
    // 2.14: the three steps are tiles that open their own place, so the two buttons under them went.
    AttentionKind.SETUP -> AttentionAnatomy(figure = false, lines = 0, body = SETUP_TILES, row = null)
    AttentionKind.PAIRING -> AttentionAnatomy(figure = true, lines = 1, body = PAIRING_ROWS)
    AttentionKind.MONTH_CLOSE -> AttentionAnatomy(figure = false, lines = 1, under = CLOSE_BAR_GAP + CLOSE_BAR)
    AttentionKind.CLOSE_REMINDER -> AttentionAnatomy(figure = false, lines = 0)
    AttentionKind.CHARGE_WAITING -> AttentionAnatomy(figure = false, lines = 1, body = MINI_STATS)
}

/**
 * A card's height at FULL 1× from its [anatomy] (pure, unit-tested): padding, the title row (taller with the [pager]), the
 * headline or its figure with the block under it and the lines, the body (16 dp under a headline, right under the title row
 * without one) and the action row.
 */
internal fun attentionCardHeight(anatomy: AttentionAnatomy, pager: Boolean): Dp {
    val a = anatomy
    val head = if (a.headline) (if (a.figure) ATTENTION_FIGURE else ATTENTION_HEADLINE) + a.under + ATTENTION_LINE * a.lines else 0.dp
    return ATTENTION_PADDING * 2 + (if (pager) ATTENTION_TITLE_PAGER else ATTENTION_TITLE) + ATTENTION_TITLE_GAP + head +
        (a.body?.let { (if (a.headline) ATTENTION_BLOCK_GAP else 0.dp) + it } ?: 0.dp) + (a.row?.let { ATTENTION_BLOCK_GAP + it } ?: 0.dp)
}

/**
 * The tallest a card of [kind] gets at FULL 1× (pure, unit-tested): [attentionCardHeight] of its [attentionAnatomy]. Null is
 * the idle card, which never has a pager (it shows only while the queue is empty), at its tallest: three rows, 334 dp
 * ([idleCardHeight] gives each form). Every card stays under [ATTENTION_CARD_CAP]: the incident card is the tallest at 477
 * with the pager.
 */
internal fun attentionCardMax(kind: AttentionKind?, pager: Boolean): Dp = attentionCardHeight(attentionAnatomy(kind), pager && kind != null)

/**
 * The idle card's height with [rows] trips (2.15 §4; pure, unit-tested): 48 + 32 + 12 + the rows + 16 + 56, so 334 dp with
 * three, 277 with two and 220 with one; without any, 48 + 32 + 12 + the headline 31 + 16 + the 96-dp tiles + 16 + 56 = 307.
 * Every form stays within [attentionCardMax] of null, which the frame counts.
 */
internal fun idleCardHeight(rows: Int): Dp = attentionCardHeight(idleAnatomy(rows), pager = false)

/** A form of 홈's destination cards or of its incident dock (2.14): 2.12's [TALL] look, or 2.13's [COMPACT] one. */
internal enum class HomeForm { TALL, COMPACT }

/** The forms 홈 draws its destination [cards] and its [dock] in. */
@Immutable internal data class HomeForms(val cards: HomeForm, val dock: HomeForm)

/**
 * The pairs [homeForms] tries, the one it likes best first: both tall; then the dock gives way, since the destinations sit
 * right under the greeting where the page's weight is; then the cards; then both.
 */
internal val HOME_FORM_ORDER = listOf(HomeForms(HomeForm.TALL, HomeForm.TALL), HomeForms(HomeForm.TALL, HomeForm.COMPACT),
    HomeForms(HomeForm.COMPACT, HomeForm.TALL), HomeForms(HomeForm.COMPACT, HomeForm.COMPACT))

/** A destination card's height in [form]: 208 or 104 dp. */
internal fun destinationHeight(form: HomeForm): Dp = if (form == HomeForm.TALL) HOME_DESTINATION_TALL else HOME_DESTINATION_COMPACT

/** The incident dock's height in [form]: 140 or 80 dp. */
internal fun dockHeight(form: HomeForm): Dp = if (form == HomeForm.TALL) HOME_DOCK_TALL else HOME_DOCK_COMPACT

/**
 * 홈's frame in the full window, block by block (2.14): the greeting block, 24, the destinations, 24, the attention card, the
 * sky's fixed part, the demo strip with its gap, and the dock, in the [forms] it was built for. [total] is the page's content
 * at its tallest; the page slot is 1097 dp.
 */
@Immutable internal data class HomeFrame(val forms: HomeForms, val greeting: Dp, val destinations: Dp, val card: Dp, val sky: Dp, val demo: Dp,
    val dock: Dp) {
    val total: Dp get() = greeting + HOME_BLOCK_GAP + destinations + HOME_BLOCK_GAP + card + sky + demo + dock

    /**
     * The sky this frame leaves at the least on a [page] of that height: its fixed 24 dp and what the blocks do not take. The
     * frame is chosen for the 1097-dp page, but a FULL window a little shorter still has the 지금 column and this frame, so the
     * follow-up bar decides by the page the window really gives ([homeBarLift]); there 1097 would promise sky that is not there.
     */
    fun skyOn(page: Dp): Dp = page - total + sky

    /** The sky this frame leaves at the least on the 1097-dp page ([skyOn]). */
    val skyLeft: Dp get() = skyOn(HOME_PAGE_FULL)
}

/**
 * The frame for the card of [kind] (null: the idle card), with the [pager] (two items or more) and the [demo] strip, in
 * [forms] (pure, unit-tested): 160 + 24 + the destinations + 24 + [attentionCardMax] + 24 + 96 in demo mode + the dock.
 */
internal fun homeFrame(kind: AttentionKind?, pager: Boolean, demo: Boolean, forms: HomeForms): HomeFrame = HomeFrame(
    forms = forms,
    greeting = HOME_GREETING_MIN,
    destinations = destinationHeight(forms.cards),
    card = attentionCardMax(kind, pager),
    sky = HOME_SKY_MIN,
    demo = if (demo) HOME_DEMO_STRIP + HOME_BAND_GAP else 0.dp,
    dock = dockHeight(forms.dock),
)

/**
 * The forms 홈 takes for a state (pure, unit-tested): the first pair of [HOME_FORM_ORDER] whose frame fits the 1097-dp page
 * with the card of [kind] (null: idle) at its tallest. Every state takes both tall forms but an open incident in demo mode,
 * whose dock steps down to the compact row (1,093 dp). The composables read the choice beside the 지금 column at the normal
 * font, where the budget holds; a change of form swaps on the card clock, and snaps under reduced motion.
 */
internal fun homeForms(kind: AttentionKind?, pager: Boolean, demo: Boolean): HomeForms =
    HOME_FORM_ORDER.firstOrNull { homeFrame(kind, pager, demo, it).total <= HOME_PAGE_FULL } ?: HOME_FORM_ORDER.last()

/**
 * The frame for 홈 as it stands (pure, unit-tested): [homeFrame] for the tallest card in [queue], with the pager from two
 * items, in the forms [homeForms] picks for it, so paging through the queue never changes a form under the driver's finger;
 * the idle card's while the queue is empty. Its [HomeFrame.forms] are what the page draws, and its [HomeFrame.skyOn] the
 * page's height is what the follow-up bar decides by ([homeBarLift]), from the state at once rather than from a sky still
 * animating.
 */
internal fun homeFrameFor(queue: List<AttentionItem>, demo: Boolean): HomeFrame {
    val pager = queue.size >= 2
    val tallest = queue.maxByOrNull { attentionCardMax(it.kind, pager) }?.kind
    return homeFrame(tallest, pager, demo, homeForms(tallest, pager, demo))
}

/**
 * The forms 홈 takes where the frame's budget does not hold (pure, unit-tested): no 지금 column, a font above 1×, a short
 * window. The page scrolls there when it must, so it keeps the tall look wherever it reads: the tall cards where the three
 * stand across ([columns] 3) and the window is not [short], the tall dock unless the window is [short].
 */
internal fun homeFallbackForms(columns: Int, short: Boolean): HomeForms =
    HomeForms(if (columns >= 3 && !short) HomeForm.TALL else HomeForm.COMPACT, if (short) HomeForm.COMPACT else HomeForm.TALL)

/** The room the follow-up bar takes over what it rides on: the 64-dp bar and the 24 dp under it. */
internal val FOLLOW_UP_ROOM = 88.dp

/**
 * How high the follow-up bar rides over 홈's foot (pure, unit-tested): over the whole [band] (the demo strip, its gap and the
 * dock) while the [sky] above it holds the bar, else over the [dock] alone, across the demo strip: a full page in demo mode
 * leaves 28 dp of sky, and the bar would cover the attention card's buttons. The incident buttons are never covered.
 * Without the demo strip the band is the dock, and the fullest page (the incident card with the pager, 64 dp of sky) lets
 * the bar reach at most the card's 24-dp bottom padding. [sky] must be the least the page really leaves: the frame's
 * [HomeFrame.skyOn] the page's own height, or the sky measured.
 */
internal fun homeBarLift(band: Dp, dock: Dp, sky: Dp): Dp = if (sky >= FOLLOW_UP_ROOM) band else dock

/**
 * Whether an incident button's caption (119 · 112 안내) sits beside its label (pure, unit-tested): from 196 dp of [button]
 * width, more with a larger font (one below 1× counts as 1×); narrower, it sits under the label in a 72-dp button. Either
 * way it stays on screen: it is the safety hint.
 */
internal fun incidentCaptionBeside(button: Dp, fontScale: Float): Boolean = button >= 196.dp * fontScale.coerceAtLeast(1f)

/** The tall dock's header line needs about 540 dp at 1× with 휴대폰에도 공유: the title, the caption and the phone note in one row. */
internal val DOCK_HEADER_ONE_ROW = 560.dp

/**
 * Whether the tall dock's header keeps to one row (pure, unit-tested): from 560 dp of the dock's [width], more with a larger
 * font (one below 1× counts as 1×). Narrower, the caption takes its own line under the title, so the one-line copy is never
 * cut in a split window or at 1.3×; the frame never sees that form (beside the 지금 column the dock is 1,080 dp at 1×).
 */
internal fun dockHeaderOneRow(width: Dp, fontScale: Float): Boolean = width >= DOCK_HEADER_ONE_ROW * fontScale.coerceAtLeast(1f)

// ───────────────────────── words of the cards (pure, unit-tested) ─────────────────────────

/** A line on 홈 and what TalkBack says for it ([spoken], the long form where the line is the short one). */
@Immutable internal data class HomeLine(val text: String, val spoken: String = text)

/**
 * The status line's queue form: "확인할 일 3건 · 카드 확인 → 목적 확인 → 차량 관리", three names, then "외 N건"; TalkBack hears
 * the names one by one.
 */
internal fun attentionQueueLine(queue: List<AttentionItem>): HomeLine {
    val names = queue.map { it.kind.label }
    val shown = names.take(3).joinToString(" → ") + if (names.size > 3) " 외 ${names.size - 3}건" else ""
    return HomeLine("${HomeCopy.QUEUE_HEAD} ${queue.size}건 · $shown", "${HomeCopy.QUEUE_HEAD} ${queue.size}건, ${names.joinToString(", ")}")
}

/**
 * The card question's headline (pure, unit-tested): the matcher put the payment on a trip ([CardCharge.tripId]); the car only
 * guessed the nearest trip ([suggestedTrip] without a match), and says so; or it found no trip at all.
 */
internal fun chargeReviewHeadline(charge: CardCharge, trip: Trip?): String = when {
    trip == null -> HomeCopy.REVIEW_NO_TRIP
    charge.tripId != null -> HomeCopy.REVIEW_MATCHED
    else -> HomeCopy.REVIEW_NEAREST
}

/** The incident card's line under 지금 할 일 (pure, unit-tested): the photos the phone sent, where they are added, or how to turn that on. */
internal fun incidentHint(photos: Int, phoneSync: Boolean): HomeLine = when {
    photos > 0 -> HomeLine("사진 ${photos}장 · ${HomeCopy.INCIDENT_PHOTOS_SAVED}")
    phoneSync -> HomeLine(HomeCopy.INCIDENT_PHONE, HomeCopy.SPOKEN_INCIDENT_PHONE)
    else -> HomeLine(HomeCopy.INCIDENT_SYNC_OFF, HomeCopy.SPOKEN_INCIDENT_SYNC_OFF)
}

/** What the car captured for an incident, as the action row's line (pure): "계기판 51,300.0 km · 운전자 Kim Jiwoo · {주소}". */
internal fun incidentFacts(incident: Incident, driver: Driver?): String = listOf(
    "계기판 ${incident.odometerKm?.takeIf { it.isFinite() }?.let(::kmText) ?: "미수신"}",
    "운전자 ${driver?.let { it.name.ifBlank { it.label } } ?: "미지정"}",
    incident.address?.takeIf { it.isNotBlank() } ?: "내비 주소 미수신",
).joinToString(" · ")

/** The month-close card's line (pure): the first step still open, or that the month can be closed now. */
internal fun monthCloseTaskLine(status: MonthStatus): HomeLine = when {
    status.unfinished > 0 -> HomeLine("목적·사용자 확인 ${status.unfinished}건 남음", "1단계 목적·사용자 확인, ${status.unfinished}건 남음")
    status.reviews > 0 -> HomeLine("카드 확인 ${status.reviews}건 남음", "2단계 카드 확인, ${status.reviews}건 남음")
    status.ready -> HomeLine("모두 확인했어요 · 지금 마감할 수 있어요")
    else -> HomeLine("남은 확인 ${status.issues}건")
}

/** The one way on of a month-close card ([monthCloseNext]). */
internal enum class MonthCloseNext { TIDY, REVIEW, CLOSE }

/**
 * The one way on that both month-close cards offer (pure, unit-tested), in [monthCloseTaskLine]'s order: 정리하기 while trips
 * still want a purpose or a driver, 확인하기 while only card questions are left, and the close once nothing is. Card questions
 * get their own step because 점검 필요 lists trips alone: sent there, a month whose last open items are card payments would
 * find an empty list and no way on.
 */
internal fun monthCloseNext(status: MonthStatus): MonthCloseNext = when {
    status.unfinished > 0 -> MonthCloseNext.TIDY
    status.reviews > 0 -> MonthCloseNext.REVIEW
    else -> MonthCloseNext.CLOSE
}

/** When a trip ran (pure, unit-tested): "09:30 – 10:00" today, "11월 8일 (일) 09:30 – 10:00" on another day. */
internal fun tripWhenLine(trip: Trip, now: Long, zone: ZoneId): String {
    val start = Instant.ofEpochMilli(trip.startAt).atZone(zone)
    val end = Instant.ofEpochMilli(trip.endAt ?: trip.updatedAt).atZone(zone)
    val span = "%02d:%02d – %02d:%02d".format(start.hour, start.minute, end.hour, end.minute)
    val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    return if (start.toLocalDate() == today) span else "${dayLabel(start.toLocalDate())} $span"
}

/** The purpose card's first line (pure): when the trip ran and where, "09:30 – 10:00 · 본사 → 강남 오피스"; the time alone without an address. */
internal fun purposeCardLine(trip: Trip, now: Long, zone: ZoneId): String {
    val route = listOfNotNull(trip.startAddress, trip.destination ?: trip.endAddress).filter { it.isNotBlank() }.joinToString(" → ")
    return listOf(tripWhenLine(trip, now, zone), route).filter { it.isNotBlank() }.joinToString(" · ")
}

/**
 * The purpose card's state tag (pure, unit-tested): while a purpose is changed, the one the trip has ("현재 일반 업무"); while
 * several trips wait for one, how many ("미분류 3건"); a single question needs none.
 */
internal fun purposeCardTag(count: Int, current: TripPurpose?): String? = when {
    current != null && current != TripPurpose.UNCLASSIFIED -> "현재 ${current.label}"
    count > 1 -> "미분류 ${count}건"
    else -> null
}

/** Where a setup step's tile leads: 설정 › 법인·차량, 설정 › 직원, or the driver picker. */
internal enum class SetupTarget { COMPANY, DRIVERS, PICKER }

/**
 * One step of 시작 설정 (2.14, a tile that is the step's button): its [label]; the [line] under it, what the step is for
 * while it is open and what it holds once it is [done]; whether it can be taken now ([enabled]: choosing a driver waits for
 * an employee); whether it is the [current] one, the first open step, whose disc is the solid accent; where it leads
 * ([target]); and the [state] TalkBack says with it.
 */
@Immutable internal data class SetupStep(val label: String, val line: String, val done: Boolean, val enabled: Boolean, val current: Boolean,
    val target: SetupTarget, val spokenLine: String = line) {
    val state: String get() = when {
        done -> HomeCopy.SETUP_STATE_DONE
        enabled -> HomeCopy.SETUP_STATE_NEXT
        else -> HomeCopy.SETUP_STATE_WAITING
    }
}

/**
 * The three setup steps (pure, unit-tested): 법인·차량 정보 (company and plate, which head the logbook form), 직원 등록 (the ④
 * column's names) and 운전자 선택 (who drives today). A done step shows what it holds, "쉐어메이트 · 123가4567", "직원 3명" or
 * the driver's name, and stays a tile that opens the same place to change it. Choosing a driver needs a registered employee
 * first, so with nobody registered that tile is disabled and says why.
 */
internal fun setupSteps(data: TripLogData): List<SetupStep> {
    val profile = data.profile
    val company = profile.companyName.isNotBlank() && profile.plateNumber.isNotBlank()
    val staff = data.drivers.isNotEmpty()
    val driver = data.drivers.firstOrNull { it.id == data.currentDriverId }
    val steps = listOf(
        SetupStep(HomeCopy.SETUP_COMPANY, if (company) "${profile.companyName} · ${profile.plateNumber}" else HomeCopy.SETUP_COMPANY_LINE, company, true, false,
            SetupTarget.COMPANY),
        SetupStep(HomeCopy.SETUP_DRIVERS, if (staff) "직원 ${data.drivers.size}명" else HomeCopy.SETUP_DRIVERS_LINE, staff, true, false, SetupTarget.DRIVERS,
            spokenLine = if (staff) "직원 ${data.drivers.size}명" else HomeCopy.SPOKEN_SETUP_DRIVERS_LINE),
        SetupStep(HomeCopy.SETUP_PICK, when {
            driver != null -> driver.name.ifBlank { driver.label }
            staff -> HomeCopy.SETUP_PICK_LINE
            else -> HomeCopy.SETUP_PICK_WAITING
        }, driver != null, staff, false, SetupTarget.PICKER),
    )
    val first = steps.indexOfFirst { !it.done && it.enabled }
    return steps.mapIndexed { index, step -> if (index == first) step.copy(current = true) else step }
}

/**
 * One row of the idle card (2.15, docs/design/DESIGN_2_15.md §4): the trip a tap opens in 운행기록 ([id]); the [day] and the
 * start, "10월 29일 (목) 02:26"; the [title] as 운행기록 titles the trip's row ([tripRowTitle]); the [purpose], drawn as the
 * list's chip (미분류 as its tag); and the row's one TalkBack stop ([spoken]): the places one by one, the day and the start,
 * the purpose, "수정구 시흥동, 판교, 10월 29일 (목) 02:26 출발, 일반 업무".
 */
@Immutable internal data class IdleRow(val id: String, val day: String, val title: String, val purpose: TripPurpose, val spoken: String)

/**
 * What the idle card shows ([homeIdle]): its [rows], newest first, and the [month] 운행기록 보기 opens on, the newest trip's.
 * No rows (and no month) without a trip: the card then shows how a trip is recorded ([HomeCopy.IDLE_STEPS]).
 */
@Immutable internal data class HomeIdle(val rows: List<IdleRow>, val month: YearMonth?)

/**
 * The idle card's rows (2.15 §4; pure, unit-tested): the newest finished trips of the data set on screen (demo trips only in
 * demo mode, real ones otherwise), [IDLE_ROWS_MAX] at most, newest first; a trip still recording is not one of them. Each is
 * titled as 운행기록 titles its row and spoken as it reads the places ([tripRowTitleSpoken]); an unclassified trip is heard
 * as 목적 미분류. 2.14 showed one trip and its driver: three rows say more about the car in the same glance.
 */
internal fun homeIdle(data: TripLogData): HomeIdle {
    val trips = data.trips.filter { it.demo == data.profile.demoMode && !it.open }.sortedByDescending { it.startAt }.take(IDLE_ROWS_MAX)
    val rows = trips.map { trip ->
        val day = "${dayLabel(localDate(trip.startAt))} ${hm(trip.startAt)}"
        val purpose = if (trip.purpose == TripPurpose.UNCLASSIFIED) IssueType.UNCLASSIFIED.label else trip.purpose.label
        IdleRow(trip.id, day, tripRowTitle(trip), trip.purpose, "${tripRowTitleSpoken(trip)}, $day 출발, $purpose")
    }
    return HomeIdle(rows, trips.firstOrNull()?.let { YearMonth.from(localDate(it.startAt)) })
}

/** One step of how a trip is recorded (2.15 §4, the idle card without any trip): its [title], the [line] under it, and what TalkBack hears for the line. */
@Immutable internal data class IdleStep(val title: String, val line: String, val spokenLine: String = line)

/** The month a batch of settled card payments opens on (pure): the month of its first payment; null for no batch. */
internal fun matchedBatchMonth(charges: List<CardCharge>, zone: ZoneId): YearMonth? =
    charges.minByOrNull { it.at }?.let { YearMonth.from(Instant.ofEpochMilli(it.at).atZone(zone)) }

/** True when [today] already holds a finished trip of the data set on screen: one condition of the tidy status line. */
internal fun finishedTripToday(data: TripLogData, today: LocalDate, zone: ZoneId): Boolean =
    data.trips.any { it.demo == data.profile.demoMode && !it.open && it.endAt != null && Instant.ofEpochMilli(it.startAt).atZone(zone).toLocalDate() == today }
