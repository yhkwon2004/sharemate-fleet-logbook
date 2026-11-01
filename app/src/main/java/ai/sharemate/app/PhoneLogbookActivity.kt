package ai.sharemate.app

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

/*
 * The phone activity owns what needs an Activity: the sync client, the camera and document launchers, the Excel file,
 * and the two things that stay modal (replacing the stored copy, a damaged file). Everything else is PhoneShell.
 */

class PhoneLogbookActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_AUTOMOTIVE)) { finish(); return }
        setContent { SharemateTheme(compact = true) { PhoneLogbookApp(intent?.data) } }
    }
}

private val STATEMENT_TYPES = arrayOf("text/csv", "text/comma-separated-values", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", "application/vnd.ms-excel", "text/html", "*/*")
private val LOGBOOK_TYPES = arrayOf(LOGBOOK_MIME, "application/json", "application/octet-stream")

/** Reads a logbook file fully, refusing anything larger than the codec allows; an open trip in a file is a corrupt copy. */
private fun readLogbook(context: android.content.Context, uri: Uri): TripLogData {
    val bytes = context.contentResolver.openInputStream(uri)?.use { stream ->
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val read = stream.read(buffer)
            if (read < 0) break
            require(output.size() + read <= LOGBOOK_MAX_BYTES) { "파일이 너무 커요." }
            output.write(buffer, 0, read)
        }
        output.toByteArray()
    } ?: throw IOException()
    return TripLogCodec.decode(bytes).also { require(it.open == null) { "기록 중인 운행이 들어 있는 파일은 가져올 수 없어요." } }
}

