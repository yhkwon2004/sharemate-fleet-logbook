# SHAREMATE 2.15 — no empty page, fewer taps

Design spec for 2.15.0 (versionCode 26). Base: main at 7ec1429 (2.14.1, two APKs). Where it differs from
DESIGN_2_12_SPEC.md, DESIGN_2_13_SPEC.md or DESIGN_2_14_HOME.md, this document wins. Everything those documents fix and this
one does not mention stays as it is (the header bar, the one money style, the one-line rule, 홈's frame and forms, the
dialogs, the scene).

## 0. Why

The user, 2026-11-13: "디자인이랑 사용자 편의성 ui 계속작업해줘" (keep working on the design and on ease of use). Their standing
taste, said twice before: open space reads as "빈" (empty), not calm; a screen must look filled by weighty elements (large
icon cards, a substantial card, big buttons) and by very little text ("한 줄 넘으면 안 읽으니까"). 2.14 filled 홈.

Audit of 2.14.1 on the Pleos Connect emulator (FULL window 1680 × 1209 dp, font 1×, the page 1112 × 1097 dp beside the
392-dp 지금 column):

1. 운행기록 opens on this month. A month without trips (the first thing after a clean install, and every start of a
   month) is one small card in the corner of an empty page.
2. 월간·Excel opens on the same empty month: a hollow preview card, or cards that only say "없어요".
3. The 지금 column ends about 290 dp above the page's end on every page.
4. 홈's idle card holds one figure and one button in 240 dp.
5. 설정 › 직원 and 설정 › 정산·세무 use the top third of the page.
6. Every expense amount needs the on-screen keyboard.
7. 검토 says "확인이 필요한 항목 7개" and then sends the driver to an Excel sheet to find out which.
8. Pairing the phone means typing an address, six digits and a 64-character key on the phone.

2.15 answers each of them. Two themes: **no page looks empty** (1–5) and **fewer taps** (6–8).

## 1. Rules for the round

- Car only, except §6: no edits to Phone*.kt, PhoneMidnight.kt, SharemateTheme.kt, SharemateUi.kt or ConnectionUi.kt.
  A shared function the phone also calls keeps its behaviour for the phone: add a car-only function beside it, or gate
  the change with `isCar()`. §6 changes the phone's connect card on purpose (PhoneSyncUi.kt `PhoneConnectCard`,
  PhoneSync.kt `pair`, and what they need); nothing else on the phone moves.
- New code goes in new files where it can (named in each section). LogbookUi.kt already has 2,700 lines: change its call
  sites by as few lines as the feature needs, and never reformat or move code you do not own. Several packages edit that
  file in parallel worktrees.
- Words: strings live in the copy objects (HomeCopy, LogCopy, ReportCopy, SettingsCopy, VehicleCopy, OverlayCopy) as
  constants or small pure functions, 해요체, one line, no final period, no space before a particle, with the copy tests'
  budgets (see the existing *CopyTest classes and the CarCopyRules test util). TalkBack gets a spoken form where the
  visible one is clipped or symbolic.
- Components and tokens only: CarCard, CarSectionTitle, CarTag, CarPillButton, PrimaryAction, QuietAction, CarIconDisc,
  CarStepDisc, CarRow, CarDivider, CarLine, CarInfoRow, CarMiniStat, CarChoiceChip, CarAmount / CarMoneyRow /
  CarMoneyField; `Car.skin` and `Scene.*` colours (no literal colours except the QR tile of §6.2). Touch targets are 56 dp
  at least. No ripple on the car: `pressable(interaction, .98f)` with `pressFill`. Motion comes from Motion.kt and snaps
  under reduced motion. Every new pair of text and ground keeps 4.5:1 by day and by night (CarSkinTest shows how the
  existing ones are checked).
- Never ask a layout that may hold a SubcomposeLayout, a BoxWithConstraints or a lazy list for intrinsic measurements.
- The budgets that already hold keep holding and stay unit-tested: 홈 never scrolls in the FULL window at 1× in any state
  (HomeFrameTest), and the 지금 column never scrolls there either (§3).
- Pure decisions are pure functions with JUnit tests (which month, which hero, which rows, which heights, every parser).
- Not in this round: the phone's screens other than its connect card; the scene's drawing; the header.

## 2. Landing month and the empty hero (운행기록, 월간·Excel)

