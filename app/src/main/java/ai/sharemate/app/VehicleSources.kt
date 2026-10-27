package ai.sharemate.app

import ai.pleos.playground.navi.constants.NaviErrorCode
import ai.pleos.playground.navi.constants.NaviMode
import ai.pleos.playground.navi.constants.NavigationStatus
import ai.pleos.playground.navi.data.DestinationArrivedInfo
import ai.pleos.playground.navi.data.DestinationInfo
import ai.pleos.playground.navi.data.RouteStartInfo
import ai.pleos.playground.navi.data.RouteStateInfo
import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener
import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.model.area.DoorArea
import ai.pleos.playground.vehicle.api.model.area.SeatArea
import ai.pleos.playground.vehicle.api.model.area.WindowArea
import ai.pleos.playground.vehicle.api.model.capability.AccessType
import ai.pleos.playground.vehicle.api.model.capability.AreaBounds
import ai.pleos.playground.vehicle.api.model.capability.CapabilityConfig
import ai.pleos.playground.vehicle.constant.unit.TemperatureUnit
import ai.pleos.playground.vehicle.listener.EvBatteryStateOfChargeListener
import android.car.Car
import android.car.VehicleGear
import android.car.VehiclePropertyIds
import android.car.hardware.CarPropertyValue
import android.car.hardware.property.CarPropertyManager
import android.car.hardware.property.EvChargeState
import android.content.Context
import android.content.pm.PackageManager
import android.os.SystemClock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

interface VehicleSource {
    val snapshot: StateFlow<VehicleSnapshot>
    val route: StateFlow<RouteEstimate?>
    fun start()
    fun stop()
    fun refresh()
    fun close()
}

/** Reads the connected platform only. No source switching and no vehicle write APIs. */
class PleosVehicleSource(context: Context) : VehicleSource {
    private val context = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val state = MutableStateFlow(VehicleSnapshot())
    private val routeState = MutableStateFlow<RouteEstimate?>(null)
    override val snapshot = state.asStateFlow()
    override val route = routeState.asStateFlow()
    private var vehicle: Vehicle? = null
    private var navi: NaviHelper? = null
    private var naviListener: NaviHelperEventListener? = null
    private var socListener: EvBatteryStateOfChargeListener? = null
    private var car: Car? = null
    private var properties: CarPropertyManager? = null
    private var polling: Job? = null
    private var reading: Job? = null
    private var active = false
    private var generation = 0
    private var routeRevision = 0
    private var routeConfirmedAt = 0L