@Composable private fun PhoneLogbookApp(initialUri: Uri?) {
    val context = LocalContext.current
    val repository = remember { TripLogRepository.get(context) }
    val sync = remember { PhoneSyncClient.get(context) }
    val state by repository.state.collectAsState()
    val link by sync.link.collectAsState()
    val photoVersion by sync.photoVersion.collectAsState()
    val queuedCards by sync.queued.collectAsState()
    var paired by remember { mutableStateOf(sync.paired) }
    var pendingCopy by remember { mutableStateOf<TripLogData?>(null) }
    var fileError by remember { mutableStateOf<String?>(null) }
    var hostBusy by remember { mutableStateOf(false) }
    var exportName by rememberSaveable { mutableStateOf<String?>(null) }
    val zone = remember { ZoneId.systemDefault() }
    val scope = rememberCoroutineScope()
    val exportFile = remember { File(context.cacheDir, "phone-logbook.xlsx") }

    // One bar for every confirmation and every recoverable error; the dispatcher and the runner refer to each other.
    val dispatchRef = remember { mutableStateOf<(FollowUp) -> Unit>({}) }
    fun hostRun(block: suspend () -> Unit) = phoneRun(scope, onError = { dispatchRef.value(FollowUp(it)) }, onBusy = { hostBusy = it }, block = block)
    val feedback = rememberPhoneFeedback(::hostRun)
    val followUp = feedback.second
    SideEffect { dispatchRef.value = followUp }

    LaunchedEffect(Unit) { repository.ensureLoaded(); sync.start() }

    // Camera: the capture is shrunk, stored under a fresh id, sent to the car, and the original deleted; the bar shows the real steps.
    var captureFor by rememberSaveable { mutableStateOf<String?>(null) }
    val captureFile = remember { File(File(context.filesDir, "evidence").apply { mkdirs() }, "incident-capture.jpg") }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val incidentId = captureFor
        captureFor = null
        if (ok && incidentId != null) hostRun {
            followUp(FollowUp("촬영본을 차량에 보내고 있어요"))
            val id = java.util.UUID.randomUUID().toString()
            val bytes = withContext(Dispatchers.IO) { try { shrinkJpeg(captureFile) } finally { captureFile.delete() } }
            withContext(Dispatchers.IO) { repository.photos.save(id, bytes) }
            try { sync.uploadPhoto(incidentId, id, bytes) }
            catch (e: Exception) { withContext(Dispatchers.IO) { repository.photos.delete(id) }; throw e }
            sync.photoVersion.value += 1
            followUp(FollowUp("사진을 차량에 저장했어요 · 사고 기록에서 볼 수 있어요"))
        } else captureFile.delete()
    }
    val capture: (String) -> Unit = { incidentId ->
        captureFor = incidentId
        runCatching { camera.launch(FileProvider.getUriForFile(context, "${context.packageName}.evidence", captureFile)) }
            .onFailure { captureFor = null; followUp(FollowUp("카메라를 열 수 없어요 · 카메라 앱이 있는지 확인해 주세요")) }
    }
    val fetchPhoto: (String) -> Unit = { id -> scope.launch { runCatching { sync.fetchPhoto(id) } } }

    // Re-read the notification-access grant when the user returns from system settings.
    var cardAlerts by remember { mutableStateOf(CardNotificationService.granted(context)) }
    val lifecycle = LocalLifecycleOwner.current
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) cardAlerts = CardNotificationService.granted(context) }
        lifecycle.lifecycle.addObserver(observer)
        onDispose { lifecycle.lifecycle.removeObserver(observer) }
    }
    val statement = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) hostRun {
            val all = withContext(Dispatchers.IO) {
                val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: throw IOException()
                parseStatementFile(bytes, zone)
            }
            val relevant = vehicleRelevant(all, repository.state.value.data, zone)
            sync.enqueue(relevant)
            followUp(FollowUp(if (relevant.isEmpty()) "명세서 ${all.size}건 중 차량 관련 결제가 없었어요" else "명세서 ${all.size}건 중 차량 관련 ${relevant.size}건을 차량으로 보냈어요 · 애매한 건만 확인을 요청해요"))
        }
    }
    val openAlertSettings = { context.startActivity(Intent(android.provider.Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
    val importStatement = { statement.launch(STATEMENT_TYPES) }

    // Replacing the stored copy is the one phone action that cannot be undone, so it asks first; a damaged file is a modal too.
    val import: (Uri) -> Unit = { uri ->
        scope.launch {
            hostBusy = true
            try { pendingCopy = withContext(Dispatchers.IO) { readLogbook(context, uri) } }
            catch (e: CancellationException) { throw e }
            catch (e: IllegalArgumentException) { fileError = e.message ?: "SHAREMATE 운행파일인지 확인해 주세요." }
            catch (_: Exception) { fileError = "파일을 읽지 못했어요. SHAREMATE 운행파일(.smlog)인지, 저장 공간이 있는지 확인해 주세요. 기존 사본은 그대로예요." }
            finally { hostBusy = false }
        }
    }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) import(uri) }
    LaunchedEffect(initialUri) { if (initialUri != null) import(initialUri) }
    val pickFile: () -> Unit = { importer.launch(LOGBOOK_TYPES) }

    // Excel: the tab builds the file and names it; the saver copies it where the user chose and the bar offers to open it.
    val saver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(XLSX_MIME)) { uri ->
        val name = exportName
        exportName = null
        if (uri != null && name != null) hostRun {
            withContext(Dispatchers.IO) { context.contentResolver.openOutputStream(uri, "wt")?.use { out -> exportFile.inputStream().use { it.copyTo(out) } } ?: throw IOException() }
            followUp(FollowUp("Excel 파일을 저장했어요", "열기") {
                try { context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, XLSX_MIME).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)) }
                catch (_: ActivityNotFoundException) { followUp(FollowUp("Excel을 열 수 있는 앱이 없어요 · 파일 앱에서 공유할 수 있어요")) }
            })
        }
    }
    val exportXlsx: suspend (List<Trip>, String) -> Unit = { trips, name ->
        val data = repository.state.value.data
        val dates = trips.map { localDate(it.startAt, zone) }
        val from = dates.minOrNull()?.withDayOfMonth(1) ?: LocalDate.now(zone).withDayOfMonth(1)
        val to = dates.maxOrNull()?.let { YearMonth.from(it).atEndOfMonth() } ?: YearMonth.now(zone).atEndOfMonth()
        val bytes = withContext(Dispatchers.Default) { logbookXlsx(data.profile, from, to, trips, data.drivers, zone, data.maintenance, data.care) }
        withContext(Dispatchers.IO) { exportFile.writeBytes(bytes) }
        exportName = name
        saver.launch(name)
    }

    val host = remember(sync, repository) {
        PhoneHost(repository, sync, capture = capture, fetchPhoto = fetchPhoto, importStatement = importStatement, pickFile = pickFile, exportXlsx = exportXlsx,
            openAlertSettings = openAlertSettings, pair = { endpoint, code -> sync.pair(endpoint, code); paired = true }, unpair = { sync.unpair(); paired = false },
            expensesTab = { args -> PhoneExpensesPlaceholder(args) }, reportTab = { args -> PhoneReportPlaceholder(args) }, assistant = null)
    }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        if (!state.loaded) PhoneHomeSkeleton(Modifier.fillMaxSize().safeDrawingPadding())
        else PhoneShell(state, link, paired, host, feedback, cardAlerts, queuedCards, photoVersion, hostBusy)
    }

    pendingCopy?.let { copy ->
        val current = state.data
        AlertDialog(onDismissRequest = { pendingCopy = null }, title = { Text("운행기록을 가져올까요?") },
            text = { Text("${copy.profile.companyName.ifBlank { "법인명 미입력" }} · ${copy.profile.plateNumber.ifBlank { "차량번호 미입력" }}\n\n휴대폰 사본 ${current.trips.size}건 → 파일 ${copy.trips.size}건\n\n휴대폰의 사본을 이 파일로 교체해요. 차량 원본에는 영향을 주지 않아요.") },
            confirmButton = { TextButton(onClick = { pendingCopy = null; hostRun { repository.importPhoneCopy(copy); followUp(FollowUp("운행파일 ${copy.trips.size}건을 가져왔어요")) } }, enabled = !hostBusy) { Text("가져오기") } },
            dismissButton = { TextButton(onClick = { pendingCopy = null }) { Text("취소") } })
    }
    (fileError ?: state.error)?.let { text ->
        AlertDialog(onDismissRequest = { fileError = null; repository.clearError() }, title = { Text("파일을 확인해 주세요") }, text = { Text(text) },
            confirmButton = { TextButton(onClick = { fileError = null; repository.clearError() }) { Text("확인") } })
    }
}

/*
 * The 경비 and 현황 tab bodies are built by WP5 as PhoneExpensesTab(...) and PhoneReportTab(...). Until the orchestrator
 * wires them, these placeholders keep the shell honest: they say what is coming and offer the actions that already exist.
 */
@Composable private fun PhoneExpensesPlaceholder(args: PhoneExpensesArgs) {
    Column(Modifier.fillMaxSize().padding(20.dp)) {
        PhoneEmpty("경비 탭을 준비하고 있어요", "카드 확인 ${args.data.charges.count { it.state == ChargeState.REVIEW && it.demo == args.data.profile.demoMode }}건 · 홈에서 처리할 수 있어요")
    }
}

@Composable private fun PhoneReportPlaceholder(args: PhoneReportArgs) {
    Column(Modifier.fillMaxSize().padding(20.dp)) {
        PhoneEmpty("현황 탭을 준비하고 있어요", "${args.month.year}년 ${args.month.monthValue}월 · 운행 ${tripsIn(phoneTrips(args.data), args.month.atDay(1), args.month.atEndOfMonth(), ZoneId.systemDefault()).size}건")
    }
}
