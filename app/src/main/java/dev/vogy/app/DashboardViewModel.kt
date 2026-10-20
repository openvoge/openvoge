package dev.vogy.app

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class ConnectionPhase { DISCONNECTED, CONNECTING, CONNECTED }

data class DashboardUiState(
    val connectionPhase: ConnectionPhase = ConnectionPhase.DISCONNECTED,
    /** Name of the bike being connected or connected to, for the banner while CONNECTING. */
    val pendingBikeName: String? = null,
    val bikeName: String? = null,
    val telemetryStatus: String = "",
    val telemetryDevice: String = "",
    val commandValue: String = "> 00 00 00 00 00 00 _",
    val rpmValue: String = "--",
    val averageSpeedValue: String = "-- km/h",
    val maxSpeedValue: String = "-- km/h",
    /** All-time top speed of the connected bike (persisted per Bluetooth address). */
    val allTimeMaxSpeedValue: String = "-- km/h",
    /** MAX SPEED card shows the all-time record instead of this session's max. */
    val showAllTimeMax: Boolean = false,
    val fuelValue: String = "-- %",
    val temperatureValue: String = "-- °C",
    val tripTimeValue: String = "--:--",
    val tripKmValue: String = "-- km",
    val totalKmValue: String = "-- km",
    val gearValue: String = "--",
    val voltageValue: String = "-- V",
    val telemetryHint: String = "",
    val speedValue: Int = 0,
    val speedUnit: SpeedUnit = SpeedUnit.KMH,
    val rawFuelPercent: Int? = null,
    val rawRpm: Int? = null,
    val cameraStatus: String = "",
    val cameraHost: String = "",
    val cameraStream: String = "",
    val cameraHint: String = ""
)

class DashboardViewModel(application: Application) : AndroidViewModel(application) {
    companion object {
        private const val PREFS_UI = "ui_prefs"
        private const val KEY_SPEED_UNIT = "speed_unit"
        private const val KEY_SHOW_ALL_TIME_MAX = "show_all_time_max"
    }

    private val preferences by lazy {
        getApplication<Application>().getSharedPreferences(PREFS_UI, Context.MODE_PRIVATE)
    }

    private var currentSpeedUnit: SpeedUnit = SpeedUnit.fromString(
        preferences.getString(KEY_SPEED_UNIT, SpeedUnit.KMH.name)
    )

    private var lastSnapshot: TelemetrySnapshot? = null

    // All-time top speed tracking: compared in memory on every frame, written only on a new record
    private var topSpeedAddress: String? = null
    private var allTimeTopKmh = 0

    private val _uiState = MutableStateFlow(
        DashboardUiState(
            showAllTimeMax = preferences.getBoolean(KEY_SHOW_ALL_TIME_MAX, false),
            allTimeMaxSpeedValue = "-- ${currentSpeedUnit.suffix}",
            speedUnit = currentSpeedUnit,
            averageSpeedValue = "-- ${currentSpeedUnit.suffix}",
            maxSpeedValue = "-- ${currentSpeedUnit.suffix}"
        )
    )
    val uiState: StateFlow<DashboardUiState> = _uiState.asStateFlow()

    // Telemetry controller owned by ViewModel so it survives configuration changes
    private val telemetryController by lazy {
        MotorcycleTelemetryController(getApplication(), object : MotorcycleTelemetryController.Listener {
            override fun onStatusChanged(message: String) {
                _uiState.value = _uiState.value.copy(telemetryStatus = message)
            }

            override fun onTelemetryConnected(deviceName: String, deviceAddress: String) {
                topSpeedAddress = deviceAddress
                allTimeTopKmh = bikeRegistry.topSpeedKmh(deviceAddress)
                _uiState.value = _uiState.value.copy(allTimeMaxSpeedValue = formatAllTimeTop())
                showTelemetryConnected(
                    deviceName = deviceName,
                    deviceAddress = deviceAddress,
                    connected = getApplication<Application>().getString(R.string.main_telemetry_connected),
                    deviceFormat = { name, address -> getApplication<Application>().getString(R.string.main_telemetry_device, name, address) },
                    hint = getApplication<Application>().getString(R.string.main_telemetry_hint)
                )
            }

            override fun onTelemetryConnecting(deviceName: String, deviceAddress: String, isActiveConnection: Boolean) {
                showTelemetryConnecting(
                    deviceName = deviceName,
                    deviceAddress = deviceAddress,
                    isActiveConnection = isActiveConnection,
                    connecting = getApplication<Application>().getString(R.string.main_telemetry_connecting),
                    deviceFormat = { name, address -> getApplication<Application>().getString(R.string.main_telemetry_device, name, address) },
                    hint = getApplication<Application>().getString(R.string.main_telemetry_hint)
                )
            }

            override fun onTelemetryData(snapshot: TelemetrySnapshot) {
                trackAllTimeTopSpeed(snapshot)
                showTelemetryData(
                    snapshot = snapshot,
                    live = getApplication<Application>().getString(R.string.main_telemetry_live),
                    hint = getApplication<Application>().getString(R.string.main_telemetry_hint),
                    neutralGear = getApplication<Application>().getString(R.string.main_telemetry_gear_neutral)
                )
            }

            override fun onTelemetryDisconnected() {
                topSpeedAddress = null
                allTimeTopKmh = 0
                showTelemetryDisconnected(
                    waiting = getApplication<Application>().getString(R.string.main_telemetry_waiting),
                    noDevice = getApplication<Application>().getString(R.string.main_telemetry_no_device),
                    hint = getApplication<Application>().getString(R.string.main_telemetry_hint_disconnected)
                )
            }
        })
    }

