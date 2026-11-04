package ai.sharemate.app

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/*
 * Who uses the phone (2.9): the Google sign-in screen over the night city, the three-step first run (내 정보 · 법인차량 등록 ·
 * 나는 누구인가요) and the account card on the settings page. The account lives on this phone only; registering a company car
 * means pairing with it (live sync, reservations) or typing its details until it can be paired.
 */

// ───────────────────────── pure helpers (unit-tested) ─────────────────────────

internal const val PROFILE_NAME_MAX = 30
internal const val PROFILE_DEPARTMENT_MAX = 30
internal const val ONBOARDING_STEPS = 3
internal const val VEHICLE_MODEL_MAX = 40
internal const val VEHICLE_COMPANY_MAX = 60

/** The three first-run steps, in order; the index is the step number the onboarding keeps. */
internal val onboardingStepTitles = listOf("내 정보", "법인차량 등록", "나는 누구인가요")

/** Trimmed, with runs of spaces folded to one, so "  김  서연 " is saved as "김 서연". */
internal fun cleanProfileText(text: String): String = text.trim().replace(Regex("\\s+"), " ")

internal data class ProfileErrors(val name: String?, val department: String?) {
    val valid get() = name == null && department == null
}

/** Name 1–30 characters, department up to 30 (optional), both after [cleanProfileText]. */
internal fun profileErrors(name: String, department: String): ProfileErrors {
    val n = cleanProfileText(name)
    val d = cleanProfileText(department)
    return ProfileErrors(
        name = when {
            n.isEmpty() -> "이름을 적어 주세요."
            n.length > PROFILE_NAME_MAX -> "이름은 ${PROFILE_NAME_MAX}자까지예요."
            else -> null
        },
        department = if (d.length > PROFILE_DEPARTMENT_MAX) "부서는 ${PROFILE_DEPARTMENT_MAX}자까지예요." else null,
    )
}

/** Throws [IllegalArgumentException] with the sentence the form shows; for the caller's onSaveProfile. */
internal fun validateProfile(name: String, department: String) {
    val errors = profileErrors(name, department)
    require(errors.valid) { errors.name ?: errors.department.orEmpty() }
}

/** Whether a step's continue button is enabled: the profile must be valid, step 2 needs one vehicle, step 3 is optional. */
internal fun onboardingCanContinue(step: Int, profileValid: Boolean, vehicleCount: Int): Boolean = when (step) {
    0 -> profileValid
    1 -> vehicleCount > 0
    else -> true
}

/** "1/3", "2/3", "3/3". */
internal fun onboardingStepText(step: Int): String = "${step.coerceIn(0, ONBOARDING_STEPS - 1) + 1}/$ONBOARDING_STEPS"

/** The letter in the avatar: the first character of the name (upper-cased), else of the e-mail, else "?". */
internal fun accountInitial(name: String, email: String = ""): String {
    val source = name.trim().ifEmpty { email.trim() }
    if (source.isEmpty()) return "?"
    val first = String(Character.toChars(source.codePointAt(0)))
    return first.uppercase()
}

private fun nameKey(text: String) = text.filterNot { it.isWhitespace() }.lowercase()

/**
 * The driver to preselect on step 3: the one already linked to the account, else the only driver whose name matches
 * [name] (spaces and case ignored), using [department] to tell apart two people with the same name. Null when unsure.
 */
internal fun preselectDriver(drivers: List<Driver>, name: String, department: String = "", current: String? = null): String? {
    if (current != null && drivers.any { it.id == current }) return current
    val key = nameKey(name)
    if (key.isEmpty()) return null
    val matches = drivers.filter { nameKey(it.name) == key }
    if (matches.size == 1) return matches.single().id
    if (matches.size > 1 && nameKey(department).isNotEmpty()) {
        val byDepartment = matches.filter { nameKey(it.department) == nameKey(department) }
        if (byDepartment.size == 1) return byDepartment.single().id
    }
    return null
}

