# SHAREMATE 2.14.0: 채운 홈 (car only)

Round 1 (docs/design/DESIGN_2_12_SPEC.md) and round 2 (docs/design/DESIGN_2_13_SPEC.md) hold wherever this one is silent.
Base: 8756b58 (2.13.0). Paths: `src/` = app/src/main/java/ai/sharemate/app/, `test/` = app/src/test/java/ai/sharemate/app/.

## 1. The feedback

> 첫 홈 화면 지금 너무 빈거 같은데 등록되면 동작하던가 디자인 유지할수있게 해줘

Sent with a screenshot of the 2.12 홈. The first home screen of 2.13.0 looks too empty: a small card, a large empty sky, small
cards at the bottom; on a clean install by night it is emptier still (the setup card alone over the sky), and that is the
first thing the contest reviewers see. Keep the design of the screenshot, where everything is stacked and has weight: large
icon cards under the greeting, a substantial card under them, the incident row with four large buttons at the bottom.
Things that need registration may start working once it is registered.

## 2. What stays binding from 2.13

- No clock anywhere. The search bar stays in the header.
- 홈 and the 지금 column never scroll in the FULL window (1680 x 1209 dp, page column 1112 x 1097 dp) at 1x font in any
  state, proven by the pure frame function (`homeFrame`, `homeForms`) and HomeFrameTest.
- One money style. Every function stays reachable.
- The phone does not change: no edit to Phone*.kt, PhoneMidnight.kt, SharemateTheme.kt, SharemateUi.kt, ConnectionUi.kt.
  The car's sky reads its horizon from a car-only helper in `SkyLayer`; the phone calls `HomeBackdrop` with its own
  `horizonAt` (.40 home, .42 / .60 sign-in) and draws exactly what it drew.

## 3. The page, top to bottom (FULL, 1x, beside the 지금 column)

24 dp between blocks.

| # | Block | dp | Notes |
|---|---|---|---|
| 1 | Greeting block | 160 (minimum) | 8 + the brand line 30 + 12 + the greeting 53 (one line) + 8 + the status line 24 = 135. The sun or moon rests right of it |
| | gap | 24 | |
| 2 | Destination row | 208 TALL / 104 COMPACT | 차량 정보 · 운행일지 확인 · 정보 내보내기, three across |
| | gap | 24 | the slot's own top padding |
| 3 | Attention slot | the card's maximum | always one card: the head of the queue, or the idle card |
| 4 | Sky | 24 + whatever is left | the city stands on the dock |
| 5 | Demo strip + gap | 80 + 16 | demo mode only |
| 6 | Incident dock | 140 TALL / 80 COMPACT | pinned to the page's bottom edge |

**Budget:** 160 + 24 + cards + 24 + `attentionCardMax(kind, pager)` + 24 + (demo ? 96 : 0) + dock <= 1,097.

### 3.1 The greeting block