    override fun start() {
        if (active) return
        active = true
        val token = ++generation
        state.value = unavailableSnapshot(DataStatus.LOADING)
        if (!context.packageManager.hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE)) {
            state.value = unavailableSnapshot(DataStatus.UNSUPPORTED)
            return
        }
        try {
            vehicle = Vehicle(context)
            vehicle?.initialize()
        } catch (error: Exception) {
            state.value = unavailableSnapshot(failureStatus(error))
        }
        try {
            car = Car.createCar(context, null, Car.CAR_WAIT_TIMEOUT_DO_NOT_WAIT) { connected, ready ->
                if (!active || token != generation) return@createCar
                properties = if (ready) runCatching { connected.getCarManager(Car.PROPERTY_SERVICE) as? CarPropertyManager }.getOrNull() else null
                if (!ready) state.update { it.copy(parked = Reading(status = DataStatus.ERROR),
                    charging = Reading(status = DataStatus.ERROR), chargeRemainingSeconds = Reading(status = DataStatus.ERROR), rangeKm = Reading(status = DataStatus.ERROR),
                    carName = Reading(status = DataStatus.ERROR)) }
                refresh()
            }
        } catch (_: Exception) {
            properties = null
        }
        connectNavigation(token)
        polling = scope.launch {
            while (isActive) {
                refresh()
                delay(10_000)
            }
        }
    }

    private fun connectNavigation(token: Int) {
        if (context.checkSelfPermission("pleos.car.permission.NAVI_ROUTE") != PackageManager.PERMISSION_GRANTED) return
        try {
            val helper = NaviHelper(context)
            navi = helper
            val listener = object : NaviHelperEventListener {
                override fun onDestinationInfo(destinationInfo: DestinationInfo) {
                    onNavigationEvent(token) {
                        val now = System.currentTimeMillis()
                        if (routeConfirmedAt <= 0 || now < routeConfirmedAt || now - routeConfirmedAt > 4_000) return@onNavigationEvent
                        routeConfirmedAt = 0
                        routeState.value = destinationEstimate(destinationInfo, routeRevision, now)
                    }
                }
                override fun onRouteStateInfo(routeStateInfo: RouteStateInfo) {
                    onNavigationEvent(token) {
                        if (routeStateInfo.termsAgreement && routeStateInfo.isRouting && routeStateInfo.naviMode == NaviMode.ROUTE_GUIDING) {
                            routeConfirmedAt = System.currentTimeMillis()
                            try { helper.getDestinationInfo() } catch (_: Exception) { invalidateRoute() }
                        } else invalidateRoute()
                    }
                }
                override fun onDestinationChanged() { onNavigationEvent(token) { invalidateRoute() } }
                override fun onRouteStarted(routeStartInfo: RouteStartInfo) { onNavigationEvent(token) { invalidateRoute() } }
                override fun onRoutingCompleted() { onNavigationEvent(token) { requestRouteState() } }
                override fun onReRouteEnded() { onNavigationEvent(token) { invalidateRoute(); requestRouteState() } }
                override fun onRouteCancelled() { onNavigationEvent(token) { invalidateRoute() } }
                override fun onDestinationArrived(destinationArrivedInfo: DestinationArrivedInfo) {
                    onNavigationEvent(token) { invalidateRoute() }
                }
                override fun onNavigationStatus(status: NavigationStatus) {
                    if (status != NavigationStatus.ROUTE_DRIVE) onNavigationEvent(token) { invalidateRoute() }
                }
                override fun onError(errorCode: NaviErrorCode) { onNavigationEvent(token) { invalidateRoute() } }
            }
            naviListener = listener
            helper.initialize(onConnected = {
                onNavigationEvent(token) { helper.addListener(listener); requestRouteState() }
            }, onFailed = { onNavigationEvent(token) { invalidateRoute() } })
        } catch (_: Exception) { invalidateRoute() }
    }

    private fun onNavigationEvent(token: Int, block: () -> Unit) {
        scope.launch {
            if (active && generation == token) try { block() } catch (_: Exception) { invalidateRoute() }
        }
    }

    private fun requestRouteState() {
        try { navi?.getRouteStateInfo() } catch (_: Exception) { invalidateRoute() }
    }

    private fun invalidateRoute() {
        routeConfirmedAt = 0
        routeRevision++
        routeState.value = null
    }

    override fun refresh() {
        if (!active || reading?.isActive == true) return
        val v = vehicle
        val token = generation
        reading = scope.launch {
            requestRouteState()
            coroutineScope {
                if (v != null) {
                    launch {
                        val value = read {
                            requireRead(awaitResult<CapabilityConfig> { ok, fail -> v.getEvBattery().checkEvBatteryStateOfChargeCapabilities(ok, fail) })
                            val result = awaitResult<Int?> { ok, fail -> v.getEvBattery().getEvBatteryStateOfCharge(ok, fail) }
                            require(result == null || result in 0..100)
                            if (socListener == null && active && token == generation) {
                                val listener = object : EvBatteryStateOfChargeListener {
                                    override fun onEvBatteryStateOfChargeUpdated(value: Int?) {
                                        scope.launch { if (active && generation == token) state.update { it.copy(soc = socReading(value)) } }
                                    }
                                    override fun onFailed(e: Exception) {
                                        scope.launch { if (active && generation == token) state.update { it.copy(soc = Reading(status = failureStatus(e))) } }
                                    }
                                }
                                v.getEvBattery().registerEvBatteryStateOfCharge(listener)
                                socListener = listener
                            }
                            result
                        }
                        state.update { it.copy(soc = value) }
                    }
                    launch { val value = read { doorPositions(v) }; state.update { it.copy(doors = value) } }
                    launch { val value = read { doorLocks(v) }; state.update { it.copy(doorLocks = value) } }
                    launch { val value = read { windowPositions(v) }; state.update { it.copy(windows = value) } }
                    launch { val value = read { seatBelts(v) }; state.update { it.copy(belts = value) } }
                    launch { val value = read { climate(v) }; state.update { it.copy(hvac = value) } }
                    launch {
                        val value = read {
                            requireRead(awaitResult<CapabilityConfig> { ok, fail -> v.getOdometer().checkCurrentDistanceDrivenCapabilities(ok, fail) })
                            awaitResult<Float?> { ok, fail -> v.getOdometer().getCurrentDistanceDriven(ok, fail) }
                                ?.also { require(it.isFinite() && it in 0f..100_000_000f) }
                        }
                        state.update { it.copy(odometerKm = value) }
                    }
                    launch {
                        val value = read {
                            val safety = v.getSafety()
                            requireRead(awaitResult<CapabilityConfig> { ok, fail -> safety.checkPowerDownWarningIndicatorCapabilities(ok, fail) })
                            awaitResult<Boolean?> { ok, fail -> safety.hasPowerDownWarningIndicator(ok, fail) }
                                ?.let { if (it) "출력 제한 경고 있음 · 차량 안내 확인" else "" }
                        }
                        state.update { it.copy(warnings = value) }
                    }
                }
                launch {
                    val value = read { readProperty<Int>(VehiclePropertyIds.GEAR_SELECTION, Car.PERMISSION_POWERTRAIN)
                        ?.let { when (it) { VehicleGear.GEAR_PARK -> true
                            VehicleGear.GEAR_DRIVE, VehicleGear.GEAR_REVERSE, VehicleGear.GEAR_NEUTRAL,
                            VehicleGear.GEAR_FIRST, VehicleGear.GEAR_SECOND, VehicleGear.GEAR_THIRD,
                            VehicleGear.GEAR_FOURTH, VehicleGear.GEAR_FIFTH, VehicleGear.GEAR_SIXTH -> false
                            else -> null } } }
                    state.update { it.copy(parked = value) }
                }
                launch {
                    val value = read { readProperty<Int>(VehiclePropertyIds.EV_CHARGE_STATE, Car.PERMISSION_ENERGY)
                        ?.let { when (it) { EvChargeState.STATE_CHARGING -> true
                            EvChargeState.STATE_FULLY_CHARGED, EvChargeState.STATE_NOT_CHARGING -> false
                            EvChargeState.STATE_ERROR -> error("Charging state error")
                            else -> null } } }
                    state.update { it.copy(charging = value) }
                }
                launch {
                    val value = read { readProperty<Int>(VehiclePropertyIds.EV_CHARGE_TIME_REMAINING, Car.PERMISSION_ENERGY)
                        ?.also { require(it >= 0) } }
                    state.update { it.copy(chargeRemainingSeconds = value) }
                }
                launch {
                    val value = read { readProperty<Float>(VehiclePropertyIds.RANGE_REMAINING, Car.PERMISSION_ENERGY)
                        ?.also { require(it.isFinite() && it >= 0f) }?.div(1_000f) }
                    state.update { it.copy(rangeKm = value) }
                }
                launch {
                    val value = read { readProperty<String>(VehiclePropertyIds.INFO_MODEL, Car.PERMISSION_CAR_INFO)?.takeIf { it.isNotBlank() } }
                    state.update { it.copy(carName = value) }
                }
                // Current pressure needs a privileged API; a low-pressure threshold is not a measurement.
                state.update { it.copy(tires = Reading(status = DataStatus.UNSUPPORTED)) }
            }
        }
    }

    private suspend fun doorPositions(v: Vehicle): String {
        val door = v.getDoor()
        val (config, areas) = awaitResult<Pair<CapabilityConfig, Map<DoorArea, AreaBounds<Int>>>> { ok, fail ->
            door.checkDoorPositionCapabilities({ c, a -> ok(c to a) }, fail)
        }
        requireRead(config)
        return supportedAreas(areas.keys) { area ->
            val value = awaitResult<Int?> { ok, fail -> door.getCurrentDoorPosition(area, ok, fail) } ?: throw MissingValue()
            val bounds = areas.getValue(area)
            "${area.values.joinToString("/") { areaLabel(it.name) }} ${positionLabel(value, bounds.minValue, bounds.maxValue)}"
        }
    }

    private suspend fun doorLocks(v: Vehicle): String {
        val door = v.getDoor()
        val (config, areas) = awaitResult<Pair<CapabilityConfig, List<DoorArea>>> { ok, fail ->
            door.checkDoorLockCapabilities({ c, a -> ok(c to a) }, fail)
        }
        requireRead(config)
        return supportedAreas(areas) { area ->
            val locked = awaitResult<Boolean?> { ok, fail -> door.getDoorLockState(area, ok, fail) } ?: throw MissingValue()
            "${area.values.joinToString("/") { areaLabel(it.name) }} ${if (locked) "잠김" else "잠금 해제"}"
        }
    }

    private suspend fun windowPositions(v: Vehicle): String {
        val window = v.getWindow()
        val (config, areas) = awaitResult<Pair<CapabilityConfig, Map<WindowArea, AreaBounds<Int>>>> { ok, fail ->
            window.checkWindowPositionCapabilities({ c, a -> ok(c to a) }, fail)
        }
        requireRead(config)
        return supportedAreas(areas.keys) { area ->
            val value = awaitResult<Int?> { ok, fail -> window.getCurrentWindowPosition(area, ok, fail) } ?: throw MissingValue()
            val bounds = areas.getValue(area)
            "${area.values.joinToString("/") { areaLabel(it.name) }} ${positionLabel(value, bounds.minValue, bounds.maxValue)}"
        }
    }

    private suspend fun seatBelts(v: Vehicle): String {
        val seat = v.getSeat()
        val (config, areas) = awaitResult<Pair<CapabilityConfig, List<SeatArea>>> { ok, fail ->
            seat.checkSeatBeltBuckledCapabilities({ c, a -> ok(c to a) }, fail)
        }
        requireRead(config)
        return supportedAreas(areas) { area ->
            val buckled = awaitResult<Boolean?> { ok, fail -> seat.getCurrentSeatBeltBuckledState(area, ok, fail) } ?: throw MissingValue()
            "${area.values.joinToString("/") { areaLabel(it.name) }} ${if (buckled) "체결" else "미체결"}"
        }
    }

    private suspend fun climate(v: Vehicle): String {
        val hvac = v.getHVAC()
        val (config, areas) = awaitResult<Pair<CapabilityConfig, Map<SeatArea, AreaBounds<Float>>>> { ok, fail ->
            hvac.checkHvacTemperatureCapabilities({ c, a, _ -> ok(c to a) }, fail)
        }
        requireRead(config)
        return supportedAreas(areas.keys) { area ->
            val target = awaitResult<Float?> { ok, fail -> hvac.getHvacTemperature(area, TemperatureUnit.CELSIUS, ok, fail) }
                ?: throw MissingValue()
            val bounds = areas.getValue(area)
            require(target.isFinite() && bounds.minValue.isFinite() && bounds.maxValue.isFinite() &&
                target in bounds.minValue..bounds.maxValue)
            "${area.values.joinToString("/") { areaLabel(it.name) }} 설정 ${String.format(Locale.KOREA, "%.1f", target)}°C"
        } + " · 실내 측정 온도 아님"
    }

    private suspend inline fun <reified T> readProperty(id: Int, permission: String): T? = withContext(Dispatchers.IO) {
        if (context.checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) throw SecurityException()
        val manager = properties ?: throw MissingValue()
        val config = manager.getCarPropertyConfig(id) ?: throw UnsupportedOperationException()
        if (config.access and 1 == 0 || !config.areaIds.contains(0)) throw UnsupportedOperationException()
        val value = manager.getProperty<T>(id, 0)
        if (value.status != CarPropertyValue.STATUS_AVAILABLE) throw MissingValue()
        // Continuous countdown data must not become fresh merely by rereading an old VHAL cache.
        if (id == VehiclePropertyIds.EV_CHARGE_TIME_REMAINING &&
            !freshChargeTimestamp(value.timestamp, SystemClock.elapsedRealtimeNanos())) throw MissingValue()
        value.value
    }

    override fun stop() {
        if (!active) return
        active = false
        generation++
        polling?.cancel(); polling = null
        reading?.cancel(); reading = null
        socListener?.let { listener -> runCatching { vehicle?.getEvBattery()?.unregisterEvBatteryStateOfCharge(listener) } }
        socListener = null
        naviListener?.let { listener -> runCatching { navi?.removeListener(listener) } }
        naviListener = null
        runCatching { navi?.release() }; navi = null
        runCatching { vehicle?.release() }; vehicle = null
        properties = null
        runCatching { car?.disconnect() }; car = null
        invalidateRoute()
        state.value = unavailableSnapshot(DataStatus.ERROR)
    }

    override fun close() { stop(); scope.cancel() }
}

