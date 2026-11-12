# SHAREMATE 2.12: car "한 줄" restyle — final build spec

> 2.13 builds on this spec: [DESIGN_2_13_SPEC.md](DESIGN_2_13_SPEC.md) (no clock, the header search bar, the no-scroll 홈, one money style) wins wherever the two differ.

**Base and conventions**
- Base: `main` @ a0f073e (2.11.0).
- `src/` = `app/src/main/java/ai/sharemate/app/`; `test/` = `app/src/test/java/ai/sharemate/app/`.
- Line numbers are a0f073e's.
- "=" means the text stays exactly as it is.
- Contrast figures are WCAG ratios computed with SceneTest's formula, written day / night.
- Text widths were measured in Asta Sans (res/font/asta_sans_variable.ttf) at FULL, 1×.

## 0. Scope and hard rules

**Where each user request is met**

| Request | Where it is met |
|---|---|
| ① One line only | Every supporting line in scope is one `CarLine`, written to fit (§9). Paragraphs are deleted or folded behind ⓘ (`CarInfoRow`). |
| ② Time always at the top right | `HeaderClock` sits at the right end of the header, with date and time, on every page and in every window class (§4). The 홈 hero clock row (HomeDashboard.kt:439-447) is deleted, so the time never shows twice. |
| ③ 자동 기록 대기 to the right of 사용자 | It becomes a status tag at the end of the 사용자 card's title row (§5.1). With no 지금 column, it is the header pill immediately right of the 사용자 chip (§4). |
| ④ 월간·Excel and 차량 in the same style | §7 and §8. |

**What changes**
- Restyled: the header, the 지금 column (every page), 설정 (all 4 sub-tabs; 기록 is the mock), 월간·Excel and 차량.
- 홈's page column and 운행기록 change only through shared car components: tabs, header, Segmented, PrimaryAction, QuietAction, LoadingAction, SwitchRow, PeriodStepper, DriverAvatar, and the 지금 column. Their copy is not trimmed.
- Nothing changes while driving: the shell composes only DrivingOverview while blocked (LogbookUi.kt:334-336).

**Phone isolation.** The phone runs `SceneTheme(compact = true)` (PhoneActivity.kt:70, PhoneLogbookActivity.kt:68).
- Untouched:
  - Scene.kt: SceneColors, Day/NightSceneColors, the Scene object, SceneModeCard, sceneModeHint.
  - SharemateTheme.kt: all of it.
  - LogbookUi.kt: Panel, Eyebrow, Muted, Stat, Field, PurposeChip, DialogButton. DialogButton reaches the phone through ExcelPreview.kt:169 ← PhoneReport.kt:169.
  - AssistantSheet.kt: AssistantSheet, AssistantBody, PromptChip, `ASSISTANT_*` (PhoneLogbookActivity.kt:274).
  - MonthCloseUi.kt: MonthCloseChip, ClosedChip.
  - Motion.kt: all existing functions.
  - PhoneReservationsUi.kt helpers: bookerLabel, reservationDetailText, reservationWindowText.
  - FleetCare.kt: careAlerts.
  - MonthWidgetInfo's existing fields (PhoneMidnight.kt:392).
- Car-gated with `isCar()`:
  - Segmented (it reaches the phone through SceneModeCard, PhoneSettings.kt:110).
  - Every change inside ExcelPreview.kt, SheetTabs included.
- Defaulted parameters only:
  - `selectionGlide(underlineColor = Scene.glow)`
  - `pressFill(color = Scene.fill)`
  - `CountBadge(ring = false)` (PhoneMidnight.kt:366, 503)
- Gate at integration:
  - `git diff main --stat` lists no Phone*.kt, SharemateTheme.kt or PhoneMidnight.kt.
  - All unit tests pass.
  - Phone 설정 and 현황 are compared before and after: they must be unchanged.

## 1. Tokens: `CarSkin` (new file src/CarSkin.kt)

```kotlin
@Immutable internal data class CarSkin(val accent: Color, val onAccent: Color, val accentInk: Color, val pressOnAccent: Color,
    val card: Color, val cardBorder: Color, val icon: Color, val chipFill: Color, val chipBorder: Color,
    val tagFill: Color, val tagBorder: Color, val trackOff: Color, val thumbOff: Color, val disc: Color)
internal val DayCarSkin = CarSkin(Color(0xFF1C6BE3), Color.White, Color(0xFF1C6BE3), Color(0x1A0B111C), Color.White, Color(0xFFDCE3F1),
    Color(0xFF121A2B), Color(0xFFEEF2FA), Color(0xFF7F8AA0), Color(0xFFEEF1F7), Color(0xFFD5DBE8), Color(0xFFE3E8F1), Color(0xFF6B7786), Color(0xFFE8EDF7))
internal val NightCarSkin = CarSkin(Color(0xFF1C6BE3), Color.White, Color(0xFF8AB4FF), Color(0x1A0B111C), Color(0xFF16213A), Color(0x24FFFFFF),
    Color(0xFFC3CCDA), Color(0xFF1D2738), Color(0xFF6E7A8F), Color(0xFF1D2738), Color(0xFF2A3549), Color(0xFF0E1625), Color(0xFF97A3B6), Color(0xFF243045))
internal object Car { val skin: CarSkin @Composable @ReadOnlyComposable get() = if (LocalScene.current.isDay) DayCarSkin else NightCarSkin }
/** LocalLayout falls back to the car layout (Layout.kt:116), so the theme's compact flag is asked as well (SharemateTheme.kt:581). */
@Composable @ReadOnlyComposable internal fun isCar(): Boolean = LocalLayout.current.startAnchored && !LocalCompactApp.current
```

`Car.skin` reads `LocalScene`, so it swaps once inside the existing fade-through and is never blended between scenes.

| Token | Day | Night | Used for |
|---|---|---|---|
| accent | #1C6BE3 | #1C6BE3 | Selected tab or segment, primary buttons, switch on, selected chips |
| onAccent | #FFFFFF | #FFFFFF | Text and icons on the accent |
| pressOnAccent | #0B111C @ .10 | same | Pressed accent buttons |
| accentInk | #1C6BE3 | #8AB4FF | Links and the selected sheet tab, on cards only |
| card | #FFFFFF | #16213A (opaque; it was .78 glass in the 지금 column) | Every card and header pill |
| cardBorder | #DCE3F1 | #FFFFFF @ .14 | Card hairlines and dividers (decorative) |
| icon | #121A2B | #C3CCDA | Line icons |
| chipFill / chipBorder | #EEF2FA / #7F8AA0 | #1D2738 / #6E7A8F | Example chips. chipBorder also outlines outlined pills, QuietAction, the switch-off state and header controls |
| tagFill / tagBorder | #EEF1F7 / #D5DBE8 | #1D2738 / #2A3549 | Tags, status tags |
| trackOff / thumbOff | #E3E8F1 / #6B7786 | #0E1625 / #97A3B6 | Switch off |
| disc | #E8EDF7 | #243045 | Avatar, icon discs |

