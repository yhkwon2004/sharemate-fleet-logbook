package ai.sharemate.app

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneAuthUiTest {
    private val kim = Driver("d1", "영업팀", "김서연")
    private val lee = Driver("d2", "관리팀", "이도윤")
    private val kim2 = Driver("d3", "개발팀", "김서연")

    @Test fun profileNeedsANameAndKeepsBothFieldsWithinThirtyCharacters() {
        assertTrue(profileErrors("김서연", "").valid)
        assertTrue("부서는 선택", profileErrors("김서연", "영업팀").valid)
        assertEquals("이름을 적어 주세요.", profileErrors("   ", "영업팀").name)
        assertTrue(profileErrors("가".repeat(30), "나".repeat(30)).valid)
        assertEquals("이름은 30자까지예요.", profileErrors("가".repeat(31), "").name)
        assertEquals("부서는 30자까지예요.", profileErrors("김서연", "나".repeat(31)).department)
        assertTrue("앞뒤 공백·연속 공백은 세지 않음", profileErrors("  " + "가".repeat(15) + "     " + "나".repeat(14) + "  ", "").valid)
    }

    @Test fun validateProfileThrowsTheFormSentence() {
        val e = assertThrows(IllegalArgumentException::class.java) { validateProfile("", "") }
        assertEquals("이름을 적어 주세요.", e.message)
        val d = assertThrows(IllegalArgumentException::class.java) { validateProfile("김서연", "가".repeat(31)) }
        assertEquals("부서는 30자까지예요.", d.message)
        validateProfile("김서연", "영업팀")
    }

    @Test fun cleanProfileTextTrimsAndFoldsSpaces() {
        assertEquals("김 서연", cleanProfileText("  김   서연 "))
        assertEquals("", cleanProfileText(" \t "))
    }

    @Test fun eachStepContinuesOnlyWhenItsConditionHolds() {
        assertFalse(onboardingCanContinue(0, profileValid = false, vehicleCount = 3))
        assertTrue(onboardingCanContinue(0, profileValid = true, vehicleCount = 0))
        assertFalse("차량이 없으면 다음 불가(나중에 등록은 따로)", onboardingCanContinue(1, profileValid = true, vehicleCount = 0))
        assertTrue(onboardingCanContinue(1, profileValid = true, vehicleCount = 1))
        assertTrue("직원 선택은 선택 사항", onboardingCanContinue(2, profileValid = false, vehicleCount = 0))
    }

    @Test fun stepTextCountsFromOneAndStaysInRange() {
        assertEquals("1/3", onboardingStepText(0))
        assertEquals("3/3", onboardingStepText(2))
        assertEquals("3/3", onboardingStepText(7))
        assertEquals("1/3", onboardingStepText(-1))
        assertEquals(ONBOARDING_STEPS, onboardingStepTitles.size)
    }

    @Test fun initialIsTheFirstCharacterOfTheNameElseTheEmail() {
        assertEquals("김", accountInitial("김서연", "kim@example.com"))
        assertEquals("J", accountInitial("  jane doe", ""))
        assertEquals("K", accountInitial("", "kim@example.com"))
        assertEquals("?", accountInitial(" ", " "))
        assertEquals("이모지(서로게이트 쌍)는 한 글자로", "🚗", accountInitial("🚗 car", ""))
    }

    @Test fun driverPreselectionPrefersTheLinkedDriverThenAUniqueNameMatch() {
        val list = listOf(kim, lee)
        assertEquals("d2", preselectDriver(list, "김서연", current = "d2"))
        assertEquals("d1", preselectDriver(list, "김서연"))
        assertEquals("공백·대소문자 무시", "d1", preselectDriver(list, " 김 서연 "))
        assertEquals("d1", preselectDriver(listOf(Driver("d1", "", "Jane Doe")), "jane doe"))
        assertNull(preselectDriver(list, "박지민"))
        assertNull(preselectDriver(list, ""))
        assertEquals("목록에 없는 연결 정보는 무시", "d1", preselectDriver(list, "김서연", current = "gone"))
        assertNull(preselectDriver(emptyList(), "김서연"))
    }

    @Test fun sameNameIsSettledByDepartmentOrLeftUnselected() {
        val list = listOf(kim, lee, kim2)
        assertNull(preselectDriver(list, "김서연"))
        assertEquals("d3", preselectDriver(list, "김서연", "개발팀"))
        assertNull(preselectDriver(list, "김서연", "총무팀"))
    }

    @Test fun manualVehicleErrorUsesTheFleetRulesAndWaitsForInput() {
        assertNull("입력 전에는 오류 없음", manualVehicleError("", "", ""))
        assertNull(manualVehicleError("12가 3456", "아이오닉 5", "쉐어메이트"))
        assertEquals("차량번호를 확인해 주세요. 예: 12가 3456", manualVehicleError("12-3456!", "", ""))
        assertNotNull(manualVehicleError("12가 3456", "가".repeat(41), ""))
    }

    @Test fun vehicleRowsSayWhetherTheCarIsConnected() {
        val paired = FleetVehicle("fp", "12가 3456", "아이오닉 5", "쉐어메이트", link = "https://10.0.0.5:9247|t|fp")
        val typed = FleetVehicle("m", "34나 5678")
        assertEquals("연결됨", fleetVehicleStatus(paired))
        assertEquals("연결 전", fleetVehicleStatus(typed))
        assertEquals("아이오닉 5 · 쉐어메이트", fleetVehicleDetail(paired))
        assertEquals("차종·회사 미등록", fleetVehicleDetail(typed))
        assertEquals("쉐어메이트", fleetVehicleDetail(typed.copy(company = "쉐어메이트")))
    }

    @Test fun accountDriverLineNamesTheEmployeeOrSaysNone() {
        assertEquals("운행기록부 사용자: 영업팀 · 김서연", accountDriverLine(kim.label))
        assertEquals("직원 미지정", accountDriverLine(null))
        assertEquals("직원 미지정", accountDriverLine(" "))
    }

    @Test fun driverListNoteExplainsWhyTheListIsEmpty() {
        assertTrue(driverListNote(paired = false, connected = false).startsWith("직원 목록은 차량과 연결하면"))
        assertTrue(driverListNote(paired = true, connected = false).startsWith("차량과 다시 연결되면"))
        assertTrue(driverListNote(paired = true, connected = true).contains("직원을 추가하면"))
    }

    @Test fun signInAndOnboardingColumnsAre400OnAPhoneAnd480OnATablet() {
        assertEquals(400.dp, authColumnMax(expanded = false))
        assertEquals(480.dp, authColumnMax(expanded = true))
    }

    @Test fun signInPutsTheArtOnTheLeftOnlyWhenWiderThanTall() {
        assertTrue(signInSplit(915.dp, 412.dp))
        assertFalse(signInSplit(412.dp, 915.dp))
        assertFalse("정사각형은 세로 배치", signInSplit(600.dp, 600.dp))
        assertTrue("낮 안개는 위쪽 55 %", SIGN_IN_HAZE_FRACTION == .55f)
    }

    @Test fun chosenCardLaysTheSelectionOverOpaqueGlass() {
        listOf(DaySceneColors, NightSceneColors).forEach { scene ->
            assertEquals("고르지 않은 카드는 유리 그대로", scene.glass, choiceCardColor(false, scene.glass, scene.selectionFill))
            val chosen = choiceCardColor(true, scene.glass, scene.selectionFill)
            assertEquals("고른 카드도 불투명", 1f, chosen.alpha, 0f)
            assertNotEquals("고른 카드는 채움이 보임", scene.glass, chosen)
        }
    }

    @Test fun theNightScrimLiesOverTheBrandBlockAndStopsShortOfTheMoon() {
        // The 1080 × 2400 px phone (2.625 px per dp): the brand block as measured, a 16-dp margin (42 px), a 64-dp fade (168 px).
        val art = Size(1080f, 2400f)
        val core = signInNightScrim(Rect(52.5f, 158f, 582.4f, 561.2f), art, 42f)
        assertEquals("글자 블록 + 여백, 픽셀 단위로 올림", Rect(0f, 0f, 625f, 604f), core)
        assertTrue("흐려지는 끝도 달(가로 82 %)보다 왼쪽", core!!.right + 168f < art.width * .82f)
        assertNull("재기 전에는 그리지 않음", signInNightScrim(Rect.Zero, art, 42f))
        assertNull("그림 밖의 글자", signInNightScrim(Rect(1200f, 100f, 1500f, 400f), art, 42f))
        assertEquals("그림을 넘지 않음", Rect(0f, 0f, 1080f, 342f), signInNightScrim(Rect(0f, 0f, 1070f, 300f), art, 42f))
        assertTrue("밤 가림막은 배경색 .70", SIGN_IN_NIGHT_SCRIM_ALPHA == .70f)
    }
}
