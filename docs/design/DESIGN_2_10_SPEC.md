# SHAREMATE 2.11 — final design spec (day scene · whitespace · motion · responsive)

# A. Scene system

**ScenePhase rule.** Keep `scenePhaseAt` (Seoul sunrise/sunset table, `SceneTest`): DAY = sunrise + 30 min → sunset − 45 min; DUSK = ±30 min around sunrise, ±45 min around sunset; NIGHT otherwise. Car addition: `UI_MODE_NIGHT_YES` (Pleos light sensor) held ≥ 90 s forces NIGHT inside a DAY/DUSK band, released 90 s after it clears, at most one sensor flip per 10 min; 낮/밤 overrides ignore it; the phone follows the clock only. Changes apply only while parked (latched while `blocked`, applied on park). Requires `android:configChanges="uiMode|orientation|screenSize|smallestScreenSize|screenLayout"` on all three activities.

**Override.** `SceneModeCard` as built — 설정 › 화면 on the car (56-dp `Segmented`, max 720 wide), first card of 설정 on the phone (48 dp): eyebrow "화면 분위기", segments 자동 · 낮 · 밤, hint from `sceneModeHint`, plus one line while the sensor vetoes: "지금은 터널·주차장이라 밤 화면이에요 · 밝아지면 낮 화면으로 돌아가요". `ScenePrefs` stays in SharedPreferences, never in the synced logbook.

**Day tokens.** Glass carrying text is white at ≥ .88 on day (cards .88, tab pill .92; night keeps its alphas). Ratios vs day bg #EEF3FA and vs the worst glass composite #EBEDF0 (white @ .88 over the bridge deck #5B6B82, the darkest art under a card; over the near towers #F1F4F7). Bold = fixed from HEAD (old values: textMuted 4.48 on rail, warn 4.40 and brand 3.55 on haze, tileEnd 2.44, Pleos active 2.36, negative 4.21, outline 2.46).