- **Brand line** (2.12's, restored): `ic_splash_logo` 30 dp in `Scene.brand`, 12 dp, `HomeCopy.BRAND` =
  "SHAREMATE  ·  FLEET JOURNAL" (labelLarge, `Scene.brand`, letter spacing 3 sp). Decorative for TalkBack. Left out in a
  SHORT window, as 2.12 did.
- **Greeting**, 12 dp under it: `homeGreeting`, displaySmall Bold. Beside the 지금 column at 1x (`oneLine`) it keeps to
  ONE line and steps its size down to 30 sp where a name would wrap it ("오늘도 안전한 운행 되세요, Kim Jiwoo 님" may need
  two lines at 44 sp in the 720-dp measure); an ellipsis only below 30 sp. With the brand line, a second line would make the
  block 187 dp and break the budget. Elsewhere it takes two lines as in 2.13. It cross-fades as `DigitChange` does.
- **Status line** 8 dp under it: as 2.13 (`homeStatusLine`).

### 3.2 The destination cards (`DestinationRow`, `DestinationCard`)

**TALL, 208 dp** (`HOME_DESTINATION_TALL`): a `CarCard` with no padding holding a 208-dp box, content centred:

| Part | Spec |
|---|---|
| Icon tile | 56 dp (`DESTINATION_TILE`), corner 16, `Car.skin.accent` fill, the glyph 28 dp (`DESTINATION_GLYPH`) in `onAccent` (4.93) |
| | 16 dp |
| Title | titleLarge (29 dp line), `Scene.text`, one line, a heading |
| | 6 dp |
| Line + chevron | `homeCardLines` in bodyMedium (21 dp), `Scene.textMuted`, one line, ellipsis (two centred lines above 1.05x, `carLineMaxLines`, so a 1.3x font outside the frame loses no words); `KeyboardArrowRight` 20 dp in textSecondary |
| Badge | `CountBadge` at the top right, 16 dp in |

40 + 56 + 16 + 29 + 6 + 21 + 40 = 208. Horizontal padding 16; the line's budget is 18 units (`HOME_TALL_LINE_UNITS`).
Pressable as before (.98 + pressFill, day shadow, no ripple). TalkBack as 2.13: "차량 정보, 정기검사 D-9 · 배터리 100%,
확인 필요 2건", click label 열기.

**COMPACT, 104 dp** (`HOME_DESTINATION_COMPACT`): the 2.13 card, unchanged.

A change of form swaps the row on `cardSwap()` (160 ms, the size following on the same clock; a snap under reduced motion).

### 3.3 The incident dock (`IncidentDock`)

**TALL, 140 dp** (`HOME_DOCK_TALL` = 16 + 24 + 12 + 72 + 16): one `CarCard(padding = 16)`.

- **Header line** (24 dp, one TalkBack heading stop `SPOKEN_DOCK`): `ic_warning` 24 · 10 · `DOCK_TITLE` "사고·특이사항"
  (titleSmall, text) · 12 · `DOCK_CAPTION` "한 번 누르면 차량 정보가 바로 기록돼요" (bodySmall, textSecondary, weight 1,
  ellipsis); on the right, while the phone link is on, `DOCK_SHARED` "휴대폰에도 공유" (labelMedium, textMuted). These are
  2.12's exact words (`git show 91085a1:app/src/main/java/ai/sharemate/app/FleetCards.kt`, IncidentQuickRow).
- 12 dp, then **four equal buttons**, 72 dp (`DOCK_BUTTON_TALL`), corner 16, 12 apart: the label (titleMedium) over its
  caption (labelMedium, textMuted): 사고 / 119 · 112 안내, 파손·긁힘 / 촬영 안내, 고장·경고 / 긴급출동 안내, 특이사항 / 메모로
  남기기. 사고 is tinted with the negative (`incidentTint`: 10 % by day, 6 % by night, over the card) inside a negative
  outline, its label in the negative; the others sit on `chipFill` inside `chipBorder`. Pressable .98 + pressFill, no ripple.
  TalkBack "사고 기록, 119 · 112 안내", click label "사고 기록" (as 2.13).
- Under 560 dp: the header over 2 x 2 buttons.
- The header keeps its one row from 560 dp of dock, times the font scale above 1x (`dockHeaderOneRow`; it needs about 540
  dp at 1x with 휴대폰에도 공유). Narrower (a split window, or 1.3x below 728 dp) the caption becomes a `CarLine` under the
  title, as the compact dock's narrow lead does, and 휴대폰에도 공유 stands at the title row's end. Still one heading stop.
  Beside the 지금 column the dock is 1,080 dp, so the frame never sees this form.

Contrast (CarSkinTest): 사고's label on its tint 4.78 / 4.56, its caption 4.97 / 5.96; 2.12's 16 % night tint left the label
at 4.09, which is why the night tint is 6 %.

**COMPACT, 80 dp** (`HOME_DOCK_COMPACT`): the 2.13 row, unchanged (224-dp lead, 56-dp stadiums, caption beside from 196 dp).

A change of form swaps the dock on `cardSwap()`.

### 3.4 The attention slot

Always one card: the head of the queue (pager, pin and every body as 2.13), or the IDLE card while the queue is empty. The
slot swaps cards on `cardSwap()`; its height follows on the same clock, so a taller card unfolds downward into the sky and
nothing above it moves. The 2.13 `AnimatedVisibility` around the slot went: the slot is never empty.

## 4. The frame function (src/HomeFrame.kt, pure)

```kotlin
enum class HomeForm { TALL, COMPACT }
data class HomeForms(val cards: HomeForm, val dock: HomeForm)
val HOME_FORM_ORDER = [TALL+TALL, TALL+COMPACT, COMPACT+TALL, COMPACT+COMPACT]
fun attentionCardMax(kind: AttentionKind?, pager: Boolean): Dp      // null = the idle card (never a pager)
fun homeFrame(kind: AttentionKind?, pager: Boolean, demo: Boolean, forms: HomeForms): HomeFrame
fun homeForms(kind: AttentionKind?, pager: Boolean, demo: Boolean): HomeForms   // the first pair that fits 1,097
fun homeFrameFor(queue: List<AttentionItem>, demo: Boolean): HomeFrame         // the tallest card in the queue; .forms
fun HomeFrame.skyOn(page: Dp): Dp                                              // the least sky on a page of that height; .skyLeft = skyOn(1,097)
fun homeFallbackForms(columns: Int, short: Boolean): HomeForms
fun homeBarLift(band: Dp, dock: Dp, sky: Dp): Dp
fun dockHeaderOneRow(width: Dp, fontScale: Float): Boolean                     // the tall dock's header in one row (§3.3)
```

- **Order of preference:** TALL cards + TALL dock; TALL cards + COMPACT dock (the dock gives way first: the destinations sit
  where the page's weight is); COMPACT cards + TALL dock; COMPACT + COMPACT.
- **Which card:** `homeFrameFor` takes the tallest card in the queue, with the pager from two items, so paging through the
  queue never changes a form under the driver's finger (an open incident always heads the queue, so this equals the shown
  card's choice in every reachable state).
- **Where it applies:** the FULL window beside the 지금 column at a font of 1x or less (`framed` in `DrivePane`). There the
  greeting also keeps to one line.
- **Debug log:** `HomeFrame` still logs a card taller than its maximum, and a framed page that needs to scroll; the page check
  now waits 400 ms (`HOME_FRAME_SETTLE_MS`) so a 160-ms swap of card or form is not reported.

### 4.1 Card maxima (dp, with pager / without)

| Card | Max | Card | Max |
|---|---|---|---|
| INCIDENT | 477 / 453 | SETUP (2.14) | 259 / 235 (was 267) |
| CHARGE (with the field's error) | 414 / 390 | MONTH_CLOSE | 264 / 240 |
| RECLASSIFY, PURPOSE | 375 / 351 | FINISHED, MATCHED | 264 / 240 |
| PAIRING | 346 / 322 | PERMISSION, CARE | 242 / 218 |
| CHARGE_WAITING | 304 / 280 | CLOSE_REMINDER | 219 / 195 |
| CARD_REVIEW | 287 / 263 | IDLE (2.14) | 240 (never pages) |

SETUP: 48 + 56 + 12 + 31 + 16 + 96 (the tiles) and no action row. IDLE: 48 + 32 + 12 + 53 (headline with the distance) + 23
(one line) + 16 + 56 (운행기록 보기).

### 4.2 The form table, every state (FULL, 1x; total of 1,097; sky = 1,097 - total + 24)

Base totals: TALL+TALL 580 (676 in demo), TALL+COMPACT 520 (616), COMPACT+TALL 476 (572), COMPACT+COMPACT 416 (512), plus the card.

| Card | pager | no demo: forms, total | demo: forms, total |
|---|---|---|---|
| INCIDENT | yes | TT 1,057 | **TC 1,093** |
| INCIDENT | no | TT 1,033 | **TC 1,069** |
| PERMISSION | yes / no | TT 822 / 798 | TT 918 / 894 |
| RECLASSIFY | yes / no | TT 955 / 931 | TT 1,051 / 1,027 |
| CARD_REVIEW | yes / no | TT 867 / 843 | TT 963 / 939 |
| CHARGE | yes / no | TT 994 / 970 | TT 1,090 / 1,066 |
| FINISHED | yes / no | TT 844 / 820 | TT 940 / 916 |
| MATCHED | yes / no | TT 844 / 820 | TT 940 / 916 |
| PURPOSE | yes / no | TT 955 / 931 | TT 1,051 / 1,027 |
| SETUP | yes / no | TT 839 / 815 | TT 935 / 911 |
| PAIRING | yes / no | TT 926 / 902 | TT 1,022 / 998 |
| MONTH_CLOSE | yes / no | TT 844 / 820 | TT 940 / 916 |
| CARE | yes / no | TT 822 / 798 | TT 918 / 894 |
| CLOSE_REMINDER | yes / no | TT 799 / 775 | TT 895 / 871 |
| CHARGE_WAITING | yes / no | TT 884 / 860 | TT 980 / 956 |
| IDLE | (never) | TT 820 | TT 916 |

TT = TALL cards + TALL dock, TC = TALL cards + COMPACT dock. Every state takes TALL + TALL except an open incident in demo
mode, whose dock steps down to the compact row (an incident in demo would be 1,153 / 1,129 with both tall). The worst page
is the demo incident with the pager, 1,093 of 1,097; the emptiest is the idle card, 820 (2.13's idle page was 384).

### 4.3 Outside the frame's guarantee

No 지금 column, a font above 1x, a short window: the page scrolls when it must (as 2.13). `homeFallbackForms`: TALL cards
where the three stand across (EXPANDED and up) and the window is not SHORT, else COMPACT (one card per row); the TALL dock
unless the window is SHORT. The dock keeps 2 x 2 under 560 dp in either form. Without the column `HomeTiles` (예약 when due,
오늘 운행, 이달 운행일지) stays between the slot and the sky.

### 4.4 The follow-up bar

The bar (64 dp, 24 over what it rides on: 88) rides over the foot. `homeBarLift(band, dock, sky)`: over the whole foot (demo
strip, gap, dock) while the sky holds the bar (>= 88), else over the dock alone, across the demo strip (96 dp of strip and
gap hold the 88). It never covers the incident buttons. In demo mode the fullest pages leave 28 to 31 dp of sky, and the bar
would cover the card's buttons; it covers the presenter's strip instead for its few seconds. Without demo the fullest page
(the incident with the pager) leaves 64 dp, so the bar reaches at most the card's 24-dp bottom padding. Where the frame holds,
the decision reads the sky the state leaves on the page the window really gives (`homeFrameFor(...).skyOn(viewport)`), so it
is made at once and the bar never jumps while a card is still unfolding; elsewhere it reads the measured sky (`onSizeChanged`
on the weighted spacer). The lift is read through `derivedStateOf`, so a sky animating does not recompose the page.

Why the page's own height: a FULL window from 600 to 1,208 dp tall keeps the 지금 column and so the frame, but its page is
shorter than 1,097. In 1680 x 1100 (a 988-dp page) the demo card question with the pager is 963 dp and rests: by 1,097 it
would leave 158 dp of sky and the bar would ride over the whole foot, 39 dp into the card (its 24-dp padding and 15 dp of
its buttons); on 988 it leaves 49, and
the bar rides over the demo strip instead (HomeFrameTest checks every demo state on pages from 800 to 1,180 dp). Without
demo there is no lower place for the bar than the dock, so in such a window a page that rests with less than 88 dp of sky
still lets the bar reach the card's foot; that is as 2.13 was, and never happens in the 1,209-dp window.

## 5. The idle card (최근 운행)

Shown only while the queue is empty; kind-less (`AttentionCard(kind = null)`). Pure model `homeIdle(data): HomeIdle`.

| Part | With a finished trip | Without any trip |
|---|---|---|
| Title row | `ic_journal` · "최근 운행" (`IDLE_TITLE`) · quiet tag "확인할 일 없음" (`IDLE_TAG`, spoken `SPOKEN_IDLE_TAG` "지금 확인할 일이 없어요") | same |
| Headline | the trip's row title as 운행기록 writes it (`tripRowTitle`: "수정구 시흥동 → 판교", or "8.0 km · 1분" without an address); spoken without the arrow, the places one by one as 운행기록 reads them (`tripRowTitleSpoken`: "수정구 시흥동, 판교", or "8.0 km, 1분") | "아직 운행이 없어요" (`IDLE_EMPTY`) |
| Figure | the distance at the headline's end (`KmFigure`, as on 목적 확인), only under a route (`tripHasRoute`): without an address the title already is the distance, as 운행기록's own row line rule says | none |
| Line | "10월 29일 (목) 02:26 · 일반 업무 · Kim Jiwoo": `dayLabel`, `hm`, the purpose label (미분류 when unclassified), the driver's name or 사용자 미지정; spoken "10월 29일 (목) 02:26 출발, 일반 업무, Kim Jiwoo" (목적 미분류 when unclassified) | "P에서 D로 바꾸면 기록이 시작돼요" (`IDLE_EMPTY_LINE`), spoken "기어를 P에서 D로 바꾸면 운행 기록이 자동으로 시작돼요" |
| Action | one quiet action "운행기록 보기" (`IDLE_ACTION`): 운행기록, 전체, on that trip's month | none |

The trip: the newest (by start) finished trip of the data set in use: demo trips only in demo mode, real trips otherwise; a
trip still recording is not finished.

## 6. The setup card (시작 설정, first run)

The headline "운행기록부에 들어갈 정보를 등록해 주세요" and the n/3 tag stay. The steps become three tiles in a row (stacked
under 560 dp of card width), each at least 96 dp (`SETUP_TILES`), corner 16, padding 16: `CarStepDisc` (the number; a check
when done; the CURRENT step, the first open one, solid accent with the number in onAccent), 12, the title (titleSmall) over
one line (bodySmall, textMuted; two above 1.05x, `carLineMaxLines`, so a 1.3x font keeps "직원을 등록하면 고를 수 있어요"
whole), 8, a chevron. Each tile is the button for its step; 2.13's two buttons at the bottom went.

| Tile | Line while open | Line when done | Opens |
|---|---|---|---|
| 법인·차량 정보 | 운행기록부 머리말에 들어가요 | "{법인명} · {차량번호}" (as 월간·Excel's preview shows them) | 설정 › 법인·차량 |
| 직원 등록 | ④ 사용자 칸에 들어가요 (spoken "운행기록부 ④ 사용자 칸에 부서와 성명이 들어가요") | 직원 N명 | 설정 › 직원 |
| 운전자 선택 | 오늘 운전하는 직원을 골라요; with no employee: disabled, "직원을 등록하면 고를 수 있어요" | the driver's name | the picker |

Looks: an open tile on `chipFill` inside `chipBorder`; a done tile outlined only; a disabled tile the 2.13 disabled look
(no fill, the outline at 38 %, title, line and chevron muted, the disc idle). Pressable .98 + pressFill, no ripple (not while
disabled). TalkBack: one button per tile, "{title}, {line}" with the state 완료 / 다음 단계 / 직원 등록 뒤
(`SETUP_STATE_*`), disabled where it cannot act.

`setupSteps(data)` returns `SetupStep(label, line, done, enabled, current, target, spokenLine)` with `state`.

## 7. The city on the bottom band (src/Sky.kt)

`carHorizonAt(windowHeight) = ((h - 20 - 140 - 12) / h).coerceIn(.52, .86)`: about .858 in the full window, the horizon at
1,037 dp, 12 dp above the dock's top (1,049). `SkyLayer` measures its own height (a `BoxWithConstraints`) and passes it to
`HomeBackdrop`. The towers now stand just above the dock: they fill the sky between the attention card and the foot, the
space under the 지금 column's last tile, and the bottom of short pages (설정, behind the .34 dim).

What else derives from the horizon, checked:

- `skyScrims`: the bottom scrim's stops follow it (clear to .818, the haze from there to .98, the base colour at 1); still in
  order at both clamps. Nothing to change.
- The haze behind the greeting is horizontal (the lead scrim) and does not read the horizon.
- The sun, the moon and the day clouds rest in the hero band, which is built from sizes: right of the greeting and down to
  the destination row's top (greeting height + 24) in every state, (816, 92)-(1160, 252) on screen; the sun stays at
  (1052.8, 181.5). The tallest tower with its antenna tops out near 610 dp, far below the band. Nothing to change.
- The reflections, the river's lines and the bridge's lamp reflections lie under the horizon: behind the dock in the page
  column, and visible under the 지금 column. Nothing to change.

## 8. Tests

- HomeFrameTest: every kind and the idle card x pager x demo fits 1,097, takes the first fitting pair in the order, matches
  the budget block by block, and keeps the 480 cap; every state is TT but the demo incident (TC); `homeFrameFor` follows the
  tallest card and leaves 28 / 64 / 301 dp of sky (demo incident, incident, idle); the forms' anatomy sums (208, 140, the
  135-dp greeting block); `homeFallbackForms`; the card maxima (SETUP 259, IDLE 240); `homeBarLift`, the bar never past the
  card's 24-dp padding without demo and never at the card in demo; `skyOn` (192 dp for the idle page on a 988-dp page) and,
  in a shorter FULL window, the demo bar deciding by the page it has (the 963-dp card question: 49 dp, over the strip) on
  pages from 800 to 1,180 dp; `dockHeaderOneRow` (from 560 dp, 728 at 1.3x).
- HomeCopyTest: the tall cards' lines at bodyMedium (18 units), the tall dock's header line (40 units) and its narrow form
  (the title with 휴대폰에도 공유, and the caption alone, 26 units each: `DOCK_NARROW_UNITS`), the setup tiles' lines (16
  units) and states, the idle strings; `setupSteps` (empty, staffed, almost, complete, a blank plate, a gone driver);
  `homeIdle` (no trip, a classified trip with its spoken headline, an unclassified trip without a driver or an address and
  so without the figure, demo data only in demo mode).
- LogCopyTest: `tripHasRoute` and `tripRowTitleSpoken` (the places one by one, no arrow; the distance and time without an
  address).
- SceneTest: `carHorizonAt` (.858 at 1,209, 12 dp over the dock, both clamps, an unmeasured window), the scrim's stops at the
  new horizon, the hero band above the city.
- CarSkinTest: 사고's tint (label, caption, outline) in both scenes.
- HomeDashboardTest: the hero band at the destinations' top.

## 9. What the lead must look at on the Pleos emulator

1. **Clean install, by night** (the reviewers' first screen): brand line, greeting, three tall cards, the 시작 설정 card with
   three tiles (1 solid accent, 3 disabled with 직원을 등록하면 고를 수 있어요), the towers standing on the dock, the tall dock.
   Nothing scrolls. Tap each tile: 설정 › 법인·차량, 설정 › 직원; after adding an employee, 운전자 선택 opens the picker.
2. **Idle by day** (no task): the 최근 운행 card with the newest trip, its distance and line; 운행기록 보기 opens 운행기록 on
   that month. A newest trip without any address shows "8.0 km · 1분" as its headline and no figure beside it. With no trip
   at all: 아직 운행이 없어요 and no button. TalkBack reads a route's headline as the places, without "화살표".
3. **Demo mode + 사고** on the dock: the dock swaps to the compact row while the incident card unfolds; nothing scrolls; the
   follow-up bar ("사고 기록됨 · 실행 취소") rides over the demo strip, not over 처리 완료. Close the incident: the dock swaps
   back to the tall form. Check the swap reads well (160 ms) and snaps with animations off.
4. **Demo mode + 충전 시작/종료** (the charge card, 1,090 of 1,097): nothing scrolls; type an amount over 1,000만 원 to show
   the field's error.
5. **The greeting at 11:00-17:59 with Kim Jiwoo**: it stays on one line (stepped down if needed); judge the size.
6. **The sky**: by day, dusk and night, the towers between the card and the dock and under the 지금 column; the haze at their
   feet (from .818); the dusk glow now sits low. The sun in the band right of the greeting. No day cloud fits the 160-dp band
   (HeroClouds' first cloud needs the band down to about 219 dp), as in 2.13 with a card: if clouds are wanted there,
   HeroClouds' offsets are the knob.
7. **Values worth tuning by eye:** the night tint of 사고 (6 %; AA caps it), the dock header's caption colour (textSecondary),
   the tall card's 16-dp side padding and 24-dp vertical padding, the greeting's 30-sp floor, the setup tiles' fills (open on
   chipFill, done outlined only).
8. **Other windows:** EXPANDED with the column (TALL cards and dock, may scroll); a 1.3x font in the full window (TALL, may
   scroll; a long destination line such as 정보 내보내기's wraps to two centred lines, and a setup tile's line to two lines,
   rather than cut); a narrow window (one compact card per row, the tall dock 2 x 2 with its caption under 사고·특이사항); a short window
   (no brand line, compact forms).
9. **TalkBack:** the brand line is skipped; each destination card and each setup tile is one button with its state; the dock
   header is one heading.
10. **The phone** home and sign-in: unchanged sky (horizon .40 / .42 / .60).