/** The typed vehicle's problem in the form's words, or null when it can be registered (or nothing is typed yet). */
internal fun manualVehicleError(plate: String, model: String, company: String): String? =
    if (plate.isBlank()) null else runCatching { validateFleetVehicle(plate, model, company) }.exceptionOrNull()?.message

internal fun fleetVehicleStatus(vehicle: FleetVehicle): String = if (vehicle.paired) "연결됨" else "연결 전"

internal fun fleetVehicleDetail(vehicle: FleetVehicle): String =
    listOf(vehicle.model, vehicle.company).filter { it.isNotBlank() }.joinToString(" · ").ifBlank { "차종·회사 미등록" }

/** The settings card's employee line. */
internal fun accountDriverLine(driverLabel: String?): String =
    driverLabel?.takeIf { it.isNotBlank() }?.let { "운행기록부 사용자: $it" } ?: "직원 미지정"

/** Step 3 without a driver list: why it is empty, from whether a car is paired and whether it is reachable now. */
internal fun driverListNote(paired: Boolean, connected: Boolean): String = when {
    paired && connected -> "연결한 차량에 등록된 직원이 아직 없어요. 차량의 설정에서 직원을 추가하면 여기에 보여요."
    paired -> "차량과 다시 연결되면 직원 목록을 받아와요. 지금은 건너뛰어도 돼요."
    else -> "직원 목록은 차량과 연결하면 차량에서 받아와요. 지금은 건너뛰어도 돼요."
}

// ───────────────────────── sign-in ─────────────────────────

private const val PRESSED_NONE = 0
private const val PRESSED_GOOGLE = 1
private const val PRESSED_DEMO = 2

/**
 * The phone's first screen when nobody is signed in: the brand over the night sky, what the app does, and the Google button.
 * [googleAvailable] false (no OAuth client ID in this build) disables the button and says why. [onDemo] adds the demo
 * account button. [busy] shows progress on the button that was pressed; [error] sits under the buttons.
 * Draws its own background and safe-drawing insets: place it at the top level, not inside a padded Scaffold.
 */
@Composable
internal fun PhoneSignInScreen(
    modifier: Modifier = Modifier,
    googleAvailable: Boolean,
    busy: Boolean,
    error: String?,
    onGoogle: () -> Unit,
    onDemo: (() -> Unit)? = null,
) {
    var pressed by rememberSaveable { mutableIntStateOf(PRESSED_NONE) }
    Box(modifier.fillMaxSize().midnightBackdrop()) {
        HomeBackdrop(Modifier.matchParentSize(), horizonAt = .46f, heightScale = .30f, moonAt = Offset(.82f, .15f), leftScrim = false)
        BoxWithConstraints(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing), contentAlignment = Alignment.TopCenter) {
            Column(Modifier.widthIn(max = 480.dp).fillMaxWidth().verticalScroll(rememberScrollState()).heightIn(min = maxHeight)
                .padding(horizontal = 24.dp, vertical = 20.dp), verticalArrangement = Arrangement.SpaceBetween) {
                // On the dark sky, above the skyline.
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.semantics(mergeDescendants = true) { heading() }) {
                        Icon(painterResource(R.drawable.ic_splash_logo), contentDescription = null, tint = Midnight.brand, modifier = Modifier.size(40.dp))
                        Text("SHAREMATE", style = MaterialTheme.typography.titleMedium, color = Midnight.text, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                    }
                    Spacer(Modifier.height(8.dp))
                    Text("회사 차량을\n휴대폰에서 관리해요", style = MaterialTheme.typography.headlineMedium, color = Midnight.text, fontWeight = FontWeight.Bold,
                        modifier = Modifier.semantics { heading() })
                    Text("법인차량 운행일지 · 경비 · 예약", style = MaterialTheme.typography.bodyLarge, color = Midnight.textSecondary)
                }
                // On navy below the city.
                Column(Modifier.padding(top = 32.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Surface(shape = RoundedCornerShape(24.dp), color = Midnight.glass.copy(alpha = .86f), border = Midnight.edge, modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(horizontal = 20.dp, vertical = 18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            ValueLine("회사 차량 선택·예약")
                            ValueLine("원격으로 운행·비용 확인")
                            ValueLine("차량이 운행일지를 스스로 정리")
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    GoogleButton(enabled = googleAvailable, loading = busy && pressed != PRESSED_DEMO) {
                        if (!busy) { pressed = PRESSED_GOOGLE; onGoogle() }
                    }
                    if (!googleAvailable) Text("이 빌드에는 Google 로그인 설정(OAuth 클라이언트 ID)이 없어서 지금은 쓸 수 없어요.",
                        style = MaterialTheme.typography.bodySmall, color = Midnight.textSecondary, modifier = Modifier.padding(horizontal = 4.dp))
                    if (onDemo != null) {
                        val demoLoading = busy && pressed == PRESSED_DEMO
                        OutlinedButton(onClick = { if (!busy) { pressed = PRESSED_DEMO; onDemo() } }, shape = RoundedCornerShape(50), border = Midnight.edge,
                            colors = ButtonDefaults.outlinedButtonColors(containerColor = Midnight.glass.copy(alpha = .86f), contentColor = Midnight.text),
                            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
                            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).semantics { if (demoLoading) stateDescription = "시연 계정으로 들어가는 중" }) {
                            ButtonLabel("시연 계정으로 둘러보기", demoLoading, "들어가는 중…")
                        }
                        Text("시연 빌드 전용이에요. Google 계정 없이 화면을 둘러볼 수 있어요.", style = MaterialTheme.typography.bodySmall, color = Midnight.textMuted,
                            modifier = Modifier.padding(horizontal = 4.dp))
                    }
                    InlineError(error, Modifier.padding(horizontal = 4.dp))
                    Text("계정 정보는 이 휴대폰에만 저장돼요.", style = MaterialTheme.typography.labelMedium, color = Midnight.textMuted, textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(top = 6.dp))
                }
            }
        }
    }
}

