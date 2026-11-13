package ai.sharemate.app

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 홈 without scrolling (2.13, docs/design/DESIGN_2_13_SPEC.md §3; 2.14, docs/design/DESIGN_2_14_HOME.md): every card kind and
 * the idle card, with and without the pager and the demo strip, fits the full Pleos window (1209 dp: the 84-dp header, the
 * page slot's 28 dp of padding and the 1097-dp page) in the forms the frame picks, which are the first pair that fits in the
 * order the design likes best; the 지금 column fits beside it; the queue keeps the order the moment demands; and the
 * driver's page follows the queue.
 */
class HomeFrameTest {
    private val kinds = AttentionKind.entries.map { it as AttentionKind? } + null
    private val tall = HomeForms(HomeForm.TALL, HomeForm.TALL)

    @Test fun everyStateFitsTheFullWindowInTheLargestFormsThatFit() {
        kinds.forEach { kind ->
            listOf(false, true).forEach { pager ->
                listOf(false, true).forEach { demo ->
                    val state = "${kind ?: "IDLE"} pager=$pager demo=$demo"
                    val forms = homeForms(kind, pager, demo)
                    val frame = homeFrame(kind, pager, demo, forms)
                    assertTrue("$state: ${frame.total}", frame.total <= HOME_PAGE_FULL)
                    assertTrue("$state: ${frame.total}", frame.total + HOME_HEADER_ROW + HOME_PAGE_PADDING <= HOME_WINDOW_FULL)
                    // The first pair that fits, in the order the design prefers: every pair before it is too tall.
                    val at = HOME_FORM_ORDER.indexOf(forms)
                    assertTrue("$state: $forms", at >= 0)
                    HOME_FORM_ORDER.take(at).forEach { better -> assertTrue("$state: $better", homeFrame(kind, pager, demo, better).total > HOME_PAGE_FULL) }
                    assertTrue("$state ${attentionCardMax(kind, pager)}", attentionCardMax(kind, pager) <= ATTENTION_CARD_CAP)
                    // The budget, block by block: 160 + 24 + cards + 24 + the card + 24 + 96 in demo + the dock.
                    assertEquals(state, HOME_GREETING_MIN + 24.dp + destinationHeight(forms.cards) + 24.dp + attentionCardMax(kind, pager) + 24.dp +
                        (if (demo) 96.dp else 0.dp) + dockHeight(forms.dock), frame.total)
                }
            }
        }
        assertEquals("1209 − 84 − 28", 1097.dp, HOME_WINDOW_FULL - HOME_HEADER_ROW - HOME_PAGE_PADDING)
    }

    @Test fun everyStateIsTallButAnOpenIncidentInDemoMode() {
        kinds.forEach { kind ->
            listOf(false, true).forEach { pager ->
                listOf(false, true).forEach { demo ->
                    val expected = if (kind == AttentionKind.INCIDENT && demo) HomeForms(HomeForm.TALL, HomeForm.COMPACT) else tall
                    assertEquals("${kind ?: "IDLE"} pager=$pager demo=$demo", expected, homeForms(kind, pager, demo))
                }
            }
        }
        assertEquals("빈 홈도 꽉 참: 최근 운행 세 줄", 914.dp, homeFrame(null, pager = false, demo = false, tall).total)
        assertEquals("시연 띠와 함께", 1010.dp, homeFrame(null, pager = false, demo = true, tall).total)
        assertEquals("시연 아닌 가장 긴 화면: 사고 카드와 넘김", 1057.dp, homeFrame(AttentionKind.INCIDENT, pager = true, demo = false, tall).total)
        assertEquals("시연 중 사고는 낮은 독으로", 1093.dp, homeFrame(AttentionKind.INCIDENT, true, true, homeForms(AttentionKind.INCIDENT, true, true)).total)
        assertEquals("시연 중 충전 비용은 키 큰 그대로", 1090.dp, homeFrame(AttentionKind.CHARGE, true, true, tall).total)
        assertEquals("아무것도 맞지 않으면 둘 다 낮게", HOME_FORM_ORDER.last(), HomeForms(HomeForm.COMPACT, HomeForm.COMPACT))
    }

    @Test fun theFrameFollowsTheTallestCardInTheQueueSoPagingNeverChangesTheForms() {
        val incident = AttentionItem(AttentionKind.INCIDENT)
        val care = AttentionItem(AttentionKind.CARE)
        assertEquals("빈 큐는 최근 운행 카드", homeFrame(null, false, true, homeForms(null, false, true)), homeFrameFor(emptyList(), demo = true))
        assertEquals(HomeForms(HomeForm.TALL, HomeForm.COMPACT), homeFrameFor(listOf(incident, care), demo = true).forms)
        assertEquals("넘기는 순서와 무관하게 가장 큰 카드로", homeFrameFor(listOf(incident, care), demo = true), homeFrameFor(listOf(care, incident), demo = true))
        assertEquals(477.dp, homeFrameFor(listOf(care, incident), demo = true).card)
        assertEquals(tall, homeFrameFor(listOf(incident, care), demo = false).forms)
        assertEquals(tall, homeFrameFor(listOf(care), demo = true).forms)
        // The sky the frame leaves at the least: 1,097 − the blocks + the fixed 24.
        assertEquals(28.dp, homeFrameFor(listOf(incident, care), demo = true).skyLeft)
        assertEquals(64.dp, homeFrameFor(listOf(incident, care), demo = false).skyLeft)
        assertEquals("빈 홈의 하늘", 207.dp, homeFrameFor(emptyList(), demo = false).skyLeft)
        // On a page of another height the same blocks leave that much more or less (a FULL window under 1209 dp keeps the frame).
        val idle = homeFrameFor(emptyList(), demo = false)
        assertEquals(idle.skyLeft, idle.skyOn(HOME_PAGE_FULL))
        assertEquals("1680 × 1100 창의 988 dp 페이지", 98.dp, idle.skyOn(988.dp))
    }

    @Test fun theFormsAreTheirAnatomy() {
        assertEquals(208.dp, destinationHeight(HomeForm.TALL))
        assertEquals(104.dp, destinationHeight(HomeForm.COMPACT))
        assertEquals(140.dp, dockHeight(HomeForm.TALL))
        assertEquals(80.dp, dockHeight(HomeForm.COMPACT))
        // The tall card: 40 + the 56-dp tile + 16 + the title (titleLarge, 29) + 6 + the line (bodyMedium, 21) + 40.
        assertEquals(HOME_DESTINATION_TALL, 40.dp + DESTINATION_TILE + 16.dp + 29.dp + 6.dp + 21.dp + 40.dp)
        // The tall dock: 16 + the header line + 12 + the 72-dp buttons + 16.
        assertEquals(HOME_DOCK_TALL, DOCK_TALL_PADDING + DOCK_HEADER + DOCK_HEADER_GAP + DOCK_BUTTON_TALL + DOCK_TALL_PADDING)
        // The greeting block with the brand line back: 8 + 30 + 12 + one 53-dp line + 8 + the 24-dp status line fits its 160.
        assertTrue(8.dp + 30.dp + 12.dp + 53.dp + 8.dp + 24.dp <= HOME_GREETING_MIN)
    }

    @Test fun whereTheFramesBudgetDoesNotHoldThePageKeepsTheTallLook() {
        assertEquals("세 장이 나란하면 키 큰 카드", tall, homeFallbackForms(columns = 3, short = false))
        assertEquals("한 줄씩이면 낮은 카드, 독은 키 큰 그대로", HomeForms(HomeForm.COMPACT, HomeForm.TALL), homeFallbackForms(columns = 1, short = false))
        assertEquals("낮은 창은 둘 다 낮게", HomeForms(HomeForm.COMPACT, HomeForm.COMPACT), homeFallbackForms(columns = 3, short = true))
    }

    @Test fun theCardMaximaAreTheSpecsAnatomy() {
        val withPager = mapOf(AttentionKind.INCIDENT to 477, AttentionKind.PERMISSION to 242, AttentionKind.RECLASSIFY to 375, AttentionKind.PURPOSE to 375,
            AttentionKind.CARD_REVIEW to 287, AttentionKind.CHARGE to 414, AttentionKind.FINISHED to 264, AttentionKind.MATCHED to 264, AttentionKind.SETUP to 259,
            AttentionKind.PAIRING to 346, AttentionKind.MONTH_CLOSE to 264, AttentionKind.CARE to 242, AttentionKind.CLOSE_REMINDER to 219,
            AttentionKind.CHARGE_WAITING to 304)
        assertEquals(AttentionKind.entries.toSet(), withPager.keys)
        withPager.forEach { (kind, max) ->
            assertEquals("$kind", max.dp, attentionCardMax(kind, pager = true))
            assertEquals("$kind 넘김 없이 24 dp 작음", (max - 24).dp, attentionCardMax(kind, pager = false))
        }
        // The charge card is 390 dp; its money field's range error adds 24.
        assertEquals(390.dp, attentionCardMax(AttentionKind.CHARGE, pager = true) - ATTENTION_FIELD_ERROR)
        // 2.14's setup card: 48 + 56 + 12 + the headline 31 + 16 + the 96-dp tiles, and no action row under them.
        assertEquals(48.dp + 56.dp + 12.dp + 31.dp + 16.dp + SETUP_TILES, attentionCardMax(AttentionKind.SETUP, pager = true))
        // The idle card (2.15) at its tallest: 48 + 32 + 12 + three 56-dp rows and two hairlines + 16 + 운행기록 보기 56. It never pages.
        assertEquals(48.dp + 32.dp + 12.dp + 56.dp * 3 + 1.dp * 2 + 16.dp + 56.dp, attentionCardMax(null, pager = false))
        assertEquals(334.dp, attentionCardMax(null, pager = false))
        assertEquals("최근 운행 카드는 넘김이 없음", 334.dp, attentionCardMax(null, pager = true))
    }

    /**
     * 2.15 (docs/design/DESIGN_2_15.md §4): the idle card lists up to three trips with no headline row, as tall as its rows, and
     * without any trip shows the headline, the three step tiles and 시연 체험 보기. Every form fits the frame the idle card is
     * budgeted for, with and without the demo strip.
     */
    @Test fun theIdleCardIsAsTallAsItsRowsAndEveryFormFitsTheFrame() {
        assertEquals(0.dp, idleRowsHeight(0))
        assertEquals(56.dp, idleRowsHeight(1))
        assertEquals(113.dp, idleRowsHeight(2))
        assertEquals(170.dp, idleRowsHeight(3))
        assertEquals("세 줄이 최대", 170.dp, idleRowsHeight(7))
        // The rows: no headline row, the rows right under the title row's 12 dp, then the action row.
        listOf(3, 2, 1).forEach { rows ->
            val anatomy = idleAnatomy(rows)
            assertFalse("$rows: 머리말 줄 없음", anatomy.headline)
            assertEquals("$rows", idleRowsHeight(rows), anatomy.body)
            assertEquals("$rows", ATTENTION_ROW, anatomy.row)
            assertEquals("$rows", 48.dp + 32.dp + 12.dp + idleRowsHeight(rows) + 16.dp + 56.dp, idleCardHeight(rows))
        }
        assertEquals(334.dp, idleCardHeight(3))
        assertEquals(277.dp, idleCardHeight(2))
        assertEquals(220.dp, idleCardHeight(1))
        // Without any trip: the headline 31 + 16 + the 96-dp step tiles + 16 + 시연 체험 보기 56.
        val none = idleAnatomy(0)
        assertTrue(none.headline)
        assertEquals(SETUP_TILES, none.body)
        assertEquals(48.dp + 32.dp + 12.dp + 31.dp + 16.dp + 96.dp + 16.dp + 56.dp, idleCardHeight(0))
        assertEquals(307.dp, idleCardHeight(0))
        assertEquals("null은 가장 큰 형태", idleAnatomy(IDLE_ROWS_MAX), attentionAnatomy(null))
        // The frame counts the tallest form, and every form fits it: 914 dp at the most, 1,010 with the demo strip.
        val expected = mapOf(3 to 914, 2 to 857, 1 to 800, 0 to 887)
        (0..IDLE_ROWS_MAX).forEach { rows ->
            assertTrue("$rows: ${idleCardHeight(rows)}", idleCardHeight(rows) <= attentionCardMax(null, pager = false))
            listOf(false, true).forEach { demo ->
                val forms = homeForms(null, pager = false, demo = demo)
                assertEquals("$rows demo=$demo", HomeForms(HomeForm.TALL, HomeForm.TALL), forms)
                val frame = homeFrame(null, pager = false, demo = demo, forms = forms).copy(card = idleCardHeight(rows))
                assertEquals("$rows demo=$demo", (expected.getValue(rows) + if (demo) 96 else 0).dp, frame.total)
                assertTrue("$rows demo=$demo: ${frame.total}", frame.total <= HOME_PAGE_FULL)
                assertTrue("$rows demo=$demo: ${frame.total}", frame.total <= homeFrame(null, pager = false, demo = demo, forms = forms).total)
            }
        }
    }

    @Test fun theNowColumnFitsBesideIt() {
        // 사용자, 예약, 오늘 운행 and 이달 운행일지 with their three 16-dp gaps; 2.15's 차량 tile takes the rest of the page after its
        // own 16-dp gap and the 8-dp foot, so the column ends on the page's foot without scrolling. §9.4: in every state of the
        // 사용자 card, the question on two lines (323), the person once it is answered (171) and a first run (212).
        val tiles = 84.dp + 199.dp + 172.dp + 48.dp
        assertEquals(826.dp, 323.dp + tiles)
        mapOf(323.dp to 247.dp, 171.dp to 399.dp, 212.dp to 358.dp).forEach { (driver, expected) ->
            val vehicle = nowVehicleHeight(HOME_PAGE_FULL, driver + tiles)!!
            assertEquals("사용자 $driver", expected, vehicle)
            assertEquals("사용자 $driver", HOME_PAGE_FULL, driver + tiles + 16.dp + vehicle + 8.dp)
            assertTrue(vehicle >= NOW_VEHICLE_MIN)
            assertTrue(vehicle <= NOW_VEHICLE_MAX)
        }
    }

    @Test fun theQueueKeepsTheOrderTheMomentDemands() {
        val tasks = listOf(ParkedTask(TaskKind.INCIDENT, 1), ParkedTask(TaskKind.CARD_REVIEW, 2), ParkedTask(TaskKind.CHARGE, 1), ParkedTask(TaskKind.PURPOSE, 3),
            ParkedTask(TaskKind.SETUP, 1), ParkedTask(TaskKind.PAIRING, 1), ParkedTask(TaskKind.MONTH_CLOSE, 1), ParkedTask(TaskKind.CARE, 2))
        val queue = attentionQueue(tasks, SignalStatus.DENIED, finishedId = "f", reclassifyId = "r", matched = 3, closeReminder = true, chargeWaiting = true)
        assertEquals(listOf(AttentionKind.INCIDENT, AttentionKind.PERMISSION, AttentionKind.RECLASSIFY, AttentionKind.CARD_REVIEW, AttentionKind.CHARGE,
            AttentionKind.FINISHED, AttentionKind.MATCHED, AttentionKind.PURPOSE, AttentionKind.SETUP, AttentionKind.PAIRING, AttentionKind.MONTH_CLOSE,
            AttentionKind.CARE, AttentionKind.CHARGE_WAITING), queue.map { it.kind })
        assertFalse("월 마감 카드 옆에 마감 안내는 없음", queue.any { it.kind == AttentionKind.CLOSE_REMINDER })
        assertEquals("개수는 할 일 목록 그대로", 2, queue.first { it.kind == AttentionKind.CARD_REVIEW }.count)
        assertEquals("카드 반영은 묶음의 건수", 3, queue.first { it.kind == AttentionKind.MATCHED }.count)
        assertEquals("운행 카드는 그 운행", "r", queue.first { it.kind == AttentionKind.RECLASSIFY }.id)
        val calm = attentionQueue(listOf(ParkedTask(TaskKind.CARE, 1)), SignalStatus.READY, null, null, 0, closeReminder = true, chargeWaiting = false)
        assertEquals("월 마감 할 일이 없으면 이달 안내", listOf(AttentionKind.CARE, AttentionKind.CLOSE_REMINDER), calm.map { it.kind })
        val finished = attentionQueue(listOf(ParkedTask(TaskKind.CARD_REVIEW, 1)), SignalStatus.READY, "f", null, 0, false, false)
        assertEquals("방금 운행은 카드 확인 뒤로", listOf(AttentionKind.CARD_REVIEW, AttentionKind.FINISHED), finished.map { it.kind })
        assertTrue("권한은 거부일 때만", attentionQueue(emptyList(), SignalStatus.ERROR, null, null, 0, false, false).isEmpty())
        assertTrue("빈 할 일은 건너뜀", attentionQueue(listOf(ParkedTask(TaskKind.PURPOSE, 0)), SignalStatus.READY, null, null, 0, false, false).isEmpty())
    }

    @Test fun theSharedKindsKeepTheTaskListsWords() {
        val shared = mapOf(AttentionKind.INCIDENT to TaskKind.INCIDENT, AttentionKind.CARD_REVIEW to TaskKind.CARD_REVIEW, AttentionKind.CHARGE to TaskKind.CHARGE,
            AttentionKind.PURPOSE to TaskKind.PURPOSE, AttentionKind.SETUP to TaskKind.SETUP, AttentionKind.PAIRING to TaskKind.PAIRING,
            AttentionKind.MONTH_CLOSE to TaskKind.MONTH_CLOSE, AttentionKind.CARE to TaskKind.CARE)
        shared.forEach { (kind, task) -> assertEquals("주행 화면의 마지막 줄과 같은 말", task.label, kind.label) }
        assertEquals(listOf("차량 권한", "목적 바꾸기", "방금 운행", "카드 반영", "월 마감", "충전 비용"),
            listOf(AttentionKind.PERMISSION, AttentionKind.RECLASSIFY, AttentionKind.FINISHED, AttentionKind.MATCHED, AttentionKind.CLOSE_REMINDER,
                AttentionKind.CHARGE_WAITING).map { it.label })
    }

    @Test fun theDriversPageHoldsUntilTheHeadChanges() {
        val incident = AttentionItem(AttentionKind.INCIDENT)
        val review = AttentionItem(AttentionKind.CARD_REVIEW, 2)
        val care = AttentionItem(AttentionKind.CARE)
        val queue = listOf(incident, review, care)
        assertEquals("넘기지 않았으면 맨 앞", 0, attentionIndex(queue, null))
        assertEquals("넘긴 자리", 2, attentionIndex(queue, AttentionPin(care, incident)))
        assertEquals("건수가 바뀌어도 같은 종류에 머묾", 1, attentionIndex(listOf(incident, review.copy(count = 1), care), AttentionPin(review, incident)))
        assertEquals("넘긴 항목이 사라지면 맨 앞", 0, attentionIndex(listOf(incident, review), AttentionPin(care, incident)))
        assertEquals("맨 앞이 바뀌면 맨 앞", 0, attentionIndex(listOf(review, care), AttentionPin(care, incident)))
        assertEquals("더 급한 일이 생겨도 맨 앞", 0, attentionIndex(listOf(AttentionItem(AttentionKind.PERMISSION), incident, review, care), AttentionPin(care, incident)))
        assertEquals(0, attentionIndex(emptyList(), AttentionPin(care, incident)))
        // 다른 목적 on the 방금 운행 card: the question for the same trip takes the pinned card's place, and the page follows it.
        val finished = AttentionItem(AttentionKind.FINISHED, id = "t")
        val before = listOf(incident, finished, care)
        val after = listOf(incident, AttentionItem(AttentionKind.RECLASSIFY, id = "t"), care)
        assertEquals(1, attentionIndex(before, AttentionPin(finished, incident)))
        assertEquals("방금 운행은 같은 운행의 목적 바꾸기로", 1, attentionIndex(after, AttentionPin(finished, incident)))
        assertEquals("다른 운행의 질문은 따라가지 않음", 0,
            attentionIndex(listOf(incident, AttentionItem(AttentionKind.RECLASSIFY, id = "u"), care), AttentionPin(finished, incident)))
        // 지금 입력 on the waiting 충전 비용 card: the amount question takes its place higher in the queue, and the page follows it.
        val waiting = AttentionItem(AttentionKind.CHARGE_WAITING)
        val charge = AttentionItem(AttentionKind.CHARGE)
        assertEquals(2, attentionIndex(listOf(incident, care, waiting), AttentionPin(waiting, incident)))
        assertEquals("기다리던 충전은 금액 질문으로", 1, attentionIndex(listOf(incident, charge, care), AttentionPin(waiting, incident)))
        assertEquals("충전 질문이 없으면 맨 앞", 0, attentionIndex(listOf(incident, care), AttentionPin(waiting, incident)))
    }

    @Test fun captionsSitBesideTheLabelFrom196Dp() {
        assertTrue("FULL의 204 dp", incidentCaptionBeside(204.dp, 1f))
        assertTrue(incidentCaptionBeside(196.dp, 1f))
        assertFalse(incidentCaptionBeside(195.dp, 1f))
        assertFalse("1.3배 글꼴은 255 dp부터", incidentCaptionBeside(254.dp, 1.3f))
        assertTrue(incidentCaptionBeside(255.dp, 1.3f))
        assertTrue("작은 글꼴은 1배로 셈", incidentCaptionBeside(196.dp, .85f))
    }

    /** The tall dock's header keeps 2.12's one row from 560 dp of dock (1,080 beside the 지금 column); narrower, the caption goes under the title. */
    @Test fun theTallDocksHeaderKeepsOneRowFrom560Dp() {
        assertTrue("지금 열 옆의 1,080 dp", dockHeaderOneRow(1080.dp, 1f))
        assertTrue(dockHeaderOneRow(560.dp, 1f))
        assertFalse("나눈 창의 좁은 독", dockHeaderOneRow(559.dp, 1f))
        assertFalse("1.3배 글꼴은 728 dp부터", dockHeaderOneRow(727.dp, 1.3f))
        assertTrue(dockHeaderOneRow(728.dp, 1.3f))
        assertTrue("작은 글꼴은 1배로 셈", dockHeaderOneRow(560.dp, .85f))
    }

    @Test fun theFollowUpBarRidesOverTheFootAndNeverOverTheButtons() {
        val dock = HOME_DOCK_TALL
        val foot = HOME_DEMO_STRIP + HOME_BAND_GAP + dock
        assertEquals("하늘이 넉넉하면 시연 띠 위로", foot, homeBarLift(foot, dock, sky = 300.dp))
        assertEquals("88 dp면 딱 맞음", foot, homeBarLift(foot, dock, sky = FOLLOW_UP_ROOM))
        assertEquals("하늘이 모자라면 시연 띠를 덮고 독 위로", dock, homeBarLift(foot, dock, sky = 28.dp))
        assertTrue("시연 띠와 그 아래 틈이 막대를 담음", HOME_DEMO_STRIP + HOME_BAND_GAP >= FOLLOW_UP_ROOM)
        // Without the demo strip the foot is the dock. The fullest page leaves 64 dp of sky, so the 88-dp bar reaches at most
        // the attention card's 24-dp bottom padding, never its buttons.
        kinds.forEach { kind ->
            listOf(false, true).forEach { pager ->
                val sky = homeFrame(kind, pager, demo = false, homeForms(kind, pager, demo = false)).skyLeft
                assertTrue("${kind ?: "IDLE"} pager=$pager: $sky", FOLLOW_UP_ROOM - sky <= ATTENTION_PADDING)
                // In demo mode the bar rides over the strip wherever the sky is short, so its top never reaches the card's foot.
                val demo = homeFrame(kind, pager, demo = true, homeForms(kind, pager, demo = true))
                val lift = homeBarLift(demo.demo + demo.dock, demo.dock, demo.skyLeft)
                val cardFoot = HOME_PAGE_FULL - demo.dock - demo.demo - demo.skyLeft
                val barTop = HOME_PAGE_FULL - lift - FOLLOW_UP_ROOM
                assertTrue("${kind ?: "IDLE"} pager=$pager demo: bar $barTop, card $cardFoot", barTop >= cardFoot)
            }
        }
    }

    /**
     * A FULL window shorter than 1209 dp keeps the 지금 column and the frame, but not its 1097-dp page: the bar decides by the
     * sky the state leaves on the page it really has ([HomeFrame.skyOn]). Where the page rests (nothing scrolls), the bar in
     * demo mode never reaches the card, whatever the page's height.
     */
    @Test fun inAShorterFullWindowTheBarDecidesByThePageItHas() {
        // 1680 × 1100: a 988-dp page. The card question with the pager in demo mode is 963 dp and rests, with 49 dp of sky.
        val review = homeFrameFor(listOf(AttentionItem(AttentionKind.CARD_REVIEW), AttentionItem(AttentionKind.FINISHED)), demo = true)
        assertEquals(963.dp, review.total)
        assertEquals("1,097 dp 페이지라면 158", 158.dp, review.skyLeft)
        assertEquals(49.dp, review.skyOn(988.dp))
        assertEquals("하늘이 49 dp면 시연 띠를 덮고 독 위로", review.dock, homeBarLift(review.demo + review.dock, review.dock, review.skyOn(988.dp)))
        listOf(1180.dp, HOME_PAGE_FULL, 1040.dp, 988.dp, 900.dp, 800.dp).forEach { page ->
            kinds.forEach { kind ->
                listOf(false, true).forEach { pager ->
                    val frame = homeFrame(kind, pager, demo = true, homeForms(kind, pager, demo = true))
                    if (frame.total <= page) {
                        val sky = frame.skyOn(page)
                        val lift = homeBarLift(frame.demo + frame.dock, frame.dock, sky)
                        val cardFoot = page - frame.dock - frame.demo - sky
                        val barTop = page - lift - FOLLOW_UP_ROOM
                        assertTrue("${kind ?: "IDLE"} pager=$pager page=$page: bar $barTop, card $cardFoot", barTop >= cardFoot)
                    }
                }
            }
        }
    }
}