    val bikeRegistry: BikeRegistry get() = telemetryController.registry

    /** Reconnects to the remembered bike only if the user enabled auto-connect for it. */
    fun startAutoConnect() {
        telemetryController.startAutoConnect()
    }

    fun connectBike(address: String, name: String, rememberForAutoConnect: Boolean) {
        telemetryController.connect(address, name, rememberForAutoConnect)
    }

    fun disconnectBike() {
        telemetryController.disconnect()
    }

    fun startBikeDiscovery(onFound: (DiscoveredBike) -> Unit) {
        telemetryController.startDiscovery(onFound)
    }

    fun stopBikeDiscovery() {
        telemetryController.stopDiscovery()
    }

    override fun onCleared() {
        telemetryController.stop()
        super.onCleared()
    }

    fun showInitialState(
        waiting: String,
        noDevice: String,
        hint: String,
        cameraWaiting: String,
        cameraNoHost: String,
        cameraHidden: String,
        cameraHint: String
    ) {
        _uiState.value = DashboardUiState(
            telemetryStatus = waiting,
            telemetryDevice = noDevice,
            telemetryHint = hint,
            speedUnit = currentSpeedUnit,
            averageSpeedValue = "-- ${currentSpeedUnit.suffix}",
            maxSpeedValue = "-- ${currentSpeedUnit.suffix}",
            cameraStatus = cameraWaiting,
            cameraHost = cameraNoHost,
            cameraStream = cameraHidden,
            cameraHint = cameraHint
        )
    }

    fun showTelemetryConnected(deviceName: String, deviceAddress: String, connected: String, deviceFormat: (String, String) -> String, hint: String) {
        _uiState.value = _uiState.value.copy(
            connectionPhase = ConnectionPhase.CONNECTED,
            pendingBikeName = deviceName,
            bikeName = deviceName,
            telemetryStatus = connected,
            telemetryDevice = deviceFormat(deviceName, deviceAddress),
            telemetryHint = hint
        )
    }

    fun showTelemetryConnecting(
        deviceName: String,
        deviceAddress: String,
        isActiveConnection: Boolean,
        connecting: String,
        deviceFormat: (String, String) -> String,
        hint: String
    ) {
        _uiState.value = _uiState.value.copy(
            connectionPhase = ConnectionPhase.CONNECTING,
            pendingBikeName = deviceName,
            bikeName = null,
            telemetryStatus = connecting,
            telemetryDevice = deviceFormat(deviceName, deviceAddress),
            telemetryHint = hint
        )
    }

    fun showTelemetryData(
        snapshot: TelemetrySnapshot,
        live: String,
        hint: String,
        neutralGear: String
    ) {
        lastSnapshot = snapshot
        val convertedSpeed = currentSpeedUnit.convertFromKmh(snapshot.speedKmh ?: 0)
        _uiState.value = _uiState.value.copy(
            telemetryStatus = live,
            commandValue = snapshot.commandId ?: "> 00 00 00 00 00 00 _",
            rpmValue = snapshot.rpm?.toString() ?: "--",
            averageSpeedValue = currentSpeedUnit.convertFromKmhString(snapshot.meanSpeed),
            maxSpeedValue = currentSpeedUnit.convertFromKmhString(snapshot.maxSpeed),
            fuelValue = formatPercent(snapshot.fuelPercent),
            temperatureValue = formatTemperature(snapshot.temperature),
            tripTimeValue = formatTripTime(snapshot.driveKmTimeMinutes),
            tripKmValue = formatDistance(snapshot.driveKm),
            totalKmValue = formatDistance(snapshot.totalKm),
            gearValue = formatGear(snapshot.gears, neutralGear),
            voltageValue = formatVoltage(snapshot.voltage),
            telemetryHint = hint,
            speedValue = convertedSpeed,
            speedUnit = currentSpeedUnit,
            rawFuelPercent = snapshot.fuelPercent,
            rawRpm = snapshot.rpm
        )
    }