@Composable private fun ValueLine(text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.size(28.dp).clip(CircleShape).background(Midnight.glow.copy(alpha = .14f)), contentAlignment = Alignment.Center) {
            Icon(Icons.Default.Check, contentDescription = null, tint = Midnight.glow, modifier = Modifier.size(16.dp))
        }
        Text(text, style = MaterialTheme.typography.titleSmall, color = Midnight.text)
    }
}

/** A neutral white pill with a plain "G" letter; not Google's logo artwork. */
@Composable private fun GoogleButton(enabled: Boolean, loading: Boolean, onClick: () -> Unit) {
    Button(onClick = onClick, enabled = enabled, shape = RoundedCornerShape(50),
        colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color(0xFF1F1F1F),
            disabledContainerColor = Midnight.glass, disabledContentColor = Midnight.textMuted),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).semantics { if (loading) stateDescription = "Google 로그인 중" }) {
        if (!loading) {
            Box(Modifier.size(26.dp).clip(CircleShape).background(if (enabled) Color(0xFFF1F3F4) else Color.Transparent)
                .border(1.dp, if (enabled) Color(0xFFDADCE0) else Midnight.glassBorder, CircleShape).clearAndSetSemantics {}, contentAlignment = Alignment.Center) {
                Text("G", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = LocalContentColor.current)
            }
            Spacer(Modifier.size(12.dp))
        }
        ButtonLabel("Google 계정으로 계속", loading, "Google 로그인 중…")
    }
}