### 2.1 The page opens where the trips are

`landingMonth(trips, today, zone): YearMonth` (pure, new, beside `reportLandingMonth` in MonthCloseUi.kt): this month when
it has a trip, or when no trip exists at all; otherwise the latest earlier month that has one.

- **운행기록.** Opened without a month from a button, the page follows `landingMonth` of the trips it lists until the
  driver steps the month; from then on the month is theirs (saved as today). A trip that arrives in a later month while
  the page still follows moves the page with it. While the page follows and stands on an earlier month, the tag row gains
  one quiet tag, `LogCopy.landingTag(thisMonth)`: "11월은 아직 운행이 없어요". The stepper still reaches this month.
- **월간·Excel.** The car's landing is `carReportLandingMonth(data, today, zone)` (pure, new): `reportLandingMonth` as it
  is, except when that is this month, this month has no trip and an earlier month has one: then that month.
  `reportLandingMonth` itself does not change (the phone reads it).
- **홈's cards** point where the pages land (car only; the phone's lines stay): 운행일지 확인 says "최근 10월 · 9건" when
  this month has no trip, none is recording and the log lands on an earlier month; 정보 내보내기 names
  `carReportLandingMonth` ("10월 운행기록부 · Excel 5개 시트"; a month still to close keeps "… · 마감 전 저장"). The count
  badge keeps counting this month.

### 2.2 The empty hero (new file EmptyHero.kt)

An empty list is no longer a small card. `CarEmptyHero` takes the whole row under the page's header rows (both panes of
the split page, or the list's place in the narrow layouts), at the top, the scene's city under it:

- the title (`headlineMedium`, bold, `Scene.text`, a heading; TalkBack hears the long form with the month and the
  employee) and, under it, at most one line (`bodyLarge`, `Scene.textSecondary`). They stand on the scene like 홈's
  greeting, with no card behind them;
- 24 dp; up to three tiles across, equal widths, 16 dp apart. A tile has the look of 홈's TALL destination card (a
  CarCard, 208 dp at least, a 56-dp accent tile with a 28-dp glyph, the title in `titleLarge`, one centred CarLine); a
  step tile carries its number as a CarStepDisc at its top start; a tile that leads somewhere is a button (chevron,
  `pressable`, `pressFill`), the others are not focusable as buttons. Where three do not stand across (the effective
  page under 900 dp) the tiles stack in the COMPACT look (104 dp, the icon at the start);
- 24 dp; the actions as a flow row, 12 dp apart: one PrimaryAction for the main way on, CarPillButtons for the rest.

It appears with the page (no stagger), never scrolls in the FULL window at 1× (about 376 dp of the 880 the page gives),
and scrolls as a whole where a large font or a short window needs it.

`logEmptyHero(...)` (pure, unit-tested for every branch) decides what it says; it replaces `logEmptyCopy` and
`LogEmptyCard`, which go:

| case | title · line | tiles | actions |
|---|---|---|---|
| FIRST: no trip anywhere, 전체, no employee filter | 아직 운행이 없어요 · 출발하면 자동으로 기록해요 | steps: ① D로 출발 / 기록이 시작돼요 ② P로 주차 / 거리와 시간이 저장돼요 ③ 목적 확인 / 홈에서 한 번 눌러요 | primary 시연 체험 보기 (starts the tutorial); pill 시연 모드 켜러 가기 (opens 설정 › 기록). In demo mode the primary is 홈에서 시연 출발 (opens 홈) and the pill goes |
| MONTH: the month has none, other months do | this month: 11월 운행이 아직 없어요 · 출발하면 자동으로 기록해요; a past month: 9월 운행 기록이 없어요 | the nearest months that have trips, three at most, nearest first: the month as the title ("10월"), "9건 · 54.0 km · 업무 80%" as the line, 마감됨 as a tag when closed; a tap opens that month | pill 이전 달 보기; pill 필터 해제 while a filter or an employee narrows the list |
| EMPLOYEE: the month has trips, the chosen employee none | Kim Jiwoo 님의 운행이 없어요 | none | primary 필터 해제; pill 이전 달 보기 |
| ISSUES: 점검 필요 is empty, the month has trips | 점검할 운행이 없어요 · the existing line | three facts of the month, not buttons: the count ("9건" / 운행), the distance ("54.0 km" / 총주행), the ratio ("80%" / 업무사용비율) | primary "10월 마감하러 가기" while the month is ready to close; pill 전체 보기 |
| MATCHED: 자동 반영 is empty | 자동으로 들어간 카드 결제가 없어요 | steps: ① 카드 등록 / 끝 4자리만 적어요 (a button: 설정 › 기록) ② 결제 알림 / 휴대폰 앱이 읽어요 ③ 자동 반영 / 운행 비용에 들어가요 | primary 카드 등록하러 가기 (설정 › 기록); pill 전체 보기 |