class DemoVehicleSource : VehicleSource {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val state = MutableStateFlow(VehicleSnapshot())
    private val routeState = MutableStateFlow<RouteEstimate?>(null)
    override val snapshot = state.asStateFlow()
    override val route = routeState.asStateFlow()
    private var scenario = DemoScenario.COMMUTE
    private var polling: Job? = null

    fun setScenario(scenario: DemoScenario) { this.scenario = scenario; refresh() }
    override fun start() {
        if (polling?.isActive == true) return
        polling = scope.launch { while (isActive) { refresh(); delay(10_000) } }
    }
    override fun refresh() {
        if (scenario == DemoScenario.UNAVAILABLE) {
            state.value = unavailableSnapshot(DataStatus.ERROR)
            routeState.value = null
            return
        }
        val now = System.currentTimeMillis()
        val family = scenario == DemoScenario.FAMILY_CHARGING
        val pet = scenario == DemoScenario.PET_RETURN
        state.value = VehicleSnapshot(
            soc = available(if (family) 63 else if (pet) 38 else 76, now),
            charging = available(family, now),
            chargeRemainingSeconds = if (family) available(35 * 60, now) else Reading(),
            doors = available(if (pet) "조회 지원 5개: 트렁크 열림 · 나머지 4개 닫힘" else "조회 지원 5개: 앞/뒷문 4개와 트렁크 닫힘", now),
            windows = available(if (pet) "조회 지원 4개: 뒤 오른쪽 열림 · 나머지 3개 닫힘" else "조회 지원 4개: 앞/뒤 좌우 창문 닫힘", now),
            tires = Reading(status = DataStatus.UNSUPPORTED),
            warnings = available("", now),
            hvac = available("앞 왼쪽 설정 ${if (pet) "22" else "23"}°C · 실내 측정 온도 아님", now),
            belts = available(if (family) "앞 왼쪽 체결 · 뒤 오른쪽 미체결 · 탑승 여부 직접 확인" else "앞 왼쪽 체결 · 다른 좌석 직접 확인", now),
            carName = available("SHAREMATE 데모 EV", now),
            doorLocks = available("조회 지원 4개: 앞/뒤 좌우 도어 잠금 해제", now),
            parked = available(true, now), rangeKm = available(if (pet) 142f else 286f, now),
            odometerKm = available(12_345.6f, now),
        )
        routeState.value = RouteEstimate("demo-return-hub", "데모 반납 장소", if (pet) 2_100 else 1_500, now, if (pet) 18_700 else 12_400)
    }
    override fun stop() { polling?.cancel(); polling = null; routeState.value = null }
    override fun close() { stop(); scope.cancel() }
}