@Composable private fun ButtonLabel(label: String, loading: Boolean, loadingLabel: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        if (loading) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = LocalContentColor.current)
        Text(if (loading) loadingLabel else label, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

// ───────────────────────── first-run onboarding ─────────────────────────

private const val VEHICLE_MODE_NONE = 0
private const val VEHICLE_MODE_PAIR = 1
private const val VEHICLE_MODE_MANUAL = 2

/**
 * The first run after sign-in, three steps with a back arrow between them: (1) name and department, (2) the company
 * vehicle, paired or typed, (3) which employee on the car's list this account is. Each step's inputs survive rotation.
 * Callbacks run in this screen's scope; an [IllegalArgumentException] message is shown inline, other failures as a
 * generic retry sentence. Draws its own background and safe-drawing insets.
 */
@Composable
internal fun PhoneOnboarding(
    modifier: Modifier = Modifier,
    account: PhoneAccount,
    fleet: FleetState,
    data: TripLogData,
    connected: Boolean,
    busy: Boolean,
    pairError: String?,
    onSaveProfile: suspend (name: String, department: String) -> Unit,
    onPair: suspend (endpoint: String, code: String) -> Unit,
    onAddManual: suspend (plate: String, model: String, company: String) -> Unit,
    onPickDriver: suspend (driverId: String?) -> Unit,
    onFinish: suspend () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val followUp = LocalFollowUp.current
    var step by rememberSaveable { mutableIntStateOf(0) }
    // Step 1
    var name by rememberSaveable { mutableStateOf(account.name) }
    var department by rememberSaveable { mutableStateOf(account.department) }
    var nameTouched by rememberSaveable { mutableStateOf(false) }
    var profileError by rememberSaveable { mutableStateOf<String?>(null) }
    // Step 2
    var vehicleMode by rememberSaveable { mutableIntStateOf(VEHICLE_MODE_NONE) }
    var plate by rememberSaveable { mutableStateOf("") }
    var model by rememberSaveable { mutableStateOf("") }
    var company by rememberSaveable { mutableStateOf("") }
    var manualError by rememberSaveable { mutableStateOf<String?>(null) }
    var pairLocalError by rememberSaveable { mutableStateOf<String?>(null) }
    // Step 3: until the user taps a row, the selection follows the preselection (the car's list may arrive after pairing).
    var picked by rememberSaveable { mutableStateOf<String?>(null) }
    var pickTouched by rememberSaveable { mutableStateOf(false) }
    var finishError by rememberSaveable { mutableStateOf<String?>(null) }

    var saving by remember { mutableStateOf(false) }
    var adding by remember { mutableStateOf(false) }
    var pairing by remember { mutableStateOf(false) }
    var finishing by remember { mutableStateOf(false) }
    val working = saving || adding || pairing || finishing

    val errors = profileErrors(name, department)
    val drivers = data.drivers
    val selectedDriver = if (pickTouched) picked?.takeIf { id -> drivers.any { it.id == id } }
        else preselectDriver(drivers, name, department, account.driverId)
    val forward = pageSwap(forward = true)
    val back = pageSwap(forward = false)

    BackHandler(enabled = step > 0 && !working) { step -= 1 }

    Box(modifier.fillMaxSize().midnightBackdrop()) {
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing), horizontalAlignment = Alignment.CenterHorizontally) {
            Column(Modifier.widthIn(max = 560.dp).fillMaxWidth().weight(1f)) {
                OnboardingHeader(step, canGoBack = step > 0 && !working) { step -= 1 }
                AnimatedContent(targetState = step, modifier = Modifier.weight(1f).fillMaxWidth(), label = "onboarding-step",
                    transitionSpec = { if (targetState > initialState) forward else back }) { shown ->
                    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(top = 8.dp, bottom = 20.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        when (shown) {
                            0 -> ProfileStep(account, name, department, if (nameTouched || name.isNotEmpty()) errors.name else null, errors.department,
                                onName = { if (it.length <= PROFILE_NAME_MAX || it.length < name.length) { name = it; nameTouched = true; profileError = null } },
                                onDepartment = { if (it.length <= PROFILE_DEPARTMENT_MAX || it.length < department.length) { department = it; profileError = null } })
                            1 -> VehicleStep(fleet, vehicleMode, { vehicleMode = it }, busy || pairing, pairLocalError ?: pairError,
                                onPair = { endpoint, code ->
                                    pairLocalError = null
                                    scope.launch {
                                        pairing = true
                                        try { onPair(endpoint, code); followUp(FollowUp("차량과 연결됐어요 · 기록을 받고 있어요")) }
                                        catch (e: CancellationException) { throw e }
                                        catch (e: IllegalArgumentException) { pairLocalError = e.message ?: PAIR_UNREACHABLE }
                                        catch (_: Exception) { pairLocalError = PAIR_UNREACHABLE }
                                        finally { pairing = false }
                                    }
                                },
                                plate = plate, model = model, company = company,
                                onPlate = { if (it.length <= 12) { plate = it; manualError = null } },
                                onModel = { if (it.length <= VEHICLE_MODEL_MAX) { model = it; manualError = null } },
                                onCompany = { if (it.length <= VEHICLE_COMPANY_MAX) { company = it; manualError = null } },
                                adding = adding, manualError = manualError,
                                onAdd = {
                                    val p = plate.trim(); val m = model.trim(); val c = company.trim()
                                    phoneRun(scope, onError = { manualError = it }, onBusy = { adding = it }) {
                                        onAddManual(p, m, c)
                                        plate = ""; model = ""; company = ""
                                        followUp(FollowUp("차량을 등록했어요 · $p"))
                                    }
                                })
                            else -> DriverStep(drivers, selectedDriver, fleet.vehicles.any { it.paired }, connected) { id -> picked = id; pickTouched = true; finishError = null }
                        }
                    }
                }
                // The step's actions stay at the bottom, above the keyboard.
                Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    InlineError(when (step) { 0 -> profileError; 2 -> finishError; else -> null }, Modifier.padding(horizontal = 4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        when (step) {
                            0 -> PhoneLoadingButton("다음", loading = saving, loadingLabel = "저장하는 중…", enabled = onboardingCanContinue(0, errors.valid, fleet.vehicles.size) && !working,
                                modifier = Modifier.weight(1f).heightIn(min = 56.dp)) {
                                val n = cleanProfileText(name); val d = cleanProfileText(department)
                                phoneRun(scope, onError = { profileError = it }, onBusy = { saving = it }) {
                                    onSaveProfile(n, d)
                                    name = n; department = d; step = 1
                                }
                            }
                            1 -> {
                                if (fleet.vehicles.isEmpty()) TextButton(onClick = { step = 2 }, enabled = !working, modifier = Modifier.heightIn(min = 56.dp)) {
                                    Text("나중에 등록", color = Midnight.textSecondary)
                                }
                                PhoneLoadingButton("다음", loading = false, enabled = onboardingCanContinue(1, errors.valid, fleet.vehicles.size) && !working,
                                    modifier = Modifier.weight(1f).heightIn(min = 56.dp)) { step = 2 }
                            }
                            else -> {
                                TextButton(onClick = {
                                    finishError = null
                                    phoneRun(scope, onError = { finishError = it }, onBusy = { finishing = it }) { onFinish() }
                                }, enabled = !working, modifier = Modifier.heightIn(min = 56.dp)) { Text("건너뛰기", color = Midnight.textSecondary) }
                                PhoneLoadingButton("시작하기", loading = finishing, loadingLabel = "준비하는 중…", enabled = !working,
                                    modifier = Modifier.weight(1f).heightIn(min = 56.dp)) {
                                    finishError = null
                                    val id = selectedDriver
                                    phoneRun(scope, onError = { finishError = it }, onBusy = { finishing = it }) { onPickDriver(id); onFinish() }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Back arrow (from step 2), the step's name, its number and three progress segments. */
@Composable private fun OnboardingHeader(step: Int, canGoBack: Boolean, onBack: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(start = 4.dp, end = 20.dp, top = 8.dp, bottom = 4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (step > 0) IconButton(onClick = onBack, enabled = canGoBack, modifier = Modifier.size(48.dp)) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "이전 단계", tint = Midnight.text)
            } else Spacer(Modifier.size(48.dp))
            Column(Modifier.weight(1f).padding(start = 4.dp).semantics(mergeDescendants = true) { heading() }) {
                Text("처음 설정", style = MaterialTheme.typography.labelMedium, color = Midnight.brand, letterSpacing = 0.6.sp)
                Text(onboardingStepTitles[step.coerceIn(0, ONBOARDING_STEPS - 1)], style = MaterialTheme.typography.titleMedium, color = Midnight.text,
                    fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Surface(shape = RoundedCornerShape(50), color = Midnight.glass, border = Midnight.edge,
                modifier = Modifier.semantics { contentDescription = "${ONBOARDING_STEPS}단계 중 ${step + 1}단계" }) {
                Text(onboardingStepText(step), style = MaterialTheme.typography.labelLarge, color = Midnight.text,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp).clearAndSetSemantics {})
            }
        }
        Row(Modifier.fillMaxWidth().padding(start = 16.dp).clearAndSetSemantics {}, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            repeat(ONBOARDING_STEPS) { index ->
                Box(Modifier.weight(1f).height(4.dp).clip(RoundedCornerShape(2.dp))
                    .background(if (index <= step) Midnight.glow else Color.White.copy(alpha = .14f)))
            }
        }
    }
}

@Composable private fun StepIntro(title: String, detail: String) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, style = MaterialTheme.typography.headlineSmall, color = Midnight.text, fontWeight = FontWeight.Bold, modifier = Modifier.semantics { heading() })
        Text(detail, style = MaterialTheme.typography.bodyMedium, color = Midnight.textSecondary)
    }
}

@Composable private fun ProfileStep(account: PhoneAccount, name: String, department: String, nameError: String?, departmentError: String?,
    onName: (String) -> Unit, onDepartment: (String) -> Unit) {
    StepIntro("운행기록부에 남길 이름을 확인해 주세요", "${account.provider.label} 이름을 가져왔어요. 차량에 등록된 직원 이름과 같게 맞추면 3단계에서 바로 찾아요.")
    Panel(color = Midnight.glass, padding = 20.dp) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.semantics(mergeDescendants = true) {}) {
            Avatar(accountInitial(name.ifBlank { account.name }, account.email), 44)
            Column(Modifier.weight(1f)) {
                Text(account.email, style = MaterialTheme.typography.titleSmall, color = Midnight.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(account.provider.label, style = MaterialTheme.typography.labelMedium, color = Midnight.textMuted)
            }
        }
        Field("이름", name, onName, error = nameError, hint = "${PROFILE_NAME_MAX}자까지")
        Field("부서", department, onDepartment, done = true, error = departmentError, hint = "선택 · ${PROFILE_DEPARTMENT_MAX}자까지")
    }
}

@Composable private fun VehicleStep(fleet: FleetState, mode: Int, onMode: (Int) -> Unit, pairBusy: Boolean, pairError: String?, onPair: (String, String) -> Unit,
    plate: String, model: String, company: String, onPlate: (String) -> Unit, onModel: (String) -> Unit, onCompany: (String) -> Unit,
    adding: Boolean, manualError: String?, onAdd: () -> Unit) {
    StepIntro("사용할 법인차량을 등록해요",
        "차량과 연결하면 운행 기록을 실시간으로 받고 예약할 수 있어요. 지금 연결할 수 없다면 차량 정보만 먼저 적어 두고 나중에 연결해도 돼요.")
    if (fleet.vehicles.isNotEmpty()) Panel(color = Midnight.glass, padding = 18.dp) {
        Eyebrow("등록한 차량 · ${fleet.vehicles.size}대", Midnight.brand)
        fleet.vehicles.forEach { vehicle -> RegisteredVehicleRow(vehicle) }
    }
    Row(Modifier.selectableGroup(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        ChoiceCard("차량과 연결", "실시간 기록 · 예약", R.drawable.ic_vehicle, mode == VEHICLE_MODE_PAIR, Modifier.weight(1f)) { onMode(VEHICLE_MODE_PAIR) }
        ChoiceCard("직접 등록", "차량번호를 먼저 적어 둬요", R.drawable.ic_journal, mode == VEHICLE_MODE_MANUAL, Modifier.weight(1f)) { onMode(VEHICLE_MODE_MANUAL) }
    }
    when (mode) {
        VEHICLE_MODE_PAIR -> PhoneConnectCard(pairBusy, pairError, onPair)
        VEHICLE_MODE_MANUAL -> Panel(color = Midnight.glass, padding = 20.dp) {
            Eyebrow("직접 등록", Midnight.brand)
            val plateError = manualVehicleError(plate, model, company)
            Field("차량번호", plate, onPlate, hint = "예: 12가 3456", error = plateError)
            Field("차종", model, onModel, hint = "선택 · ${VEHICLE_MODEL_MAX}자까지")
            Field("회사명", company, onCompany, done = true, hint = "선택 · ${VEHICLE_COMPANY_MAX}자까지")
            InlineError(manualError)
            PhoneLoadingButton("차량 등록", loading = adding, loadingLabel = "등록하는 중…", enabled = plate.isNotBlank() && plateError == null,
                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp), onClick = onAdd)
            Muted("직접 등록한 차량은 ‘연결 전’으로 남아요. 운행 기록과 예약은 차량과 연결한 뒤에 받아요.")
        }
        else -> Unit
    }
}

@Composable private fun RegisteredVehicleRow(vehicle: FleetVehicle) {
    val status = fleetVehicleStatus(vehicle)
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).semantics(mergeDescendants = true) {}, verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.size(32.dp).clip(CircleShape).background(if (vehicle.paired) Midnight.glow.copy(alpha = .16f) else Color.White.copy(alpha = .08f)),
            contentAlignment = Alignment.Center) {
            Icon(Icons.Default.Check, contentDescription = null, tint = if (vehicle.paired) Midnight.glow else Midnight.textSecondary, modifier = Modifier.size(18.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(vehicle.title, style = MaterialTheme.typography.titleSmall, color = Midnight.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(fleetVehicleDetail(vehicle), style = MaterialTheme.typography.bodySmall, color = Midnight.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text(status, style = MaterialTheme.typography.labelMedium, color = if (vehicle.paired) Midnight.glow else Midnight.textSecondary)
    }
}

/** A large glass choice: lit tile, title and one line; cyan edge when chosen. */
@Composable private fun ChoiceCard(title: String, detail: String, icon: Int, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(24.dp)
    Surface(shape = shape, color = if (selected) Color(0xFF173049) else Midnight.glass, border = if (selected) BorderStroke(1.5.dp, Midnight.glow) else Midnight.edge,
        modifier = modifier.clip(shape).selectable(selected = selected, role = Role.RadioButton, onClick = onClick)) {
        Column(Modifier.background(Brush.verticalGradient(listOf(Color.White.copy(alpha = .06f), Color.Transparent))).heightIn(min = 136.dp).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            val tile = RoundedCornerShape(14.dp)
            Box(Modifier.size(44.dp).clip(tile).background(Brush.linearGradient(listOf(Color(0x803B6FFF), Color(0x6639D5F0)))).border(Midnight.edge, tile),
                contentAlignment = Alignment.Center) { Icon(painterResource(icon), null, Modifier.size(22.dp), tint = Color(0xFFF2F8FF)) }
            Text(title, style = MaterialTheme.typography.titleSmall, color = if (selected) Midnight.glow else Midnight.text, fontWeight = FontWeight.Bold)
            Text(detail, style = MaterialTheme.typography.labelMedium, color = Midnight.textSecondary, maxLines = 2)
        }
    }
}

@Composable private fun DriverStep(drivers: List<Driver>, selected: String?, paired: Boolean, connected: Boolean, onPick: (String) -> Unit) {
    StepIntro("나는 누구인가요?", "차량에 등록된 직원 목록이에요. 나를 골라 두면 이 휴대폰의 계정과 연결돼요.")
    if (drivers.isEmpty()) Panel(color = Midnight.glass, padding = 20.dp) {
        Eyebrow("직원 목록", Midnight.brand)
        Text(driverListNote(paired, connected), style = MaterialTheme.typography.bodyMedium, color = Midnight.textSecondary,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
    } else Panel(color = Midnight.glass, padding = 12.dp) {
        Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            drivers.forEach { driver ->
                val on = driver.id == selected
                val shape = RoundedCornerShape(18.dp)
                Row(Modifier.fillMaxWidth().clip(shape).background(if (on) Midnight.glow.copy(alpha = .12f) else Color.Transparent)
                    .border(1.dp, if (on) Midnight.glow.copy(alpha = .5f) else Color.Transparent, shape)
                    .selectable(selected = on, role = Role.RadioButton) { onPick(driver.id) }
                    .heightIn(min = 56.dp).padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    RadioButton(selected = on, onClick = null, colors = RadioButtonDefaults.colors(selectedColor = Midnight.glow, unselectedColor = Midnight.textSecondary))
                    Column(Modifier.weight(1f)) {
                        Text(driver.name, style = MaterialTheme.typography.titleSmall, color = Midnight.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (driver.department.isNotBlank()) Text(driver.department, style = MaterialTheme.typography.bodySmall, color = Midnight.textMuted, maxLines = 1)
                    }
                }
            }
        }
    }
}

// ───────────────────────── account card (settings) ─────────────────────────

/** The circle with the account's initial; photos are not downloaded. */
@Composable private fun Avatar(initial: String, sizeDp: Int) {
    Box(Modifier.size(sizeDp.dp).clip(CircleShape).background(Brush.linearGradient(listOf(Color(0xFF3B6FFF), Color(0xFF39D5F0))))
        .clearAndSetSemantics {}, contentAlignment = Alignment.Center) {
        Text(initial, color = Color.White, fontWeight = FontWeight.Bold, fontSize = (sizeDp * .42f).sp)
    }
}

/**
 * The settings page's account card: initial, name, e-mail, provider, the linked employee and 로그아웃 behind a confirm
 * dialog. [driverLabel] is the linked driver's Driver.label, or null when none is linked.
 */
@Composable
internal fun PhoneAccountCard(
    modifier: Modifier = Modifier,
    account: PhoneAccount,
    driverLabel: String?,
    onSignOut: () -> Unit,
) {
    var confirming by rememberSaveable { mutableStateOf(false) }
    Panel(modifier, color = Midnight.glass, padding = 20.dp) {
        Eyebrow("계정", Midnight.brand)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp),
            modifier = Modifier.semantics(mergeDescendants = true) {}) {
            Avatar(accountInitial(account.name, account.email), 52)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(account.name + account.department.takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty(), style = MaterialTheme.typography.titleMedium,
                    color = Midnight.text, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(account.email, style = MaterialTheme.typography.bodySmall, color = Midnight.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Surface(shape = RoundedCornerShape(50), color = Color.White.copy(alpha = .08f), border = Midnight.edge) {
                Text(account.provider.label, style = MaterialTheme.typography.labelMedium, color = Midnight.text, maxLines = 1,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp))
            }
        }
        Text(accountDriverLine(driverLabel), style = MaterialTheme.typography.bodyMedium,
            color = if (driverLabel.isNullOrBlank()) Midnight.textMuted else Midnight.textSecondary)
        OutlinedButton(onClick = { confirming = true }, shape = RoundedCornerShape(14.dp), border = Midnight.edge,
            colors = ButtonDefaults.outlinedButtonColors(contentColor = Midnight.text), modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
            Text("로그아웃", style = MaterialTheme.typography.labelLarge)
        }
    }
    if (confirming) AlertDialog(onDismissRequest = { confirming = false },
        title = { Text("로그아웃할까요?") },
        text = { Text("등록한 차량과 기록 사본은 이 휴대폰에 남고, 다시 로그인하면 이어서 볼 수 있어요.") },
        confirmButton = { TextButton(onClick = { confirming = false; onSignOut() }, modifier = Modifier.heightIn(min = 48.dp)) { Text("로그아웃") } },
        dismissButton = { TextButton(onClick = { confirming = false }, modifier = Modifier.heightIn(min = 48.dp)) { Text("취소") } })
}
