package ai.sharemate.app

import ai.pleos.playground.navi.data.CurrentLocationInfo
import ai.pleos.playground.navi.constants.NaviErrorCode
import ai.pleos.playground.navi.data.DestinationArrivedInfo
import ai.pleos.playground.navi.data.DestinationInfo
import ai.pleos.playground.navi.data.RouteStartInfo
import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener
import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.listener.DistanceDrivenListener
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.car.Car
import android.car.VehicleGear
import android.car.VehiclePropertyIds
import android.car.hardware.CarPropertyValue
import android.car.hardware.property.CarPropertyManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Locale

/** Vehicle signals the trip detector needs: gear, odometer, and where the car is. */
interface DriveSignals {
    val samples: StateFlow<DriveSample?>
    val status: StateFlow<SignalStatus>
    val model: StateFlow<String?>
    fun start()
    fun stop()
}

private fun gearState(value: Int?): GearState = when (value) {
    VehicleGear.GEAR_PARK -> GearState.PARK
    VehicleGear.GEAR_DRIVE, VehicleGear.GEAR_REVERSE, VehicleGear.GEAR_NEUTRAL -> GearState.MOVING
    else -> GearState.UNKNOWN
}

internal fun driveSignalsFresh(gear: GearState, odometer: Double?, gearAgeMillis: Long, odometerAgeMillis: Long): Boolean =
    gear != GearState.UNKNOWN && odometer != null && odometer.isFinite() && odometer in 0.0..10_000_000.0 &&
        gearAgeMillis in 0..45_000 && odometerAgeMillis in 0..45_000

internal fun freshNavigationAddress(address: String?, ageMillis: Long): String? =
    address?.trim()?.take(120)?.takeIf { it.isNotEmpty() && ageMillis in 0..45_000 }

/** Reads the connected Pleos vehicle only; it never writes vehicle settings or changes the navigation route. */
class PleosDriveSignals(context: Context) : DriveSignals {
    private val context = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mutableSamples = MutableStateFlow<DriveSample?>(null)
    private val mutableStatus = MutableStateFlow(SignalStatus.STARTING)
    private val mutableModel = MutableStateFlow<String?>(null)
    override val samples = mutableSamples.asStateFlow()
    override val status = mutableStatus.asStateFlow()
    override val model = mutableModel.asStateFlow()
    private var vehicle: Vehicle? = null
    private var car: Car? = null
    private var properties: CarPropertyManager? = null
    private var navi: NaviHelper? = null
    private var naviListener: NaviHelperEventListener? = null
    private var distanceListener: DistanceDrivenListener? = null
    private var heartbeat: Job? = null
    private var gear = GearState.UNKNOWN
    private var odometer: Double? = null
    private var address: String? = null
    private var addressAt = -1L
    private var destination: String? = null
    private var naviReady = false
    private var active = false
    private var odometerAt = -1L
    private var gearAt = -1L
    private var gearGeneration = 0
    private var waitingForParkDistance = false
    private var gearFailure: SignalStatus? = null
    private var odometerFailure: SignalStatus? = null

    private val gearCallback = object : CarPropertyManager.CarPropertyEventCallback {
        override fun onChangeEvent(value: CarPropertyValue<*>) {
            scope.launch { if (active) onGear(if (value.status == CarPropertyValue.STATUS_AVAILABLE) gearState(value.value as? Int) else GearState.UNKNOWN) }
        }
        override fun onErrorEvent(propertyId: Int, zone: Int) {
            scope.launch { if (active) onGear(GearState.UNKNOWN) }
        }
    }