private class MissingValue : IllegalStateException()

private fun requireRead(config: CapabilityConfig) {
    if (config.accessType != AccessType.READ && config.accessType != AccessType.READ_WRITE) throw UnsupportedOperationException()
}

private suspend fun <T> awaitResult(call: ((T) -> Unit, (Exception) -> Unit) -> Unit): T =
    withTimeout(4_000) {
        suspendCancellableCoroutine { continuation ->
            call({ value -> if (continuation.isActive) continuation.resume(value) },
                { error -> if (continuation.isActive) continuation.resumeWithException(error) })
        }
    }

private suspend fun <T> read(block: suspend () -> T?): Reading<T> = try {
    val value = block()
    if (value == null) Reading(status = DataStatus.ERROR) else available(value)
} catch (_: TimeoutCancellationException) {
    Reading(status = DataStatus.ERROR)
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    Reading(status = failureStatus(e))
}

internal fun failureStatus(error: Exception): DataStatus = when {
    error is SecurityException || error.message?.contains("SECURITY_PERMISSION") == true -> DataStatus.DENIED
    error is UnsupportedOperationException || error.message?.contains("NOT_SUPPORTED") == true -> DataStatus.UNSUPPORTED
    else -> DataStatus.ERROR
}

private suspend fun <A> supportedAreas(areas: Collection<A>, readArea: suspend (A) -> String): String {
    if (areas.isEmpty()) throw UnsupportedOperationException()
    val results = coroutineScope { areas.map { async { readArea(it) } }.awaitAll() }
    return "조회 지원 ${areas.size}개: ${results.joinToString(" · ")}"
}

