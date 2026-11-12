package ai.sharemate.app

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The 2.12 car skin against WCAG AA (docs/design/DESIGN_2_12_SPEC.md §1), in both scenes: every text pair at 4.5 or more,
 * every control, outline and status dot that must be seen at 3 or more (1.4.11). A translucent token is laid over what it
 * sits on before its luminance is taken, as the screen composites it. Each pair also matches the spec's table (day / night)
 * within rounding, so a token that drifts from the table is caught even while it still passes.
 */
class CarSkinTest {
    private class Skin(val name: String, val car: CarSkin, val scene: SceneColors, val gleoSpeaker: Color)

    // The assistant's ink comes from the Pleos palettes in SharemateTheme.kt, which are private: LightPleos by day, DarkPleos by night.
    private val day = Skin("낮", DayCarSkin, DaySceneColors, Color(0xFF3F4B81))
    private val night = Skin("밤", NightCarSkin, NightSceneColors, Color(0xFFB6C1F3))

    private val text = 4.5
    private val nonText = 3.0

    // SceneTest's formula (WCAG 2.x relative luminance), kept here because SceneTest's is local to one test.
    private fun luminance(c: Color): Double {
        fun lin(v: Float): Double = if (v <= 0.03928f) v / 12.92 else Math.pow((v + 0.055) / 1.055, 2.4)
        return 0.2126 * lin(c.red) + 0.7152 * lin(c.green) + 0.0722 * lin(c.blue)
    }

    private fun contrast(a: Color, b: Color): Double {
        val l1 = luminance(a)
        val l2 = luminance(b)
        return (maxOf(l1, l2) + 0.05) / (minOf(l1, l2) + 0.05)
    }

    /**
     * One row of the table in both scenes: [fg] on [bg] (each laid over the card first when translucent) clears [floor]
     * and reads as the spec's [figures] (day to night).
     */
    private fun pair(what: String, floor: Double, figures: Pair<Double, Double>, bg: (Skin) -> Color, fg: (Skin) -> Color) {
        for ((skin, figure) in listOf(day to figures.first, night to figures.second)) {
            val under = bg(skin).let { if (it.alpha < 1f) it.compositeOver(skin.car.card) else it }
            val over = fg(skin).let { if (it.alpha < 1f) it.compositeOver(under) else it }
            val ratio = contrast(over, under)
            assertTrue("${skin.name} $what: $ratio < $floor", ratio >= floor)
            assertEquals("${skin.name} $what (표 ${figure})", figure, ratio, .03)
        }
    }

    private val card = { s: Skin -> s.car.card }

    @Test fun everyTextColourClearsAaOnTheCard() {
        pair("text", text, 17.38 to 16.00, card) { it.scene.text }
        pair("textSecondary", text, 8.07 to 9.88, card) { it.scene.textSecondary }
        pair("textMuted", text, 5.82 to 6.27, card) { it.scene.textMuted }
        pair("accentInk", text, 4.93 to 7.66, card) { it.car.accentInk }
        pair("glow", text, 6.14 to 10.32, card) { it.scene.glow }
        pair("warn", text, 6.80 to 9.09, card) { it.scene.warn }
        pair("positive", text, 6.21 to 9.08, card) { it.scene.positive }
        pair("negative", text, 5.60 to 4.81, card) { it.scene.negative }
        pair("gleoSpeaker", text, 8.28 to 9.07, card) { it.gleoSpeaker }
    }

    @Test fun theWhiteLabelClearsAaOnTheAccentAndWhileItIsPressed() {
        pair("onAccent on accent", text, 4.93 to 4.93, { it.car.accent }) { it.car.onAccent }
        // The press darkens the accent (pressOnAccent over it), so the label gains contrast while held instead of losing it.
        pair("onAccent on the pressed accent", text, 5.70 to 5.70, { it.car.pressOnAccent.compositeOver(it.car.accent) }) { it.car.onAccent }
    }

    @Test fun chipsTagsAndDiscsKeepTheirTextAndMarksReadable() {
        val chip = { s: Skin -> s.car.chipFill }
        pair("text on chipFill", text, 15.49 to 15.00, chip) { it.scene.text }
        pair("textSecondary on chipFill", text, 7.19 to 9.27, chip) { it.scene.textSecondary }
        pair("icon on chipFill", nonText, 15.49 to 9.27, chip) { it.car.icon }
        // 2.13: a disabled primary has no fill any more (textMuted on the card, 5.82 / 6.27); textMuted on the chip fill is a
        // muted line on a selected row ([carSelection]).
        pair("textMuted on chipFill", text, 5.19 to 5.88, chip) { it.scene.textMuted }
        val tag = { s: Skin -> s.car.tagFill }
        pair("text on tagFill", text, 15.36 to 15.00, tag) { it.scene.text }
        pair("textSecondary on tagFill", text, 7.13 to 9.27, tag) { it.scene.textSecondary }
        // The status dots ([pillDotColor]) on a tag.
        pair("READY dot (glow)", nonText, 5.43 to 9.68, tag) { it.scene.glow }
        pair("POSITIVE dot", nonText, 5.49 to 8.51, tag) { it.scene.positive }
        pair("NEGATIVE dot", nonText, 4.95 to 4.51, tag) { it.scene.negative }
        pair("QUIET dot (textMuted)", nonText, 5.15 to 5.88, tag) { it.scene.textMuted }
        pair("DEMO dot (gleoSpeaker)", nonText, 7.32 to 8.51, tag) { it.gleoSpeaker }
        val disc = { s: Skin -> s.car.disc }
        pair("initials on the disc", text, 14.80 to 13.26, disc) { it.scene.text }
        pair("person glyph on the disc", nonText, 14.80 to 8.19, disc) { it.car.icon }
        // 직원 rows on the selection fill take textSecondary: textMuted would be 4.48 there by night.
        pair("textSecondary on the selection fill", text, 6.77 to 7.07, { it.scene.selectionFill }) { it.scene.textSecondary }
    }

