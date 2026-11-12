# SHAREMATE 2.13: round-2 build spec (car only)

Save as docs/design/DESIGN_2_13_SPEC.md. Round 1's spec (docs/design/DESIGN_2_12_SPEC.md) holds wherever this one is silent.

## 0. Base, conventions, rules

- **Base.** `main`. Car sources are as at 1785bcc (HEAD 91085a1 adds only README.md and PhoneAuthUi.kt).
- **Paths.** `src/` = app/src/main/java/ai/sharemate/app/, `test/` = app/src/test/java/ai/sharemate/app/.
- **Files.** LU LogbookUi.kt · HD HomeDashboard.kt · HC HomeCards.kt · FC FleetCards.kt · FU FleetCareUi.kt · MC MonthCloseUi.kt · PT ParkedTasks.kt · VP VehiclePane.kt · DU DriverUi.kt · AS AssistantSheet.kt · DT DemoTutorial.kt · EP ExcelPreview.kt · SK Sky.kt · CR CarReservationsUi.kt · CU CarUi.kt · LY Layout.kt.
- "=" keeps the text. Contrast is day / night on `card`.
- **Verified metrics (v9.png, px = dp).** Window 1680 x 1209. Header row 84 (LU:607). Page slot 1112 x 1097 (LU:395). 지금 column 392. One text line is round(.952 s) + round(.241 s) dp tall (Asta Sans hhea 952 / -241): 16 sp → 19, 18 → 21, 19 → 23, 20 → 24, 21 → 25, 24 → 29, 26 → 31, 30 → 36, 44 → 53. Each further line adds the style's line height. These reproduce the shot exactly (사용자 295, 예약 84, 오늘 운행 199, 이달 운행일지 172).
- **Rules (binding).** No clock anywhere in the app. One search bar in the header on every page. 홈 and the 지금 column never scroll in the FULL window at 1x. One money style. The phone does not change. Reuse CarSkin and CarUi; one accent for the current choice; one line per supporting text with the long form in TalkBack; every function stays reachable; day and night; AA; targets of 56 dp or more; no ripple; reduced motion snaps; nothing composes while driving (LU:362). Copy in 해요체, no final period, no space before a particle.

## 1. Foundation (package F2)

### 1.1 New in CarUi.kt

```kotlin
@Composable internal fun Modifier.carSelection(selected: Boolean, shape: Shape, outlined: Boolean = false): Modifier
@Composable internal fun CarChoiceTile(title: String, line: String, onClick: () -> Unit, modifier: Modifier = Modifier,
    selected: Boolean? = null, dot: Color? = null, description: String? = null)
@Composable internal fun CarChoiceChip(label: String, selected: Boolean, onClick: () -> Unit)             // FU:385 ChoiceChip, moved
@Composable internal fun CarStepDisc(number: Int, done: Boolean, idle: Boolean = false, size: Dp = 32.dp) // MC:436 StepDisc, moved
@Composable internal fun CarDialogFade()                                                                 // DU:306-310, moved
@Composable internal fun CarDialogFrame(title: String, onDismiss: () -> Unit, maxWidth: Dp = 600.dp, content: @Composable ColumnScope.() -> Unit)
@Composable internal fun CarDialog(title: String, onDismiss: () -> Unit, icon: Painter? = null, lines: List<String> = emptyList(),
    spoken: String? = null, body: (@Composable ColumnScope.() -> Unit)? = null, buttons: @Composable RowScope.() -> Unit)
internal fun dialogLines(text: String): List<String>
```

- **`carSelection`.** The current row of a list. Selected: `chipFill` under a 2-dp `accentInk` ring; the name stays `Scene.text`; a check is `Icons.Filled.Check` in accentInk. Not selected: nothing, or a 1-dp `chipBorder` when `outlined`. Ring on chipFill 4.39 / 7.18, beside the card 4.93 / 7.66. On chipFill: text 15.49 / 15.00, textSecondary 7.19 / 9.27, textMuted 5.19 / 5.88, negative 4.99 / 4.51, positive 5.53 / 8.51, warn 6.06 / 8.53, chipBorder 3.10 / 3.46. A card that holds buttons takes the ring only. `Scene.selectionFill`, `selectionRing` and glow-as-selection leave the car.
- **`CarChoiceTile`.** A choice among options. 72 dp, corner 16, `.98` + pressFill. Rest: 1-dp chipBorder; a 10-dp `dot` · 8 · title (labelLarge, text) over the line (bodySmall, textMuted), centred. `selected == true`: accent fill, both lines onAccent, a 16-dp check for the dot, pressOnAccent. `selected != null` makes it a radio button; null a button. TalkBack: "{title}, {line}" or `description`.
- **`CarDialog`.** `Dialog(usePlatformDefaultWidth = false)` with the fade; `card`, 1-dp cardBorder, corner 28, padding 28, `paneTitle = title`. Title row (`CarSectionTitle(title, icon)`), 12, the lines (bodySmall, textSecondary 8.07 / 9.88; one node), `body`, 24, the buttons. Lines fit one line at 600 dp (`DIALOG_LINE_UNITS = 30`) and may take two. Buttons are equal halves of 56 dp, the way on at the right; they stack under 400 dp of content or above a 1.05x font. Acknowledge: `PrimaryAction("확인")` full width. Decide: `QuietAction` · `PrimaryAction`. Destroy: `QuietAction("취소")` · `QuietAction("삭제", color = Scene.negative)`, never the accent. Read only: `QuietAction("닫기")`. Back and a tap beside the card dismiss.
- **`dialogLines`.** One sentence per line, no final period: split at ". ", "? " or "! ", drop one trailing period. Repository messages stay as written.
- **Defaulted additions.** `CarTag(dot: Color? = null)` (a 10-dp dot in that colour; the label takes `Scene.text`). `CarSectionTitle(live: Boolean = false)` (polite live region on the title). `CarMiniStat(…, muted: Boolean = false)` and the overload `CarMiniStat(icon, label, won: Long?, modifier)`.

### 1.2 Money (new CarMoney.kt): see §5

### 1.3 Edits in place

| What | Where | Change |
|---|---|---|
| Disabled primary | CU:117-122, LU:869-879 | No fill, 1-dp chipBorder at 38 %, textMuted label (5.82 / 6.27). It read as a field (v4 카드 저장, v13 저장된 기준) |
| Loading button | LoadingUi.kt:115-136 | Keeps the accent while loading, ignores taps, `semantics { disabled() }`; 진행 중 and the live region = |
| Field focus | LU:1569 | `focusedBorderColor = if (isCar()) Car.skin.accentInk else Scene.glow` (4.93 / 7.66) |
| `PurposeChip` | LU:917-929 | `if (isCar()) { CarTag(purpose.label, dot = purposeColor(purpose)); return }`; the dots are ≥ 4.0 on tagFill |
| Purpose tiles | LU:1069-1102 | `PurposeTile` goes. `PurposeOptions` draws three `CarChoiceTile`s (dot = `purposeColor`), across from 420 dp (was 480), stacked below. Labels and captions (업무 거리 포함 · 업무 합계 제외) = |
| Visibility | LU:1298, AS:86, HD:852-903 | `MonthStepButton`, `SHEET_SCRIM_ALPHA`, `ReservationTile`, `TodayTile`, `MonthTile` become internal; the three tiles gain `modifier: Modifier = Modifier` |
| Card gap | LY:74-75 | `cardGap = 16.dp` at EXPANDED and FULL (were 20 and 24): the mock's one gap |
| Follow-up data | LU:151 | `FollowUp(message, action, perform, second: FollowUpAction? = null)`; `data class FollowUpAction(val label: String, val perform: suspend () -> Unit)` |
| Drawables | res/drawable | ic_won: `M4,6 L8,18.5 L12,6 L16,18.5 L20,6 M3,11.5 L21,11.5`. New car-only ic_search_bar: ic_search at stroke 1.6 (ic_search itself keeps 1.8, because the phone header draws it too). New ic_warning: `M12,4.5 L21,19.5 L3,19.5 Z M12,10.5 L12,14.5 M12,17 L12,17.1` (same family as ic_calendar) |

### 1.4 Seams (mechanical, no visible change)

So that H alone owns `LogbookApp`:
- LU:252-258 becomes `internal fun CoroutineScope.showFollowUp(snackbar: SnackbarHostState, act: Act, item: FollowUp)`.
- LU:427-431 becomes `@Composable private fun BoxScope.ShellFollowUpBar(snackbar, pageWidth: Dp, lift: Dp)`.
- LU:439 and :441-453 become `@Composable private fun ShellOverlays(…)`; the picker stays inside the `LocalFollowUp` provider.
- `info` holds the saved file's path alone (LU:338); the dialog composes today's sentence around it. `SettingsPane`'s callback (LU:410) becomes `{ text -> followUp(FollowUp(text)) }`.
- `LogPane` gains `openReport: (YearMonth) -> Unit = {}`; LU:404 passes `goReport`.

## 2. Header bar (package H)

```kotlin
@Immutable internal data class HeaderPlan(val oneRow: Boolean, val statusInHeader: Boolean)
internal fun headerPlan(layout: LayoutTokens, nowColumn: Boolean) = HeaderPlan(oneRow = nowColumn || layout.full, statusInHeader = !nowColumn)
internal enum class SearchBarMode { EXAMPLE, PHRASE, LABEL, ICON }
internal fun searchBarMode(width: Dp, fontScale: Float): SearchBarMode
internal fun headerChipMax(cluster: Dp, fixed: Dp, fontScale: Float): Dp   // (cluster − fixed − 16 − 280·s).coerceAtLeast(56), s = max(1, fontScale)
```

**Rows.** The cluster order is the same everywhere: bar · 시연 체험 · 사용자 chip · status pill.

| Window | Row 1 | Row 2 |
|---|---|---|
| With the 지금 column | mark · tabs · bar to the rail's end (over the column too) · 시연 체험 (홈 only, rule LU:583 =) | none |
| FULL short | mark · tabs · bar · 시연 체험 · chip · status | none |
| EXPANDED without the column, MEDIUM, COMPACT | mark · tabs (scrolling below EXPANDED) | bar · 시연 체험 · chip · status |