    override fun start() {
        if (active) return
        active = true
        if (!context.packageManager.hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE)) {
            mutableStatus.value = SignalStatus.UNSUPPORTED
            return
        }
        connectOdometer()
        connectGear()
        connectNavigation()
        heartbeat = scope.launch {
            var ticks = 0
            while (isActive) {
                delay(15_000)
                ticks++
                // Listeners carry changes; the heartbeat refreshes values a platform may not push and keeps the trip alive.
                readGear()
                if (ticks % 2 == 0) readOdometer()
                if (gear == GearState.MOVING && ticks % 4 == 0) requestLocation()
                emit()
            }
        }
    }

    private fun connectOdometer() {
        try {
            val v = Vehicle(context).also { it.initialize() }
            vehicle = v
            val listener = object : DistanceDrivenListener {
                override fun onDistanceDrivenUpdated(value: Float?) { scope.launch { if (active) onOdometer(value) } }
                override fun onFailed(e: Exception) { scope.launch { if (active) onOdometerFailure(e) } }
            }
            v.getOdometer().checkCurrentDistanceDrivenCapabilities({
                scope.launch {
                    if (!active) return@launch
                    runCatching { v.getOdometer().registerDistanceDriven(listener); distanceListener = listener }
                    readOdometer()
                }
            }, { error -> scope.launch { if (active) onOdometerFailure(error) } })
        } catch (error: Exception) {
            onOdometerFailure(error)
        }
    }

    private fun readOdometer() {
        val v = vehicle ?: return
        val generation = gearGeneration
        runCatching { v.getOdometer().getCurrentDistanceDriven({ value -> scope.launch { if (active) onOdometer(value, generation) } },
            { error -> scope.launch { if (active && generation == gearGeneration) onOdometerFailure(error) } }) }
            .onFailure { onOdometerFailure(it as? Exception ?: IllegalStateException()) }
    }

    private fun onOdometer(value: Float?, readGeneration: Int? = null) {
        if (readGeneration != null && readGeneration != gearGeneration) return
        val km = value?.toDouble()?.takeIf { it.isFinite() && it in 0.0..10_000_000.0 }
            ?: return onOdometerFailure(IllegalStateException())
        odometer = km
        odometerAt = SystemClock.elapsedRealtime()
        odometerFailure = null
        if (readGeneration == gearGeneration) waitingForParkDistance = false
        emit()
    }

    private fun onOdometerFailure(error: Exception) {
        odometerAt = -1
        odometerFailure = if (failureStatus(error) == DataStatus.DENIED) SignalStatus.DENIED else SignalStatus.ERROR
        emit()
    }

    private fun connectGear() {
        try {
            car = Car.createCar(context, null, Car.CAR_WAIT_TIMEOUT_DO_NOT_WAIT) { connected, ready ->
                scope.launch {
                    if (!active) return@launch
                    if (!ready) { properties = null; onGear(GearState.UNKNOWN); return@launch }
                    val manager = runCatching { connected.getCarManager(Car.PROPERTY_SERVICE) as? CarPropertyManager }.getOrNull() ?: return@launch
                    properties = manager
                    runCatching {
                        @Suppress("DEPRECATION")
                        check(manager.registerCallback(gearCallback, VehiclePropertyIds.GEAR_SELECTION, CarPropertyManager.SENSOR_RATE_ONCHANGE))
                    }.onFailure { onGearFailure(it) }
                    readGear()
                    runCatching { manager.getProperty(String::class.java, VehiclePropertyIds.INFO_MODEL, 0)?.value?.takeIf { it.isNotBlank() } }
                        .getOrNull()?.let { mutableModel.value = it.take(60) }
                }
            }
        } catch (error: Exception) {
            onGearFailure(error)
        }
    }

    private fun readGear() {
        val manager = properties ?: return
        runCatching {
            val value = manager.getProperty(Int::class.javaObjectType, VehiclePropertyIds.GEAR_SELECTION, 0)
            onGear(if (value?.status == CarPropertyValue.STATUS_AVAILABLE) gearState(value.value) else GearState.UNKNOWN)
        }.onFailure { onGearFailure(it) }
    }

    private fun onGearFailure(error: Throwable) {
        onGear(GearState.UNKNOWN)
        gearFailure = if (error is SecurityException) SignalStatus.DENIED else SignalStatus.ERROR
        emit()
    }

    private fun onGear(value: GearState) {
        gearAt = SystemClock.elapsedRealtime()
        gearFailure = if (value == GearState.UNKNOWN) SignalStatus.ERROR else null
        if (value == gear) return
        gear = value
        gearGeneration++
        invalidateLocation()
        // A cached distance from before P would omit the last segment of the trip.
        waitingForParkDistance = value == GearState.PARK
        readOdometer()
        // Start and end addresses come from the vehicle navigation, not from a phone.
        requestLocation()
        emit()
    }

    private fun connectNavigation() {
        if (context.checkSelfPermission("pleos.car.permission.NAVI_ROUTE") != PackageManager.PERMISSION_GRANTED) return
        try {
            val helper = NaviHelper(context)
            navi = helper
            val listener = object : NaviHelperEventListener {
                override fun onCurrentLocationInfo(currentLocationInfo: CurrentLocationInfo) {
                    scope.launch {
                        if (!active || !naviReady) return@launch
                        address = currentLocationInfo.address
                        // SDK callbacks expose no acquisition timestamp; this bounds reception age only.
                        addressAt = SystemClock.elapsedRealtime()
                        emit()
                    }
                }
                override fun onDestinationInfo(destinationInfo: DestinationInfo) {
                    scope.launch { if (active) { destination = destinationInfo.name.trim().take(80).ifBlank { null }; emit() } }
                }
                override fun onRouteStarted(routeStartInfo: RouteStartInfo) {
                    scope.launch { if (active) { destination = routeStartInfo.name.trim().take(80).ifBlank { null }; emit() } }
                }
                override fun onDestinationArrived(destinationArrivedInfo: DestinationArrivedInfo) {
                    scope.launch {
                        if (!active) return@launch
                        destination = destinationArrivedInfo.name.trim().take(80).ifBlank { destination }
                        // A destination's POI address is route context, not a measured arrival position.
                        requestLocation()
                        emit()
                    }
                }
                override fun onRouteCancelled() {
                    scope.launch {
                        if (!active) return@launch
                        destination = null
                        invalidateLocation()
                        emit()
                        requestLocation()
                    }
                }
                override fun onError(errorCode: NaviErrorCode) {
                    scope.launch { if (active) { invalidateNavigation(); emit() } }
                }
            }
            naviListener = listener
            helper.initialize(onConnected = {
                scope.launch {
                    if (!active) return@launch
                    naviReady = true
                    runCatching { helper.addListener(listener); requestLocation() }
                        .onFailure { naviReady = false; invalidateNavigation(); emit() }
                }
            }, onFailed = {
                scope.launch { if (active) { naviReady = false; invalidateNavigation(); emit() } }
            })
        } catch (_: Exception) {
            navi = null
            naviReady = false
            invalidateNavigation()
            emit()
        }
    }

    private fun invalidateLocation() { address = null; addressAt = -1L }

    private fun invalidateNavigation() { invalidateLocation(); destination = null }

    private fun requestLocation() {
        if (!naviReady) { invalidateLocation(); return }
        runCatching { navi?.getCurrentLocationInfo() }
            .onFailure { invalidateNavigation(); emit() }
    }

    private fun emit() {
        val now = SystemClock.elapsedRealtime()
        val distance = odometer.takeUnless { waitingForParkDistance }
        val fresh = driveSignalsFresh(gear, distance, if (gearAt < 0) Long.MAX_VALUE else now - gearAt,
            if (odometerAt < 0) Long.MAX_VALUE else now - odometerAt)
        mutableStatus.value = if (fresh) SignalStatus.READY else gearFailure ?: odometerFailure ?:
            if (gearAt < 0 || odometerAt < 0 || waitingForParkDistance) SignalStatus.STARTING else SignalStatus.ERROR
        mutableSamples.value = DriveSample(System.currentTimeMillis(), if (fresh) gear else GearState.UNKNOWN,
            if (fresh) distance else null, freshNavigationAddress(address, if (addressAt < 0) Long.MAX_VALUE else now - addressAt), destination)
    }

    override fun stop() {
        active = false
        naviReady = false
        invalidateNavigation()
        heartbeat?.cancel(); heartbeat = null
        runCatching { properties?.unregisterCallback(gearCallback) }
        distanceListener?.let { listener -> runCatching { vehicle?.getOdometer()?.unregisterDistanceDriven(listener) } }
        naviListener?.let { listener -> runCatching { navi?.removeListener(listener) } }
        runCatching { navi?.release() }
        runCatching { vehicle?.release() }
        runCatching { car?.disconnect() }
        properties = null; car = null; navi = null; naviListener = null; vehicle = null; distanceListener = null
        scope.cancel()
    }
}