    @Test fun theOneSelectionPaintKeepsEverythingOnASelectedRowReadable() {
        // [carSelection] (DESIGN_2_13_SPEC.md §1.1): the chip fill under a 2-dp accentInk ring.
        val chip = { s: Skin -> s.car.chipFill }
        pair("the ring and its check on chipFill", nonText, 4.39 to 7.18, chip) { it.car.accentInk }
        pair("the ring against the card beside the row", nonText, 4.93 to 7.66, card) { it.car.accentInk }
        pair("text on a selected row", text, 15.49 to 15.00, chip) { it.scene.text }
        pair("textSecondary on a selected row", text, 7.19 to 9.27, chip) { it.scene.textSecondary }
        pair("textMuted on a selected row", text, 5.19 to 5.88, chip) { it.scene.textMuted }
        pair("negative (a 점검 dot) on a selected row", nonText, 4.99 to 4.51, chip) { it.scene.negative }
        pair("positive on a selected row", nonText, 5.53 to 8.51, chip) { it.scene.positive }
        pair("warn on a selected row", nonText, 6.06 to 8.53, chip) { it.scene.warn }
        pair("chipBorder of an outlined row on chipFill", nonText, 3.10 to 3.46, chip) { it.car.chipBorder }
        // DriverAvatar(ring = true): a 2-dp accentInk ring around the chosen person's disc.
        pair("the avatar ring against the disc", nonText, 4.20 to 6.35, { it.car.disc }) { it.car.accentInk }
    }

    @Test fun thePurposeDotsStandOutOnTheTagFill() {
        // [purposeColor] reads the Pleos palette of the scene (DayPleos by day, DarkPleos by night, private to SharemateTheme.kt):
        // COMMUTE positive, BUSINESS chartActive, PERSONAL textTertiary, UNCLASSIFIED negative. [PurposeChip] draws each as the
        // dot of a [CarTag] on the car (2.13), so each must stand out from the tag fill (1.4.11); every one clears 4.0.
        val tag = { s: Skin -> s.car.tagFill }
        val dots = listOf(
            Triple("COMMUTE (positive)", Color(0xFF0064FF) to Color(0xFF0082FF), 4.35 to 4.03),
            Triple("BUSINESS (chartActive)", Color(0xFF00703C) to Color(0xFF00E074), 5.49 to 8.51),
            Triple("PERSONAL (textTertiary)", Color(0xFF5A6678) to Color(0xFF97A3B6), 5.15 to 5.88),
            Triple("UNCLASSIFIED (negative)", Color(0xFFC62B0C) to Color(0xFFFF4C28), 4.95 to 4.51),
        )
        // 3 is the floor for a mark; the spec (§1.3) holds the dots to 4.0.
        for ((what, colors, figures) in dots) pair("$what dot", maxOf(nonText, 4.0), figures, tag) { if (it === day) colors.first else colors.second }
    }

    @Test fun controlsAndOutlinesStandOutFromTheCard() {
        pair("accent (a chosen segment, a switch that is on)", nonText, 4.93 to 3.25, card) { it.car.accent }
        pair("chipBorder (pill buttons, QuietAction, the switch off)", nonText, 3.47 to 3.69, card) { it.car.chipBorder }
        pair("the switch thumb on its track when off", nonText, 3.71 to 7.09, { it.car.trackOff }) { it.car.thumbOff }
        pair("a field's outline", nonText, 4.56 to 3.69, card) { it.scene.outline }
    }

    @Test fun theTabBadgeReadsOnTheAccentThroughItsRing() {
        // The digits are the tile's foreground: white by day, #F2F8FF by night.
        pair("badge digits", text, 5.35 to 5.00, { it.scene.badge }) { it.scene.onTile }
        // The badge itself is 1.09 against the accent, so a covered tab rings it in onAccent: the ring stands out from both.
        pair("the badge ring on the accent", nonText, 4.93 to 4.93, { it.car.accent }) { it.car.onAccent }
        pair("the badge ring against the badge", nonText, 5.35 to 5.35, { it.scene.badge }) { it.car.onAccent }
    }
}
