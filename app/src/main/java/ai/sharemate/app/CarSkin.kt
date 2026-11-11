package ai.sharemate.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color

/*
 * The car's 2.12 skin, "한 줄" (docs/design/DESIGN_2_12_SPEC.md §1): opaque white cards (navy by night), one solid accent for
 * the current choice and a card's one primary action, line icons in ink, and grey tags and chips. It sits on top of the scene
 * tokens and replaces none of them: the text colours, the status colours (glow keeps 자동 기록 대기, 수신 중 and gear P) and
 * the list-row selection still come from [Scene]. The page backdrop is the scene's as well. The mock's own blue (#3A8BFD,
 * white on it 3.34) and greys (#A9B3D4, 2.08 on white) fail AA, so the accent is #1C6BE3 (4.93) and supporting lines take
 * textMuted. Every pair is checked in CarSkinTest. The phone never reads any of this: see [isCar].
 */

/**
 * The 2.12 car tokens of one scene. Translucent ones ([pressOnAccent], the night [cardBorder]) are drawn over an opaque
 * surface; nothing here is ever blended between the day and the night skin.
 */
@Immutable
internal data class CarSkin(
    /** The current choice (a tab, a segment, a switch that is on, a chip) and a card's one primary action. */
    val accent: Color,
    /** Text and icons on [accent]: 4.93 in both scenes. */
    val onAccent: Color,
    /** Links and the selected sheet tab, on [card] only: 4.42 on the day page and 4.39 on [chipFill] fall short. */
    val accentInk: Color,
    /** The layer over [accent] while it is held. Darker, never lighter: the white press layer would leave onAccent at 4.52 by night. */
    val pressOnAccent: Color,
    /** Every card and header pill, opaque in both scenes, so the sky and the towers never show through. */
    val card: Color,
    /** The card's hairline and its dividers (decorative). */
    val cardBorder: Color,
    /** Line icons. */
    val icon: Color,
    /** Example chips. Never under an accent fill (3.05 by night). */
    val chipFill: Color,
    /** The outline of controls that are not filled: chips, pill buttons, QuietAction, a switch that is off, header controls (3.47 / 3.69 on the card). */
    val chipBorder: Color,
    /** Tags and status tags. */
    val tagFill: Color,
    val tagBorder: Color,
    /** A switch that is off: its track and its thumb (3.71 / 7.09 against the track). */
    val trackOff: Color,
    val thumbOff: Color,
    /** The avatar and icon discs. */
    val disc: Color,
)

internal val DayCarSkin = CarSkin(
    accent = Color(0xFF1C6BE3), onAccent = Color.White, accentInk = Color(0xFF1C6BE3), pressOnAccent = Color(0x1A0B111C),
    card = Color.White, cardBorder = Color(0xFFDCE3F1), icon = Color(0xFF121A2B), chipFill = Color(0xFFEEF2FA), chipBorder = Color(0xFF7F8AA0),
    tagFill = Color(0xFFEEF1F7), tagBorder = Color(0xFFD5DBE8), trackOff = Color(0xFFE3E8F1), thumbOff = Color(0xFF6B7786), disc = Color(0xFFE8EDF7),
)

internal val NightCarSkin = CarSkin(
    accent = Color(0xFF1C6BE3), onAccent = Color.White, accentInk = Color(0xFF8AB4FF), pressOnAccent = Color(0x1A0B111C),
    card = Color(0xFF16213A), cardBorder = Color(0x24FFFFFF), icon = Color(0xFFC3CCDA), chipFill = Color(0xFF1D2738), chipBorder = Color(0xFF6E7A8F),
    tagFill = Color(0xFF1D2738), tagBorder = Color(0xFF2A3549), trackOff = Color(0xFF0E1625), thumbOff = Color(0xFF97A3B6), disc = Color(0xFF243045),
)

/**
 * The car skin in effect, read where it is drawn: `Car.skin.accent`. It follows [LocalScene], so it swaps once, hidden inside
 * the scene's fade-through, together with every other token.
 */
internal object Car {
    val skin: CarSkin @Composable @ReadOnlyComposable get() = if (LocalScene.current.isDay) DayCarSkin else NightCarSkin
}

/**
 * True on the car, where the 2.12 look applies: the start-anchored layout inside the car's theme. [LocalLayout] falls back to
 * the car layout where no window measured it (the car's dialogs and sheets, but also any phone composable outside its
 * shell), so the theme's compact flag, which only the phone's theme sets, is asked as well. Shared components that the phone
 * also draws ([Segmented] through the phone's scene card) keep their old look behind this.
 */
@Composable @ReadOnlyComposable
internal fun isCar(): Boolean = LocalLayout.current.startAnchored && !LocalCompactApp.current
