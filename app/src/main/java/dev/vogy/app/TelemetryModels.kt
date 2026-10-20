package dev.vogy.app

import android.bluetooth.BluetoothGattCharacteristic

enum class TelemetryProtocol {
    LEGACY,
    MODERN
}

data class TelemetryCharacteristics(
    val protocol: TelemetryProtocol,
    val notify: BluetoothGattCharacteristic,
    val write: BluetoothGattCharacteristic
)

data class TelemetrySnapshot(
    val commandId: String? = null,
    val rpm: Int? = null,
    val speedKmh: Int? = null,
    val fuelPercent: Int? = null,
    val totalKm: String? = null,
    val driveKm: String? = null,
    val meanSpeed: String? = null,
    val maxSpeed: String? = null,
    val voltage: String? = null,
    val temperature: String? = null,
    val gears: Int? = null,
    val driveKmTimeMinutes: Int? = null,
    val naviState: Int? = null,
    val phoneState: Int? = null,
    val raw: String = ""
) {
    fun mergedWith(next: TelemetrySnapshot): TelemetrySnapshot {
        return copy(
            commandId = next.commandId ?: commandId,
            rpm = next.rpm ?: rpm,
            speedKmh = next.speedKmh ?: speedKmh,
            fuelPercent = next.fuelPercent ?: fuelPercent,
            totalKm = next.totalKm ?: totalKm,
            driveKm = next.driveKm ?: driveKm,
            meanSpeed = next.meanSpeed ?: meanSpeed,
            maxSpeed = next.maxSpeed ?: maxSpeed,
            voltage = next.voltage ?: voltage,
            temperature = next.temperature ?: temperature,
            gears = next.gears ?: gears,
            driveKmTimeMinutes = next.driveKmTimeMinutes ?: driveKmTimeMinutes,
            naviState = next.naviState ?: naviState,
            phoneState = next.phoneState ?: phoneState,
            raw = next.raw.ifBlank { raw }
        )
    }
}