/** Presentation drive at 15x speed, always labelled as demo data and kept on its own odometer chain. */
object DemoDrive {
    private val mutableMoving = MutableStateFlow(false)
    val moving: StateFlow<Boolean> = mutableMoving.asStateFlow()
    fun setMoving(value: Boolean) { mutableMoving.value = value }
}

private val demoPlaces = listOf(
    "서울 중구 세종대로" to "본사", "서울 강남구 테헤란로" to "강남 거래처", "경기 성남시 분당구 판교역로" to "판교 연구소",
    "서울 영등포구 여의대로" to "여의도 지점", "경기 수원시 영통구 광교로" to "광교 대리점",
)

class DemoDriveSignals(private val startOdometerKm: Double, private val tripCount: Int) : DriveSignals {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mutableSamples = MutableStateFlow<DriveSample?>(null)
    override val samples = mutableSamples.asStateFlow()
    override val status = MutableStateFlow(SignalStatus.DEMO).asStateFlow()
    override val model = MutableStateFlow<String?>("시연 차량").asStateFlow()
    private var job: Job? = null

    override fun start() {
        if (job?.isActive == true) return
        job = scope.launch {
            var odometer = startOdometerKm
            var leg = tripCount
            var moving = false
            while (isActive) {
                val nowMoving = DemoDrive.moving.value
                if (nowMoving && !moving) leg++
                moving = nowMoving
                // 60 km/h at 15x speed: 0.25 km per second while moving.
                if (moving) odometer += 0.25
                val (from, _) = demoPlaces[(leg - 1).mod(demoPlaces.size)]
                val (to, name) = demoPlaces[leg.mod(demoPlaces.size)]
                mutableSamples.value = DriveSample(System.currentTimeMillis(), if (moving) GearState.MOVING else GearState.PARK,
                    odometer, if (moving) from else to, name, demo = true)
                delay(1_000)
            }
        }
    }