    fun showTelemetryDisconnected(waiting: String, noDevice: String, hint: String) {
        lastSnapshot = null
        _uiState.value = _uiState.value.copy(
            connectionPhase = ConnectionPhase.DISCONNECTED,
            pendingBikeName = null,
            bikeName = null,
            telemetryStatus = waiting,
            telemetryDevice = noDevice,
            commandValue = "> 00 00 00 00 00 00 _",
            rpmValue = "--",
            averageSpeedValue = "-- ${currentSpeedUnit.suffix}",
            maxSpeedValue = "-- ${currentSpeedUnit.suffix}",
            fuelValue = "-- %",
            temperatureValue = "-- °C",
            tripTimeValue = "--:--",
            tripKmValue = "-- km",
            totalKmValue = "-- km",
            gearValue = "--",
            voltageValue = "-- V",
            telemetryHint = hint,
            speedValue = 0,
            speedUnit = currentSpeedUnit,
            rawFuelPercent = null,
            rawRpm = null
        )
    }

    /** Tap on MAX SPEED: switch between this session's max and the bike's all-time record. */
    fun toggleMaxSpeedMode() {
        val next = !_uiState.value.showAllTimeMax
        preferences.edit().putBoolean(KEY_SHOW_ALL_TIME_MAX, next).apply()
        _uiState.value = _uiState.value.copy(showAllTimeMax = next)
    }

    private fun trackAllTimeTopSpeed(snapshot: TelemetrySnapshot) {
        val address = topSpeedAddress ?: return
        val reportedMax = snapshot.maxSpeed?.trim()?.toDoubleOrNull()?.toInt() ?: 0
        val candidate = maxOf(snapshot.speedKmh ?: 0, reportedMax)
        if (candidate <= allTimeTopKmh) return
        allTimeTopKmh = candidate
        bikeRegistry.recordTopSpeed(address, candidate)
        _uiState.value = _uiState.value.copy(allTimeMaxSpeedValue = formatAllTimeTop())
    }

    private fun formatAllTimeTop(): String =
        if (topSpeedAddress == null) "-- ${currentSpeedUnit.suffix}"
        else "${currentSpeedUnit.convertFromKmh(allTimeTopKmh)} ${currentSpeedUnit.suffix}"

    fun toggleSpeedUnit() {
        val nextUnit = if (currentSpeedUnit == SpeedUnit.KMH) SpeedUnit.MPH else SpeedUnit.KMH
        setSpeedUnit(nextUnit)
    }

    fun setSpeedUnit(unit: SpeedUnit) {
        currentSpeedUnit = unit
        preferences.edit().putString(KEY_SPEED_UNIT, unit.name).apply()

        val snap = lastSnapshot
        val displaySpeed = snap?.speedKmh?.let { unit.convertFromKmh(it) } ?: 0
        val avgSpeed = unit.convertFromKmhString(snap?.meanSpeed)
        val maxSpeed = unit.convertFromKmhString(snap?.maxSpeed)

        _uiState.value = _uiState.value.copy(
            speedUnit = unit,
            speedValue = displaySpeed,
            averageSpeedValue = avgSpeed,
            maxSpeedValue = maxSpeed,
            allTimeMaxSpeedValue = formatAllTimeTop()
        )
    }

    fun showCameraConnected(status: String, host: String, stream: String, hint: String) {
        _uiState.value = _uiState.value.copy(
            cameraStatus = status,
            cameraHost = host,
            cameraStream = stream,
            cameraHint = hint
        )
    }

    fun showCameraPacket(status: String, host: String, stream: String, hint: String) {
        _uiState.value = _uiState.value.copy(
            cameraStatus = status,
            cameraHost = host,
            cameraStream = stream,
            cameraHint = hint
        )
    }

    fun showCameraDisconnected(status: String, host: String, stream: String, hint: String) {
        _uiState.value = _uiState.value.copy(
            cameraStatus = status,
            cameraHost = host,
            cameraStream = stream,
            cameraHint = hint
        )
    }

    fun showCameraPlaybackReady(status: String) {
        _uiState.value = _uiState.value.copy(cameraStatus = status)
    }

    fun showCameraPlaybackAttempt(stream: String) {
        _uiState.value = _uiState.value.copy(cameraStream = stream)
    }

    fun showCameraPlaybackError(stream: String) {
        _uiState.value = _uiState.value.copy(cameraStream = stream)
    }

    private fun formatPercent(value: Int?): String {
        return value?.let { "$it %" } ?: "-- %"
    }

    private fun formatTemperature(value: String?): String {
        return value?.takeIf { it.isNotBlank() }?.let { "$it °C" } ?: "-- °C"
    }

    private fun formatTripTime(minutes: Int?): String {
        if (minutes == null) return "--:--"
        val hours = minutes / 60
        val remainingMinutes = minutes % 60
        return "%02d:%02d".format(hours, remainingMinutes)
    }

    private fun formatDistance(value: String?): String {
        return value?.takeIf { it.isNotBlank() }?.let { "$it km" } ?: "-- km"
    }

    private fun formatGear(value: Int?, neutralGear: String): String {
        return when (value) {
            null -> "--"
            0 -> neutralGear
            else -> value.toString()
        }
    }

    private fun formatVoltage(value: String?): String {
        return value?.takeIf { it.isNotBlank() }?.let { "$it V" } ?: "-- V"
    }
}