**Rules**
- `accent` means the current choice, or a card's one primary action.
- `Scene.glow` keeps status and live meanings: the 자동 기록 대기 dot, 수신 중, gear P.
- `selectionFill` and `selectionRing` keep list-row selection: TripRow, DriverListRow, the picker.
- `Scene.accent` (#2F6BFF; white on it is only 4.50 by day and 4.28 by night) stays for ring sweeps only.
- The 홈 search-bar outline (HomeDashboard.kt:491) moves to `Car.skin.accent`.
- accentInk is used only on `card`. On the day page (#EEF3FA) it is 4.42, on a day dialog (#E9EFF7) 4.26, on chipFill 4.39.
- Never put an accent fill on chipFill or tagFill (night 3.05).
- Pressed accent buttons use pressOnAccent. The white .05 press layer would give 4.52 at night.
- The page backdrop is unchanged. The mock's own colours fail AA: blue #3A8BFD with white is 3.34; grey #A9B3D4 on white is 2.08.

**AA table** (day / night; all pass)
- **Text on the card:**
  - text 17.38 / 16.00 · textSecondary 8.07 / 9.88 · textMuted 5.82 / 6.27
  - accentInk 4.93 / 7.66
  - glow 6.14 / 10.32 · warn 6.80 / 9.09 · positive 6.21 / 9.08 · negative 5.60 / 4.81
  - gleoSpeaker 8.28 / 9.07
- **On the accent:**
  - onAccent 4.93 / 4.93
  - onAccent on a pressed accent (pressOnAccent over accent) 5.70 / 5.70
- **On chipFill:** text 15.49 / 15.00 · textSecondary 7.19 / 9.27 · icon 15.49 / 9.27 · textMuted (the disabled primary label) 5.19 / 5.88.
- **On tagFill:**
  - text 15.36 / 15.00 · textSecondary 7.13 / 9.27
  - dots: glow 5.43 / 9.68 · positive 5.49 / 8.51 · negative 4.95 / 4.51 · textMuted 5.15 / 5.88 · gleoSpeaker 7.32 / 8.51
- **On the disc:** initials 14.80 / 13.26 · Person glyph (icon) 14.80 / 8.19.
- **On the selection fill:** textSecondary 6.77 / 7.07. textMuted would be 4.48 at night, so it is not used there.
- **Non-text against the card (WCAG 1.4.11, ≥ 3):**
  - accent 4.93 / 3.25
  - chipBorder 3.47 / 3.69
  - switch off: thumb on track 3.71 / 7.09, with the chipBorder outline as its boundary
  - Field outline 4.56 / 3.69 (unchanged)
- **Tab badge:** white digits on the badge 5.35. Badge against the accent is only 1.09, so the selected tab draws a 2-dp onAccent ring (ring against the accent 4.93, against the badge 5.35).
- **Header over the 홈 sky (#4F9BE8):** textSecondary on the bare sky would be 2.76. Every header element therefore sits on an opaque `card` pill (text 17.38).

**`CarSkinTest`** (new; F) asserts every pair above for both skins: ≥ 4.5 for text, ≥ 3 for non-text. Translucent tokens are composited over `card` before luminance is computed. It needs its own private `contrast()`, because SceneTest's is local to one test (SceneTest.kt:305-309).

## 2. Components

**Conventions**
- New components go in src/CarUi.kt (F) unless marked "in place".
- Press feedback on the car: `pressable(source, .98f)` plus `pressFill`, with no ripple. LogbookApp already provides no ripple (LogbookUi.kt:322).
- Touch targets are ≥ 56 dp. A pill may be drawn 46 dp tall inside a 56-dp target.
- Motion follows docs/design/DESIGN_2_10_SPEC.md §C. Under reduced motion everything snaps.

### 2.1 `CarCard` (a sibling of Panel; Panel stays as it is)

```kotlin
@Composable internal fun CarCard(modifier: Modifier = Modifier, title: (@Composable () -> Unit)? = null, onClick: (() -> Unit)? = null,
    onClickLabel: String? = null, description: String? = null, live: Boolean = false, padding: Dp = carCardPadding(), gap: Dp = 16.dp,
    content: @Composable ColumnScope.() -> Unit)
@Composable @ReadOnlyComposable internal fun carCardPadding(): Dp = if (LocalLayout.current.expanded) 24.dp else 20.dp
```

- **Surface:** corner 20, `Car.skin.card`, a 1-dp `cardBorder`, contentColor `Scene.text`.
- **Body:** a Column with the given `padding`: `title`, then 12 dp, then a `Column(spacedBy(gap))` of the content.
- **When tappable:**
  - `dayCardShadow`, which becomes `internal`; it is private today at HomeDashboard.kt:522.
  - `.pressable(.98f)` on the Surface.
  - Inside: `clickable(indication = null, role = Role.Button, onClickLabel)` and `pressFill`.
- **Merging:** with `description` or `live`, the inner column is `semantics(mergeDescendants = true) { contentDescription = description; if (live) liveRegion = Polite }`.
- **Where it is used:**
  - NowTile (HomeDashboard.kt:777) is deleted. The 지금 tiles are CarCards with padding 20.
  - Every Panel on the restyled pages becomes a CarCard.

### 2.2 `CarSectionTitle`

```kotlin
@Composable internal fun CarSectionTitle(title: String, modifier: Modifier = Modifier, icon: Painter? = null, chevron: Boolean = false,
    heading: Boolean = true, trailing: (@Composable RowScope.() -> Unit)? = null)
```

- **Layout:** `FlowRow(horizontalArrangement = SpaceBetween, verticalArrangement = Center)` holding two parts.
  - Part one: icon (24 dp, `Car.skin.icon`, decorative) · 10 dp · the title in titleMedium (21 SemiBold) `Scene.text`, one line, ellipsized, carrying `heading()` on the Text only.
  - Part two: `trailing`, then an optional chevron (`Icons.AutoMirrored.Outlined.KeyboardArrowRight`, 24 dp, textSecondary, decorative).
- **Wrapping:** if part two does not fit (1.3× or a narrow card), it wraps under the title, start-aligned.
- **Height:** the row is 32 dp, or 56 dp when `trailing` holds a control.
- **TalkBack:** `trailing` keeps its own semantics node; this is how the 사용자 status tag stays a separate live region.
- **Chevron:** only on cards whose whole surface opens a page: 예약 → 차량, 오늘 운행 → 운행기록, 이달 → 월간·Excel.

### 2.3 `CarLine` and the one-line rule

```kotlin
internal fun carLineMaxLines(fontScale: Float): Int = if (fontScale > 1.05f) 2 else 1
@Composable internal fun CarLine(text: String, modifier: Modifier = Modifier, color: Color = Scene.textMuted, spoken: String? = null)
```

- **Style:** bodySmall (16/24) in `color`, `maxLines = carLineMaxLines(fontScale)`, `TextOverflow.Ellipsis`.
- **Spoken text:** `spoken` replaces the line for TalkBack via `semantics { contentDescription = spoken }`.
- **Overflow check:** in debug builds `onTextLayout` logs `Log.w("CarLine", text)` when `hasVisualOverflow && fontScale <= 1.05f`. This is how the screenshot pass catches copy that does not fit.
- **Why two lines above 1×:** a 1.3× font loses no words this way (WCAG 1.4.4).

### 2.4 `CarInfoRow` (the ⓘ row)

```kotlin
@Composable internal fun CarInfoRow(line: String, modifier: Modifier = Modifier, more: List<String> = emptyList(), spoken: String? = null)
```

- **Layout:** `Icons.Outlined.Info` (20 dp, textMuted) · 10 · `CarLine(line, spoken)`.
- **With `more` not empty:**
  - The row becomes a 56-dp toggle: `toggleable`, `onClickLabel` 자세히 보기 / 접기, `stateDescription` 접힘 / 펼침.
  - It gets a trailing `Icons.Outlined.KeyboardArrowDown` that rotates 180°.
  - It expands the `more` lines under the line, indented 30 dp, in bodySmall textMuted. Wrapping is allowed there, because this is the long form.
  - Motion: `expandVertically` + fade, 200 ms FastOutSlowIn; the arrow rotates on the same clock; both snap under reduced motion.
  - The expanded state is held in `rememberSaveable`.

### 2.5 `CarTag` and `pillDotColor`

```kotlin
@Composable internal fun CarTag(text: String, modifier: Modifier = Modifier, tone: PillTone? = null, live: Boolean = false, description: String? = null)
@Composable internal fun pillDotColor(tone: PillTone): Color // READY glow · QUIET textMuted · POSITIVE positive · NEGATIVE negative · DEMO LocalPleosColors.gleoSpeaker
```

- **Shape:** at least 32 dp tall, stadium, tagFill with a 1-dp tagBorder, padding 12 × 5.
- **Content:**
  - A 10-dp dot, 8 dp before the label, only when `tone` is set.
  - The label in labelMedium (16), one line: `Scene.text` when there is a tone, `textSecondary` without one.
- **Semantics:** never interactive. `mergeDescendants`, plus `liveRegion = Polite` when `live`, and `contentDescription = description` when given.
- **Motion:** a dot swaps colour at once; status colours never animate.

### 2.6 `CarPillButton`

```kotlin
@Composable internal fun CarPillButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true,
    color: Color = Scene.text, description: String? = null, width: Dp? = null)
```

- **Target and pill:** a 56-dp tall target holding a 46-dp stadium.
- **Pill style:** transparent, a 1-dp chipBorder, the label in labelLarge in `color`, 1 line, padding h 24.
- **Width:** `width` when given, else at least 112 dp.
- **Press:** `.98` plus `pressFill`, clipped to the pill. Role.Button.
- **Disabled:** label and border at .38.
- **TalkBack:** `description` replaces the label; the visible label is always part of it.
- **Uses:** 바꾸기, step actions, 추가 / 닫기, 삭제 / 취소 in `Scene.negative`, 완료 / 다시 열기, 새 연결 번호.

### 2.7 `CarExampleChip`

```kotlin
@Composable internal fun CarExampleChip(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, actionLabel: String? = null)
```

- 56-dp target holding a 46-dp stadium.
- Style: chipFill with a 1-dp chipBorder, labelLarge in text, padding h 18, 1 line.
- `onClickLabel = actionLabel`.
- Laid out in a FlowRow with 8-dp gaps.
- `AssistantPreviewCard` uses it. PromptChip stays as it is for the phone sheet.

### 2.8 `CarMiniStat`

```kotlin
@Composable internal fun CarMiniStat(icon: Painter, label: String, value: String, modifier: Modifier = Modifier, live: Boolean = false)
```

- **Label row:** icon (18 dp, `Car.skin.icon`) · 6 · label in labelMedium textSecondary, 1 line.
- **Value:** 4 dp below, `BasicText` in titleSmall with `fontFeatureSettings = "tnum"` in text, `maxLines = 1`.
  - It steps down with `autoSize = TextAutoSize.StepBased(14.sp, 19.sp, .5.sp)`, as DriverAvatar does (DriverUi.kt:114).
  - Values never ellipsize. "12,345.6 km" measures 107 dp in a 109-dp cell.
- **Change motion:** a `live` value (telemetry) updates in place. Any other value cross-fades with DigitChange's transition: 160 ms with a 6-dp lift, `SizeTransform(clip = false) { digitSizeSpec(reduce) }` (Motion.kt:157-173).
- **Layout:** three across, 12 dp apart, each `weight(1f)`.

### 2.9 `CarRow`

```kotlin
@Composable internal fun CarRow(title: String, modifier: Modifier = Modifier, line: String? = null, lead: (@Composable () -> Unit)? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null, titleColor: Color = Scene.text, lineColor: Color = Scene.textMuted,
    titleMaxLines: Int = 1, state: String? = null, spoken: String? = null)
```

- **Height:** at least 72 dp with a line, 56 without.
- **Layout:**
  - `lead`: a 24-dp slot holding an icon, a 12-dp dot or a 32-dp disc.
  - 12 dp.
  - A text column (`weight 1f`): the title in titleSmall, then a `CarLine`.
  - 12 dp.
  - `trailing`.
- **TalkBack:**
  - The text column merges its descendants and carries `stateDescription = state` and `contentDescription = spoken`.
  - The trailing control keeps its own node.

### 2.10 `CarLink`, `CarIconDisc`, `CarDivider`, `carSwitchColors`

- **`CarLink(label, onClick, modifier, description = null)`**
  - The label in labelMedium accentInk (4.93 / 7.66), then a chevron (AutoMirrored.Outlined.KeyboardArrowRight, 18 dp, accentInk).
  - 56-dp target, `.98` plus `pressFill`, Role.Button.
  - Cards only.
- **`CarIconDisc(icon: Painter, modifier, size = 44.dp, iconSize = 22.dp)`:** a disc circle with the icon in `Car.skin.icon`; decorative.
- **`CarDivider(modifier)`:** `HorizontalDivider(thickness = 1.dp, color = Car.skin.cardBorder)`.
- **`carSwitchColors()`:** `SwitchDefaults.colors(...)`
  - Checked: track accent, thumb onAccent, border accent.
  - Unchecked: track trackOff, thumb thumbOff, border chipBorder.
  - Disabled: the same colours at .38.

### 2.11 In-place restyles of car components (F)

**`Segmented`** (LogbookUi.kt:607). New signature:
```kotlin
@Composable internal fun Segmented(labels: List<String>, selected: Int, modifier: Modifier = Modifier, icons: List<Painter>? = null,
    separated: Boolean = false, onSelect: (Int) -> Unit)
```
- **Phone:** `!isCar()` keeps today's body byte-for-byte.
- **Car, joined:**
  - Container: a stadium in `card` with a 1-dp cardBorder; Row padding 5, items 5 dp apart.
  - Items: `weight 1f`, `heightIn(56)`, `.98`, no ripple, `selected` plus Role.Tab.
  - Item content: an optional 20-dp icon, 8 dp, the label in labelLarge, 1 line, ellipsized.
  - Selection: a SOLID glide (§2.12), 180 ms.
  - Content colour: `if (covered) onAccent else (text label / Car.skin.icon icon)`, where `covered` is `derivedStateOf { glideCovers(glide.value, bounds[index]) }`.
- **Car, separated** (화면 밝기):
  - `Row(Modifier.selectableGroup(), spacedBy 8)`; items `weight 1f`, each a 56-dp target holding a 46-dp stadium.
  - Selected item: accent fill with onAccent icon and label.
  - Other items: transparent with a 1-dp chipBorder, the icon in `Car.skin.icon` and the label in text.
  - Role.RadioButton plus `selected`. The swap is instant; the scene fade-through that follows is the motion.
- **Call sites:** the positional call at LogbookUi.kt:1817 becomes `{ onSection(it) }`. F does this so the build stays green.

**`PrimaryAction`** (LogbookUi.kt:653). New signature: `(label, modifier, enabled, icon: Painter? = null, onClick)`.
- Material `Button` with a stadium shape, `heightIn(min = 56)` (was 64), contentPadding h 24.
- Colours: container accent; while pressed `pressOnAccent.compositeOver(accent)` (90 ms in, 160 ms out, snap under reduced motion); content onAccent; disabled container chipFill with a textMuted label.
- An optional 20-dp icon, 8 dp before the label; label in labelLarge, 1 line.
- Keeps `carPress`.

**`QuietAction`** (LogbookUi.kt:676). The signature is unchanged.
- Shape: a 56-dp stadium (was 60).
- Container: transparent; while pressed `Scene.fill`.
- Border: a 1-dp chipBorder.
- Content: in `color` (default `onSurface`); the label in labelLarge, centred.
- Disabled: border and label at .38.
- `description` semantics as today.

**`LoadingAction`** (LoadingUi.kt:109 and :128). Both overloads gain `icon: Painter? = null, quiet: Boolean = false`.
- The accent look of PrimaryAction, or the outlined look of QuietAction when `quiet`.
- 56 dp tall.
- While loading: `ActivityMark` plus `loadingLabel`, with the live region and "진행 중" as today.

**`SwitchRow`** (LogbookUi.kt:1923). New signature:
```kotlin
@Composable internal fun SwitchRow(title: String, line: String, checked: Boolean, onChange: (Boolean) -> Unit, icon: Painter? = null, spoken: String? = null)
```
- The whole row is `toggleable(role = Switch)`, at least 72 dp tall, padding v 8.
- Layout: `CarIconDisc(icon)` · 16 · a column (the title in titleSmall, 1 line, over `CarLine(line, spoken = spoken)`) · 16 · `Switch(checked, onCheckedChange = null, colors = carSwitchColors())`.
- TalkBack: the title, then `spoken` or the line, then on or off.
- Positional calls still compile.

**`DriverAvatar`** (DriverUi.kt:107). The signature is unchanged.
- The disc is filled with `Car.skin.disc` and has no edge.
- `ring = true` still draws the 1.5-dp selectionRing.
- Initials as today.
- A null driver, or a blank monogram, shows `Icons.Outlined.Person` at 45 % of the size, in `Car.skin.icon`.

### 2.12 Glide machinery (HomeDashboard.kt:143-196; F)

```kotlin
internal enum class GlideMark { PILL, UNDERLINE, SOLID }
@Composable internal fun rememberGlideRect(bounds: ItemBounds, selected: Int, durationMs: Int): State<Rect?>  // today's Animatable: snap on first placement and under reduced motion
@Composable internal fun Modifier.drawGlide(glide: State<Rect?>, mark: GlideMark, ringWidth: Dp = 1.5.dp, corner: Dp? = null, underlineColor: Color = Scene.glow): Modifier
@Composable internal fun Modifier.selectionGlide(bounds: ItemBounds, selected: Int, durationMs: Int = MOTION_PAGE_MS, ringWidth: Dp = 1.5.dp, corner: Dp? = null,
    mark: GlideMark = GlideMark.PILL, underlineColor: Color = Scene.glow): Modifier  // wrapper; PILL and UNDERLINE draw exactly as today
internal fun glideCovers(glide: Rect?, item: Rect?): Boolean // pure: overlap width ≥ 50 % of the item's width
@Composable internal fun Modifier.pressFill(interactionSource: InteractionSource, color: Color = Scene.fill): Modifier
```

- SOLID draws `Car.skin.accent` as a stadium 46 dp tall, centred vertically in the item rect.
- This replaces the design system's offscreen "inkSwap" (decision 4).

## 3. Icons

**New drawables (F).**
- Same family as ic_calendar: `width/height 28dp`, `viewport 24`, `fillColor @android:color/transparent`, `strokeColor #FFFFFFFF`, `strokeWidth 1.6`, round caps and joins.
- All ten were rendered and checked: each draws the intended glyph.

| File | Use | pathData |
|---|---|---|
| ic_settings | 설정 tab | `M18.8,10.2 L21.4,10.4 A9.5,9.5 0 0,1 21.4,13.6 L18.8,13.8 A7,7 0 0,1 17,17 L18.1,19.3 A9.5,9.5 0 0,1 15.3,20.9 L13.8,18.8 A7,7 0 0,1 10.2,18.8 L8.8,20.9 A9.5,9.5 0 0,1 5.9,19.3 L7,17 A7,7 0 0,1 5.2,13.8 L2.6,13.6 A9.5,9.5 0 0,1 2.6,10.4 L5.2,10.2 A7,7 0 0,1 7,7 L5.9,4.7 A9.5,9.5 0 0,1 8.8,3.1 L10.2,5.2 A7,7 0 0,1 13.8,5.2 L15.3,3.1 A9.5,9.5 0 0,1 18.1,4.7 L17,7 A7,7 0 0,1 18.8,10.2 Z M15,12 A3,3 0 1,1 9,12 A3,3 0 1,1 15,12 Z` |
| ic_sun | 화면 밝기, 낮 | `M12,8 A4,4 0 1,1 12,16 A4,4 0 1,1 12,8 Z M12,2.5 L12,4.5 M12,19.5 L12,21.5 M2.5,12 L4.5,12 M19.5,12 L21.5,12 M5.3,5.3 L6.7,6.7 M17.3,17.3 L18.7,18.7 M5.3,18.7 L6.7,17.3 M17.3,6.7 L18.7,5.3` |
| ic_moon | 밤 | `M15.5,3.5 A8.5,8.5 0 1,0 20.5,15.5 A7,7 0 0,1 15.5,3.5 Z` |
| ic_bolt | 자동, the 자동 완성 row | `M13,2.5 L5.5,13.5 L11.5,13.5 L10.5,21.5 L18.5,10 L12.5,10 Z` |
| ic_clock | 시연 모드 | `M12,3 A9,9 0 1,1 12,21 A9,9 0 1,1 12,3 Z M12,7.5 L12,12 L15.5,14` |
| ic_gauge | 계기판, 총주행 | `M5.6,18.4 A9,9 0 1,1 18.4,18.4 M12,12 L16,7.5 M10.5,12 A1.5,1.5 0 1,0 13.5,12 A1.5,1.5 0 1,0 10.5,12 Z` |
| ic_won | 등록 경비 (mini stat), 경비, 유류비 정산, 비용 참고 | `M4,5 L7.5,19 L12,8 L16.5,19 L20,5 M3.5,10.5 L20.5,10.5 M3.5,14 L20.5,14` |
| ic_phone | 휴대폰 연동, 휴대폰 연결, the save bar (Material's Phone is a handset) | `M8.5,2.5 L15.5,2.5 A1.5,1.5 0 0,1 17,4 L17,20 A1.5,1.5 0 0,1 15.5,21.5 L8.5,21.5 A1.5,1.5 0 0,1 7,20 L7,4 A1.5,1.5 0 0,1 8.5,2.5 Z M10.5,18.5 L13.5,18.5` |
| ic_autofill | 자동 완성 card | `M5,4 L19,4 A1,1 0 0,1 20,5 L20,19 A1,1 0 0,1 19,20 L5,20 A1,1 0 0,1 4,19 L4,5 A1,1 0 0,1 5,4 Z M8,12.5 L11,15.5 L16.5,9` |
| ic_assistant | 운행 비서 card | `M8,4.5 L6,4.5 A1,1 0 0,0 5,5.5 L5,20 A1,1 0 0,0 6,21 L18,21 A1,1 0 0,0 19,20 L19,5.5 A1,1 0 0,0 18,4.5 L16,4.5 M9,3 L15,3 L15,6 L9,6 Z M8.5,11 L15.5,11 M8.5,15 L13,15` |

**Material core icons.** All of these were checked present in the material-icons-core jar, as `Icons.Outlined.*`: Notifications, Info, LocationOn, Person, AccountBox, Build, Warning, CheckCircle, Lock, Add, Refresh, KeyboardArrowDown. Also `Icons.AutoMirrored.Outlined.KeyboardArrowRight` and `Icons.AutoMirrored.Outlined.KeyboardArrowLeft`.

**Existing drawables reused:** ic_dashboard, ic_journal, ic_calendar, ic_vehicle, ic_report, ic_receipt, ic_export, ic_search, ic_tune.

**Icon map**

| Area | Assignments |
|---|---|
| Tabs | 홈 ic_dashboard · 운행기록 ic_journal · 월간·Excel ic_calendar (was ic_report) · 차량 ic_vehicle · 설정 ic_settings (was ic_tune) |
| 지금 column | 예약 Notifications · 오늘 운행 ic_report · 완료 운행 ic_vehicle · 등록 경비 ic_won · 계기판 ic_gauge |
| 설정 › 법인·차량 | 법인·차량 정보 AccountBox · 차량 관리 기한 ic_calendar |
| 설정 › 직원 | 직원 N명 Person · 새 직원 Add · 출발 전 운전자 확인 CheckCircle |
| 설정 › 정산·세무 | 정산·세무 기준 ic_tune · 유류비 정산 ic_won |
| 설정 › 기록 | 화면 밝기 ic_sun · 자동 ic_bolt · 낮 ic_sun · 밤 ic_moon · 기록 및 개인정보 ic_journal · rows: LocationOn, ic_bolt, ic_phone, ic_clock · 자동 완성 ic_autofill · 운행 비서 ic_assistant |
| 월간·Excel | Excel 저장 ic_export · 월 마감 CheckCircle · 운행 ic_vehicle · 총주행 ic_gauge · 경비 ic_won · 업무사용비율 ic_report · 직원별 운행 Person · 등록 경비 ic_receipt · 유류비 정산 ic_won · 증빙 준비 상태 ic_journal · 비용 참고 ic_won · save bar ic_phone |
| 차량 | car card ic_vehicle · 차량 정보 Info · 차량 관리 ic_calendar · 정비 기록 Build · 휴대폰 연결 ic_phone (stats: 기록 버전 ic_journal, 최근 수신 Refresh, 연결 ic_phone) · 차량 예약 Notifications · 사고·특이사항 Warning · 인증키 Lock |

## 4. Header (`TopBar`, LogbookUi.kt:478-559; S)

**Layout plan**
```kotlin
@Immutable internal data class HeaderPlan(val oneRow: Boolean, val clockInline: Boolean, val statusInHeader: Boolean)
internal fun headerPlan(layout: LayoutTokens, nowColumn: Boolean) = HeaderPlan(oneRow = nowColumn || layout.full, clockInline = layout.expanded, statusInHeader = !nowColumn)
internal fun clockFitsInline(space: Dp, fontScale: Float): Boolean = space >= 52.dp + 180.dp * fontScale.coerceAtLeast(1f)
internal fun driverChipLabel(driver: Driver?): String = driver?.let { "사용자 ${it.label}, 바꾸기" } ?: "사용자 미선택, 운전자 선택"
```

The new `TopBar` signature: `TopBar(state, now, layout, tabs, selected, onSelect, onDriver, onTutorial, onAssistant, nowColumn: Boolean)`. The call at :358-359 drops `clock` and `showDriver`.

| Window | Row 1 | Row 2 |
|---|---|---|
| With the 지금 column: FULL; EXPANDED ≥ 1296 dp; the full window at 1.3× | S · tabs ··· 운행 비서 (icon) · 시연 체험 (on 홈 while there are no real trips) · clock (inline) | — |
| FULL short, no column | S · tabs ··· tools · chip · status · clock (inline, or stacked when `clockFitsInline(maxWidth − 72.dp, fontScale)` fails) | — |
| EXPANDED without the column (1100–1295 at 1×, or short) | S · tabs ··· `Box(weight 1f, CenterEnd){ BoxWithConstraints { clock(inline if clockFitsInline(maxWidth)) } }` | tools ··· `Box(weight 1f, CenterEnd){ chip }` · status |
| MEDIUM / COMPACT | S · `Box(weight 1f){ tabs.horizontalScroll }` · clock (stacked) | tools ··· chip · status |

**Measurement order in the one-row cluster.** The cluster is declared `tools, chip(weight 1f, fill = false), status, clock`. Row measures the unweighted children first, in order (tools, status, clock), so the clock sees what is left after tools and status. The chip is measured last and gives way first (`driverChipMode`, :463).

**Fit at 1×**
- The tab pill is about 633 dp (labels measured 16–89 dp), plus 36 dp per badge.
- One row with the column is about 1,040–1,181 of 1,536 dp.
- FULL short with chip and status is about 1,373 typical. Worst case (3 badges, recording status, 시연 체험) it fits once the chip drops to AVATAR.
- At 1.3× with the column it is about 1,139 of 1,584 dp.
- Rows are 12 dp apart (8 when short); outer padding is 8 (4 when short).
- A row is 68 dp (the tab pill), so one row totals 84 dp (76 short), two rows 152 dp (136 short). The second row at EXPANDED without the column is accepted.

**Pieces** (private composables in LogbookUi.kt; S)

- **Mark** (:521): `ic_splash_logo` 52 dp. The tint changes from `Scene.brand` to `Scene.text` (black by day, as in the mock; white by night).
- **Tabs** (:343-349):
  - The 월간·Excel icon changes `ic_report` → `ic_calendar`; 설정 changes `ic_tune` → `ic_settings`.
  - **`GlassTabs`** (HomeDashboard.kt:210, restyled by S):
    - Container: a stadium in `card` with a 1-dp cardBorder (was glass .92); padding 6 (4 short), tabs 4 dp apart.
    - Tabs: 56 dp, padding h 20, `.98`, no ripple.
    - Content: icon 24 dp in textSecondary, 10 dp, the label in labelLarge in text.
    - Selection: a SOLID glide, 220 ms (MOTION_PAGE_MS).
    - Covered tabs flip their icon and label to onAccent (`glideCovers`). The `animateColorAsState` tints are removed.
    - The badge becomes `CountBadge(count, ring = covered)`.
    - TalkBack unchanged: "{label}, 확인 필요 N건", Role.Tab, selected.
  - **`CountBadge`** (HomeDashboard.kt:589) gains `ring: Boolean = false`, which draws a 2-dp onAccent ring.
- **`HeaderClock(text: ClockText, inline: Boolean)`**:
  - Not interactive. A 56-dp stadium in `card` with a 1-dp cardBorder.
  - Inline: padding h 20; a baseline-aligned row of the date (labelLarge, textSecondary), 12 dp, then the time (titleLarge, Bold, `tnum`, text).
  - Stacked: padding h 16, v 3; the date (labelMedium, textSecondary) over the time (titleMedium, Bold, `tnum`, text), centred. At 1.3× the pill grows to fit.
  - Text: `remember(now / 60_000, zone) { headerClock(now, zone) }` on the shell's one-second tick (:233). It updates in place, never animated, with no live region.
  - Semantics: `clearAndSetSemantics { contentDescription = text.spoken }`.
- **`StatusPill(state, now)`** (:582-601), header only, when `statusInHeader`:
  - A 56-dp stadium in `card` with a 1-dp cardBorder, padding h 18.
  - Content: a 12-dp dot in `pillDotColor(tone)`, 10 dp, the label in labelLarge in text, 1 line.
  - It keeps `mergeDescendants` and the polite live region.
  - It takes `now` instead of calling `System.currentTimeMillis()` (:584).
- **Driver chip** (:484-507):
  - Surface `card` with a 1-dp chipBorder (it is a control). The modes are as today.
  - The caption changes `지금 운전` → `사용자` (labelSmall, textSecondary). The name stays `Kim Jiwoo` / `직원 선택`.
  - TalkBack is `driverChipLabel(driver)`, so the spoken name contains the visible label (WCAG 2.5.3).
- **`HeaderTool(icon, label: String?, description, onClick)`** (replaces the OutlinedButtons at :512-518):
  - A 56-dp stadium in `card` with a 1-dp chipBorder; 56 × 56 when icon-only.
  - Icon 22 dp in `Car.skin.icon`; label in labelLarge in text; padding h 18.
  - `.98` plus `pressFill`.
  - 운행 비서 shows when `selected != 0`. It is icon-only (ic_search) in one-row headers and at COMPACT, and labelled from MEDIUM up in two-row headers. TalkBack: "운행 비서 열기".
  - 시연 체험 is a text pill. It shows when `showTutorial && (selected == 0 || !oneRow)`.

**Header copy** (S)

| Where | Current | New |
|---|---|---|
| Clock (:524-527) | Short windows only: "22:20"; TalkBack "현재 시각 22:20" | Every window: "11월 9일 (월)" + "22:20"; TalkBack "11월 9일 월요일 22시 20분" |
| Chip caption (:499) | 지금 운전 | 사용자 |
| Chip TalkBack (:487) | "지금 운전 Sales · Kim Jiwoo, 바꾸기" / "지금 운전 미선택, 바꾸기" | "사용자 Sales · Kim Jiwoo, 바꾸기" / "사용자 미선택, 운전자 선택" |
| Status labels (:568-580), tab labels, 운행 비서 / 운행 비서 열기 / 시연 체험, mark "SHAREMATE" | | = |

**`statusPillInfo`** (:568; F): "자동 기록 대기" now has tone `PillTone.READY` (a glow dot, as in the mock's teal dot). The `PillTone` enum gains READY. STARTING stays QUIET.

## 5. 지금 column (NowColumn, HomeDashboard.kt:756-769; S)

**The column itself**
- These stay as they are: the 16-dp gap between tiles, the column's own scroll, `traversalIndex 1f`, no stagger.
- Every tile is a CarCard with padding 20. At night the tiles are opaque; `homeCardGlass(.78)` is retired from the column.
- New parameter `openToday`, wired as `{ openLog(0, YearMonth.from(localDate(now))) }` (openLog is defined at LogbookUi.kt:327). The call is at :390-394.
- Order: 사용자 → 예약 → 오늘 운행 → 이달 운행일지 (the mock).

### 5.1 사용자 (`DriverNowCard`, DriverUi.kt:133-211)

`CarCard(padding = 20.dp, title = { CarSectionTitle("사용자", trailing = { StatusTag(state, now) }) })`.
- `StatusTag` is `CarTag(label, tone = tone, live = true)` from `statusPillInfo(state, overview.fresh, overview.status)` with `overview = drivingOverviewInfo(state, now, true, zone)`.
- The title row is drawn in every state. It sits outside the merged person block (formerly :156).

| State | Layout |
|---|---|
| Default | `Row(CenterVertically) { merged person block (weight 1f): DriverAvatar 56 · 12 · name (titleLarge Bold, 1 line) over department (bodyMedium textSecondary, 1 line); 16 · CarPillButton("바꾸기", width = changeButtonWidth(...), description = "운전자 바꾸기") }`; 12; `CarLine(lastDriveLabel(lastAt, now, zone), color = textSecondary)` with `clearAndSetSemantics {}`, because the merged block already says it. The person block keeps `contentDescription = driverNowDescription(driver, driverStatusLine(...))`; the heading moves to the title row |
| Today's question due | As default. 바꾸기 steps aside (the 200-ms fade, :168-171). Then the fold as today (:175-191): 16 · CarDivider · 16 · the question in titleSmall, `maxLines = 2`, heading plus polite live region · 12 · `PrimaryAction("네")` and `QuietAction("다른 직원")`, `weight 1f` each, 56 dp |
| Nobody chosen | `DriverAvatar(null, 56)` (Person glyph) · 12 · "누가 운전하나요?" (titleLarge Bold); 16; `PrimaryAction("운전자 선택", fillMaxWidth)` opens the picker |
| No 직원 | Glyph avatar · 12 · Column("등록된 직원이 없어요" (titleLarge Bold), CarLine); 16; `PrimaryAction("직원 추가", fillMaxWidth)` opens 설정 › 직원 |

**바꾸기 width.**
- `changeButtonWidth(space, nameWidth, labelWidth) = (space − nameWidth).coerceIn(max(112.dp, labelWidth + 48.dp), max(151.dp, labelWidth + 48.dp))`.
- `space` = content − 56 − 12 − 16 (268 at 1×).
- `nameWidth` is measured with `rememberTextMeasurer` in titleLarge Bold. "Kim Jiwoo" measures 111 dp, so the button stays 151 (the mock's 151 × 46).

| | Current | New |
|---|---|---|
| Label (:201) | 지금 운전 | 사용자 (CarSectionTitle) |
| Status line (:173) | 오늘 확인함 · 마지막 운행 10월 28일 | 마지막 운행 10월 28일 (`lastDriveLabel`); TalkBack keeps 오늘 확인함 |
| No 직원 detail (:149) | 운행기록부 ④ 사용자 칸에 들어갈 부서와 성명을 등록해 주세요. | 부서·성명이 ④ 사용자 칸에 들어가요; spoken "운행기록부 ④ 사용자 칸에 들어갈 부서와 성명을 등록해 주세요" |
| 등록된 직원이 없어요, 누가 운전하나요?, 운전자 선택, 직원 추가, 바꾸기 (운전자 바꾸기), question, 네, 다른 직원 | | = |

### 5.2 예약 (`ReservationTile`, :803-816)

- `CarCard(onClick = openVehicle, onClickLabel = "예약 보기", description = line.spoken, live = true)`.
- One row: `Icons.Outlined.Notifications` 28 dp (icon) · 12 · Column("예약" in bodyMedium textSecondary over the line in titleSmall text, 1 line) · chevron.
- The dot is gone: "사용 중" carries the state.
- Line source: `reservationTileLine(data, now, zone)` (new, in CarReservationsUi.kt), remembered on (data, minute).
  - It reads `activeReservation` and then every `upcomingReservations` entry, with no 24-hour horizon.
  - `nextReservationLine` stays for the 홈 line (LogbookUi.kt:812).
- Booker name: the suffix " · {bookerName} 님" is added unless the booker is the current 사용자, or is not on the roster.

| Case | Current (CarReservationsUi.kt:75-85; tile :809) | New line | TalkBack |
|---|---|---|---|
| None | 24시간 안에 잡힌 예약이 없어요 | 현재 잡힌 예약이 없어요 | 예약, 현재 잡힌 예약이 없어요 |
| In use | 예약 사용 중 · 김 님 · 17:00까지 (· 운전자를 예약한 직원으로 맞췄어요) | 사용 중 · 17:00까지 (+ " · 김 님") | 예약, 사용 중, 17:00까지, 영업 · 김 (, 운전자를 예약한 직원으로 맞췄어요) |
| Starting within 15 min | 곧 예약 시작 · 김 님 · 14:00부터 | 곧 시작 · 14:00부터 (+ name) | 예약, 곧 시작, 14:00부터 17:00까지, 영업 · 김 |
| Next booking | 다음 예약 · 오늘 14:00 · 영업 · 김 · 일반 업무 · 본사 → 수원 (within 24 h only) | `reservationStartText` + name: 오늘 14:00 · 김 님 / 내일 09:30 · 김 님 / 11월 21일 (토) 18:00 · 김 님 | 예약, 다음 예약 {start}, {reservationDetailText} |

### 5.3 오늘 운행 (`TodayTile`, :822-848)

The tile is now tappable: `CarCard(onClick = openToday, onClickLabel = "운행기록 보기", description = info.spoken, title = { CarSectionTitle("오늘 운행", icon = ic_report, chevron = true) })`.
- **km figure:** the value in displaySmall `tnum` text, followed by "km" in titleMedium textMuted. It updates in place while recording (`homeShowsLiveTrip`), otherwise it cross-fades.
- **Mini stats:** `CarMiniStat(ic_vehicle, "완료 운행", "N건")`, `CarMiniStat(ic_won, "등록 경비", "N원")`, `CarMiniStat(ic_gauge, "계기판", odometer | "확인 필요", live = true)`.
- **Pure helper** (HomeDashboard.kt): `todayTileInfo(state, now, zone): TodayTileInfo(km, trips, expenses, odometer, spoken)`.
- **TalkBack** is unchanged from :831-832, e.g. "오늘 운행 0.0 km, 완료 0건, 등록 경비 0원, 계기판 확인 필요".
- **Copy:** 오늘 운행, km, 완료 운행, 등록 경비, 계기판 and the value formats are all =.

### 5.4 이달 운행일지 (`MonthTile`, :854-870)

`CarCard(onClick = openMonth, onClickLabel = "월간 현황 열기", description = (as today, :859), title = { CarSectionTitle(info.title, chevron = true) })`.
- **Body:** a Row of two parts, 16 dp apart:
  - A weighted column: the word in headlineMedium Bold in its tone, 1 line (glow 6.14 / 10.32, warn 6.80 / 9.09, text); under it `info.line` (DigitChange, bodySmall textSecondary, 1 line).
  - `CompletionRing` at 88 dp.
- **`MonthWidgetInfo` gains `val line: String = detail`.** The recording branch (HomeDashboard.kt:380) sets line = "이번 달 첫 운행"; the phone and the 홈 widget keep reading `detail`.
- **The auto line (:865)** leaves the view; TalkBack keeps it.
- **Words** = (대기 · 기록 중 · 완성 · 마감됨 · 확인 N건 · 마감 후 확인 N건).

### 5.5 `DriverConfirmRow` (HomeCards.kt:412-453; narrow windows)

- The CarCard look (Surface `card`, cardBorder, corner 20, padding 12).
- `DriverAvatar(40)` on the disc.
- The question in titleSmall with maxLines 2, still a heading.
- 네 and 다른 직원 come through the restyled PrimaryAction and QuietAction (56 dp).
- It still stacks below 560 dp. Copy =.

### 5.6 홈 hero clock removal (HomeDashboard.kt; S)

- Delete the clock row (:439-447), keeping `Spacer(Modifier.height(4.dp))` as the first child so SpaceBetween keeps its top anchor.
- The `Appear` indices become greeting 0, search 1, cards 2.
- Delete `HomePlan.clockInHeader` (:279, :288).
- Update the KDocs at :273, :398 and :405-410.
- `AssistantSearch` border (:491) changes `Scene.accent` → `Car.skin.accent`.

## 6. 설정 (SettingsPane, LogbookUi.kt:1771-1832; T)

**Frame**
- Sub-tabs: `Segmented(listOf("법인·차량", "직원", "정산·세무", "기록"), section, Modifier.fillMaxWidth()) { onSection(it) }`. The bar fills the page, as in the mock (was max 960). The labels are =.
- Every Panel becomes a CarCard (padding 24 from EXPANDED).
- These stay as they are: the section scroll, `fadingTopEdge`, `Crossfade(pageFade)`, `rememberSaveableStateHolder`, `goSettings(0/1/2)`.

**`settingsCardColumns`** (:1760) becomes `if (layout.expanded && page / fontScale.coerceAtLeast(1f) >= 760.dp && page >= 984.dp) 2 else 1`.
- FULL gives 2 × 544 (content 496; beside a switch 368).
- 1.3× gives 2 × 513.
- The 1296–1400-dp band with the column gives one 720 column.

### 6.1 법인·차량

**`ProfileCard`** (:1834-1865)

| Line | Current | New | Component |
|---|---|---|---|
| 1844 | FLEET PROFILE | removed | — |
| 1845 | 운행일지의 기본 정보 | 법인·차량 정보 | CarSectionTitle, AccountBox |
| 1846 | 한 번 저장하면 이후 운행과 Excel에 함께 사용합니다. | 저장하면 운행기록부 Excel에 들어가요 (true: LogbookExcel.kt:101-102) | CarLine |
| 1848-1856 | Fields; 000-00-00000 형식으로 입력해 주세요 (1841); 차량에서 가져오기 | = | Field · QuietAction |
| 1858 | 변경 내용 저장 / 저장된 정보 | 정보 저장 / 저장된 정보 | PrimaryAction |
| 1861 | 법인·차량 정보를 저장했어요. | 법인·차량 정보를 저장했어요 | follow-up |

**`CareSettingsCard`** (FleetCareUi.kt:215-260)

| Line | Current | New | Component |
|---|---|---|---|
| 232 | eyebrow 차량 관리 기한 | 차량 관리 기한 | CarSectionTitle, ic_calendar |
| 233 | 검사·보험·리스·정비 알림 | removed | — |
| 234 | 등록된 날짜 기준으로만 알려요 · 자동 조회는 하지 않아요. 30일 전부터 홈과 차량 탭에 알림이 떠요. | 입력한 날짜 30일 전부터 홈·차량에 알려요 (spoken: 등록된 날짜 기준으로만 알려요 · 자동 조회는 하지 않아요 · 30일 전부터 홈과 차량 탭에 알림이 떠요) | CarLine |
| 236-247 | Fields, hints and errors (135-158); 지금 계기판 값 넣기 | = | Field · QuietAction |
| 248-252 | Three two-sentence variants | `careOdometerLine(odometer, fresh)`: 계기판 값은 차량 신호를 받으면 넣을 수 있어요 / 지금 계기판 51,300 km / 마지막 수신 계기판 51,300 km (spoken adds 엔진오일 정비 기록을 추가하면 마지막 정비 값이 저절로 바뀌어요) | CarLine |
| 253, 256 | 기한 저장 / 저장된 기한; 차량 관리 기한을 저장했어요 · 실행 취소 | = | PrimaryAction |

### 6.2 직원 (`DriverManager`, DriverUi.kt:368-533)

**Layout**
- One CarCard across the page.
- From 960 dp (:356) the list sits left of the editor, with a 1-dp cardBorder rule between them. Narrower, the editor sits under the list after a CarDivider.
- The editor loses its `Scene.fill` panel (:460), because accent buttons never sit on a fill.

| Line | Current | New | Component |
|---|---|---|---|
| 426 | 직원 · ④ 부서와 성명 | removed | — |
| 428 / 432 / 433 | 아직 등록된 직원이 없어요 / 등록된 직원 N명 / 최대 50명 | 직원 N명 (0명 too), trailing 최대 50명 | CarSectionTitle, Person; trailing labelMedium textSecondary |
| 429 | 차량을 이용하는 직원의 부서와 성명을 등록하세요. 운행마다 운행기록부 ④ 사용자 칸에 들어가요. | 운행기록부 ④ 사용자 칸에 부서와 성명이 들어가요 (empty list only) | CarLine |
| 521-522 | Department · history, maxLines 2 | Same words on one line, via `driverListLine` | CarLine in textSecondary (6.77 / 7.07 on the selection fill) |
| 531 | NowChip 지금 운전 | 지금 운전 | `CarTag(tone = READY)` |
| 442 / 443 | 새 직원 / 직원은 50명까지 등록할 수 있어요 | = | QuietAction · CarLine |
| 445 | 출발 전 운전자 확인 / 직원이 2명 이상이면 하루 한 번 지금 운전하는 직원이 맞는지 물어요 · 기본 켜짐 | Same title / 직원 2명부터 하루 한 번 물어요 (spoken: the original) | SwitchRow, CheckCircle, a CarDivider above |
| 462 | 새 직원 | = | CarSectionTitle, Add |
| 465-470 | Avatar 56 · name / department / history | Avatar 56 (ring when current) · name (titleMedium) over `driverListLine` | — |
| 473 | 지금 운전하는 직원이에요 | 지금 운전 at the end of the header row | `CarTag(tone = READY)` |
| 474 | 지금 운전하는 직원으로 | 운전자로 선택 (TalkBack: {name} 님을 지금 운전하는 직원으로 선택) | QuietAction |
| 455 / 458 / 479 | 부서 (예: 영업1팀), 성명 (예: 김서연), 같은 부서에 같은 이름이 있어요 | =; the warning stays warn-coloured with its live region | Field · Text |
| 481 | 고친 부서와 성명은 이 직원의 지난 운행 ④ 사용자 칸에도 그대로 들어가요. | 지난 운행의 ④ 사용자 칸도 함께 바뀌어요 | CarLine |
| 483 / 486 / 487 | 직원 추가 · 수정 저장 · 취소 | = | PrimaryAction · QuietAction |
| 491 | 직원 삭제 (DialogButton) | 직원 삭제 | `CarPillButton(color = Scene.negative)` (5.60 / 4.81) |
| 492 | 운행 기록이 있어 삭제할 수 없어요 · 부서와 성명은 고칠 수 있어요 | 운행 기록이 있어 삭제할 수 없어요 (spoken: the original) | CarLine |
| 392-419 | Follow-ups | = | — |

### 6.3 정산·세무

**`CostCard`** (:1872-1894)

| Line | Current | New | Component |
|---|---|---|---|
| 1879 | eyebrow 정산·세무 | 정산·세무 기준 | CarSectionTitle, ic_tune |
| 1880-1882 | Fields; the 연간 비용 hint is blank while the field is empty | = ; the empty 연간 비용 field's hint becomes 감가상각·리스·렌트·유류·보험·자동차세 등 합계 | Field |
| 1885 | 연간 비용은 감가상각비(또는 리스·렌트료), 유류비, …의 합계입니다. | removed (now that hint) | — |
| 1884 | Validation (TripLogRepository.kt:507-511) | = | — |
| 1887 | 변경 내용 저장 | 기준 저장 / 저장된 기준 | PrimaryAction |
| 1890 | 정산·세무 기준을 저장했어요. | 정산·세무 기준을 저장했어요 | follow-up |

**`SettlementCard`**: one card, shared with 월간·Excel and owned by R (§7.4). 설정 calls `SettlementCard(monthTrips, state.data, modifier, period = "이번 달")` (:1793).

### 6.4 기록 (the mock)

Left column: 화면 밝기 above 기록 및 개인정보. Right column: 자동 완성 above 운행 비서. The PLEOS SDK panel (:1807-1812) is deleted.

**`CarSceneCard()`** (new, in Scene.kt after SceneModeCard). It replaces `SceneModeCard()` at :1800; SceneModeCard and sceneModeHint stay for the phone.
- Title: `CarSectionTitle("화면 밝기", icon = ic_sun)`.
  - The trailing part is `sceneStateLine(mode, phase, morning = LocalDateTime.now().hour < 12, vetoed = LocalSceneVeto.current)` in labelMedium textSecondary.
  - Its TalkBack is the full `sceneModeHint(...)`.
- Control: `Segmented(listOf("자동", "낮", "밤"), …, icons = [ic_bolt, ic_sun, ic_moon], separated = true) { ScenePrefs.set(…) }`.
- The hint line (Scene.kt:368) leaves the view.

**`PrivacyCard`** (:1896-1921). Each row has a CarDivider above it.

| Line | Current | New | Component |
|---|---|---|---|
| 1899 | 기록 방식·개인정보 | 기록 및 개인정보 | CarSectionTitle, ic_journal |
| 1901 | 시연 체험 다시 보기 | 시연 체험 다시보기 | CarLink in the title row |
| 1903 | 출발·도착 주소 저장 / 끄면 계기판 거리와 시각만 남기고 내비 주소는 저장하지 않아요. | Same title / 끄면 계기판 거리와 시각만 저장해요 (spoken: 끄면 계기판 거리와 시각만 남기고 내비 주소는 저장하지 않아요) | SwitchRow, LocationOn |
| 1907 | 자동 완성 · 기본 켜짐 / 반복 도착지의 목적과 카드 결제 비용을 사람 손 없이 채웁니다. … | Same title / 반복 도착지의 목적과 카드 결제를 자동 입력해요 (spoken: 반복 도착지의 목적과 카드 결제 비용을 사람 손 없이 채워요 · 자동으로 들어간 항목은 ‘자동 분류’·‘카드 자동 매칭’으로 표시되고 언제든 되돌릴 수 있어요 · 끄면 정차 후 직접 확인해요) | SwitchRow, ic_bolt |
| 1910 | 휴대폰 실시간 연동 / 같은 Wi-Fi·핫스팟의 휴대폰 SHAREMATE와 … | Same title / 휴대폰과 운행 정보를 실시간 공유해요 (spoken: 같은 Wi-Fi·핫스팟의 휴대폰 SHAREMATE와 암호화 연결로 운행·경비·특이사항을 실시간 공유해요 · 휴대폰에서 바꾼 내용은 ‘휴대폰’ 출처로 차량에 기록돼요) | SwitchRow, ic_phone |
| 1913 | 시연 모드 / 실제 차량 대신 15배속 시연 주행으로 기록해요. 시연 기록은 따로 … | Same title / 실제 차량 대신 15배속 시연 주행으로 기록해요 (spoken adds 시연 기록은 따로 표시되고 실제 기록과 섞이지 않아요) | SwitchRow, ic_clock |
| 1918 | 기록은 이 차량의 앱 저장소에 보관되고 … 위 설정을 따릅니다. | ⓘ 기록은 이 차량에만 두고 백업·외부 서버로 보내지 않아요; more: 내비 주소는 초기 연결, 출발·주차 전환과 운행 중에 조회해요 · 주소 저장 여부는 위 설정을 따라요 | CarInfoRow |
| 1808-1811 | SDK panel | ⓘ 차량 신호는 기록에 필요한 것만 읽어요; more: Vehicle SDK로 거리·배터리·VIN을, AAOS로 기어와 충전 상태를 읽어요 · 내비 주소는 받은 경우에만 기록해요 · 권한·미지원·수신 지연은 ‘확인 필요’로 표시해요 · 차량 제어와 내비 목적지 변경은 하지 않아요 | CarInfoRow |
| 1919 | Gleo paragraph | moved to 운행 비서's ⓘ | — |

Behaviour stays: turning 시연 모드 off still stops DemoDrive (:1914).

**`AutopilotSettings`** (FleetCards.kt:551-563)

| Line | Current | New | Component |
|---|---|---|---|
| 556 | eyebrow 자동 완성 | 자동 완성 | CarSectionTitle, ic_autofill |
| 557 + 560 | Two paragraphs | `cardRegistryLine(saved.cards)`; spoken = 557 + 560 in 해요체 | CarLine |
| LogbookUi.kt:1805 | Field 법인카드 끝 4자리 (쉼표로 구분), 예: 1234, 5678 | = | Field |
| 559 | 끝 4자리 숫자를 쉼표로 구분해 입력해 주세요. | 끝 4자리 숫자를 쉼표로 구분해 입력해 주세요 | negative Text |
| 561 | 카드 저장 | = (the mock's 운행 비서 열기 here is a copy slip) | PrimaryAction |

**`AssistantPreviewCard`** (AssistantSheet.kt:503-524; car-only caller LogbookUi.kt:1806)

| Line | Current | New | Component |
|---|---|---|---|
| 508 / 510 | 운행 비서 + tag ASSISTANT_LABEL | = | CarSectionTitle, ic_assistant; trailing `CarTag(ASSISTANT_LABEL)` (no dot; wraps at 1.3×) |
| 514 | 말하듯 적으면 운행 목적을 기록하고 비용을 더하고 이달을 요약해요. | 말하듯 적으면 목적·비용을 기록하고 이달을 요약해요 | CarLine |
| 516-517 | Chips “…” (ASSISTANT_EXAMPLES; 2 below EXPANDED) | = | CarExampleChip, actionLabel 운행 비서에서 열기 |
| 522 | 운행 비서 열기 | = | PrimaryAction |
| 520 | $ASSISTANT_NOTE · Gleo 음성 연동은 승인 후 같은 세 동작으로 이어져요. | ⓘ ASSISTANT_NOTE; more: Gleo 음성 연동은 심사·호출자 검증 후 제공 예정이에요 · 승인되면 같은 세 동작이 음성으로 이어져요 · 운행 목적과 비용은 정차 후 화면에서 기록해 주세요 | CarInfoRow (last row) |

## 7. 월간·Excel (ReportPane, LogbookUi.kt:1499-1627; R)

### 7.1 Header row (LogbookUi.kt:1541-1576; the layout is unchanged)

- **`PeriodStepper` / `MonthStepButton`** (:1054-1078) are restyled in place. 운행기록 (:978) gets the same look.
  - One stadium in `card` with a cardBorder, padding 5, 66 dp tall.
  - Two round 56-dp chevron targets, tinted `Car.skin.icon`. A dead › drops to .38 with no fill.
  - The label stays headlineSmall, a heading and a polite live region.
  - `reportStepperCopy` = (2026년 10월, 이전 달, 다음 달 · 아직 오지 않은 달 …).
- **월간 · 연간** (:1542): the car Segmented, max 320.
- **Excel 저장** (:1544): `LoadingAction(icon = ic_export)` with the accent look. "만드는 중…" and the disabled state "저장할 운행이 없어요" are =.

### 7.2 Month card: new `ReportCloseCard` (MonthCloseUi.kt; R)

ReportPane (:1580) calls it instead of MonthClosePanel, with the same parameters minus `compact`. MonthClosePanel, MonthCloseCard and StepRow stay for 홈 (LogbookUi.kt:767).

**Title and status**
- Title: `CarSectionTitle("월 마감 · ${trips}건", icon = CheckCircle, trailing = closed?.let { CarTag("마감됨 · ${closedDay}", tone = POSITIVE) })`. The current title is "MONTH CLOSE · 9건" (MC:225); the ClosedChip (:227) becomes the tag.
- Headline: `monthCloseHeadline` in headlineMedium =.
- Bar: positive on ringTrack (4.66 / 6.65), sweep key "report-close-bar"; TalkBack "완성률 N퍼센트" =.

**Closed month**
- `CarMiniStat(ic_vehicle, "운행", "N건")`, `(ic_gauge, "총주행", km)`, `(ic_won, "경비", won)` (MC:235-237).
- Then the note.
- Then `QuietAction("마감 해제")` and `QuietAction("Excel 저장" / "저장 중…")`, half width each (:242, :248); the follow-ups are =.

**Open month**
- **Steps.** `monthCloseSteps` labels, details and actions are = (정리하기 · 확인하기 · Excel 저장 / 저장 중…).
  - When `closeStepsAcross(contentWidth, fontScale)` is true, the steps sit four across, 16 dp apart. Each cell: disc 32 · label (titleSmall), then the detail (CarLine; negative while the step has an action, else textMuted), then the action as a `CarPillButton`.
  - Otherwise, stacked `CarRow`s with the disc as `lead` and the pill as `trailing`.
  - Disc: done → positive fill with ✓ in onTertiary (6.21 / 7.44); otherwise the `disc` fill with the number in text; idle → the number in textSecondary.
  - TalkBack: 완료 / 대기 / 선택 / 남음 and "N단계" =.
- **Button.** Under the steps, one row: the note (`weight 1f`) and `LoadingAction("$name 마감", "마감 중…", act, enabled = status.ready)` (min 240 dp; accent).
  - While disabled it carries `stateDescription = monthCloseFootnote(status, today)`.
  - Follow-up "10월을 마감했어요 · 마감 해제" =.
- **Note.** `monthCloseNote(status, today, edited)`: when `more` is empty it is a CarLine, otherwise a CarInfoRow.

| Case | Current (MC:239, :262) | New |
|---|---|---|
| Closed, edits after close | 마감 후 수정된 운행 N건 · 운행기록에 ‘마감 후 수정’으로 표시돼요 | 마감 후 수정 N건 · 운행기록에 표시돼요 (CarLine) |
| Closed with no edits, or ready | 마감 후 고친 운행은 …표시돼요. 기록을 잠그지는 않아요. / 마감은 이 달이 완성됐다는 표시예요. … | ⓘ 마감 후에도 운행은 고칠 수 있어요; more: 고친 운행엔 ‘마감 후 수정’이 붙어요 |
| Past month without trips | 운행이 없는 달은 마감하지 않아도 돼요. | 운행이 없는 달은 마감하지 않아도 돼요 (CarLine), then QuietAction 이전 달 보기 = |
| Otherwise (open issues; this month without trips) | 남은 확인 N건을 끝내면 마감 버튼이 열려요. / 운행이 기록되면 여기서 마감할 수 있어요. | No note: step ④ says it ("①·②를 끝내면 열려요" / "운행이 없어요"); TalkBack keeps the footnote on the button |

### 7.3 View tabs, range line, 운행 현황

- **View tabs** (:1584): the car Segmented with 운행 현황 · 경비·정산 · Excel · 검토 =, filling the page (was max 960).
- **Range line** (:1585): "시연 데이터 · 2026-10-01 ~ 2026-10-31 · 9건" =, in labelMedium textSecondary. `excelPreviewHeight` is unchanged.

**`RatioCard`** (:1662)

| Line | Current | New |
|---|---|---|
| 1667 | 업무사용비율 ⑬ = ⑫ ÷ ⑪ | CarSectionTitle 업무사용비율 (ic_report) + `CarTag("⑬ = ⑫ ÷ ⑪", description = "업무 거리 나누기 총주행 거리")` |
| 1668 | Ring caption 업무사용비율 | Hidden: `RatioRing(…, showCaption = false)`; TalkBack keeps "업무사용비율 80%" |
| 1670-1674, 1682 | 출·퇴근 ⑧ · 일반 업무 ⑨ · 개인 · 미분류 with bars and TalkBack; 총주행 거리 ⑪ | = |

**`EmployeeUsageCard`** (:1629)

| Line | Current | New |
|---|---|---|
| 1631 | EMPLOYEES / 직원별 이번 기간 이용 | 직원별 운행 (Person) |
| 1633 | 완료된 운행이 없어요. 출발하면 자동으로 기록해요. | 완료된 운행이 없어요 (CarLine) |
| 1637-1640 | Rows | = as CarRow(name or 직원 미지정, line "N건 · 업무 x km", trailing total km) |
| 1644 | 운전하는 직원은 ‘지금 운전’에서 바꾸고, 지난 운행은 운행 상세에서 고칠 수 있어요. | ⓘ 지난 운행의 직원은 운행 상세에서 고쳐요 |

### 7.4 경비·정산

**`ExpenseOverview`** (:1648)

| Line | Current | New |
|---|---|---|
| 1650 | EXPENSES / 등록 경비 | 등록 경비 (ic_receipt) |
| 1651-1657 | 경비 합계 (large) and the type rows | = |
| 1658 | 직접 등록한 금액입니다. 영수증 인증이나 지급 완료를 의미하지 않습니다. | ⓘ 직접 등록한 금액이에요; more: 영수증 인증이나 지급 완료를 뜻하지 않아요 |

**`SettlementCard`** (:1723)
- New signature: `SettlementCard(trips, data, modifier, goSettings: (() -> Unit)? = null, period: String? = null)`. F adds `period`; R writes the body.
- Copy comes from `settlementCopy(rateWon, period)`.

| Line | Current | New |
|---|---|---|
| 1726 | 유류비 정산 · 업무용 거리 × km당 단가 | CarSectionTitle 유류비 정산 (ic_won) + `CarTag("km당 300원")` while a rate is set |
| 1728 | km당 정산 단가를 입력하면 직원별 정산액을 계산해요 | km당 단가를 입력하면 직원별로 계산해요 (titleSmall) |
| 1729 | 회사에서 정한 단가를 … 별개로 표시해요. | removed |
| 1730 | 설정에서 입력 | = (월간·Excel only) |
| 1733 | 이 기간의 업무용 운행이 없어요. | 이 기간 업무용 운행이 없어요 / 설정: 이번 달 업무용 운행이 없어요 (CarLine) |
| 1734-1742 | Rows | = as CarRow(name, line km, trailing amount) |
| 1744 | 정산 참고 합계 · km당 N원 | 정산 참고 합계 / 설정: 이번 달 정산 참고 합계 (Stat, large) |
| 1745 | 직원 미지정 운행은 정산 보류입니다. 등록 경비를 이 금액에 자동으로 더하지 않아요. | ⓘ 직원 미지정 운행은 정산을 보류해요; more: 등록 경비는 이 금액에 자동으로 더하지 않아요 |

### 7.5 Excel view

- **Preview card.** At :1588 the preview sits inside a `CarCard(Modifier.height(previewHeight))`; ExcelPreview fills it. These are =: the table, the sheet tabs, the pager (‹ 이전 · n / m · 다음 ›, the hint "가로로 더 보기 · 행을 누르면 전체 내용 표시", EP:201-205) and the row dialog (EP:163-169).
- **SheetTabs on the car:** the selected label and the underline use `Car.skin.accentInk`, via `underlineColor` and the tint. The phone keeps glow.

**Inside ExcelPreview.kt, car-gated with `isCar()`.** The phone's path is byte-identical.

| Line | Current | New (car) |
|---|---|---|
| 129 | ClosedChip | `CarTag("마감됨 · 11/2", tone = POSITIVE)` |
| 131 | 시연 데이터 · 제출용이 아닙니다 | 시연 데이터 · 제출용이 아니에요 |
| 142 | Panel: 미리보기를 만들지 못했어요 / 기록은 변경하지 않았습니다. 조회 기간을 다시 선택해 주세요. | No Panel: title = / CarLine 기록은 그대로예요 · 기간을 다시 골라 주세요 |
| 149-151 | Panel: title = / 조회 기간과 직원 선택을 확인하세요. 저장 시에도 빈 내역으로 표시됩니다. | No Panel: title = / CarLine 저장하면 빈 시트로 들어가요 |
| 162 | 실제 저장 데이터 · Excel 인쇄 배치와 다를 수 있어요. 증빙·세무 요건은 제출 전 별도로 확인하세요. | ⓘ Excel 인쇄 배치와 다를 수 있어요; more: 실제 저장 데이터로 만든 미리보기예요 · 증빙·세무 요건은 제출 전 따로 확인해 주세요 |

**`SaveBar`** (:1476, :1616)
- Card fill with a cardBorder. It stays 72 dp, so `SAVE_BAR_RESERVE` is =.
- Contents: ic_phone (22 dp), then `CarLine("휴대폰 앱에서 여는 전체 운행 파일")` (was "휴대폰 SHAREMATE에서 여는 전체 운행 파일이에요"), then `LoadingAction("휴대폰용 운행파일 저장", quiet = true)` with "만드는 중…" =.
- While disabled it carries the state "저장할 운행이 없어요".

### 7.6 검토

**Audit card** (:1599-1605)

| Line | Current | New |
|---|---|---|
| 1600 | 증빙 준비 상태 | = (ic_journal) |
| 1602 | `auditHeadline` | = |
| 1603 | 직원·운행 목적·계기판 거리의 연결을 점검합니다. Excel 확인사항 시트에서 세부 항목을 볼 수 있어요. | 세부 항목은 Excel 확인사항 시트에 있어요 (CarLine) |
| 1604 | 국세청 운행기록부 항목 참고 · 제출 전 검토용. 영수증과 세무 요건은 별도로 확인해야 합니다. | ⓘ 제출 전 검토용이에요; more: 직원·운행 목적·계기판 거리의 연결을 점검해요 · 국세청 운행기록부 항목을 참고했어요 · 영수증과 세무 요건은 따로 확인해 주세요 |

**`TaxCard`** (:1691). `taxBasis` replaces `taxEyebrow`.

| Line | Current | New |
|---|---|---|
| 1693 | 비용 참고 · 올해 업무사용비율 기준 | CarSectionTitle 비용 참고 (ic_won) + `CarTag(taxBasis(year, today))` = "올해 업무사용비율 기준" / "2025년 업무사용비율 기준" |
| 1697 | `taxCardBlocker`: 연간 차량 비용을 입력하면 운행기록부의 효과를 계산해요 / 2025년 운행 기록이 없어 업무사용비율을 계산할 수 없어요 | 연간 차량 비용을 입력하면 계산해요 / 2025년 운행이 없어 비율을 계산할 수 없어요 / 올해 운행이 없어 비율을 계산할 수 없어요 (titleSmall) |
| 1699 | 감가상각비·유류비·… 1,500만 원까지만 업무용으로 인정돼요. | ⓘ 운행기록부가 없으면 연 1,500만 원까지만 인정돼요; more: 차량 1대당 기준이에요 · 연간 비용: 감가상각비·유류비·보험료·자동차세 등 |
| 1700 | 설정에서 입력 | = |
| 1701/1704 | 연간 차량 관련 비용 | 연간 차량 비용 (`CarMiniStat`, ic_won) |
| 1705 | 업무 비율 적용액 · 단순 비교 | 업무 비율 적용액 (Stat, large, onTertiaryContainer) |
| 1706 | 미작성 방식 비교액 | 기록부 없을 때 (`CarMiniStat`, ic_won) |
| 1709-1711 | 두 계산의 차이는 …입니다. / … 계산액이 비교액보다 작아요. / 현재 입력 기준으로 두 계산액이 같아요. | `taxDifferenceLine`: 두 계산의 차이 1,200,000원 / 비율 50% 적용액이 더 작아요 / 두 계산액이 같아요 (titleSmall) |
| 1714 | 보험 가입·보유 기간·… 세무사에게 확인해 주세요. | ⓘ 참고 계산이에요 · 세무사에게 확인해 주세요; more: 보험 가입·보유 기간·비용별 한도 등을 반영하지 않은 참고 계산이에요 · 실제 인정액과 신고 요건은 담당 세무사에게 확인해 주세요 |

### 7.7 Confirm dialog (:1623-1626; DialogButton untouched)

- Title "정리되지 않은 운행이 있어요" =.
- Body: `exportConfirmLines(totals.unclassifiedKm, trips.count { it.driverId == null })` joined with "\n". It shows only the lines that apply: "미분류 12.0 km는 업무 거리에서 빠져요", "사용자 미지정 3건은 ④란이 비어요".
- Buttons: 저장 → "그래도 저장"; 정리하러 가기 =.

## 8. 차량 (VehiclePane, VehiclePane.kt:71; R)

**Order.**
- The car card sits beside 차량 정보, as today (`vehiclePaneLayout` =).
- Below them, the left column holds 차량 관리, 정비 기록 (new) and 휴대폰 연결; the right column holds 차량 예약 and 사고·특이사항.
- Narrow windows stack the cards in the same order.

**Car card** (`VehicleHero`, :134). It becomes a CarCard; `KeyCard` stays for DemoTutorial.kt:76.
- Copy comes from `vehicleHeroCopy(demo, fresh, signal, gear)` → `VehicleHeroCopy(tag, tone, gear, footer)`.
- Gear and odometer update in place. The drawing and its description (165) are =.

| Line | Current | New |
|---|---|---|
| 147, 151 | MY FLEET / plate or 차량번호 미등록 | CarSectionTitle(plate or 차량번호 미등록, ic_vehicle) |
| 149, 203 | 차량 신호 / 시연 신호; Stat 데이터 연결 수신 중 / 확인 필요 | One `CarTag(tag, tone)`: 수신 중 POSITIVE · 연결 중 QUIET · signal label (차량 정보 권한 필요 …) NEGATIVE · 확인 필요 NEGATIVE · 시연 신호 DEMO. TalkBack "차량 신호 {tag}"; not live |
| 155 / 161 | 계기판 누적거리 / 수신 기어 | 계기판 / 기어 (TalkBack keeps 계기판 누적거리, 수신 기어). Values and 확인 필요 = |
| 142-143 | Footer: 가상 신호 · 실제 차량 상태가 아닙니다 / signal label / 자동 기록 중 · x km / 수신한 차량 정보만 표시합니다 | ⓘ 차량에서 받은 정보만 보여요 · demo: ⓘ 시연 신호 · 실제 차량 상태가 아니에요. The recording and stale footers are removed: the status tag and the car card's tag already say them |

**차량 정보** (`VehicleDetails`, :171)

| Line | Current | New |
|---|---|---|
| 189-190 | 차량 정보 / Pleos SDK · AAOS / 시연 데이터 | CarSectionTitle 차량 정보 (Info) + CarTag (Pleos SDK · AAOS / 시연 데이터) |
| 192 | 지금 운전 row | removed (the 사용자 card or the header chip shows the driver on every layout) |
| 193 | 차량 수신 차종 / 등록 차종 / 미등록 | = |
| 194 | 차대번호(VIN) | 차대번호 |
| 196 | 배터리: x% · 충전 중 / 현재 확인 불가 | x% · 충전 중 / 확인 불가 |
| 198 | 마지막 저장 잔량: x% · 현재 상태와 다를 수 있음 | x% (the caveat moves to TalkBack) |
| 203-204 | Stats 데이터 연결 / 최근 수신 · 이전 수신 | 데이터 연결 removed; 최근 수신 / 이전 수신 with "N초 전 / N분 전 / 수신 전" becomes a VehicleInfoRow |
| 208-218 | 최근 완료 운행 / 최근 운행 · 종료시각 확인, the time, km, 목적 · 이름 / 직원 미지정 | = |
| 210-211 | 완료된 운행이 아직 없어요 / 출발하면 자동으로 기록을 시작해요. | = / removed |

**차량 관리** (`VehicleCareCard`, FleetCareUi.kt:269). This card now holds deadlines only.
- Title: `CarSectionTitle("차량 관리", ic_calendar, trailing = CarTag(the odometer strings of :283 =))`. Those strings are: 계기판 수신 전 / 계기판 51,300 km / 계기판 51,300 km · 마지막 수신값.
- Empty state:
  - The title (287) = (등록된 기한이 없어요 / 정비 주기는 계기판 값을 받으면 계산해요).
  - Line 288 → "기한을 입력하면 30일 전부터 알려요".
  - QuietAction 기한 입력하기 =.
- `CareAlertRow` (:314) becomes a CarRow with a 12-dp level dot as `lead` and `state = careLevelState`. The service bar with its TalkBack is =; the `careAlerts` titles and details are shared and unchanged.

**정비 기록** (new `MaintenanceCard(state, now, act, repository, modifier)`; split from :292-310; it takes the `adding` state).

| Line | Current | New |
|---|---|---|
| 294-296 | 정비 기록 · N건; 정비 기록 추가 / 닫기 | CarSectionTitle 정비 기록 (Build), trailing CarTag "N건" + `CarPillButton("추가" / "닫기", description = "정비 기록 추가" / "정비 기록 입력 닫기")` |
| 299 | 정비 기록이 없어요. 정비소에서 법인카드로 결제하면 … 직접 추가할 수도 있어요. | 정비 기록이 없어요 · 법인카드 결제는 자동으로 들어와요 |
| 341-346 | `MaintenanceRow`: date · kind · amount, detail, 삭제 | = as CarRow; `CarPillButton("삭제", color = negative)`; TalkBack = |
| 310 | 최근 5건만 표시해요. 전체 기록은 Excel 차량관리 시트에 들어가요. | 최근 5건 · 전체는 Excel 차량관리 시트에 있어요 |

The form (:365-406) sits inline; the `Scene.rail` sheet is gone.

| Line | Current | New |
|---|---|---|
| 376 | 어떤 정비였나요? | 정비 종류 |
| 352-361 | `ChoiceChip`: selection fill + glow | A 56-dp target holding a 46-dp pill. Selected: accent with onAccent, Role.RadioButton, `selected`. Others: chipBorder |
| 381-387 | Fields, hints, errors | = |
| 388 | 엔진오일·소모품 기록은 마지막 정비 계기판 값을 함께 갱신해요. | 마지막 정비 계기판 값도 함께 바뀌어요 (only for OIL) |
| 390, 403 | 기록 추가 · 취소; follow-up with 실행 취소 | = |

**휴대폰 연결** (`PhoneSyncCard`, FleetCards.kt:455). It absorbs the pairing content on 차량; VehiclePane drops the separate PairingCard call (:80). `PairingCard` stays untouched for 홈 (HomeCards.kt:177).

| Line | Current | New |
|---|---|---|
| 465-471 | Dot + headline (꺼짐 / error / 연결 서버 준비 중 / 휴대폰과 실시간 동기화 중 / 연결된 휴대폰 N대 · 대기 / 휴대폰을 연결해 주세요), live | CarSectionTitle 휴대폰 연결 (ic_phone) + `CarTag(phoneLinkTag(…), live = true)`: 꺼짐 · 서버 오류 · 준비 중 · 실시간 동기화 중 · 대기 · 연결 전. A server error's own text shows as a CarLine |
| 475-476, 447 | 기록 버전 #N / 휴대폰 최근 수신 N초 전 / 연결된 휴대폰 N대 | While enabled: CarMiniStat(ic_journal, 기록 버전, #N) · CarMiniStat(Refresh, 최근 수신, N초 전 / N분 전 / 없음, live) · CarMiniStat(ic_phone, 연결, N대) |
| 430 | 휴대폰 연결 · 처음 한 번만 / 휴대폰 추가 연결 | Shown when phoneSync && running: label 연결 번호 (titleSmall) + CarTag "4:32 남음" (not live) |
| 434-435 | Code display and its TalkBack | = |
| 436 | 휴대폰 SHAREMATE에서 차량 주소와 이 번호를 입력하세요 · 4:32 남음 | 같은 Wi-Fi에서 휴대폰 앱에 주소와 번호를 입력해요 |
| 438 | 다른 휴대폰을 더 연결하려면 새 번호를 만드세요 / 연결 번호가 만료됐어요 | 새 번호로 휴대폰을 더 연결해요 / 연결 번호가 만료됐어요 |
| 441-442 | 차량 인증키 · 처음 연결할 때 입력 + fingerprint | CarRow 인증키 (Lock) with the fingerprint (data; may wrap, `titleMaxLines = 2`); TalkBack 차량 인증키, 처음 연결할 때 입력 |
| 443 | Addresses / 네트워크 주소 확인 중 | CarRow 주소 |
| 446 | 새 연결 번호 / 번호 다시 만들기 | = as CarPillButton |
| 450 + 478 | 같은 Wi-Fi·핫스팟에서 연결하세요. … / 차량이 원본이고 휴대폰은 거울이에요. … | ⓘ 차량 기록이 원본이에요; more: 휴대폰에서 바꾼 목적·경비·특이사항은 ‘휴대폰’ 출처로 남아요 · 휴대폰은 인증키로 차량을 확인한 뒤 번호를 보내요 |
| 479 | 설정 › 기록에서 켜면 휴대폰 SHAREMATE가 운행·경비·특이사항을 실시간으로 받고, … | 설정 › 기록에서 켜면 휴대폰이 실시간으로 받아요 (CarLine) |

**차량 예약** (`CarReservationsCard`, CarReservationsUi.kt:95)

| Line | Current | New |
|---|---|---|
| 113 | 차량 예약 · 다가오는 N건 | CarSectionTitle 차량 예약 (Notifications) + CarTag "다가오는 N건" when N > 0 |
| 115 | 예정된 예약이 없어요 | 현재 잡힌 예약이 없어요 |
| 116 | `carReservationsEmptyText` paragraph | ⓘ from `carReservationsEmptyText(autopilot)`: (휴대폰 앱 예약 탭에서 잡으면 여기 보여요, [예약 시간이 되면 예약한 직원으로 바꿔요, (autopilot) 그 시간 운행은 예약 목적으로 기록돼요]) |
| 137-148 | Rows: carActiveTitle (glow bold), window, detail, 취소 + TalkBack, dots (glow / brand .7) | = as CarRow (`titleMaxLines = 2`: a booking window is data) + `CarPillButton("취소", color = negative)` |
| 133 | 외 N건은 휴대폰 SHAREMATE의 예약 탭에서 볼 수 있어요. | `carReservationsOverflow(n)` = 외 N건은 휴대폰 앱 예약 탭에서 봐요 |

The follow-up "예약을 취소했어요 · 실행 취소" is =.

**사고·특이사항** (`IncidentHistory`, FleetCards.kt:278)

| Line | Current | New |
|---|---|---|
| 287 | 사고·특이사항 기록 · N건 | CarSectionTitle 사고·특이사항 (Warning) + CarTag "N건" |
| 288 | 기록이 없어요. 홈의 사고·특이사항 버튼을 누르면 그 순간의 차량 정보가 저장돼요. | 기록이 없어요 · 홈의 버튼으로 바로 남겨요 |
| 293-309 | Rows: 종류 · M/d HH:mm, detail or 상세 없음, 완료 / 다시 열기, TalkBack and state | = as CarRow (dot lead, `state` = status) + CarPillButton |

The follow-ups, including the breakdown → 정비 offer, are =.

**Contrast specific to 차량 and 월간 (on the card).**
- negative labels 5.60 / 4.81
- glow active booking title 6.14 / 10.32
- positive bar on ringTrack 4.66 / 6.65
- brand .7 booking dot 3.42 / 5.30
- selected chip: accent 4.93 / 3.25 against the card; label on it 4.93

## 9. The one-line rule, enforced

**Width budgets** (FULL, 1×, measured in Asta Sans)
- 지금 column content: 352 dp.
- 설정 half card: 496 dp. A line beside a switch gets 368; an ⓘ line 466.
- Page-wide card: 1064 dp.
- 차량 car card: 618 dp; 차량 정보: 374 dp.
- A syllable at bodySmall 16 sp measures about 14–14.7 dp.

**Tightest lines, all fitting**

| Line | Measured / budget (dp) |
|---|---|
| 반복 도착지의 목적과 카드 결제를 자동 입력해요 | 292 / 368 (306 at the narrowest two-up page) |
| 실제 차량 대신 15배속 시연 주행으로 기록해요 | 282 / 368 |
| 기기 안에서 규칙으로 처리해요 · 마이크 없음 · 네트워크 없음 | 373 / 466 |
| 카드 미등록 · 주유·충전·통행료·주차 결제만 받아요 | 320 / 496 |
| 정비 기록이 없어요 · 법인카드 결제는 자동으로 들어와요 | 344 / 496 |
| 같은 Wi-Fi에서 휴대폰 앱에 주소와 번호를 입력해요 | 316 / 496 |
| 업무 100% · 1,234.5 km · 123건 | 220 / 248 |
| 11월 21일 (토) 18:00 · 김서연 님 | 249 / 276 |
| 12,345.6 km (mini stat) | 107 / 109 (autoSize below) |

**Data that may still ellipsize at 1×** (accepted; TalkBack has the full text)
- A 직원 row with the 지금 운전 tag and a last drive in a previous year: 344 of about 300 dp.
- Booking-window titles: they get two lines.

**Copy objects and budget tests**
- Every static visible line in 설정 is a constant in `internal object SettingsCopy` (new SettingsCopy.kt, T), with `SPOKEN_*` for the long TalkBack forms. 월간·Excel and 차량 have the same in ReportCopy.kt (R).
- The test util `test/CarCopyRules.kt` (F) defines `oneLineUnits(text)`: Hangul 1, whitespace 0.25, anything else 0.55. Its budgets:
  - beside a switch ≤ 20
  - card line ≤ 26
  - ⓘ line ≤ 26
  - 지금 column ≤ 19 beside the avatar
- Tests assert those budgets and that no visible line ends in ".".

**Writing rules**
- One fact per line, in 해요체, with no final period.
- Numbers and states first ("N건 남음", "20일까지"). No space before particles.
- Titles are at most 8 syllables. No English eyebrows on these pages.
- Anything that needs "그리고" or "단" goes into an ⓘ row's `more` or into `spoken`.

## 10. Pure helpers and tests (complete list)

### F
- **`headerClock(at: Long, zone: ZoneId): ClockText(date, time, spoken)`** (LogbookUi.kt, next to dayLabel):
  - date = `dayLabel(...)`; time is `HH:mm`, 24-hour, zero-padded.
  - spoken = "{M}월 {d}일 {요일 full} {H}시 {m}분"; the 분 part is omitted when the minute is 0.
  - Tests (new CarUiTest):
    - 2026-11-09 22:20:41 in Seoul → "11월 9일 (월)", "22:20", "11월 9일 월요일 22시 20분"
    - 09:05 → "09:05", spoken "11월 9일 월요일 9시 5분"
    - 2027-01-01 00:00:30 → "1월 1일 (금)", "00:00", "1월 1일 금요일 0시"
    - 23:59:59 followed by 00:00:00 rolls the date
    - the same instant in UTC and in Seoul → "13:20" / "22:20"
- **`carLineMaxLines`:** 0.85 → 1, 1 → 1, 1.05 → 1, 1.15 → 2, 1.3 → 2.
- **`glideCovers`:** same rect → true; exactly half → true; 49 % → false; either null → false.
- **`statusPillInfo`:** LogbookUiTest:29 now expects `"자동 기록 대기" to PillTone.READY`. STARTING stays QUIET (:33).
- **CarSkinTest:** §1.

### S
- **New HeaderTest:**
  - `headerPlan`:
    - 1680 dp with the column → (true, true, false)
    - 1680 × 500 → (true, true, true)
    - 1200 → (false, true, true)
    - 900 → (false, false, true)
    - `carLayout(1680.dp / 1.3f)` with the column → (true, true, false)
  - `clockFitsInline`: 232 → true; 231 → false; 286 at 1.3× → true; 285 → false; 232 at 0.85× → true.
  - `driverChipLabel`: Driver("영업", "김") → "사용자 영업 · 김, 바꾸기"; null → "사용자 미선택, 운전자 선택".
- **CarReservationsUiTest** (new test for `reservationTileLine`, using the 김 booking 14–17 on the 4th):
  - current driver 김, at 15:00 → "사용 중 · 17:00까지", spoken ending "…운전자를 예약한 직원으로 맞췄어요"
  - no current driver → "사용 중 · 17:00까지 · 김 님"
  - at 13:50 → "곧 시작 · 14:00부터 · 김 님"
  - at 09:00 → "오늘 14:00 · 김 님", spoken "예약, 다음 예약 오늘 14:00, 영업 · 김, …"
  - at 3/13:00 → "내일 14:00 · 김 님" (null before)
  - on the 1st → "11월 4일 (수) 14:00 · 김 님"
  - none, cancelled or demo → "현재 잡힌 예약이 없어요"
  - booker not on the roster → "오늘 14:00"
- **HomeDashboardTest:**
  - Remove the `clockInHeader` assertions at :121 and :131. The message at :131 becomes "낮은 창: 위젯 한 줄, 브랜드 줄 숨김".
  - `MonthWidgetInfo.line`: recording → "이번 달 첫 운행"; empty → "이번 달 운행이 아직 없어요"; otherwise equals `detail`.
  - `todayTileInfo`:
    - empty → "—", "0건", "0원", "확인 필요", spoken "오늘 운행 정보 없음, 완료 0건, 등록 경비 0원, 계기판 확인 필요"
    - an open trip is left out of the count but included in the km
- **DriverRosterTest** (`changeButtonWidth`, with space 268 and label 57): name 112 → 151; name 140 → 128; name 200 → 112; label 120 → 168.
- **DriverRosterTest:68-73 is unchanged:** `driverStatusLine` and `driverNowDescription` keep their output.

### T
- **SceneTest** (`sceneStateLine(mode, phase, morning = false, vetoed = false)`):
  - 자동: by day → 지금 낮 화면; at night → 지금 밤 화면
  - 자동 at dusk: with morning → 지금 해 뜰 무렵; without → 지금 해 질 무렵
  - 자동 with the sensor veto → 어두운 곳이라 밤 화면
  - 낮, even with the veto → 항상 낮 화면; 밤 → 항상 밤 화면
  - every result is ≤ 10 syllables and does not end in 요
  - SceneTest:428-453 stays green
- **FleetCareUiTest** (`careOdometerLine`): (null, false) → 계기판 값은 차량 신호를 받으면 넣을 수 있어요; (51300.0, true) → 지금 계기판 51,300 km; (51300.0, false) → 마지막 수신 계기판 51,300 km.
- **DriverRosterTest** (`driverListLine(driver, trips, lastAt, now, zone)`):
  - Sales, 12 trips, last 11/8 → Sales · 운행 12건 · 마지막 운행 11월 8일
  - a blank department is skipped
  - 영업1팀 with no trips → 영업1팀 · 운행 기록 없음
- **New SettingsCopyTest:**
  - `cardRegistryLine`: [] → 카드 미등록 · 주유·충전·통행료·주차 결제만 받아요; ["1234"] → 등록한 카드 1장의 결제만 받아요; two cards → 등록한 카드 2장의 결제만 받아요
  - the `SettingsCopy` budgets (§9)
- **LogbookUiTest** (`settingsCardsGoTwoUpWhenThePageHasRoom`): add 1400 / 832 → 1; 1200 / 1104 → 2; 1296 / 780 → 1. The existing four assertions stay green.

### R
- **LogbookUiTest:176-179:** `taxBasis` → "올해 업무사용비율 기준" / "2025년 업무사용비율 기준".
- **LogbookUiTest:181-189 (`taxCardBlocker`):** "연간 차량 비용을 입력하면 계산해요" (both cost-null cases); "2025년 운행이 없어 비율을 계산할 수 없어요"; "올해 운행이 없어 비율을 계산할 수 없어요"; null when both are set.
- **New ReportCopyTest:**
  - `settlementCopy`:
    - (300, "이번 달") → tag "km당 300원", total "이번 달 정산 참고 합계", empty "이번 달 업무용 운행이 없어요"
    - (1200, null) → "km당 1,200원", "정산 참고 합계", "이 기간 업무용 운행이 없어요"
    - (0, null) → tag null
  - `taxDifferenceLine`: (1_200_000, .9) → "두 계산의 차이 1,200,000원"; (−300_000, .5) → "비율 50% 적용액이 더 작아요"; 0 → "두 계산액이 같아요".
  - `exportConfirmLines`: (12.0, 0) → only "미분류 12.0 km는 업무 거리에서 빠져요"; (0.0, 3) → only "사용자 미지정 3건은 ④란이 비어요"; both → both, in that order.
  - the ReportCopy budgets.
- **MonthCloseUiTest** (`monthCloseNote(status, today, edited)`):
  - closed with 1 edit → ("마감 후 수정 1건 · 운행기록에 표시돼요", [])
  - closed with 0 edits, and ready → ("마감 후에도 운행은 고칠 수 있어요", ["고친 운행엔 ‘마감 후 수정’이 붙어요"])
  - August with no trips → ("운행이 없는 달은 마감하지 않아도 돼요", [])
  - November with no trips, and October with open issues → null
- **MonthCloseUiTest** (`closeStepsAcross(content, fontScale) = content ÷ max(1, scale) ≥ 980 dp`): 1064 at 1× → true; 1056 → true; 896 → false; 998 at 1.3× → false.
- **MonthCloseUiTest:65-72 (`monthCloseFootnote`) stays green.**
- **VehiclePaneTest** (`vehicleHeroCopy`):
  - (real, fresh, READY, PARK) → 수신 중 / POSITIVE / "P"
  - STARTING → 연결 중 / QUIET
  - DENIED → 차량 정보 권한 필요 / NEGATIVE
  - stale READY → 확인 필요 / NEGATIVE, gear "확인 필요"
  - demo → 시연 신호 / DEMO, footer "시연 신호 · 실제 차량 상태가 아니에요"
- **VehiclePaneTest** (`phoneLinkTag(enabled, running, error, online, paired)`):
  - off → 꺼짐 / QUIET
  - not running with an error → 서버 오류 / NEGATIVE
  - not running, no error → 준비 중 / QUIET
  - online → 실시간 동기화 중 / POSITIVE
  - paired but not online → 대기 / READY
  - none paired → 연결 전 / READY
- **CarReservationsUiTest:56-59:** `carReservationsEmptyText(true)` → ("휴대폰 앱 예약 탭에서 잡으면 여기 보여요", ["예약 시간이 되면 예약한 직원으로 바꿔요", "그 시간 운행은 예약 목적으로 기록돼요"]); `(false)` → only the first sentence. Add `carReservationsOverflow(3)` = "외 3건은 휴대폰 앱 예약 탭에서 봐요".

### Unchanged and still passing
- `excelPreviewHeight` (LogbookUiTest:41-50)
- `auditHeadline`, `reportStepperCopy`, `vehiclePaneLayout`, `careAlerts`, `carActiveTitle`, `nextReservationLine` (CarReservationsUiTest:28-47), `monthCloseSteps` (MonthCloseUiTest)
- `driverHistoryLine` (DriverRosterTest:104-107), FleetCareUiTest:59-78, `driverChipMode` and `driverChipAvatar` (LogbookUiTest:220-238)

## 11. Motion, TalkBack and ergonomics checklist

**Motion**
- The SOLID glide is 180 ms for segments and 220 ms for tabs, FastOutSlowIn; it snaps on first placement and under reduced motion.
- The ⓘ expand is 200 ms.
- The 사용자 question folds in 200 ms (as built). Press is 90 ms in and 160 ms out.
- Unchanged: the month card's `animateContentSize(260)`, the keyed `sweepProgress` bars, the view `Crossfade` (160), and `Appear` (first composition only, capped at 5).
- Updated in place, never animated: the clock, the odometer, km while recording, 최근 수신, the pairing countdown, and status colours.

**TalkBack**
- Every CarSectionTitle is a heading.
- Live regions: the status tag (polite), the 예약 tile, the period label, the phone-link tag, LoadingAction while it runs, and the duplicate-name warning.
- The sub-tabs and the views are tabs. The 자동 · 낮 · 밤 options are radio buttons in a selectableGroup. The maintenance-kind chips are radio buttons.
- 직원 rows keep "수정" and "수정 중".

**Targets**
- At least 56 dp: segments, tabs, header pills, CarPillButton, CarExampleChip, CarLink, CarInfoRow (toggle), PeriodStepper arrows, PrimaryAction, QuietAction, LoadingAction.
- At least 72 dp: SwitchRow, and CarRow when it has a line.
- No ripple anywhere on the car.

**Reachable, as today**
- **Header:** every tab; 운행 비서; 시연 체험 (also from 설정 › 기록); the picker, from the chip and from 바꾸기.
- **설정:**
  - every field, save and 실행 취소
  - 차량에서 가져오기, 지금 계기판 값 넣기
  - 새 직원, 운전자로 선택, 직원 삭제 with undo, the 50-person cap
  - the four switches and 출발 전 운전자 확인
  - 시연 체험 다시보기, 카드 저장
  - the three example chips, opened with their phrase, and 운행 비서 열기
- **월간·Excel:**
  - the stepper, 월간 · 연간, every Excel 저장 (`requestExport`, `excelSaveAvailable`)
  - 마감 and 마감 해제
  - 정리하기, 확인하기, 이전 달 보기, 설정에서 입력
  - sheet tabs, pager, row dialog, the phone-file save, the confirm dialog's 정리하러 가기
- **차량:**
  - 기한 입력하기
  - 정비 기록 추가 and 삭제 with undo
  - 새 연결 번호 / 번호 다시 만들기
  - 예약 취소 with undo
  - 완료 / 다시 열기, including the 고장 → 정비 offer

## 12. Package summary (details are in the packages field)

1. **F (foundation)** is committed first.
2. **S** (header + 지금 column + 홈 hero clock), **T** (설정) and **R** (월간·Excel + 차량) then run in parallel worktrees on top of F, owning disjoint functions.
3. **Integration** merges S, T and R. Import-block conflicts in LogbookUi.kt, HomeDashboard.kt, DriverUi.kt, FleetCareUi.kt, FleetCards.kt and CarReservationsUi.kt are expected; resolve them as the union of both sides. Then:
   1. Run the tests, lint and assembleDebug.
   2. Check the phone-isolation gate (§0).
   3. Bump to 2.12.0 / versionCode 22, and set schema.json "version" to 2.12.0 (GleoIntegrationTest).
   4. Re-take the shots (§13).

## 13. README screenshots to re-take, and recipes

The car shots, all 1680 dp, re-shot with the restyle:

| # | File | What it should show |
|---|---|---|
| 1 | car-home.jpg | Day, morning: the clock top right; the 사용자 card with the 자동 기록 대기 tag; the 지금 column restyle; no hero clock. Use `cmd alarm set-timezone America/Halifax` for 09:xx, then set back Asia/Seoul. |
| 2 | car-home-night.jpg | Night: the 사용자 card with today's question and 네 · 다른 직원. Force-stop to bring the daily question back. |
| 3 | car-driver-picker.jpg | The picker over day 홈 (disc avatars, header, column). |
| 4 | car-drivers.jpg | 설정 › 직원 with Lee Minho open in the editor. |
| 5 | car-log.jpg | 운행기록: previous month, a trip open. The header, stepper and segmented control changed. |
| 6 | car-report.jpg | 월간·Excel: ‹ to 2026년 10월; the closed card and the Excel preview card. |
| 7 | car-vehicle.jpg | 차량. |
| 8 | car-settings.jpg | 설정 › 기록, day: the mock. |

**The demo GIF.** docs/demo/car-day-night.gif shows the old header, the hero clock and 설정 › 화면 분위기. Re-record it with the same story, using the documented ffmpeg recipe.

**Optional new shot:** car-settings-night.jpg, as night evidence.

**README alt text.** Update the alt text of every car shot to the new words:
- 사용자 (not 지금 운전)
- 화면 밝기 (not 화면 분위기)
- 기록 및 개인정보
- no Pleos SDK card
- the clock top right
- 자동 기록 대기 in the 사용자 card

**Phone shots:** not re-taken. Instead, compare phone-settings.jpg and phone-report.jpg against a fresh capture; they must be identical.

**Verification captures (not for the README):**
- `font_scale 1.3`: wait about 15 s before relaunching.
- `wm density 240`: the no-column header with the stacked clock.
- A short window.
- Reduced motion: `animator_duration_scale 0`.
- Reset all of them afterwards.