| token | day hex | use | vs bg | vs glass |
|---|---|---|---|---|
| bg | #EEF3FA | base | — | 1.11 vs white (fill) |
| rail | #E3EAF4 | sheet/list container | — | — |
| glass | #FFFFFF @ .88 | cards, panels, pills | — | — |
| glassBorder (edgeTop/Mid/Bottom) | #121A2B @ .28 → .14 → .08 | hairline, decorative (1.68 vs white) | — | — |
| text | #121A2B | headline, body | 15.59 | 14.82 |
| textSecondary | #44506A | supporting lines | 7.24 | 6.88 |
| textMuted | **#5A6678** | captions; never on the hero haze | 5.22 | 4.96 (rail 4.81) |
| glow | #0B6B7E | active/selected; white on it 6.14 | 5.51 | 5.24 |
| warn | **#8A4B00** | 점검 필요; chip fill #FFE3B8 (5.48) | 6.10 | 5.80 |
| badge | #C8321A | count badges; white digits 5.35 | 4.80 | 4.56 |
| brand | **#7E4F33** | eyebrows | 6.17 | 5.86 |
| tileStart → tileEnd | **#2456D6 → #0B6B7E** | icon tiles; white icon 6.22 / 6.14 | — | — |
| accent | #2F6BFF | ring sweep, search outline (component) | 4.04 | 3.84 |
| divider · ringTrack · fill | #121A2B @ .16 · .14 · .06 | hairlines, track, row fill | decorative | — |
| *new* positive | #00703C | 수신 중 · 연결됨 · 충전 중 | 5.57 | 5.29 |
| *new* negative | #C62B0C | error text, 사고 label | 5.02 | 4.77 |
| *new* outline | #6B7786 | TextField / OutlinedButton borders | 4.09 | 4.56 vs white |
| *new* selectionFill · selectionRing | glow @ .12 (#E2EDF0) · glow solid, 1.5 dp | selected tab/segment/chip = fill + ring + glow label (ring 6.14, so never colour-only) | — | glow on fill 5.15 |
| *new* haze · sun · shadow | #FFFFFF · #FFF3C4 → #FFD27A (halo #FFF1C2) · #121A2B @ .12, blur 12, y 4 | day scrims · artwork · under tappable cards | — | — |

Night accents are never day text (#4CE3F0 1.39, #FFB45C 1.58, #E4BFA6 1.53 vs bg). Only text, textSecondary and brand may sit on the hero haze; at its weakest point under text (white @ .76 over the sky top #4F9BE8 = #D5E7F9) they read 13.76 / 6.39 / 5.45; on the sign-in haze (white @ .84 = #E3EFFB) 14.90 / 6.92 / 5.90.

**DUSK deltas:** none — night tokens + `DuskPaint`. **NIGHT:** unchanged.

**Material3 day scheme (`DayColors`).** background #EEF3FA; surface #FFFFFF; onSurface #121A2B (17.38); surfaceVariant #E3EAF4, onSurfaceVariant #44506A (6.67); primary #121A2B / onPrimary white (17.38), primaryContainer #DCE6F5; secondary #0064FF (white 4.92); tertiary **#00703C** (#008A4B was 4.43); error **#C62B0C** / onError white (5.60); outline **#6B7786**, outlineVariant #D5DDE8; inverseSurface #121A2B, inverseOnSurface white, inversePrimary #8FCBFF (10.06). `DayPleos`: active and chartActive #00703C, negative #C62B0C, textTertiary #5A6678.

**Day artwork (`DayPaint`, same `skylineScene()` geometry).**
- Sky to the horizon: 0 → #4F9BE8, .45 → #8FC1F2, .70 → #D7E9F8, 1.0 → #F3EEE4 (warm haze). Horizon glow → white @ .60 → 0 at (w·.76, horizon), r = w·.52. Stars 0.
- Sun at `orbAt` — car **(.62, .21)**, clear of the hero haze; phone (.86, .235) as built: disc r = ht·.045, radial #FFF3C4 → #FFD27A rim; halo #FFF1C2 @ .55 → 0, r = ht·.30; no rays, no `orbShade`.
- Clouds: four groups (built three-oval shape) at x .50–.95, y .06–.20, white @ .70 with an underside copy #C9D8E8 @ .45 offset +.012 h; static; never under the hero column or the sun.
- Ridge #AEC1D6; far towers #B5C6DA, windows white @ .55 / #E6EEF7 @ .50; near towers vertical #7B90AC → #6A7F9B (2.2:1 vs the .45 sky stop, so the city stays present), windows #DCEAF7 @ .80 / white @ .60, bloom off; antennas #5B6B82, white @ .80 tips, no red beacons.
- Water 0 → #B9D4EC, .40 → #D5E6F4, 1 → bg; reflections white @ .35; ripples white @ .30. Bridge deck #5B6B82 (4.69 vs horizon), posts #7F8EA3, lamps #D8E2EE, no lamp reflection.
- Scrims (`HomeBackdrop`): car hero haze = horizontal white .90 at x = 0 → .76 at the hero column's right edge → 0 at +300 dp; phone home: vertical white .88 → .76 at the greeting's bottom → 0 at +120 dp; phone sign-in (`leftScrim = false` today): add `scrimTop` white .84 → 0 over the top 55 % (#44506A on raw sky is 2.76). Bottom scrim as built. Night scrims unchanged.

**Crossfade rule: fade-through.** Foreground tokens are never lerped — lerped text on lerped bg bottoms out at 1.16, and no single t keeps both polarities ≥ 4.5 on glass and bg together. 800 ms auto / 480 ms on an override tap: content alpha 1 → 0 (0–200, FastOutLinearIn) → `LocalScene`, Material scheme and `PleosColors` swap once at 200 ms → sky `Crossfade(phase)` 200–800 (Linear) while the sun rises 8 % of ht into place, the moon sinks and stars fade in the first 40 % → content alpha 0 → 1 (560–800, LinearOutSlowIn). Reduced motion: instant swap. Deferred until 3 s without touch, no field focused, no sheet open, no page swap running; never while driving.

# B. Layout system

Class = `widthDp ÷ max(1, fontScale)` (1680 at 1.3× behaves as EXPANDED, so the five-tab pill never overflows); gutters and maxima in raw dp. Height: tall ≥ 900, medium 600–899, short < 600 (phone landscape 411). 8-dp grid; section gap 32 car / 24 phone; header pill and content share one rail.

| class | width | gutter | content max | alignment | columns · gap |
|---|---|---|---|---|---|
| car FULL (new) | ≥ 1400 | 72 | 1200 | left-anchored: column ends at x = 1272; the right 408 dp is sky with nothing interactive | 12 · 24 |
| car EXPANDED | 1100–1399 | 48 | fill | left | 12 · 20 |
| car MEDIUM | 760–1099 | 32 | fill | left | 8 · 16 |
| car COMPACT | < 760 | 20 | fill | left | 4 · 12 |
| phone EXPANDED (tablet) | ≥ 840 | 48 | 760 | centred | 4 cards · 16 |
| phone MEDIUM (landscape) | 600–839 | 32 | 600 | centred | 2 · 12 |
| phone COMPACT | < 600 | 20 | fill | — | 2 × 2 · 12 |

- **Car home.** Header 64 inside the column. `DriverConfirmRow` folds into the hero as one line + two 56-dp chips until answered. Hero column 720: clock row → eyebrow → one headline (displaySmall 44, `scaledDisplay`) → one supporting line → search 640 × 68; hero 420–600 tall, min-only at 1.3×. Cards 3 × 368 × 184, gap 24; widgets 2 × 588 (min 150); 다음 예약 becomes the 오늘 운행 widget's third line (no band); incident row = one panel, four 72-dp buttons; "휴대폰에도 공유" moves to 차량. MEDIUM: 96-dp horizontal cards, widgets stack. Short: hero min 300, eyebrow hidden, clock into the header, widgets one 120-dp row with a 44-dp ring. 1.3×: cards 3-across at FULL, 2 + 1 below.
- **Car 운행기록.** Month stepper + totals + 3-way `Segmented` (max 720) on one 64-dp row; list 560 / detail 616, gap 24; rows 88. MEDIUM and below: full-width list, detail as an 85 %-wide side sheet. Short: rows 64, filters collapse to one 56-dp dropdown chip.
- **Car 월간·Excel.** 이번 달/올해 segmented 480 wide, Excel 저장 at the column's right; month-close card max 760; sheet tabs 56-dp text tabs; preview table scrolls horizontally with a pinned first column; sticky 72-dp save bar inside the column, max 760.
- **Car 차량.** Car card 720 (drawing 420 tall) + info column 456; below, 2 × 588. MEDIUM: one column, drawing 260; short: drawing hidden, plate and odometer kept.
- **Car 설정.** Sub-tabs 56-dp text tabs, max 960; forms max 720, left-anchored; two field columns at ≥ EXPANDED, else one (always one at 1.3×); 화면 분위기 first.
- **Phone home.** Header 56; eyebrow, headlineMedium 24, one line; cards 2 × 2 (min 84), gap 12; one widget row (today km | month ring 52); task card only when a task exists; four 48-dp incident chips; the "차량이 원본이에요" paragraph and the empty "지금 할 일이 없어요" band move to 설정 › 차량 연결. Nav pill 72 + 10 margins, bottom padding 96. Landscape: 40/60 split — hero left, cards + widget right, nav an 80-dp left glass rail. Tablet: cards 4-across. 1.3×: one card per row.
- **Phone 예약.** The timeline card is the single hero; 48-dp date chips scroll; 새 예약 opens as a 92 % bottom sheet (a 50/50 right pane at ≥ 600) from a free slot or a 56-dp 예약하기 button above the pill; steppers 56, ± 48.
- **Phone 운행 / 경비 / 현황.** Employee chips 48 (`FlowRow` at 1.3×); each day group one glass panel with 1-dp dividers, rows ≥ 72; 현황 ≤ three cards above the fold, two columns at ≥ 600. Landscape and tablet: list 55 % / detail 45 % instead of a route push.
- **Phone sign-in / onboarding / settings.** Content max 400 centred (480 on tablets); sign-in art the top 42 % under the top haze, landscape: art left 45 %; onboarding step header 56, 4-dp progress, 72-dp bottom bar with a 56-dp 다음; settings cards max 560, rows 48. 1.3×: buttons wrap, never clip.

# C. Motion spec

Easings: FastOutSlowIn (standard), LinearOutSlowIn (enter), FastOutLinearIn (exit), Linear (opacity, loops). No bounce anywhere (`DampingRatioNoBouncy`); car interactions ≤ 260 ms — the parked-only scene change is the one longer exception.

| interaction | what moves | ms | easing | reduced-motion | note |
|---|---|---|---|---|---|
| Press | scale 1 → .98 car / .97 phone; fill white +6 % (night) / navy 6 % (day) | 90 in / 160 out | LinearOutSlowIn / FastOutSlowIn | fill only | `pressable`; car no ripple, phone bounded M3 ripple |
| Tab change | lit pill glides (x + width) and tints; page `pageSwap` 24 dp slide + fade | 220 (exit 140, enter delay 40) | FastOutSlowIn | snap | header, nav, sky static |
| Segmented · filter · 자동/낮/밤 | pill slides; fill + ring | 180 | FastOutSlowIn | snap | `Segmented` |
| First appearance | `Appear` fade + 10 % lift, stagger 40 | 220; cap 5 items car / 8 phone | FastOutSlowIn | in place | first composition only; never on return or sync |
| Number update | old and new digits cross-fade + 6 dp lift | 160 | FastOutSlowIn | snap | no count-up (a number is data); widgets, ring label, detail only; never clock or live telemetry |
| Ring fill | sweep previous → new % | 600 first (120 ms after the card lands) / 320 later | FastOutSlowIn | snap | `Animatable` in `CompletionRing` |
| Scene change | fade-through per A | 800 auto / 480 override | FastOutLinearIn · Linear · LinearOutSlowIn | instant swap | parked only; two skylines only during the 600-ms crossfade |
| Sheets · dialogs | phone sheet slides up, scrim 0 → .45; car sheet 24 dp lift + fade; dialog fade + scale .96 → 1 (car: fade only) | 240 / 180 (car 200 / 160); dialog 160 / 120 | FastOutSlowIn / FastOutLinearIn | fade snap | assistant, driver picker, booking |
| Snackbar / FollowUpBar | slide up + fade | 120 | FastOutSlowIn | snap | existing `barEnter/Exit` |
| Pull-to-refresh (phone) | indicator follows the finger to 64 dp, 0.5 resistance after; spins while refreshing | settle 200, done fade 150 | FastOutSlowIn | static indicator | content never stretches |
| Layout reflow | bounds of cards, widgets, header, nav (`LookaheadScope` + `animateBounds`); `animateContentSize` on growing rows | 260, starting 60 ms after the last size change | FastOutSlowIn | snap | fallback `AnimatedContent` 220 |
| Greeting · badge · driver confirm | text cross-fade + 4 dp lift; badge fill .85 → 1 with digit cross-fade; 네 row `shrinkVertically` + fade | 220 / 160 / 200 | FastOutSlowIn | snap | `AnimatedContent(key = text)`; no scale pop |

Existing `cardSwap`, `SkyLayer` dim, skeleton pulse and `ThinkingDots` stay as built; `StreamingText` only in the parked assistant sheet.

**Do not animate:** anything while `blocked` (`DrivingOverview`, scene swap, sky layer — Pleos driver-distraction rules; values update instantly); the clock and live telemetry (distance, odometer, SOC — truth never counts up; the sky `drawWithCache` must not invalidate per tick); the skyline (no parallax, twinkle, drift, flicker or sun arc outside the scene change); text size, weight or tracking; status colours and the 자동 기록 dot (no pulse); badges (no bounce); list reorders from background sync (apply on next open or behind a "새 기록 N건" pill); headers and tab bars during a page change; focus and errors (instant); splash → home beyond one 220-ms fade; card → page shared elements (out of scope).

# D. Implementation notes

1. **Tokens** (`Scene.kt`, `SharemateTheme.kt`, `SceneTest`). Edit `DaySceneColors` (textMuted, warn, brand, tileStart/End, divider, ringTrack, fill); add `positive`, `negative`, `outline`, `selectionFill`, `selectionRing`, `haze`, `sun`, `shadow` (night values #00E074 / #FF4C28 / #5E6A7E / glow @ .14 / glow @ .45 / bg / unused / transparent). Fix `DayColors` (outline, error, tertiary) and `DayPleos` (active, chartActive, negative, textTertiary). Delete `lerpScene`, `lerpScheme`, `lerpPleos` and the per-frame `remember(dayness)`; `SceneTheme` provides the palette by phase plus a `LocalSceneMix` content alpha. Extend `dayTextClearsAaOnWhiteGlass` to #EBEDF0, the rail, the haze composites and the new tokens; replace the lerp test with a fade-through sequencing test.
2. **Backdrop** (`Sky.kt`, `HomeBackdrop`, `PhoneAuthUi`). Restyle `DayPaint`; `HomeBackdrop` takes `hazeEnd: Dp` and draws the white haze on day, the bg scrim on night; add `scrimTop` for the sign-in; orb travel reads `sceneMix` as `State` inside `onDrawBehind` so paths stay cached; `Crossfade` 600 ms inside the 800-ms window.
3. **Scene controller.** Process-level `SceneController`: `scenePhaseAt` + sensor veto (90 s hold, 10-min rate limit) + parked latch + quiet-time deferral; manifest `configChanges`; `PhoneActivity` moves from `SharemateTheme(compact = true)` to `SceneTheme` + `SceneSystemBars`.
4. **Layout helpers** (`Layout.kt`). Add `WidthClass.FULL`, `HeightClass`, `effectiveWidth = width / fontScale`, car thresholds 760 / 1100 / 1400 with contentMax 1200 at FULL, `contentWidth()` left-anchored on the car and centred on the phone, `heroWidth()`; replace `wide = maxWidth >= 1100` in `LogbookUi` and the ad-hoc gutters in `PhoneShell`; update `layoutTokensGrowWithTheWindow`.
5. **Motion helpers** (`Motion.kt`). `pressable` timings and day fill, `Appear` caps, `DigitChange`, `CompletionRing` `Animatable`, pill glide and day selection ring in `Segmented` / `GlassTabs` / `PhoneGlassNav`, `fadeThrough`, `Modifier.reflow()` (`LookaheadScope` + `animateBounds`, `@OptIn(ExperimentalSharedTransitionApi)`, Compose 1.8 via BOM 2025.04.01); retire `animatedValue(countUp = true)` (no callers).
6. **Screens**, in order: car home → car panes → phone home → phone tabs → sign-in / onboarding / settings → `VehiclePane` car card off `SharemateTheme(dark = true)` (#0E1522, #1E2C47, #E4BFA6, #C7C9CE → scene tokens) → sweep the 24 remaining literals (card sheens `Color.White.copy(.07)` in `HomeDashboard` / `PhoneMidnight` / `PhoneAuthUi` → `edgeTop`; `PhoneSyncUi.connectedGreen` → positive; `SharemateUi` #DAAF6B / #FFCC80 → warn; `PhoneAuthUi` tile → tile; the Google button's whites stay, brand-mandated).
7. **Verification.** Day and night screenshots for the README (car home, 운행기록, 월간, 차량, 설정; phone home, 예약, sign-in); 1.3× previews for car home, 운행기록 and phone home; 900-wide and short-window car previews; `set-timezone` on the emulator for the band edges.

**Pitfalls (engineering judge).** HEAD's `SceneTheme` rebuilds `SceneColors`, `ColorScheme` and `PleosColors` every frame for 900 ms and lerps text (1.16 mid-frame): remove it, do not tune it. Without `configChanges="uiMode"` a headlight toggle recreates `MainActivity` and wipes the hysteresis. `animateBounds` only works when the same keyed composable survives the breakpoint flip — one composable per card, layout chosen inside. `pageStates` must survive rotation once the activity stops recreating. Hero clamp min-only at 1.3× or the headline clips. Tabs stay 56 dp inside the 68-dp pill (short header: pill padding 4). The skyline `drawWithCache` never reads the clock. Glass alpha below .88 on day voids the composite ratios. `ScenePrefs` stays out of the synced logbook. The right 408 dp at FULL holds nothing interactive.