The demo-mode switch is never flipped from here: on a real car it stops real recording, so the hero only leads to it.

### 2.3 The Excel preview of an empty range (car only)

Under the two lines it already shows ("이 범위의 기록이 없어요", "저장하면 빈 시트로 들어가요"), the car's preview draws the
chosen sheet's header row and eight ruled, empty rows at the data rows' height, so the form the file will hold is
visible before the first trip. The phone's preview does not change.

## 3. The 지금 column ends where the page ends (new file NowVehicle.kt)

A fifth tile closes the column: **차량**.

- The title row (CarSectionTitle "차량" with the vehicle icon and a chevron; the plate as a CarTag at its end when one is
  saved); the car's picture, the same drawing the 차량 page shows, fitted and centred; one row of three mini stats from the
  live signal: 배터리 ("100%"), 기어 ("P"), 신호 (수신 중 / 확인 필요, the header pill's own words). A value the car does not
  report is "—". A tap opens 차량 ("차량 정보 보기"); TalkBack hears one stop with the plate and the three values.
- Its height is what the page leaves, between `NOW_VEHICLE_MIN` (148 dp, the picture at 64) and `NOW_VEHICLE_MAX`
  (280 dp): `nowVehicleHeight(page, others)` (pure, unit-tested) returns the height, or null when even the minimum does
  not fit; the column then scrolls as before with the tile at its minimum. In the FULL window at 1× it always fits: the
  tallest state of the four tiles above it is 826 dp with their gaps (2.13's figure less the foot), so the tile gets
  148–247 dp there and more where the driver card is short (first run).
- The column itself measures the four tiles above, then gives the tile the rest: a small custom Layout (or an equivalent
  that does not need intrinsics over the driver card). It still appears with the shell, stands outside the page change,
  and comes after the page for TalkBack; the tile is its last stop.
- The tile stays on every page, 차량 included: the column never changes between pages.

## 4. 홈's idle card: 최근 운행, three rows

While the queue is empty the card lists the newest finished trips of the data set on screen, three at most:

- the title row as now (최근 운행, the tag 확인할 일 없음); no headline row;
- a row per trip, `IDLE_ROW` 56 dp, hairlines between: the day and the start ("10월 29일 (목) 02:26", tabular digits,
  `Scene.textSecondary`), the trip's title as 운행기록 writes it (weight 1, one line), the purpose as the list's chip (미분류
  as its tag). A row is a button that opens 운행기록 on that trip; TalkBack hears the spoken title, the day and the purpose;
- the action row: 운행기록 보기 (opens the newest trip's month), as now.

Height: 48 + 32 + 12 + rows + 16 + 56, so 334 dp with three rows (`attentionAnatomy(null)` and HomeFrameTest follow; the
frame stays 914 dp at the most, 1,010 with the demo strip). With fewer trips the card is as tall as its rows.

Without any trip (setup done): the headline 아직 운행이 없어요 and the three steps of §2.2's FIRST case as tiles in the
setup tiles' look (96 dp, the step disc, title, one line; not buttons, no chevron), then one action, 시연 체험 보기.

## 5. Fewer taps

### 5.1 Quick amounts under 운행 비용's amount field (운행기록 › 운행 상세)

One row of chips under the field (CarChoiceChip's look, 56-dp targets, wrapping onto a second row where it must):

- up to two **recent amounts** of the chosen type, the most recent distinct ones in the log
  (`recentAmounts(trips, type, limit = 2)`, pure), written as money ("4,500원"); a tap sets the field to it;
- then **+1,000**, **+5,000**, **+10,000**: a tap adds to the amount in the field (empty counts as 0), never past the
  field's limit (`quickAmount(current, add, limit)`, pure).

The chips follow the type chosen above them. 홈's 충전 card already offers amounts as chips: use its look and its words.

### 5.2 검토's 증빙 준비 상태 lists what is left

Under the headline the card lists the kinds of issue the period has, the ones a close needs first
(`auditBreakdown(issues)`, pure, in the order 점검 필요 uses): a CarRow per kind with its label, 마감 전 필수 or 참고 as the
row's line, the count at the end ("3건") and, under 월간, a chevron: a tap opens 운행기록 › 점검 필요 on that month. Under
연간 the rows are not buttons. With nothing left: one row, 모든 운행이 확인됐어요, with the positive check. The sentence about
the Excel sheet moves behind the card's ⓘ row.

## 6. The phone pairs by QR (the one change on the phone)

Today the phone types three things the car shows. 2.15 puts them in one QR code.

### 6.1 The link (new file PairingLink.kt, pure, shared)

`sharemate://pair?v=1&e=<host:port>[&e=<host:port>…]&c=<6 digits>&k=<64 hex>`

- `pairingLinkText(endpoints, code, fingerprint)` builds it (the endpoints in the car's order, four at most).
- `parsePairingLink(text): PairingLink?` is strict: that scheme and host, `v=1`, one to four endpoints (a host of letters,
  digits, dots and hyphens, or an IPv4 literal; a port 1–65535), a code of exactly six digits, a key of exactly 64 hex
  digits (lower-cased), 512 characters at most, no other parameter. Anything else is null. Unit-tested with good links,
  every kind of bad one, and the round trip.

### 6.2 The car shows it (new file QrCode.kt; FleetCards.kt `PhonePairing`)

- `qrMatrix(text)` (ZXing `QRCodeWriter`, error correction M, no margin; dependency `com.google.zxing:core:3.5.3`,
  Apache-2.0, with its licence text beside the font's in `licenses/` and `app/src/main/assets/licenses/`).
- `QrCode(text, size)` draws it on a Canvas: always a white tile (#FFFFFF, corner 16) with a quiet zone of four modules
  and modules in #0B1220, by day and by night, because a camera has to read it; 232 dp at least, so a module is 4 dp or
  more; "휴대폰 연결 QR" for TalkBack.
- In 차량 › 휴대폰 연결 the QR leads the pairing: the QR at the start; beside it "휴대폰 앱에서 이 QR을 찍으세요"
  (`titleSmall`), the countdown tag and 번호 다시 만들기 (stacked above the rest in a narrow card). The three step rows of
  2.13 fold behind one row, 직접 입력하기 (closed by default; its state survives the code's refresh). Without a live code
  there is no QR: the place says why, as the code row does now, with 새 연결 번호.
- The QR is rebuilt only when the link's text changes (`remember(text)`), never on the clock's tick.

### 6.3 The phone reads it (PhoneSyncUi.kt `PhoneConnectCard`, PhoneSync.kt, new file QrScan.kt)

- The card leads with **QR 찍어서 연결** (the filled button, a camera mark): the system camera takes a picture into a
  file in the app's cache (FileProvider; add a `<cache-path>` for it), the picture is decoded off the main thread, and
  the file is deleted. Under it a text button, **사진에서 QR 읽기** (the system photo picker). Then "직접 입력" and the
  three fields and the button as today.
- `decodeQr(pixels, width, height): String?` (pure JVM: ZXing `QRCodeReader`, `HybridBinarizer`, then
  `GlobalHistogramBinarizer`, TRY_HARDER, also inverted) and the Android wrapper that loads the picture at 1,600 px on the
  long edge at most, then tries its centre crops. Unit-tested: a matrix rendered at several scales, inverted, and set in
  a larger noisy frame decodes to its text.
- A link that parses fills the three fields and connects at once: the person just scanned the car in front of them. The
  endpoints are tried in order; one that cannot be reached moves on to the next; a wrong code or a wrong key stops at
  once and is never sent to a second address (the car counts failures). `PhoneSyncClient.pair` takes the endpoints as one
  comma-separated string for this.
- What can go wrong is said under the buttons in one line, as a polite live region: QR을 읽지 못했어요 · 화면에 QR이 다
  나오게 다시 찍어 주세요; SHAREMATE 연결 QR이 아니에요; and the existing pairing errors.
- No new permission: the camera and the picker are the system's.

## 7. 설정 pages that reach the page's end

- **정산·세무.** A second pair under the first: 비용 참고 (`TaxCard`, this year) beside 업무사용비율 (`RatioCard`, this year,
  with the year as a tag). The page then shows what the two figures above do.
- **직원.** Under the manager one card across the page, 직원별 운행 with the month it shows as a tag (`landingMonth`): the
  rows of 월간's card, each with a share bar under it (the row's distance over the month's, on the ring-track token, 8 dp).
  Without a trip anywhere the card is left out.

## 8. Release

- 2.15.0, versionCode 26, in app/build.gradle.kts and app/src/main/assets/schema.json.
- Both flavors build and pass: `testCarDebugUnitTest testPhoneDebugUnitTest lintCarDebug lintPhoneDebug assembleCarDebug
  assemblePhoneDebug`. Baseline on main: 494 tests, 0 failures; lint 0 errors, 15 warnings on each flavor (a new
  dependency may add one version notice).
- The lead checks on the emulators afterwards: every case of §2.2, the landing months, the column's fifth tile in the
  tallest and the first-run state by day and by night, the idle card with three rows, one row and none, the quick
  amounts, 검토's rows, the QR on the car (decoded from a screenshot), the phone's connect card with a picture of a QR,
  the two 설정 pages, 홈's budget, and the phone's other screens against 2.14.1.

## 9. Corrections after the first build

The first build (f91f300) was looked at on the Pleos emulator (FULL window, 1×, by day and by night, a data set with
trips and a clean install) and reviewed from three sides. What follows replaces the sections it names.

### 9.1 The empty hero takes the page's height (replaces §2.2's fixed 208 dp)

A clean install's 운행기록 filled 37 % of its page. Across the page, the TALL tiles now grow with the room: the tile row's
height is the hero's bounded height less its own title, line, gaps and actions and less a 200-dp band for the city,
held between 208 and 360 dp (`heroTileHeight(room, chrome)`, pure). From 300 dp up a tile carries an 88-dp accent square
with a 44-dp glyph and its title in `headlineSmall`. Where the hero's height is not bounded, or the tiles stack, they
keep 208 and 104.

- The three first-trip steps carry a small picture under their line, so a tall tile says what it means without more
  words: ① the gear strip `P R N D` with D lit, ② the same strip with P lit, ③ the three purpose chips (출·퇴근, 일반 업무,
  개인) as the list draws them, not buttons. `HeroTile.ornament` names it; other tiles have none. TalkBack skips them.
- **Month tiles never stretch.** The months of a MONTH hero always stand as the stacked COMPACT rows (104 dp, the icon at
  the start, the month, its line, 마감됨 as a tag, the chevron), across the page, whatever its width. One month is one row.
- A tile's tag is not a second TalkBack stop: its spoken form already says it.
- **No hero without tiles.** EMPLOYEE lists that employee's nearest months with trips as rows (a tap opens the month,
  the filter kept); when the employee has no trip anywhere, the tiles are the people who drove in the month on screen,
  three at most, the name over "9건 · 54.0 km" (a tap filters by that person). MONTH with an employee who has no trip
  anywhere does the same.

### 9.2 A pinned trip keeps its pane (운행기록, split page)

The hero takes both panes only while no trip is shown. When the list empties under a trip the driver is editing (the
last 점검 필요 fixed in the pane), the detail stays where it is and the hero stands in the list column, in its stacked
form. The month a selection belongs to is saved with it, so a page that comes back after its followed month moved drops
the old month's trip.

### 9.3 월간·Excel without trips (운행 현황, 경비·정산, 검토)

Where the period has no trip, those three views show a hero without a title in place of their two cards (the month-close
card above them already says the month is empty). On an empty log: the three steps of a month, ① 목적 확인 / 운행마다 한 번
눌러요 ② 월 마감 / 확인이 끝나면 잠가요 ③ Excel 저장 / 운행기록부 양식으로 받아요, and 시연 체험 보기. Otherwise the nearest
months with trips as rows (a tap moves the page to that month) and 이전 달 보기. The Excel view keeps its empty form.

### 9.4 The 지금 column always ends at the page's end (replaces §3's 280-dp cap)

`NOW_VEHICLE_MAX` is 400 dp: the tile takes the room the page leaves in every state (247 with the daily question, about
399 once it is answered, about 358 on a first run). Height the picture cannot use becomes content, not air: when the
picture slot exceeds the picture's own height by 62 dp or more, a second row of three mini stats stands under the first:
관리 (the next care deadline as "D-7", or —), 충전 (충전 중, or —) and 휴대폰 (연결 N대, 대기, or 꺼짐). The tile's one
TalkBack stop names them too. The budget test covers the three driver-card states.

### 9.5 설정 › 직원 and › 정산·세무 (replaces §7's cards where they were hollow)

- **직원, no employee yet:** under the manager, on the scene, the small heading 직원을 등록하면 and three tiles in the
  hero's look, not buttons: ④ 사용자 칸 / 운행기록부에 이름이 들어가요, 출발 전 확인 / 하루 한 번 운전자를 물어요, 유류비 정산 /
  직원별로 계산해요.
- **직원, with employees:** 직원별 운행 stays stretched to the page's foot and uses the height. Every registered employee
  has a row (one without trips in the month: "운행 없음", an empty track, muted), then 사용자 미지정 when there is one.
  Where 208 dp or more is left under the rows, three facts of the month fill it as tiles: the count / 운행, the distance /
  총주행, the ratio / 업무사용비율. The card shows as soon as an employee exists; before the first trip its tag says
  아직 운행 없음 and the facts read 0건, 0.0 km and –.
- **정산·세무:** 유류비 정산 shows `landingMonth` with the month as its tag when this month has no trip yet. 비용 참고, in
  설정 only, fills its stretched height with the comparison as two bars under the money rows (기록부 없을 때 and 업무 비율
  적용액, the larger at full width, 24 dp on the ring-track token, the amount at the bar's end in the money style); before
  a yearly cost exists, one bar for the 1,500만 원 limit with its line. 월간's 검토 keeps the card as it was.

### 9.6 Pairing

- **An expired code is no code.** The QR, its headline, the countdown and the pill read the code only while
  `now < pairingExpiresAt` (`syncPairingLinkText(info, now)`), and the server publishes once when the code runs out, so
  홈's pairing card drops it too. At expiry the placeholder stands with 연결 번호가 만료됐어요 and 새 연결 번호.
- **A link is trusted only as far as typing was.** `pairingLinkIsLocal(link)` (pure): true only when every endpoint is an
  IPv4 literal on 10/8, 172.16/12, 192.168/16 or 169.254/16. The phone connects at once only for a picture it took
  itself of a local link. A link from 사진에서 QR 읽기, or one that names any other host, fills the three fields and waits:
  one line under the buttons, "주소를 확인하고 차량과 연결을 눌러 주세요 · 203.0.113.9:443", and the person presses 차량과 연결,
  as when typing.
- **The card stands across the 차량 page**, under the two columns. From 900 dp of card width the three manual steps
  stand beside the QR and its words, with no fold; narrower, 직접 입력하기 folds them as before.
- The QR's headline is 휴대폰 앱으로 찍어요.
- ZXing's NOTICE text follows the licence in both licence files.

### 9.7 홈

- A closed month of another year reads "2026년 12월 운행기록부 · Excel 저장", which fits the tall card's line.

### 9.8 This month without a trip (replaces the month rows of §9.1 and §9.3 for the month in progress)

Looked at again on the emulator, an empty current month with one month row was still a title over one row. Only sets of
three stand across (steps and facts); things to open (months, people) are always rows, so a single month or a single
person never stretches into one page-wide tile.

- **운행기록, this month:** under the title and its line, the three first-trip steps across with their pictures, as on a
  clean install, and under them the rows that lead to earlier trips (the nearest months, or the people who drove when an
  employee without trips is chosen), then 이전 달 보기. The tiles give the rows their room first (`heroRowsHeight`): 360 dp
  with one row, 265 with three, in the FULL window. A past month keeps its rows alone.
- **월간·Excel, this month:** the three steps of a month across (목적 확인, 월 마감, Excel 저장) with the month rows under
  them, and no button: the month-close card above already offers 이전 달 보기. A past month and 연간 keep the rows and the
  step back.
- **People are rows** in EMPLOYEE too. Where the month's trips have no person at all, its three facts stand in.
- On the 지금 column's 차량 tile, 신호 reads 시연 신호 in demo mode and 휴대폰 reads 준비 중 / 연결 전 / 연결 N대 / 서버 오류 /
  꺼짐, the 차량 page's own words. A link that waits on the phone names its first address that is not local.