    override fun stop() { job?.cancel(); job = null; scope.cancel() }
}

/** Keeps recording in the background so trips are logged without opening the app, like a built-in feature. */
class TripLogService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var signals: DriveSignals? = null
    private var signalJobs: List<Job> = emptyList()
    private var demoMode: Boolean? = null

    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "운행 자동 기록", NotificationManager.IMPORTANCE_LOW))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
            startForeground(NOTIFICATION_ID, notification("자동 기록 준비 중"), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(NOTIFICATION_ID, notification("자동 기록 준비 중"))
        val repository = TripLogRepository.get(this)
        scope.launch {
            repository.ensureLoaded()
            repository.state.map { it.data.profile.demoMode }.distinctUntilChanged().collect { demo -> switchSignals(demo) }
        }
        scope.launch {
            repository.state.map { notificationText(it) }.distinctUntilChanged().collect { text ->
                runCatching { manager.notify(NOTIFICATION_ID, notification(text)) }
            }
        }
    }

    private fun switchSignals(demo: Boolean) {
        if (demoMode == demo && signals != null) return
        demoMode = demo
        signalJobs.forEach { it.cancel() }
        signals?.stop()
        val repository = TripLogRepository.get(this)
        val data = repository.state.value.data
        val next = if (demo) {
            val demoTrips = data.trips.filter { it.demo }
            DemoDriveSignals(data.open?.takeIf { it.demo }?.endOdometerKm ?: demoTrips.maxOfOrNull { it.endOdometerKm } ?: 12_345.6, demoTrips.size)
        } else PleosDriveSignals(this)
        signals = next
        signalJobs = listOf(
            scope.launch { next.samples.filterNotNull().collect { sample ->
                try {
                    repository.ingest(sample)
                    repository.setSignal(next.status.value, next.model.value)
                } catch (error: CancellationException) { throw error }
                catch (_: Exception) { repository.recordingFailed() }
            } },
            scope.launch { next.status.collect { repository.setSignal(it, next.model.value) } },
            scope.launch { next.model.collect { repository.setSignal(next.status.value, it) } },
        )
        next.start()
    }

    private fun notificationText(state: TripLogState): String {
        val open = state.data.open
        return when {
            state.loadFailed || state.error != null -> "기록 저장 확인 필요 · 앱을 열어 확인해 주세요"
            state.signal !in setOf(SignalStatus.READY, SignalStatus.DEMO) -> state.signal.label
            open != null -> "운행 기록 중 · ${String.format(Locale.KOREA, "%.1f", open.distanceKm)} km"
            state.signal == SignalStatus.READY -> "자동 기록 대기 · 출발하면 기록을 시작해요"
            else -> state.signal.label
        }
    }

    private fun notification(text: String): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, LauncherActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle("SHAREMATE 운행일지")
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .build()
    }

    override fun onDestroy() {
        signalJobs.forEach { it.cancel() }
        signals?.stop()
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "trip_log"
        private const val NOTIFICATION_ID = 42
        fun start(context: Context) {
            if (!context.packageManager.hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE)) return
            runCatching { context.startForegroundService(Intent(context, TripLogService::class.java)) }
                .onFailure { TripLogRepository.get(context).recordingFailed() }
        }
    }
}

class TripLogBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) TripLogService.start(context)
    }
}