Heights stay 84 (76 short) and 152 (136).

**`HeaderCluster`** is a small custom layout (a Row cannot make two children give way in turn). It measures the status pill and 시연 체험 at their own widths (`fixed`, with their 16-dp gaps), then the chip within `headerChipMax` (its three modes, LU:491, =), and gives the bar the rest.

| Mode | Bar width (x font scale, at least 1) | Shows |
|---|---|---|
| EXAMPLE | ≥ 496 | icon · 운행 비서에게 물어보세요 · 예: 주차비 4,500원 추가해줘 |
| PHRASE | ≥ 280 | icon · 운행 비서에게 물어보세요 |
| LABEL | ≥ 160 | icon · 운행 비서, at its own width at the row's start (`HeaderTool`) |
| ICON | < 160 | the 56 x 56 icon |

- **Minimum as a bar:** 280 dp. The chip steps down to its avatar before the bar drops to LABEL.
- **Maximum:** none. It ends at the rail's end or at the next pill.
- **FULL with the column** (tab pill 628, 시연 체험 102): 824 → 706 with 시연 체험 → 600 with three badges → 526 at worst. Always EXAMPLE.
- **Elsewhere:** 672 in a 1120-dp window. In a 600-dp window the chip is the avatar and the bar keeps 312.

**Anatomy.** A 56-dp stadium on `card` with a 1-dp chipBorder; never the accent (it marks the chosen tab beside it). Padding start 20, end 24. ic_search_bar 22 dp in `Car.skin.icon` · 12 · the phrase (bodyLarge, textSecondary 8.07 / 9.88) · 12 · the example (bodyMedium, textMuted 5.82 / 6.27, weight 1, ellipsis). One line. Press `.98` + pressFill. It is a button, not a field: it calls `onAssistant` (LU:384) and opens the sheet (LU:441). TalkBack in every mode: "운행 비서에게 물어보세요, 예: 주차비 4,500원 추가해줘", button, click label 운행 비서 열기.

**Deleted.**
- LU: `ClockText` and `headerClock` (:120-136), `HeaderPlan.clockInline` (:507-510), `clockFitsInline` (:512-517), `HEADER_CHIP_RESERVE` (:526-530), `clockText` (:551), the search icon tool (:581-582), the `clock` lambda and its calls (:589-596, :625, :635, :644), `HeaderClock` (:652-676).
- HD: `AssistantSearch` (:545-567), its call (:528), `HomeHero`'s `onAssistant`. LU: `DrivePane`'s `assistant` (:973, :403).

## 3. 홈 without scroll (package H)

### 3.1 Frame and budget (FULL, 1x, with the column)

Order: greeting block → attention card → sky → bottom band (demo strip, destination row, incident dock). The band never moves, so the incident buttons are always in the same place.

| Slot | dp | Kind |
|---|---|---|
| Header | 84 | fixed |
| Page padding | 28 | fixed (8 + 20) |
| Greeting block | 160 | minimum; content is 8 + greeting 53 (105 on two lines) + 8 + status 24 = 93 or 145 |
| Gap | 24 | only with a card |
| Attention card | 0 to 477 | content; cap 480 |
| Sky | ≥ 24 | 24 fixed + weight 1 |
| Demo strip + gap | 80 + 16 | demo only |
| Destination row | 104 | fixed (`heightIn(min = 104.dp)`) |
| Gap | 16 | fixed |
| Incident dock | 80 | fixed (12 + 56 + 12) |
| **Sum of maxima** (card at 480) | **1,096** | ≤ 1,209: 113 dp to spare, 209 without demo |

```kotlin
BoxWithConstraints { val viewport = maxHeight
  Column(Modifier.fadingTopEdge(scroll).verticalScroll(scroll, enabled = scroll.maxValue > 0)) {
    Column(Modifier.heightIn(min = viewport)) {
      HomeGreeting(Modifier.heightIn(min = HOME_GREETING_MIN)); AttentionSlot(Modifier.padding(top = 24.dp))
      Spacer(Modifier.height(HOME_SKY_MIN)); Spacer(Modifier.weight(1f))        // the sky
      DemoStrip(); DestinationRow(); IncidentDock()                              // 16 apart
    } } }
```

Under an unbounded maximum a Column weighs against its minimum, so the spacer takes `viewport − content` and the band sits on the page's bottom edge. A card that unfolds (260 ms, `cardSwap`; a snap under reduced motion) only shortens the sky.

**Why nothing clips.** Scrolling is off while the content fits; if it ever did not, the same column scrolls rather than cuts. `homeFrame` is unit-tested against 1209 dp for every card kind, with pager and demo, and the composables use its constants. Data is line-capped (headline 1 line, incident note 1, charge chips 2 rows). A debug build logs `HomeFrame` when `scroll.maxValue > 0`, or a card is taller than `attentionCardMax`, at FULL 1x.

### 3.2 The attention slot

**Queue** (pure `attentionQueue`), most urgent first: INCIDENT · PERMISSION · RECLASSIFY · CARD_REVIEW · CHARGE · FINISHED · MATCHED · PURPOSE · SETUP · PAIRING · MONTH_CLOSE · CARE · CLOSE_REMINDER · CHARGE_WAITING.
- It is `parkedTasks`' order (PT:71-80) with the stack's extras (HC:94-106), the two banners, the permission prompt and the waiting charge folded in.
- Two changes from today: an open incident leads a reopened purpose question; 방금 운행 waits behind card and charge questions instead of being dropped.
- PERMISSION while the signal is DENIED. MATCHED while the shell holds a batch (LU:283). CLOSE_REMINDER only when no MONTH_CLOSE task exists and this month's reminder is not closed. CHARGE_WAITING while `hiddenPendingCharge` is not null.
- Labels: 특이사항 대응 · 차량 권한 · 목적 바꾸기 · 카드 확인 · 충전 비용 · 방금 운행 · 카드 반영 · 목적 확인 · 시작 설정 · 휴대폰 연결 · 월 마감 · 차량 관리 · 월 마감 · 충전 비용. The eight shared kinds equal `TaskKind.label`, so the driving screen's last line (PT:91) still names what the parked 홈 shows.

**Reaching the rest.**
- From two items the title row ends in `‹ CarTag("1/3") ›`: `MonthStepButton`, 56-dp targets, a dead arrow at 38 %.
- The status line under the greeting names the queue.
- `AttentionPin(item, head)` holds the driver's page. `attentionIndex(queue, pin)`: 0 without a pin or when the queue's head is no longer `pin.head`; else the index of the item with the pin's kind and id; a FINISHED pin follows the RECLASSIFY item of the same trip; else 0.
- TalkBack: tag "확인할 일 3건 중 1번째"; arrows "다음 할 일, 카드 확인" and "이전 할 일, …".

**`AttentionCard`** = `CarCard(padding = 24.dp)`:
- Title row: icon · title · state tag · pager. 32 dp, 56 with the pager. The title is the card's one heading.
- 12, then the headline (headlineSmall, 1 line) with a figure at its end (53 dp at most).
- 4, then a `CarLine` (twice at most); `spoken` carries the long form; no ⓘ that unfolds.
- 16, then at most one body block.
- 16, then the action row (56 dp): the start holds one line, link or field; the end at most two buttons.

