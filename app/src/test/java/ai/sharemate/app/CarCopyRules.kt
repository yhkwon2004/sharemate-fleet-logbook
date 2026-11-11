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
