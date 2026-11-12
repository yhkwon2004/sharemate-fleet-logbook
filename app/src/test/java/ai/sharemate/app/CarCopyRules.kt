package ai.sharemate.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue

/*
 * The one-line rule of 2.12 (docs/design/DESIGN_2_12_SPEC.md §9), shared by the copy tests of the car's restyled pages. A line's
 * width is estimated in units: a Hangul syllable 1, whitespace .25, anything else (digits, Latin, ·, punctuation) .55. A
 * syllable at bodySmall 16 sp in Asta Sans measures about 14–14.7 dp, so each budget keeps a line inside the narrowest place
 * it is drawn at the normal font. A larger font is not the budget's business: CarLine takes two lines above 1.05×.
 */

/** A line beside a switch (설정's SwitchRow): 368 dp at FULL, 306 on the narrowest two-up page. */
internal const val SWITCH_LINE_UNITS = 20.0

/** A supporting line across a card ([CarLine]). */
internal const val CARD_LINE_UNITS = 26.0

/** The line of an ⓘ row ([CarInfoRow]), after its mark. */
internal const val INFO_LINE_UNITS = 26.0

/** A line in the 지금 column beside the 56-dp avatar. */
internal const val NOW_LINE_UNITS = 19.0

// 2.13 (docs/design/DESIGN_2_13_SPEC.md §10): the budgets of round 2's new places, at FULL and the normal font.

/** A line of a [CarDialog] at its 600-dp width, the card less its 28-dp padding. */
internal const val DIALOG_LINE_UNITS = 30.0

/** The follow-up bar's message beside one pill (the widest, 회사 비용 아님), in the 760-dp bar. */
internal const val FOLLOW_UP_UNITS = 32.0

/** The follow-up bar's message beside two pills (a second action and the undo). */
internal const val FOLLOW_UP_TWO_UNITS = 24.0

/** The line of a 운행기록 row, under the route, beside the start time and the purpose tag. */
internal const val LOG_ROW_LINE_UNITS = 19.0

/** The line of a tile on 홈: a destination card's line, three across at 360 dp. */
internal const val HOME_TILE_LINE_UNITS = 19.0

// 2.14 (docs/design/DESIGN_2_14_HOME.md): the tall forms' places, at FULL and the normal font.

/**
 * A tall destination card's line: bodyMedium (18 sp, a unit about 16.3 dp) in a 360-dp card less 16 dp a side, the chevron
 * and its gap: 304 dp.
 */
internal const val HOME_TALL_LINE_UNITS = 18.0

/** A setup tile's line beside its disc: a third of the 1064-dp card (346 dp) less 16 a side, the disc, the chevron and gaps. */
internal const val SETUP_TILE_LINE_UNITS = 16.0

/** The tall dock's caption after 사고·특이사항, beside 휴대폰에도 공유 (bodySmall in the 1080-dp header line). */
internal const val DOCK_CAPTION_UNITS = 40.0

/** A line of 홈's attention card, across its whole width. */
internal const val HOME_CARD_LINE_UNITS = 38.0

/** 홈's status line under the greeting, within the 720-dp reading measure. */
internal const val HOME_STATUS_UNITS = 36.0

/** The incident dock's lead line, in its 224-dp lead. */
internal const val DOCK_LINE_UNITS = 13.0

/** The header search bar's phrase, which must show whole from the bar's PHRASE width (280 dp). */
internal const val BAR_PHRASE_UNITS = 12.0

/** The width of [text] in units: a Hangul syllable 1, whitespace .25, anything else .55. */
internal fun oneLineUnits(text: String): Double = text.sumOf { ch ->
    when {
        ch in '가'..'힣' -> 1.0
        ch.isWhitespace() -> .25
        else -> .55
    }
}

/** Asserts that [text] is one car line: no line break, no final period, and at most [budget] units ([oneLineUnits]). */
internal fun assertOneLine(text: String, budget: Double) {
    assertFalse("한 줄이어야 함: $text", text.contains('\n'))
    assertFalse("마침표로 끝나지 않음: $text", text.trimEnd().endsWith('.'))
    val units = oneLineUnits(text)
    assertTrue("$text: $units > $budget", units <= budget)
}