| Kind | Title (icon) · tag | Headline · figure / lines | Body | Row: start · end | Max |
|---|---|---|---|---|---|
| INCIDENT | 사고 … (ic_warning) · 대응 중 · 14:29 (NEGATIVE) | 지금 할 일 / `incidentHint` | `incidentSteps` to the letter (28-dp `CarStepDisc` in 32-dp rows, bodyLarge); note, 1 line; `PhotoStrip(size = 56.dp)` | `incidentFacts` line · 기록 삭제 · 처리 완료 | 477 |
| PERMISSION | 차량 권한 (Lock) · 권한 필요 (NEGATIVE) | 차량 정보 권한이 필요해요 / 계기판 누적거리를 읽어 자동으로 기록해요 | none | 차량 정보 권한 허용 | 242 |
| RECLASSIFY, PURPOSE | 목적 바꾸기 / 목적 확인 (ic_journal) · `purposeCardTag` | 어떤 목적이었나요? / 운행 목적만 확인해 주세요 · km / `tripWhenLine` · route; `autoPurposeProgress` | `PurposeOptions` (no suggestion, after 다른 목적, always when reclassifying) | person link · 다른 목적 or 그대로 두기 · the suggested purpose (PT:121 =) | 375 |
| CARD_REVIEW | 카드 확인 (ic_receipt) · N건 · 한 건씩 | `chargeReviewHeadline` · `CarAmount(HERO, PENDING)` / {가맹점} · 11/2 13:30 · 주차; 09:00 출발 · 12.4 km · 강남 오피스 | none | 판단 근거 · … · 회사 비용 아님 · 맞아요 | 287 |
| CHARGE | 충전 비용 / 주유 비용 (ic_won) · 감지 · 14:05–14:35 (POSITIVE) | 충전 비용만 알려 주세요 / 배터리 40% → 80% · 30분 · 계기판 51,300.0 km | `CarExampleChip`s, 2 rows at most: 비용 없음 · earlier amounts (PT:174 =) · 10,000원 · 20,000원 · 30,000원 · 50,000원 | `CarMoneyField("직접 입력")` · 등록 · 카드 승인 기다리기 | 390 (414 with the field's error) |
| FINISHED | 방금 운행 (ic_vehicle) · 09:30 – 10:00 · 30분 | 일반 업무로 자동 분류했어요 · km / `autoFillMessage` (=) | none | 통행료 3,600원 반영 · 한국도로공사 (HC:274 =), else link 비용 추가 · 다른 목적 · 확인 | 264 |
| MATCHED | 카드 반영 (ic_receipt) · N건 | 카드 결제 N건을 운행에 반영했어요 · `CarAmount(HERO)` / 내역에서 한 건씩 되돌릴 수 있어요 | none | 닫기 · 내역 보기 | 264 |
| SETUP | 시작 설정 (ic_settings) · 0/3 | 운행기록부에 들어갈 정보를 등록해 주세요 | three steps across (`CarStepDisc` + label): 법인·차량 정보 · 직원 등록 · 운전자 선택 | the first two open steps: 법인·차량 입력 · 직원 추가 · 운전자 선택 | 267 |
| PAIRING | 휴대폰 연결 (ic_phone) · 4:32 남음 | 연결 번호 · the code (displaySmall, tnum) / `PHONE_HOW` | 주소 row; 인증키 row, never cut | `PHONE_NOTE` line · 번호 다시 만들기 or 새 연결 번호 | 346 |
| MONTH_CLOSE | 월 마감 (CheckCircle) · N건 | `monthCloseHeadline`, the 10-dp bar 12 dp under it / `monthCloseTaskLine` | none | link 월간에서 보기 · 정리하기, 확인하기 or {10월} 마감 | 264 |
| CARE | 차량 관리 (ic_calendar) · 곧 만기, or 기한 지남 (NEGATIVE) | `alert.title` / `alert.detail` | none | `careTaskNote` line · 7일 뒤 다시 · 검사 완료 (`careDoneLabel` =) | 242 |
| CLOSE_REMINDER | 월 마감 (CheckCircle) · 마감 전 | `monthCloseBannerText` (=) | none | 닫기 · 정리하기, 확인하기 or 마감하기 | 219 |
| CHARGE_WAITING | 충전 비용 (ic_won) · 카드 승인 대기 | 카드 승인을 기다리고 있어요 / `chargeWaitingShort` | while autopilot is on: 이달 완성 · 목적 자동 분류 · 카드 자동 반영 (`CarMiniStat`) | 지금 입력 | 304 |

Maxima include the pager; without it each is 24 less. Sums: card padding 48, title 56, 12, headline 31 or 53, 23 per line, 16 + body, 16 + 56.

**Behaviour that changes with the cards.**
- **INCIDENT.** The accident tint goes (cards are opaque). The photo × asks first: `CarDialog("사진을 지울까요?", lines = ["지운 사진은 복구되지 않아요"])`, 취소 · 삭제. Deleting a record with photos keeps its question (§8).
- **PURPOSE.** The person link shows `driver.label`, or 사용자 미지정, behind a Person glyph; it opens the picker for that trip. Spoken "이 운행의 운전자 바꾸기, Sales · Kim Jiwoo" or "이 운행의 운전자 지정, 사용자 미지정".
- **CARD_REVIEW.** `chargeReviewHeadline`: the matcher's trip → 이 운행의 비용이 맞나요?; the nearest-trip guess (`charge.tripId == null`, CardMatch.kt:401) → 가장 가까운 운행의 비용이 맞나요?; no trip → 어느 운행의 비용인지 찾지 못했어요. 맞아요 answers "{가맹점} 4,500원을 13:30 운행에 넣었어요 · 회사 비용 아님". 회사 비용 아님 answers "{가맹점} 4,500원을 회사 비용에서 뺐어요 · 실행 취소" (undo = `confirmCardCharge`, only with a trip).
- **CHARGE.** 등록 needs `input.ok`; the field clears on success only (today it clears, then the repository's error dialog opens for an amount over 1,000만 원, FC:403). 등록 is spoken "등록, 최근 운행의 주유·충전 경비로 들어가요". The two footnotes (FC:410-411) go.
- **MATCHED.** 내역 보기 opens 자동 반영 on the month of the batch's first payment (today: this month, LU:1034).
- **SETUP.** Each button opens its own place: 설정 › 법인·차량, 설정 › 직원, the picker (`goSettings` becomes `(Int) -> Unit`).
- **MONTH_CLOSE.** 정리하기 opens 점검 필요 of that month; 확인하기 pins 카드 확인; {10월} 마감 is the `LoadingAction` with today's answer (MC:294). The full stepper is one tap away (월간에서 보기).
- **CARE.** 7일 뒤 다시 answers "7일 뒤 다시 알려요 · 실행 취소" (`withoutCareSnooze`). 완료 answers 검사 완료로 기록했어요 / 보험 만기일을 지웠어요 / 리스·렌트 만기일을 지웠어요 with two actions, 새 기한 입력 (설정 › 법인·차량) · 실행 취소; 정비 = (FU:498).
- **CLOSE_REMINDER.** The same way on as MONTH_CLOSE (`monthCloseNext`): 정리하기 opens 점검 필요 of that month while trips are open; 확인하기 pins 카드 확인 when only card questions are left (점검 필요 lists trips alone, so it would be empty); 마감하기 opens 월간·Excel. 닫기 hides it for this month, as × does today.
- **Follow-up words.** An incident answers "{종류} 기록됨 · 실행 취소" (was 실수예요). 처리 완료 answers 실행 취소 (was 되돌리기); a 고장·경고 offers 남기기 · 실행 취소.

### 3.3 What each state shows

| State | Status line | Slot | Page used (of 1097) |
|---|---|---|---|
| No task | 출발하면 자동으로 기록해요, or 오늘 정리 끝 · 다음 운행도 자동 기록 | empty: the sky | 384 |
| One task | the car's state | its card, no pager | up to 861 (incident) |
| Several | 확인할 일 3건 · 카드 확인 → 목적 확인 → 차량 관리 | the head + pager | up to 885 |
| Banners | as above | MATCHED or CLOSE_REMINDER, in the queue | 672 / 627 |
| First run | the car's state | SETUP; 시연 체험 in the header | 651 |
| Permission | 차량 정보 권한이 있어야 기록해요 | PERMISSION | 626 |
| Demo | 시연 주행을 15배속으로 기록해요 | the queue; the controls are the demo strip | + 96; worst 981 |
| Recording | 09:12 출발 · 3.2 km, updated in place | the queue as it stands, still answerable | as the queue |
| Charge | the car's state | CHARGE, or CHARGE_WAITING while the card feed is awaited | 822 at most / 712 |

`homeStatusLine` precedence: a recording trip · a queue of two or more · demo · the signal (DENIED, STARTING, else 차량 데이터를 받을 수 없어요 · 연결을 확인해 주세요) · tidy · ready. `tidy` = empty queue, a trip finished today, signal READY.

### 3.4 Greeting, band, sky

- **Greeting.** displaySmall Bold, up to 2 lines, `homeGreeting` =. One status line 8 dp under it (bodyLarge, textSecondary, `carLineMaxLines`). Both keep to the 720-dp reading measure. The brand line (HD:513-516), the 8-dp inset (HD:491) and `heroMin` go.
- **Destination cards** (184 → 104). `CarCard(onClick, padding = 20.dp)`: `CarSectionTitle(title, icon, chevron = true, trailing = CountBadge)` over `CarLine(line)`. Titles, `homeCardLines` and TalkBack =; click label 열기. Three across from EXPANDED at every font (360 dp each at FULL), one per row below. `homeCardRows` and the 2 + 1 rule go. The 차량 정보 line no longer drops a snoozed deadline (LU:1007 passes no snoozes).
- **Incident dock** (147 → 80). `CarCard(padding = 12.dp)`, one row: a 224-dp lead (ic_warning · 사고·특이사항 over one line), then four 56-dp outlined stadiums at weight 1 (204 dp at FULL). 사고 has its label and border in `Scene.negative` (5.60 / 4.81). The caption follows the label on the same line from 196 dp of button width (x font scale); narrower, it sits under the label in a 72-dp button. Lead line: 바로 기록 · 휴대폰에도 공유, or 누르면 바로 기록돼요 with sync off. Under 560 dp the lead takes its own row above 2 x 2 buttons.
- **Demo strip.** `CarCard(padding = 12.dp)`, 80 dp, above the destination row: `CarTag("시연 모드", DEMO)`, then the three buttons with today's labels, weights and rules (LU:1063-1065).
- **Follow-up bar.** On 홈 at rest it rides above the band (bottom padding 24 + the band's height), as it does over the save bar. The sky is at least 137 dp; the bar needs 88. It never covers the card or the incident buttons.
- **Sun and moon.** `heroBand(width, firstCardTop, 720, 0, 0, 0, 24)`, from sizes only. With a card: (744, 0)–(1088, 160); the sun stays at (1052.8, 181.5) in the window and needs the band down to 152.7, which is why the block is 160. Idle: down to 873 (777 in demo). `Skyline` gains `cloudAlphaFor: ((Int) -> Float)? = null`; `HeroSkyline` (SK:487) fades each day cloud in and out with the band in 220 ms (a snap under reduced motion). The phone passes nothing.

### 3.5 Fallbacks

The same column in every window; it scrolls only when the content exceeds the viewport.
- **EXPANDED with the column** (page 776–879). A badge may wrap under a destination title (+30) and the dock takes its two-line buttons (+16). The worst case is about 1,140 dp: it still fits the 1209-dp window. Bar 488–591.
- **1.3x in the full window.** The greeting takes headlineLarge; CarLines take two lines; cards stay three across. It scrolls only under the tallest cards.
- **No column.** Two-row header. No greeting minimum and no sun. Between the card and the sky: the 예약 tile while a booking is in use or starts within a day (`reservationDueWithinDay`), then 오늘 운행 | 이달 운행일지 (the column's tiles; stacked below EXPANDED). Idle fits a 1120 x 806 window; a card makes it scroll.
- **SHORT.** One 76-dp header row at FULL; the page scrolls.
- **Keyboard.** While it is up the window is shorter (`safeDrawingPadding`, LU:352): the column scrolls and brings the field into view.

### 3.6 Copy that changes on 홈

| Where | Current | New |
|---|---|---|
| LU:582, HD:558 | 운행 비서 열기 (. 예: …) | name 운행 비서에게 물어보세요, 예: 주차비 4,500원 추가해줘; click label 운행 비서 열기 |
| LU:658-676 | 11월 9일 (월) 22:20 | removed |
| HD:515 | SHAREMATE · FLEET JOURNAL | removed |
| HD:294 | 시연 주행은 15배속으로 기록되고 ‘시연 데이터’로 구분돼요. | 시연 주행을 15배속으로 기록해요 |
| HD:295 | 09:12 출발 · 3.2 km · 주차하면 … | 09:12 출발 · 3.2 km |
| HD:296 | 출발하면 자동으로 기록하고, 주차하면 … | 출발하면 자동으로 기록해요 |
| HD:297 | 계기판 누적거리를 읽으려면 … | 차량 정보 권한이 있어야 기록해요 |
| HD:298, 299 | 차량과 연결하고 있어요. / 차량 데이터를 받을 수 없어요. 연결 상태를 … | 차량과 연결하고 있어요 / 차량 데이터를 받을 수 없어요 · 연결을 확인해 주세요 |
| HC:222 | 정차 후 정리 1/3 · 충전 비용 → 목적 확인 | 확인할 일 3건 · 충전 비용 → 목적 확인 → 카드 확인 (three names, then 외 N건) |
| HC:336-350 | 카드 결제 N건 {원}을 운행에 자동 반영했어요 · × | the MATCHED card; 닫기, spoken 자동 반영 알림 닫기 |
| MC:215-217 | × | 닫기, spoken 이달 마감 안내 닫기 |
| FC:565-589 | 이달 운행일지 87% 완성 … | the CHARGE_WAITING card; `chargeWaitingLine` (PT:236) is its `spoken` |
| LU:1108 | 지금 운전하는 직원 선택 | 운전자 선택 |
| LU:1112 | 운행기록부 양식에 들어갈 정보를 한 번만 등록해 주세요 | 운행기록부에 들어갈 정보를 등록해 주세요 |
| LU:1126 | 설정 열기 | 법인·차량 입력 · 직원 추가 |
| HC:319-321 | 운전자 Sales · Kim Jiwoo / 미지정 · 변경 | the person link; empty 사용자 미지정 |
| FC:129 | 실수예요 | 실행 취소 |
| FC:134 | 사고·특이사항 · 한 번 누르면 차량 정보가 바로 기록돼요 | title + line; the old sentence is spoken |
| FC:175 | 되돌리기 | 실행 취소 |
| FC:219-222 | Stats 계기판 · 운전자; 위치 | 계기판 51,300.0 km · 운전자 Kim Jiwoo · {주소} (운전자 미지정, 내비 주소 미수신) |
| FC:226 | 사진 N장 · 휴대폰에서 촬영해 차량에 저장됐어요. | 사진 N장 · 휴대폰에서 찍어 차량에 저장했어요 |
| FC:227 | 휴대폰 SHAREMATE에 같은 기록이 … | 사진과 메모는 휴대폰 앱에서 덧붙여요 |
| FC:228 | 사진·메모는 휴대폰에서 … 켜 주세요. | 사진·메모는 휴대폰 연동을 켜면 덧붙일 수 있어요 (spoken adds 설정 › 기록에서 휴대폰 실시간 연동을 켜 주세요) |
| FC:385 | 이 차가 지난번에 낸 금액 | removed: the chips say it |
| FC:402 | 직접 입력 (원) | 직접 입력 |
| FU:460, 470 | guidance and 완료를 누르면 … | 완료하면 검사 기록을 남기고 만료일을 지워요 · 완료하면 등록된 만기일을 지워요 · 완료하면 지금 계기판 값이 마지막 정비로 남아요; the old sentences are spoken |
| FU:503, 507, 511 | 검사 완료 · 다음 검사일은 설정 › … | 검사 완료로 기록했어요 · 보험 만기일을 지웠어요 · 리스·렌트 만기일을 지웠어요, each with 새 기한 입력 · 실행 취소 |

`HeaderCopy` and `HomeCopy` (new HomeCopy.kt) hold every static string of §2 and §3 with its `SPOKEN_*` form.

## 4. 지금 column at FULL (package P)

| | No question | Question | Question on two lines |
|---|---|---|---|
| 사용자 (212 with nobody chosen or no 직원) | 171 | 295 | 323 |
| 예약 · 오늘 운행 · 이달 운행일지 | 84 + 199 + 172 | = | = |
| Gaps 3 x 16, bottom 8 | 56 | = | = |
| **Total** (≤ 1097) | **682** | **806** | **834** |

No layout change. `NowColumn` (HD:836) takes `verticalScroll(scroll, enabled = scroll.maxValue > 0)`. `MonthTile` draws 완성 and 마감됨 in `Scene.positive` (6.21 / 9.08), as 월간 does; 확인 N건 keeps `warn`. 등록 경비 becomes the money mini stat (§5).

## 5. Money: one style (F2 builds, every package applies)

```kotlin
enum class MoneySize { HERO, TOTAL, ROW }
enum class MoneyTone { NEUTRAL, POSITIVE, PENDING }
fun moneyText(won: Long?, missing: String): MoneyText            // pure: digits, unit, zero, spoken
@Composable fun CarAmount(won: Long?, modifier: Modifier = Modifier, size: MoneySize = ROW, tone: MoneyTone = NEUTRAL, missing: String = "—")
@Composable fun CarMoneyRow(label: String, won: Long?, modifier: Modifier = Modifier, line: String? = null, total: Boolean = false,
    tone: MoneyTone = NEUTRAL, missing: String = "—", lead: (@Composable () -> Unit)? = null, action: (@Composable RowScope.() -> Unit)? = null)
@Composable fun CarMoneyField(label: String, digits: String, onDigits: (String) -> Unit, input: MoneyInput, modifier: Modifier = Modifier, maxDigits: Int = 8, done: Boolean = false)
data class MoneyInput(val won: Long?, val ok: Boolean, val error: String?, val helper: String)
fun moneyInput(digits: String, min: Long, max: Long, hint: String, reading: Boolean = false): MoneyInput
fun moneyDigits(input: String, maxDigits: Int): String?          // digits only, no leading zeros; null past the limit: the edit is ignored
fun groupDigits(digits: String): String                          // "32000000" → "32,000,000"
fun wonReading(won: Long): String                                // 3,200만 원
fun wonLimit(max: Long): String                                  // 1,000만 원까지 입력할 수 있어요
fun wonShownOffset(n: Int, raw: Int) = if (raw <= 0) 0 else raw + (n - 1) / 3 - (n - raw) / 3
fun wonRawOffset(n: Int, shown: Int): Int { val head = n - 3 * ((n - 1) / 3); return if (shown <= head) shown else shown - (shown - head + 3) / 4 }
object WonTransformation : VisualTransformation                  // groupDigits and the two mappings
```

**Amount.** One `BasicText` of an AnnotatedString. Digits carry `fontFeatureSettings = "tnum"`. 원 is a Medium span on the same baseline, never under 16 sp, with no space before it. One line, end-aligned, never ellipsised: it steps down to 70 % (`TextAutoSize`). It never animates.

| Size | Digits | 원 | Use |
|---|---|---|---|
| HERO | displaySmall 44 | 21 sp | a 홈 card's figure |
| TOTAL | headlineMedium 30 | 19 sp | a total row |
| ROW | titleMedium 21 | 16 sp | a row's amount |
| mini stat | titleSmall 19 | same size | `CarMiniStat(won = …)` |

**Colour.** NEUTRAL: digits `text` 17.38 / 16.00, 원 `textMuted` 5.82 / 6.27. POSITIVE (`Scene.positive` 6.21 / 9.08): only the 비용 참고 result while it exceeds 기록부 없을 때. PENDING (`Scene.warn` 6.80 / 9.09): an amount that waits for an answer (카드 확인). Zero and missing are derived, never passed: `textMuted`, Medium. A tone never stands alone, and a card holds one at most.

**Zero and missing.** A fixed slot shows 0원, muted. A list leaves zero rows out; a list without rows shows its one empty line and no total. No amount (`null`) shows `missing`.

**Rows.** `CarMoneyRow` is a `CarRow`: label, optional line, then the amount and an optional pill; the label gives way. A list is rows, a `CarDivider`, then the `total` row (TOTAL size, same right edge). One TalkBack stop: "주유·충전, 45,000원".

**Words.** Rate: km당 150원, never 원/km. A count with its sum: 2건 · 8,100원. Limits: 1,000만 원 (a space after 만 or 억 only). A tag never holds a sum. Sentences use `formatWon` with the particle attached (4,500원을). No ₩ in text.

**Field.** Its own `OutlinedTextField` in `Field`'s look (`Field` is the phone's too). The state stays raw digits. Digits are end-aligned with tnum, 원 straight after them, 0 as the focused placeholder. The cursor always rests after a digit, so a backspace after a comma deletes the digit before it; a pasted 32,000,000원 is taken. Helper line: the error; else the amount read back (연간 비용 from 1만 up); else the hint. Past the range the error is `wonLimit(max)`; each action needs `input.ok`; the repository's messages stay as the backstop.

| Field | Range · digits | Hint | Read back |
|---|---|---|---|
| 운행 비용 금액 (LU:1533) | 1 – 1,000만 · 8 | 1원~1,000만 원 | no |
| 정비 금액 (FU:419) | 0 – 1,000만 · 8 | 보증·무상이면 비워 두세요 | no |
| 충전 직접 입력 (FC:401) | 1 – 1,000만 · 8 | none | no |
| km당 단가 (LU:2200) | 0 – 10만 · 6 | 회사에서 정한 km당 단가 | no |
| 연간 차량 비용 (LU:2202) | 0 – 100억 · 11 | `COST_HINT` | 3,200만 원 |

**The ₩ mark.** The redrawn ic_won (§1.3): one bar, so it no longer fills in at 18 dp. One per card: a money card's title, or a money mini stat's label. Never beside an amount.

**Where it lands.**

| Where | Today | Becomes | Package |
|---|---|---|---|
| 지금 › 등록 경비 (HD:453, 892), 월 마감 › 경비 (MC:365) | 0원 in full ink | `CarMiniStat(won = …)`; `TodayTileInfo.expenseWon: Long = 0` | P |
| 등록 경비 (LU:1957-1963) | total first; five rows, zeros inked, no tnum | non-zero types as rows, divider, total row 경비 합계; none → 등록한 경비가 없어요 | P |
| 유류비 정산 (LU:2056-2062) | 0원 at 30 sp under the empty line (v13) | rows; divider and total only with rows | P |
| 비용 참고 (LU:2009-2027) | three ₩ icons; Material's onTertiaryContainer | rows 연간 차량 비용 · 기록부 없을 때, divider, total row 업무 비율 적용액 with `taxDifferenceLine` as its line | P |
| 설정 › 정산·세무 (LU:2200-2205) | 32000000 … 원; the hint echoes it; a loose red sentence | `CarMoneyField` x 2 | P |
| 정비 기록 (FU:374, 411-420) | the amount ends the title | `CarMoneyRow("11/2 · 엔진오일·소모품", missing = "금액 없음")`; `CarMoneyField` | P |
| Excel preview cells (EP:297-300) | proportional digits | tnum behind `isCar()` | P |
| 운행 상세 (LU:1520, 1533-1535) | inline text; raw field | `CarMoneyRow` + 삭제 pill; `CarMoneyField`; the sum trails the 운행 비용 title | L |
| 자동 반영 list (HC:379-381) | tertiaryContainer chip | `CarMoneyRow(lead = ic_receipt)` + 회사 비용 아님 | L |
| 충전 비용 (FC:335-403), 카드 확인 (FC:604), 카드 반영 | chips, raw field, 44-sp text | §3.2 | H |
| 시연 체험 (DT:98, 102, 103) | literal 55,000원; `Stat` 업무 거리 · 등록 경비 (formatWon); 200원/km | `CarAmount(TOTAL)`; `CarMiniStat` 업무 거리 (ic_gauge) · `CarMiniStat(won =)` 등록 경비 (ic_won); km당 200원 · 정산 참고 2,480원 | D (DT:102 in the fix pass) |
| Trip row, follow-ups, chips, TalkBack | `formatWon` inline | = | — |

## 6. Polish (after the money work)

| # | Rough spot | Fix | Where · package |
|---|---|---|---|
| 1 | The disabled primary is a pale slab; a loading button turns into one | §1.3 | F2 |
| 2 | Two selection colours (teal beside the blue accent: v10, v11, v12, k9) | `carSelection`; focus in accentInk | LU:1414 (L) · DU:123, 387-402 (D) · DU:584-585 (P) · AS:286 (D) |
| 3 | 차량 정보 is half empty: the drawing box is 420 dp, the bitmap (1942 x 809) draws 258 | Drawing 260 in both layouts: the row drops to 463 dp and the cards under it rise 160. 차량 정보: `SpaceBetween`, a `CarDivider` over 최근 완료 운행 | VP:68, 218 (P) |
| 4 | Empty month: four idle steps and a 마감 button that cannot enable (v5). Closed month: two 520-dp outlined buttons (v6) | No trips: the headline, then one row: note (`CLOSE_WAITING` 운행이 기록되면 여기서 마감해요, or `CLOSE_NOTHING`) · `CarPillButton("이전 달 보기")`. Closed: note · 마감 해제 pill · `QuietAction("Excel 저장")` | MC:159-164, 368-411 (P) |
| 5 | Three tag shapes in one trip row; counts in titles | `PurposeChip` → `CarTag` with a dot (F2). 월 마감 + `CarTag("9건")`. `CarTag` for 최대 50명 and the 화면 밝기 state | MC:355, DU:487, Scene.kt:413 (P) |
| 6 | Orphan buttons | 추가 pill in the 직원 title row (as 정비 기록, FU:345). The 번호 pill ends the 연결 번호 row. 운행 상세's 변경 → 바꾸기 pill (§7) | DU:500, FC:528-546 (P) · LU:1515 (L) |
| 7 | Two card rhythms (24 and 16) | `cardGap` 16 (F2); 설정's one-up gap 20 → 16 | LY:74-75 (F2) · LU:2105 (P) |
| 8 | Material's solid warning triangle; ic_search stroke 1.8 | ic_warning; the bar's ic_search_bar at 1.6 (F2; the shared ic_search stays 1.8 for the phone) | FC:297 (P), the dock and incident card (H) |
| 9 | The range line is raw ISO on the bare page | `CarTag(reportRangeLabel(…))`: 10월 1일 ~ 31일 · 9건, plus `CarTag("시연 데이터", DEMO)`. `excelPreviewHeight` counts the tag: `max(32, 10 + 19·scale)` for the line | LU:1606, 1879 (P) |
| 10 | In-card gaps off the 8-12-16-24 scale | 14 → 16; 10 → 12; 28 / 20 → 24 / 16; 6 → 8 | FU:413 · VP:218-219, 230 (P) |
| 11 | Card pairs end ragged (v13: 284 and 213 dp) | `SideBySide(equalHeight = true)` = `Row(Modifier.height(IntrinsicSize.Min))` for the six card pairs (LU:1891, 1892, 1896, 2107, 2113, 2118); check on the emulator that they compose | LU:1586 (P) |

## 7. 운행기록 (package L)

### 7.1 Decisions
- **The current choice.** Controls fill with the accent (segments, expense-type chips, purpose tiles). The open row takes `carSelection`. An issue card takes the ring only.
- **The page opens on its first row.** Where the page is two-pane (`logListWidth != null`) the detail shows the list's first item: the newest trip, the first issue's trip, the first matched trip. Derived, never stored (`logSelection`): until a row is tapped the detail follows the list; a month, employee or filter change and a delete clear the tap; an edit in the pane pins its trip. The placeholder (LU:1262) goes. 닫기 (LU:1492) is not drawn in the pane; the sheet keeps it. The side-sheet layouts never open by themselves. `MatchedTripList` gains `selectedId` (HC:374 passes false today).
- At FULL the list is 526 dp and the detail 570 (card gap 16).

### 7.2 Header (LU:1177-1274)
Two-pane: one row, stepper · `Segmented` (weight 1, max 560) · employee chip; under it one 32-dp row of tags. Narrow: stepper ··· chip, then the filter (COMPACT: the chip under it). SHORT as today, tags at the row's end.

| Line | Current | New |
|---|---|---|
| LU:1232, 1241 | 직원: 전체 직원 ▾ | 전체 직원 / Sales · Kim Jiwoo; Person 20 dp, `KeyboardArrowDown` for ▾; TalkBack "직원 필터, 전체 직원" |
| LU:1204-1205 | menu rows | =, a check on the current one; `DropdownMenu(shape = 20, card, cardBorder)` |
| LU:1196 | 총 54.0 km · 업무 80% · 9건 (grey text) | = as a `CarTag`; hidden in an empty month |
| LU:1425 | 시연 on every row | 시연 데이터, once, `CarTag(DEMO)` beside it |
| new | | 필수 2건 · 참고 5건 (`CarTag`, 점검 필요 only) |

`DropdownChip` (LU:1334): a 56-dp stadium on `card` with a 1-dp chipBorder, `.98` + pressFill. While it narrows the list it takes `carSelection`, so a forgotten filter shows.

### 7.3 Lists
- **전체.** One `CarCard(padding = 16.dp, gap = 4.dp)` per day: `CarSectionTitle(dayLabel)` with "3건 · 21.0 km" trailing (LU:1394-1395 =).
- **`TripRow`** (LU:1407): a row in a 14-dp shape with `carSelection`, 72 dp (64 SHORT), `.98` + pressFill; the ripple branch goes.

| Slot | Shows |
|---|---|
| lead | a 12-dp negative dot when flagged (the slot is kept) · the start time, titleSmall tnum |
| title | `tripRowTitle`: 수정구 시흥동 → 판교; without an address 8.0 km · 1분 |
| line | `tripRowLine`: [마감 후 수정 ·] [8.0 km · 1분 ·] Kim Jiwoo, or 사용자 미지정 [· 4,500원]; the distance only under a route title |
| trailing | `PurposeChip` (a `CarTag` with its dot) |

Title ≤ 16 units, line ≤ 19 (`LOG_ROW_LINE_UNITS`). TalkBack, one stop (`tripRowSpoken`): "02:26 출발, 수정구 시흥동, 판교, 8.0 km, 1분, 일반 업무, Sales · Kim Jiwoo, 경비 4,500원, 마감 후 수정"; `selected`; state 점검 필요; click label 운행 상세 보기.

- **Issue card** (LU:1446-1472): `CarCard(padding = 20.dp, onClick = open the trip)`.

| Line | Current | New |
|---|---|---|
| LU:1451, 1453 | ● 목적 미분류 [· 5.0 km]; 10월 29일 (목) 02:26 | = ; plus `CarTag("마감 전 필수")` for 목적 미분류 and 사용자 미지정, `CarTag("참고")` for the rest |
| LU:1455 | `type.detail` | `CarLine(issueLine(type, trip), spoken = type.detail)` |
| LU:1459-1465 | 출·퇴근 · 일반 업무 · 개인 / 사용자 지정 / 기록 삭제 / 누락 구간을 운행으로 추가 | = (`QuietAction`) |
| LU:1466 | no answer | "누락 구간 12.0 km를 추가했어요 · 실행 취소" (undo deletes the new trip) |
| LU:1470 | 자세히 | removed: the card opens the trip |

`issueLine`: 목적 미분류 and 사용자 미지정 → the trip itself, "8.0 km · 수정구 시흥동 → 판교" · 0 km 운행 → 시동만 켠 기록이면 삭제해도 돼요 · 기록되지 않은 주행 → 앞 운행과 계기판 거리가 이어지지 않아요 · 누적거리 겹침 → 출발 계기판이 앞 운행 도착보다 작아요 · 비정상 평균 속도 → 평균 시속 200 km를 넘어요 · 종료 시각 추정 → 앱이 멈춰 마지막 수신 시각으로 끝냈어요 · 개인 운행의 카드 비용 → 회사 규정에 따라 처리해 주세요. Order (`issueOrder`): the two required types first, then newest first.

- **Matched card** (HC:372-394): `CarCard(padding = 16.dp, gap = 4.dp)` with the `TripRow`, a `CarDivider`, then per payment `CarMoneyRow("주유·충전", won, line = merchant or 카드 자동 매칭, lead = ic_receipt, action = CarPillButton("회사 비용 아님"))`. Words of HC:384-387 =.
- **Empty** (LU:1150-1159, 1316-1327): `CarCard` (max 560): `CarIconDisc` · title · optional `CarLine`; then pills 이전 달 보기 · 필터 해제 (=).

| Case | Current title | New title · icon |
|---|---|---|
| No trips | 11월 [영업 · 권 님의] 운행 기록이 없어요 | 운행 기록이 없어요 · ic_journal |
| Nothing to check | 11월 … 점검할 운행이 없어요 | 점검할 운행이 없어요 · CheckCircle; line = ; while the month is ready to close, `PrimaryAction("10월 마감하러 가기")` opens 월간·Excel on it |
| No matches | 11월 카드 결제가 자동으로 들어간 운행이 없어요 | 자동 반영된 운행이 없어요 · ic_receipt |

TalkBack keeps today's titles (`LogEmptyCopy.spoken`). LU:1384, LU:1438 and HC:367 are unreachable (LU:1245 draws the card first): deleted.

### 7.4 Detail (LU:1477-1558) and side sheet
Pane: `card` + cardBorder, corner 20. Sheet (LU:1358-1379): the same, corner 28; scrim black at `SHEET_SCRIM_ALPHA` (was `Scene.bg` at .55, a white veil by day); pane title 운행 상세, Back and motion =. Padding `carCardPadding()`, a `CarDivider` between blocks.

| Line | Current | New |
|---|---|---|
| LU:1489-1490 | eyebrow 10월 29일 (목) [· 시연 데이터] over 02:26 – 02:27 | `CarSectionTitle("10월 29일 (목) 02:26 – 02:27", ic_vehicle, live in the pane)`; tags 시연 데이터, 마감 후 수정 |
| LU:1492 | 닫기 | sheet only, `CarPillButton` |
| LU:1494-1499 | km on a slab; ⑤ 주행 전 계기판 · ⑥ 주행 후 계기판 | 8.0 displaySmall tnum + km; three `CarMiniStat`s ⑤ 주행 전 · ⑥ 주행 후 · 주행 시간; spoken "⑤ 주행 전 계기판 12,345.0 km" |
| LU:1501 | 차량 계기판에서 읽은 값이라 수정할 수 없어요. / 앞뒤 운행의 … 보완한 구간이에요. | ⓘ 계기판에서 읽은 값이라 고칠 수 없어요 / 앞뒤 운행의 계기판 값으로 채운 구간이에요 |
| new | a flagged row never said why | the trip's issues except the two required types: dot · label [· km] · `issueLine`; 기록되지 않은 주행 gets a pill 구간 추가 |
| LU:1506-1507 | 출발 · 도착 over titleLarge values | = ; label (labelMedium, 40 dp) beside the value (titleSmall, 2 lines) |
| LU:1510, 1512 | 운행 목적; 목적: 일반 업무 · 직접 선택 | `CarSectionTitle("운행 목적", ic_report)` + `CarTag("직접 선택")`; `PurposeOptions` |
| LU:1514-1515 | ④ 사용자 over the label; 변경 | `CarRow` (lead `DriverAvatar` 40): Kim Jiwoo over ④ 사용자 · Sales; nobody: 사용자 미지정 over ④ 사용자 칸이 비어요; pill 바꾸기 / 지정, spoken 이 운행의 사용자 바꾸기 |
| LU:1517 | 운행 비용 | `CarSectionTitle("운행 비용", ic_won)`, trailing `CarAmount(sum)` and pill 추가 / 닫기 (접힘 / 펼침); no rows: 등록한 비용이 없어요 |
| LU:1520-1528 | 주유·충전 45,000원 · merchant · source · 삭제 | `CarMoneyRow("주유·충전", line = "GS칼텍스 · 카드 자동 매칭", action = 삭제 in negative)`; spoken "주유·충전 45,000원 삭제" = |
| new | the fare the navigation gave is unused (TripLog.kt:92) | `CarExampleChip("통행료 3,600원 · 내비 예상")` while the trip passed a gate and has no toll (`tollSuggestion`); one tap adds it |
| LU:1531-1535 | five-way Segmented, always open; raw field | folded behind 추가: five `CarChoiceChip`s, `CarMoneyField("금액")`, 추가; answer "주차 4,500원을 추가했어요 · 실행 취소" |
| LU:1540-1543 | ⑩ 비고 · 방문처·업무 내용 · n / 300 · 저장 | = with `CarSectionTitle("⑩ 비고", Edit)`; answer "비고를 저장했어요 · 실행 취소" |
| LU:1546-1547 | 수정 이력 + up to 8 lines | ⓘ 수정 이력 3건; `more` = those lines |
| LU:1549 | 이 기록 삭제 | = `CarPillButton` in negative |
| LU:1551-1557 | 운행 기록을 삭제할까요? + two sentences | `CarDialog`: title = (ic_warning); lines 계기판 거리가 끊겨 ‘기록되지 않은 주행’으로 표시될 수 있어요 / 시동만 켠 기록이나 시연 기록만 지우길 권해요; 취소 · 삭제 (spoken 운행 기록 삭제) |

Other follow-ups (LU:181-183, 1463, 1524, 1555; HC:387) keep their words. New motion: only the expense form's fold (200 ms). `LogCopy` (new LogCopy.kt) holds every static line with its `SPOKEN_*`.

## 8. Dialogs, sheets, overlays (package D)

### 8.1 Follow-up bar
`CarFollowUpBar(data)` replaces `FollowUpBar` in `ShellFollowUpBar`; `FollowUpBar` stays byte-identical for the phone.
- It wears the other scene's skin: `CompositionLocalProvider(LocalScene provides followUpScene(Scene.isDay))`. This fixes the purple action at night (`DarkColors` sets no `inversePrimary`, SharemateTheme.kt:233-251, which LU:467 reads).
- `card`, cardBorder, corner 32, 64 dp or taller, 760 dp at most. Message bodyMedium: one line at FULL 1x, two elsewhere. Actions are `CarPillButton`s (≥ 112 dp): `second` first, the undo last. The live region, the 120-ms slide and the durations (LU:255) =.
- Budgets: `FOLLOW_UP_UNITS = 32` with one action, `FOLLOW_UP_TWO_UNITS = 24` with two.
- **Classifying** (LU:181-185). `classifyFollowUp`: always 실행 취소; with other unclassified trips that day also "3건도 같게" ("일반 업무로 기록 · 같은 날 미분류 3건"). The bulk answers "3건을 일반 업무로 기록했어요 · 실행 취소".

### 8.2 Driver picker (DU:279-405)
`CarDialogFrame(title, dismiss, maxWidth = 720.dp)`. The grid, its height rule, the search field and the opening scroll =.

| Line | Current | New |
|---|---|---|
| DU:285 | 지금 운전하는 직원 / 이 운행을 운전한 직원 | = (`CarSectionTitle`, Person) / 이 운행의 운전자 |
| DU:340, 341 | 새 운행과 기록 중인 운행에 바로 반영돼요. / 새 운행부터 적용돼요. | the same without the period (`pickerLead`) |
| DU:347 | 부서와 성명을 등록하면 … | `NOW_CARD_NO_DRIVERS_LINE`, spoken `NOW_CARD_NO_DRIVERS_SPOKEN` |
| DU:363 | 운행기록부 ④ 사용자 칸이 바뀌고 수정 이력에 남아요. | ⓘ ④ 사용자 칸이 바뀌고 수정 이력에 남아요 |
| DU:366-367 | `DialogButton` 직원 추가·관리 · 닫기 | `CarPillButton`s, labels = ; nobody registered: `QuietAction("닫기")` · `PrimaryAction("직원 추가")` |

`DriverPickCard`: `carSelection(chosen, shape, outlined = true)`; the name in `Scene.text` (was glow); the status in textMuted; the check moves into the name's row, so the status keeps its width. `DriverAvatar(ring = true)` draws a 2-dp accentInk ring (4.20 / 6.35 on the disc). `pickerCardStyle` (DriverRoster.kt:172) goes.

### 8.3 Dialogs → `CarDialog`

| Code | Current | New | Package |
|---|---|---|---|
| LU:451-452 | 확인이 필요해요 · message · 확인 | title = (ic_warning); `dialogLines(message)`; acknowledge | D |
| LU:453, 338 | 저장 위치 · 파일 선택기를 사용할 수 없어 … + path | title = (Info); line 파일 선택기가 없어 앱 보관 폴더에 저장했어요; body: the path, labelMedium, up to 3 lines; acknowledge | D |
| LU:1915-1918 | `CONFIRM_TITLE` · `exportConfirmLines` | = (ic_export); decide: 정리하러 가기 · 그래도 저장 | P |
| LU:1551-1557 | trip delete | §7.4 | L |
| FC:239-242 | 사진 N장도 함께 지워져요 · 기록은 하단 바에서 되돌릴 수 있지만 … | title = (ic_warning); line 기록은 되돌릴 수 있지만 사진은 복구되지 않아요; destroy, 삭제 spoken 기록과 사진 N장 삭제 | H |
| EP:181-187 | {sheet} · {n}행 · cells · 닫기 | on the car `CarDialog` (ic_report), the cells scrolling, read only; the phone keeps its AlertDialog | D |
| MainActivity.kt:87 | 추가로 요청할 수 있는 권한이 없습니다. | 더 요청할 권한이 없어요 | D |

### 8.4 Assistant sheet on the car (every change is `if (isCar()) … else` today's code)

| Line | Current | Car |
|---|---|---|
| AS:150 | sheet on `surface` | `Car.skin.card`; width, scrim and handle = |
| AS:314-325 | 운행 비서 · tag · note · 닫기 | `CarSectionTitle(ASSISTANT_TITLE, ic_assistant)` + `CarTag(ASSISTANT_LABEL)` + `CarPillButton("닫기")`; `CarInfoRow(ASSISTANT_NOTE)` |
| AS:332 | 무엇을 도와드릴까요? 아래 제안을 … | 무엇을 도와드릴까요? over 아래 제안을 누르거나 짧게 적어 주세요 |
| AS:333 | 할 수 있는 일 · … | ⓘ 바꾸기 전에 이해한 내용을 먼저 보여 드려요; more: 누른 뒤에만 기록해요 · 할 수 있는 일: 목적 기록 · 비용 추가 · 요약 · 차량 기한 · 월 마감 확인 |
| AS:342-346, 353-354 | bubbles on primaryContainer / surfaceHigh | chipFill with chipBorder / tagFill with tagBorder |
| AS:380 | 이렇게 이해했어요. 맞으면 {추가}을 … | 맞으면 기록을 눌러 주세요 · 바로 되돌릴 수 있어요 (추가를 for a cost) |
| AS:382-384, 288-290 | Material buttons | `PrimaryAction` (기록 / 추가, 보내기) · `QuietAction("고치기")` |
| AS:277, 407 | `PromptChip` | `CarExampleChip` (action label 운행 비서에 보내기) |
| AS:412 | 되돌렸어요. 기록은 이전 상태예요. | 되돌렸어요 · 기록은 이전 상태예요 |
| AS:415-419, 429 | text buttons | `CarPillButton`s, labels = |
| AS:286 | focus in `primary` | accentInk |
| AS:247 | 운행 비서: {first sentence} | `assistantFollowUp(text, car)`: no final period; 운행 비서가 기록을 바꿨어요 when over the budget |

The car never shows `sourceNote`, AS:273 or the disabled-chip state (its writer can always write).

### 8.5 시연 체험 (DT:49-144)
The window fades (`CarDialogFade`); the backdrop stays `Scene.bg` with a cardBorder edge; both panels are `CarCard`s; `KeyCard` (VP:155) loses its last caller and goes.

| Line | Current | New |
|---|---|---|
| DT:66-69 | SHAREMATE EXPERIENCE · n / 6 · 직접 체험하는 … · 시연 닫기 | `CarSectionTitle("시연 체험", ic_clock)` · `CarTag("n / 6")` spoken 6단계 중 n단계 · `CarPillButton("닫기")` spoken 시연 닫기 |
| DT:72 | 시연 데이터 · 실제 직원·운행기록과 … | ⓘ 시연 데이터 · 실제 기록과 설정은 바꾸지 않아요 |
| DT:39, 77 | 01 DRIVER … 06 EXPORT | removed |
| DT:83 | 선택한 직원이 이번 운행에 기록돼요. | the same without the period |
| DT:84 | 시연 신호로 같은 … | ⓘ 시연 신호로 실제와 같은 기록 로직을 돌려요; more: 실제 SDK 연결은 Virtual Console에서 검증해요 |
| DT:85 | 운행 목적은 운전자가 … | ⓘ 운행 목적은 운전자가 확인해요; more: 센서가 업무 여부를 정하지 않아요 |
| DT:86, 87 | two sentences | 입력한 비용만 보관해요 · 영수증 인증·자동 결제 없음 / 정산 참고액과 실제 등록 경비를 따로 보여요 |
| DT:88 | 국세청 운행기록부 항목을 … | ⓘ 국세청 항목을 참고한 검토용 파일이에요; more: 세무 신고·제출은 자동으로 하지 않아요 |
| DT:95 | eyebrow 차량을 사용하는 직원 · 시연용으로 … | `CarSectionTitle("운전자", Person)` · Segmented = · 시연용 직원이에요 · 실제 직원은 설정에서 관리해요 |
| DT:96 | eyebrow 기록 중 · 출발·주차 신호와 … | `CarTag("기록 중", POSITIVE)` · Stat = · 출발·주차 신호와 누적거리로 기록해요 |
| DT:97 | 다음 버튼으로 … | 목적을 고른 뒤 ‘목적 확인’을 눌러요 |
| DT:98 | eyebrow · Stat 55,000원 · 시연은 금액을 … | 주유·충전 over `CarAmount(55_000, TOTAL)` · ⓘ 시연은 금액을 미리 채워요; more: 실제 경비는 운행 상세에서 종류와 금액만 입력해요 |
| DT:102 | `Stat` 업무 거리 · `Stat` 등록 경비 | `CarMiniStat(ic_gauge, "업무 거리")` · `CarMiniStat(ic_won, "등록 경비", won =)`, the money style of step 4 |
| DT:103 | 회사 단가 200원/km · 정산 참고 … | km당 200원 · 정산 참고 2,480원 |
| DT:105-114 | GLEO / 음성 액션 로직 체험 · OutlinedButton · note | 음성 요약 체험 · `CarExampleChip("“오늘 운행 요약해 줘”", actionLabel = "요약 실행")` · ⓘ 앱 안의 요약 처리만 체험해요; more: 실제 마이크·Gleo 연결은 스키마 등록과 승인 후 쓸 수 있어요 |
| DT:128-137 | OutlinedButton 이전 · Button | `QuietAction` · `PrimaryAction`; step 6 is `LoadingAction("시연 Excel 저장", loadingLabel = "만드는 중…")` and closes after the save, so its answer is seen |

`OverlayCopy` (new OverlayCopy.kt) holds every new string of §8 with its `SPOKEN_*`.

## 9. Usability fixes adopted, and where they land

| Fix | Lands in |
|---|---|
| A wrong purpose can always be undone; N건도 같게 sits beside it | §8.1 (D) |
| 홈's work starts under the greeting | §3 (H) |
| Rows without an address differ; the page opens on its first row | §7.1, §7.3 (L) |
| Card answers say what they did and offer the way back; a truthful headline for the nearest-trip guess; 내역 보기 opens the batch's month | §3.2 (H) |
| 마감 전 필수 / 참고, their count, required first; the empty card leads to the close; the reminder opens 점검 필요 | §7.2, §7.3 (L), §3.2 (H) |
| The toll chip; 비용 추가 on the finished-trip card | §7.4 (L), §3.2 (H) |
| One word per thing: 바꾸기 / 지정, 사용자 미지정 (also LU:1945 and VP:239, were 직원 미지정; `SETTLEMENT_NOTE` 사용자 미지정 운행은 정산을 보류해요), 실행 취소 (also FC:319) | all |
| A snooze answers with undo; the 차량 정보 line keeps the deadline; 새 기한 입력 after 완료 | §3.2, §3.4 (H) |
| 추가 scrolls the maintenance form into view (`bringIntoViewRequester`) | FU:348 (P) |
| A photo asks before it is deleted | §3.2 (H) |
| Undo restores the deleted maintenance record itself: new `TripLogRepository.restoreMaintenance(record)` (same id, date and card link; today FU:360 re-adds a new one) | FU:359-361 (P) |
| 누락 구간 answers and can be undone | §7.3 (L) |
| With the link off, 휴대폰 연결 shows the switch itself (`SwitchRow` with `SettingsCopy.PHONE_SYNC_*`; `PHONE_OFF` goes); the rows follow the phone's order with `CarStepDisc` 1-2-3: 주소 · 연결 번호 · 인증키 | FC:487-548 (P) |
| One selection paint; 완성 in positive | §1.1, §4 |

## 10. Pure helpers and tests

**F2.** New `CarMoneyTest`:
- `groupDigits`: "", "150", "1500", "32000000", eleven digits.
- Offsets, n = 0..11: every raw offset lands inside the shown text, never decreases, round-trips and follows a digit. For n = 8: raw 2 → 2, 3 → 4, 8 → 10; shown 3 → 2, 7 → 5.
- `moneyDigits`: "4,500원" → "4500"; "007" → "7"; "000" → "0"; nine digits at 8 → null.
- `wonReading`: 4,500원 · 1만 5,000원 · 10만 원 · 1,000만 원 · 1억 2,000만 원 · 100억 원.
- `moneyInput`: blank at min 1 → not ok, no error; "20000000" → error; blank at min 0 → ok; reading → "3,200만 원".
- `moneyText`: 0 → zero; null → missing, spoken "금액 없음".

New `CarDialogTest`: `dialogLines` ("저장하지 못했어요. 다시 시도해 주세요." → two lines; "JPEG 사진만 첨부할 수 있어요 (최대 1.5MB)." → one line with 1.5 intact; blank → empty). `CarSkinTest`: the `carSelection` pairs of §1.1, the avatar ring, the four purpose dots ≥ 3 on tagFill. `CarCopyRules`: `DIALOG_LINE_UNITS = 30`, `FOLLOW_UP_UNITS = 32`, `FOLLOW_UP_TWO_UNITS = 24`, `LOG_ROW_LINE_UNITS = 19`, `HOME_TILE_LINE_UNITS = 19`, `HOME_CARD_LINE_UNITS = 38`, `HOME_STATUS_UNITS = 36`, `DOCK_LINE_UNITS = 13`, `BAR_PHRASE_UNITS = 12`. LogbookUiTest:225-232: gap 16 → 526.08 and 522.24.

**H.**
```kotlin
internal enum class AttentionKind(val label: String)                                    // HomeFrame.kt
@Immutable internal data class AttentionItem(val kind: AttentionKind, val count: Int = 1, val id: String? = null)
@Immutable internal data class AttentionPin(val item: AttentionItem, val head: AttentionItem)
internal fun attentionQueue(tasks: List<ParkedTask>, signal: SignalStatus, finishedId: String?, reclassifyId: String?, matched: Int, closeReminder: Boolean, chargeWaiting: Boolean): List<AttentionItem>
internal fun attentionIndex(queue: List<AttentionItem>, pin: AttentionPin?): Int
internal fun attentionCardMax(kind: AttentionKind, pager: Boolean): Dp
internal fun homeFrame(kind: AttentionKind?, pager: Boolean, demo: Boolean): HomeFrame      // greeting, cardGap, card, sky, demo, destinations, dock, total
internal fun homeStatusLine(signal: SignalStatus, demo: Boolean, open: Trip?, queue: List<AttentionItem>, tidy: Boolean): HomeLine
internal fun incidentCaptionBeside(button: Dp, fontScale: Float): Boolean                  // ≥ 196·s
```
Also `chargeReviewHeadline`, `incidentHint`, `incidentFacts`, `monthCloseTaskLine`, `monthCloseNext` (the fix pass: both month-close cards' button), `chargeWaitingShort`, `careTaskNote(kind): Pair<String, String>`, `withoutCareSnooze`, `tripWhenLine`, `purposeCardTag`, `setupSteps`, `matchedBatchMonth`, `reservationDueWithinDay`.
- **HeaderTest.** `headerPlan` without `clockInline` (the five cases of :19-39). `searchBarMode`: 824 and 496 → EXAMPLE; 495 and 280 → PHRASE; 279 and 160 → LABEL; 159 → ICON; 364 at 1.3x → PHRASE, 363 → LABEL; .85x counts as 1. `headerChipMax`: (1024, 176) → 552; (560, 176) → 88; (560, 295) → 56. Delete :41-47.
- **HomeFrameTest** (new). For every kind x pager x demo: `total + 84 + 28 ≤ 1209` and `attentionCardMax ≤ 480`. Idle total 384; INCIDENT with pager and demo 981. The column: 323 + 84 + 199 + 172 + 56 = 834 ≤ 1097. Queue order; FINISHED behind CARD_REVIEW; no CLOSE_REMINDER beside MONTH_CLOSE; the shared labels equal `TaskKind.label`. `attentionIndex`: 0 without a pin, when the pinned item has gone, and when the head changed; a FINISHED pin follows its RECLASSIFY.
- **HomeDashboardTest.** :34-41 become the `homeStatusLine` cases (each state of §3.3, the precedence, spoken forms). :154-174 drop `heroMin`, `showEyebrow` and `compactWidgets` and expect 3 columns at 1.3x. :176-183 become `heroBand(1112f, 184f, 720f, 0f, 0f, 0f, 24f) == Rect(744f, 0f, 1088f, 160f)`, and 897f → bottom 873f. Delete :185-191 and :193-204.
- **SceneTest:142.** Add `heroOrbAt(Rect(816f, 92f, 1160f, 252f), 39.2f, 24f, 181.5f, 1092f)` = (1052.8, 181.5); a band ending at 244 gives null.
- **HomeCardsTest:21-61.** Rewritten on `attentionQueue` and `ParkedStackAnswers` (the same four behaviours).
- **ParkedTasksTest.** :48-49 go with `stepperLabel`; :173-177 go with `autopilotSubline`; add `chargeWaitingShort`. :66-67 and :82-83 stay.
- **FleetCareUiTest:96-102.** Every `careTaskNote` line starts 완료하면, fits `HOME_CARD_LINE_UNITS` and has a longer spoken form; add `withoutCareSnooze`.
- **CarReservationsUiTest:27-47, :65.** `reservationDueWithinDay`: in use, the 13:50 lead and 09:00 the same day → true; 25 h ahead, none, cancelled, demo → false. The `carActiveTitle` assertions stay.
- **CarUiTest:41-54.** Deleted with `headerClock`.
- **HomeCopyTest** (new). Status lines ≤ 36 units, dock lines ≤ 13, card lines ≤ 38, destination lines ≤ 19, the bar's phrase ≤ 12, titles ≤ 8 syllables, no final period, each `SPOKEN_*` longer than its line; every `incidentSteps` line ≤ 56 units.

**L.**
```kotlin
fun tripRowTitle(trip: Trip): String
fun tripRowLine(trip: Trip, driverName: String?, edited: Boolean): String
fun tripRowSpoken(trip: Trip, driver: Driver?, edited: Boolean): String
fun logTopId(filter: Int, trips: List<Trip>, issues: List<TripIssue>, matched: List<Pair<Trip, List<TripExpense>>>): String?
fun logSelection(selectedId: String?, known: Set<String>, topId: String?, twoPane: Boolean): String?
fun logTotalsLine(totals: LogbookTotals): String
fun issueLine(type: IssueType, trip: Trip): String
fun issueRequired(type: IssueType): Boolean
fun issueOrder(issues: List<TripIssue>, trips: Map<String, Trip>): List<TripIssue>
fun issueCountLine(required: Int, reference: Int): String      // 필수 2건 · 참고 5건
fun odometerNote(origin: TripOrigin): String
fun expenseLine(expense: TripExpense): String
fun historyLine(count: Int): String
fun tollSuggestion(trip: Trip): Long?
```
- **LogCopyTest** (new). `tripRowTitle`: A → B; the destination wins over the end address; none → "8.0 km · 1분". `tripRowLine`: "8.0 km · 1분 · 김" under a route; "김" under a distance title; no driver → 사용자 미지정; edited with a cost → "마감 후 수정 · 8.0 km · 1분 · 김 · 4,500원"; never a department or 시연. `tripRowSpoken` holds the department and 시연 데이터, no arrow. `logTopId`, `logSelection` (the explicit id wins; null two-pane → top; null in the sheet → null; a vanished id → top). `issueLine` for every type; `issueOrder`; `issueCountLine`; `tollSuggestion` (a gate, a fare and no toll → the fare; else null). Budgets: row lines ≤ 19, card and ⓘ lines ≤ 26, no final period.
- **LogbookUiTest:77-91.** Titles 운행 기록이 없어요 / 점검할 운행이 없어요 / 자동 반영된 운행이 없어요; today's titles move to `spoken`; `detail` and `clearable` unchanged.

**D.** `followUpScene(isDay)`, `classifyFollowUp`, `pickerLead(noDrivers, tripLine, forTrip, recording): String?`, `assistantFollowUp(text, car)`.
- **OverlayCopyTest** (new). Dialog lines ≤ 30; picker, sheet and walkthrough lines ≤ 26; nothing ends in a period; `driverPickMessage` for "Kim Jiwoo" ≤ 32; `followUpScene(true)` is `NightSceneColors`, `(false)` `DaySceneColors`; `classifyFollowUp` (no others → one action; three others → 실행 취소 and "3건도 같게"; the bulk line).
- **DriverRosterTest.** :139-161 go with `pickerCardStyle`; add `pickerLead` (nobody → null; a trip → its line; a trip not found → null; recording; otherwise).
- **AssistantSheetTest.** `assistantFollowUp`: a short sentence on the car → 운행 비서: 방금 운행을 업무로 기록했어요; a 60-character one → 운행 비서가 기록을 바꿨어요; car = false → today's text.

**P.** `reportRangeLabel(from, to, trips, today)`, `monthCloseNote` with `CLOSE_WAITING`.
- **ReportCopyTest.** `reportRangeLabel`: one month → "10월 1일 ~ 31일 · 9건"; a year → "1월 1일 ~ 11월 12일 · 54건"; another year leads with 2025년. Budgets for `EXPENSE_EMPTY`, `CLOSE_WAITING`, the new `SETTLEMENT_NOTE`; no car copy constant holds ₩ or 원/km.
- **MonthCloseUiTest:85.** This month without trips → ("운행이 기록되면 여기서 마감해요", []).
- **VehiclePaneTest:25-32.** Drawing 260 in both layouts.
- **LogbookUiTest:41-61.** 950 → 732 (was 748); 1.3x → 729.3; the floor 560 and the 1.3x floor 704 stay; "FULL 창" inner 612.
- **FleetCareTest.** `restoreMaintenance`: the record returns with its id, date and source; never twice; `care` untouched.
- `HomeDashboardTest:134-152` stands (`expenseWon` is defaulted).

## 11. Release, gates, README

**Integration.** Merge H, L, D, P onto F2 (import blocks: union). Then:
1. Tests, lint, assembleDebug.
2. Phone gate: `git diff main --stat` lists no Phone*.kt, PhoneMidnight.kt or SharemateTheme.kt; fresh captures of the phone's 홈, 운행 목록, 현황, 설정 and assistant sheet are identical to 2.12.0.
3. No-scroll gate, FULL 1x, day and night, `adb logcat -s HomeFrame CarLine` silent through: a clean install (SETUP), a revoked permission, 시연 모드 on, 시연 출발 and 주차 (purpose card + pager), 충전 시작 and 종료, 카드 승인, each incident kind (사고 has four steps), a booking, the daily question.
4. Version 2.13.0, versionCode 23; app/src/main/assets/schema.json "version" 2.13.0 (GleoIntegrationTest:67).

**README shots to re-take** (all at 1680 dp; every car shot changes, because the header does):

| File | What it should show |
|---|---|
| car-home.jpg | Day, morning, idle: the bar in the header, no clock, the greeting over the open sky, the three destination cards and the incident dock on one screen |
| car-home-night.jpg | Night: an attention card with the pager (목적 확인 1/2) and the daily question in the 사용자 card |
| car-driver-picker.jpg | The picker on its card with the ringed choice |
| car-drivers.jpg | 설정 › 직원 with 추가 in the title row and the ringed row |
| car-log.jpg | 운행기록: day cards, the first row open, a cost row and the purpose tiles |
| car-report.jpg | 월간·Excel: closed October, the range tag, the preview |
| car-vehicle.jpg | 차량 with the 260-dp drawing and the cards risen |
| car-settings.jpg | 설정 › 기록, day |
| docs/demo/car-day-night.gif | Re-record: it shows the clock and the hero search field |

Optional new shots: car-home-task.jpg (카드 확인 with its pending amount, day) and car-costs.jpg (설정 › 정산·세무 with grouped fields beside the settlement rows). Update the alt text of every car shot: no clock, the search bar in the header, 확인할 일, 사용자 미지정. Phone shots are not re-taken.