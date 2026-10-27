package ai.sharemate.app

import android.car.Car
import android.car.drivingstate.CarUxRestrictions
import android.car.drivingstate.CarUxRestrictionsManager
import android.content.pm.PackageManager
import android.content.pm.PermissionInfo
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

data class DrivingUiState(
    val automotive: Boolean,
    val available: Boolean = false,
    val restricted: Boolean = true,
)

class MainActivity : ComponentActivity() {
    private var driving by mutableStateOf(DrivingUiState(automotive = true))
    private var car: Car? = null
    private var restrictions: CarUxRestrictionsManager? = null
    private var foreground = false
    private var connectionGeneration = 0
    private val permissionRequest = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        // Restart recording so newly granted vehicle permissions take effect.
        stopService(android.content.Intent(this, TripLogService::class.java))
        TripLogService.start(this)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        driving = DrivingUiState(packageManager.hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE))
        TripLogService.start(this)
        setContent { LogbookApp(driving, ::requestDataPermissions) }
    }

    private fun requestDataPermissions() {
        if (driving.automotive && (!driving.available || driving.restricted)) return
        val requested = packageManager.getPackageInfo(packageName, PackageManager.GET_PERMISSIONS).requestedPermissions.orEmpty()
        val missing = requested.filter { name ->
            checkSelfPermission(name) != PackageManager.PERMISSION_GRANTED && runCatching {
                val info = packageManager.getPermissionInfo(name, 0)
                info.protectionLevel and PermissionInfo.PROTECTION_MASK_BASE == PermissionInfo.PROTECTION_DANGEROUS
            }.getOrDefault(false)
        }
        if (missing.isNotEmpty()) permissionRequest.launch(missing.toTypedArray())
        else Toast.makeText(this, "추가로 요청할 수 있는 권한이 없습니다.", Toast.LENGTH_SHORT).show()
    }

    override fun onStart() {
        super.onStart()
        foreground = true
        if (driving.automotive) connectRestrictions()
    }

    private fun connectRestrictions() {
        val token = ++connectionGeneration
        driving = driving.copy(available = false, restricted = true)
        try {
            car = Car.createCar(this, null, Car.CAR_WAIT_TIMEOUT_DO_NOT_WAIT) { connected, ready ->
                if (!foreground || token != connectionGeneration) return@createCar
                runOnUiThread {
                    if (!foreground || token != connectionGeneration) return@runOnUiThread
                    if (!ready) {
                        restrictions = null
                        driving = driving.copy(available = false, restricted = true)
                    } else {
                        try {
                            runCatching { restrictions?.unregisterListener() }
                            restrictions = connected.getCarManager(Car.CAR_UX_RESTRICTION_SERVICE) as CarUxRestrictionsManager
                            restrictions?.registerListener { updateRestrictions(it, token) }
                            restrictions?.currentCarUxRestrictions?.let { updateRestrictions(it, token) }
                        } catch (_: Exception) {
                            driving = driving.copy(available = false, restricted = true)
                        }
                    }
                }
            }
        } catch (_: Exception) {
            driving = driving.copy(available = false, restricted = true)
        }
    }

    private fun updateRestrictions(value: CarUxRestrictions, token: Int) {
        runOnUiThread {
            if (foreground && token == connectionGeneration) driving = driving.copy(
                available = true,
                // This parked-use app restricts all detailed interactions when any UX restriction is active.
                restricted = value.isRequiresDistractionOptimization || value.activeRestrictions != CarUxRestrictions.UX_RESTRICTIONS_BASELINE,
            )
        }
    }

    override fun onStop() {
        foreground = false
        connectionGeneration++
        runCatching { restrictions?.unregisterListener() }
        runCatching { car?.disconnect() }
        restrictions = null
        car = null
        driving = driving.copy(available = false, restricted = true)
        super.onStop()
    }
}