internal fun positionLabel(value: Int, min: Int, max: Int): String {
    require(min <= 0 && max >= 0 && value in min..max)
    // AAOS WINDOW_POS uses negative values for out-of-plane vent opening; zero is closed.
    return if (value == 0) "닫힘" else "열림"
}

internal fun destinationEstimate(destination: DestinationInfo, revision: Int, now: Long): RouteEstimate? {
    if (destination.remainTime < 0 || destination.name.isBlank() || destination.name.length > 200 ||
        !destination.latitude.isFinite() || !destination.longitude.isFinite() ||
        destination.latitude !in -90.0..90.0 || destination.longitude !in -180.0..180.0) return null
    // The SDK has no request IDs. Invalidation changes identity, requiring the user to bind a new route.
    val id = "$revision|${destination.poiProvider}|${destination.poiId}|${destination.poiSubId.orEmpty()}|${destination.latitude}|${destination.longitude}"
    if (id.length > 500) return null
    return RouteEstimate(id, destination.name, destination.remainTime.toLong(), now, destination.remainDistance.toLong().takeIf { it >= 0 })
}

private fun areaLabel(name: String): String = when (name) {
    "ROW_1_LEFT" -> "앞 왼쪽"; "ROW_1_CENTER" -> "앞 중앙"; "ROW_1_RIGHT" -> "앞 오른쪽"
    "ROW_2_LEFT" -> "뒤 왼쪽"; "ROW_2_CENTER" -> "뒤 중앙"; "ROW_2_RIGHT" -> "뒤 오른쪽"
    "ROW_3_LEFT" -> "3열 왼쪽"; "ROW_3_CENTER" -> "3열 중앙"; "ROW_3_RIGHT" -> "3열 오른쪽"
    "HOOD" -> "보닛"; "REAR" -> "트렁크"; "FRONT_WINDSHIELD" -> "앞 유리"; "REAR_WINDSHIELD" -> "뒤 유리"
    "ROOF_TOP_1" -> "앞 루프"; "ROOF_TOP_2" -> "뒤 루프"; else -> name
}

private fun <T> available(value: T, now: Long = System.currentTimeMillis()) = Reading(value, DataStatus.AVAILABLE, now)

internal fun socReading(value: Int?): Reading<Int> =
    if (value != null && value in 0..100) available(value) else Reading(status = DataStatus.ERROR)

internal fun freshChargeTimestamp(timestampNanos: Long, nowNanos: Long): Boolean =
    timestampNanos > 0 && nowNanos >= timestampNanos && nowNanos - timestampNanos <= 30_000_000_000L

private fun unavailableSnapshot(status: DataStatus) = VehicleSnapshot(
    soc = Reading(status = status), charging = Reading(status = status), doors = Reading(status = status),
    chargeRemainingSeconds = Reading(status = status),
    windows = Reading(status = status), tires = Reading(status = DataStatus.UNSUPPORTED), warnings = Reading(status = status),
    hvac = Reading(status = status), belts = Reading(status = status), carName = Reading(status = status),
    doorLocks = Reading(status = status), parked = Reading(status = status), rangeKm = Reading(status = status),
    odometerKm = Reading(status = status),
)
