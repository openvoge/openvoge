package dev.vogy.app

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import java.util.UUID
import kotlin.math.absoluteValue

/** A device seen by the picker scan. */
data class DiscoveredBike(
    val address: String,
    val name: String,
    val rssi: Int,
    val isMotorcycle: Boolean
)

/**
 * BLE link to the motorcycle, driven entirely by Bluetooth callbacks — no sleeps, no timeouts:
 *
 * - The user picks a device from [startDiscovery]; [connect] uses the scanned [BluetoothDevice]
 *   (the bike advertises with a random address, which a bonded/public handle would miss).
 * - GATT operations run through a serial queue: each op starts when the previous callback lands.
 * - The remembered "auto" bike reconnects with `connectGatt(autoConnect = true)`, which lets the
 *   Bluetooth stack connect whenever the bike shows up.
 */
class MotorcycleTelemetryController(
    context: Context,
    private val listener: Listener
) {

    companion object {
        private val LEGACY_TELEMETRY_SERVICE_UUID: UUID = UUID.fromString("0000FFE0-0000-1000-8000-00805F9B34FB")
        private val LEGACY_TELEMETRY_WRITE_UUID: UUID = UUID.fromString("0000FFE1-0000-1000-8000-00805F9B34FB")
        private val LEGACY_TELEMETRY_NOTIFY_UUID: UUID = UUID.fromString("0000FFE2-0000-1000-8000-00805F9B34FB")
        private val MODERN_TELEMETRY_WRITE_UUID: UUID = UUID.fromString("6052202a-2928-4131-a2d0-456d5673ed2f")
        private val MODERN_TELEMETRY_NOTIFY_UUID: UUID = UUID.fromString("ab9938d5-c354-4e2b-94f4-364e16ebcd33")
        private val CCC_DESCRIPTOR_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805F9B34FB")
        private const val TELEMETRY_MTU = 247
        /** Vendor protocol keep-alive: the VOGE app requests a fresh frame every second. */
        private const val TELEMETRY_POLL_INTERVAL_MS = 1_000L
        private const val LOG_TAG = "BLE"

        fun isMotorcycleName(name: String?): Boolean {
            if (name.isNullOrBlank()) return false
            val lower = name.lowercase()
            return lower.contains("voge") || lower.contains("loncin") || lower.contains("bicose")
        }
    }

    interface Listener {
        fun onStatusChanged(message: String)
        fun onTelemetryConnecting(deviceName: String, deviceAddress: String, isActiveConnection: Boolean)
        fun onTelemetryConnected(deviceName: String, deviceAddress: String)
        fun onTelemetryData(snapshot: TelemetrySnapshot)
        fun onTelemetryDisconnected()
    }

    private sealed class GattOp {
        object DiscoverServices : GattOp()
        object RequestMtu : GattOp()
        class EnableNotifications(val characteristic: BluetoothGattCharacteristic) : GattOp()
        class Write(val characteristic: BluetoothGattCharacteristic, val value: ByteArray) : GattOp()
    }

    private val appContext = context.applicationContext
    private val isDebug = (appContext.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
    private val bluetoothAdapter: BluetoothAdapter? by lazy {
        (appContext.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
    }
    val registry = BikeRegistry(appContext)

    private val mainHandler = Handler(Looper.getMainLooper())
    private var bluetoothGatt: BluetoothGatt? = null
    private var notifyCharacteristic: BluetoothGattCharacteristic? = null
    private var writeCharacteristic: BluetoothGattCharacteristic? = null
    private var telemetryProtocol = TelemetryProtocol.LEGACY
    private var telemetrySequence = 0
    private var currentDeviceName = ""
    private var currentDeviceAddress = ""
    private var latestSnapshot = TelemetrySnapshot()
    private var linkReady = false
    /** Set by an explicit user disconnect so the auto bike doesn't immediately reconnect. */
    private var userDisconnected = false

    private val opQueue = ArrayDeque<GattOp>()
    private var opInFlight = false

    // Picker discovery
    private val discoveredDevices = mutableMapOf<String, BluetoothDevice>()
    private var discoveryCallback: ((DiscoveredBike) -> Unit)? = null
    private var isDiscovering = false

    val isConnectedOrConnecting: Boolean get() = bluetoothGatt != null

    private val pollRunnable = object : Runnable {
        override fun run() {
            if (!linkReady) return
            writeCharacteristic?.let { enqueue(GattOp.Write(it, buildTelemetryPollRequest())) }
            mainHandler.postDelayed(this, TELEMETRY_POLL_INTERVAL_MS)
        }
    }

    // ─── Public API ──────────────────────────────────────────────────────────

    /** Live BLE scan for the picker. Runs until [stopDiscovery]; results stream in as found. */
    @SuppressLint("MissingPermission")
    fun startDiscovery(onFound: (DiscoveredBike) -> Unit) {
        discoveryCallback = onFound
        if (isDiscovering) return
        val scanner = bluetoothAdapter?.takeIf { it.isEnabled }?.bluetoothLeScanner
        if (scanner == null || !hasScanPermission()) {
            listener.onStatusChanged(appContext.getString(R.string.bluetooth_status_off))
            return
        }
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        try {
            // Unfiltered: VOGE advertisements don't carry the telemetry service UUID
            scanner.startScan(null, settings, discoveryScanCallback)
            isDiscovering = true
        } catch (e: Exception) {
            Log.w(LOG_TAG, "startDiscovery failed: $e")
        }
    }

    @SuppressLint("MissingPermission")
    fun stopDiscovery() {
        discoveryCallback = null
        if (!isDiscovering) return
        isDiscovering = false
        try {
            if (hasScanPermission()) bluetoothAdapter?.bluetoothLeScanner?.stopScan(discoveryScanCallback)
        } catch (_: Exception) {
        }
    }

    /**
     * User picked a device. Prefers the handle from the picker scan (correct address type);
     * falls back to a random-address LE handle for saved bikes that weren't seen this session.
     */
    fun connect(address: String, name: String, rememberForAutoConnect: Boolean) {
        stopDiscovery()
        userDisconnected = false
        if (rememberForAutoConnect) registry.autoConnectAddress = address
        val device = discoveredDevices[address] ?: leDevice(address) ?: return
        openGatt(device, name, autoConnect = false)
    }

    /** Arms background reconnection to the remembered bike, if the user chose one. */
    fun startAutoConnect() {
        if (bluetoothGatt != null || userDisconnected) return
        val bike = registry.autoConnectBike() ?: return
        val device = discoveredDevices[bike.address] ?: leDevice(bike.address) ?: return
        openGatt(device, bike.name, autoConnect = true)
    }

    /** Explicit user disconnect: drop the link and don't auto-reconnect this session. */
    fun disconnect() {
        userDisconnected = true
        closeGatt()
        listener.onTelemetryDisconnected()
        listener.onStatusChanged(appContext.getString(R.string.main_telemetry_disconnected))
    }

    fun stop() {
        stopDiscovery()
        closeGatt()
    }

    // ─── Connection ──────────────────────────────────────────────────────────

    @SuppressLint("MissingPermission")
    private fun openGatt(device: BluetoothDevice, name: String, autoConnect: Boolean) {
        if (!hasConnectPermission()) {
            listener.onStatusChanged(appContext.getString(R.string.main_telemetry_permissions))
            return
        }
        closeGatt()
        currentDeviceName = name.ifBlank { appContext.getString(R.string.main_telemetry_unknown_device) }
        currentDeviceAddress = device.address
        latestSnapshot = TelemetrySnapshot()
        if (isDebug) Log.d(LOG_TAG, "openGatt ${device.address} name=$currentDeviceName autoConnect=$autoConnect")
        listener.onTelemetryConnecting(currentDeviceName, currentDeviceAddress, isActiveConnection = !autoConnect)
        listener.onStatusChanged(appContext.getString(R.string.main_telemetry_connecting))
        bluetoothGatt = try {
            device.connectGatt(appContext, autoConnect, gattCallback, BluetoothDevice.TRANSPORT_LE)
        } catch (_: SecurityException) {
            null
        }
        if (bluetoothGatt == null) {
            listener.onTelemetryDisconnected()
            listener.onStatusChanged(appContext.getString(R.string.main_telemetry_disconnected))
        }
    }

    @SuppressLint("MissingPermission")
    private fun closeGatt() {
        mainHandler.removeCallbacks(pollRunnable)
        linkReady = false
        opQueue.clear()
        opInFlight = false
        try {
            bluetoothGatt?.disconnect()
            bluetoothGatt?.close()
        } catch (_: SecurityException) {
        }
        bluetoothGatt = null
        notifyCharacteristic = null
        writeCharacteristic = null
    }

    private fun leDevice(address: String): BluetoothDevice? = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            bluetoothAdapter?.getRemoteLeDevice(address, BluetoothDevice.ADDRESS_TYPE_RANDOM)
        } else {
            bluetoothAdapter?.getRemoteDevice(address)
        }
    } catch (_: IllegalArgumentException) {
        null
    }

    private fun onLinkLost(wasReady: Boolean, status: Int) {
        val lostAddress = currentDeviceAddress
        val lostName = currentDeviceName
        closeGatt()
        listener.onTelemetryDisconnected()
        listener.onStatusChanged(
            appContext.getString(
                if (wasReady) R.string.main_telemetry_disconnected else R.string.main_telemetry_connect_failed
            )
        )
        Log.i(LOG_TAG, "Link lost status=$status ready=$wasReady device=$lostAddress")
        // The auto bike reconnects through the Bluetooth stack whenever it comes back in range
        if (!userDisconnected && registry.autoConnectAddress.equals(lostAddress, ignoreCase = true)) {
            val device = discoveredDevices[lostAddress] ?: leDevice(lostAddress) ?: return
            openGatt(device, lostName, autoConnect = true)
        }
    }

    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    private val gattCallback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            mainHandler.post {
                if (gatt != bluetoothGatt) return@post
                if (isDebug) Log.d(LOG_TAG, "onConnectionStateChange status=$status newState=$newState")
                when (newState) {
                    BluetoothProfile.STATE_CONNECTED -> {
                        // Same order as the VOGE app: discover first, then priority + notifications
                        enqueue(GattOp.DiscoverServices)
                    }
                    BluetoothProfile.STATE_DISCONNECTED -> onLinkLost(linkReady, status)
                }
            }
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            mainHandler.post {
                if (gatt != bluetoothGatt) return@post
                val chars = if (status == BluetoothGatt.GATT_SUCCESS) findTelemetryCharacteristics(gatt) else null
                if (chars == null) {
                    Log.w(LOG_TAG, "Telemetry service not found (status=$status)")
                    listener.onStatusChanged(appContext.getString(R.string.main_telemetry_service_missing))
                    gatt.disconnect()
                    return@post
                }
                telemetryProtocol = chars.protocol
                notifyCharacteristic = chars.notify
                writeCharacteristic = chars.write
                telemetrySequence = initialTelemetrySequence()
                try {
                    gatt.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
                } catch (_: SecurityException) {
                }
                enqueue(GattOp.EnableNotifications(chars.notify))
                enqueue(GattOp.RequestMtu)
                enqueue(GattOp.Write(chars.write, kickoffFrame()))
                completeOp()
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            mainHandler.post { if (gatt == bluetoothGatt) completeOp() }
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            mainHandler.post {
                if (gatt != bluetoothGatt) return@post
                if (descriptor.characteristic?.uuid == notifyCharacteristic?.uuid && status == BluetoothGatt.GATT_SUCCESS) {
                    markLinkReady()
                }
                completeOp()
            }
        }

        override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            mainHandler.post { if (gatt == bluetoothGatt) completeOp() }
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            val value = characteristic.value?.copyOf()
            mainHandler.post { if (gatt == bluetoothGatt) handlePayload(value) }
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            mainHandler.post { if (gatt == bluetoothGatt) handlePayload(value) }
        }
    }

    private fun markLinkReady() {
        if (linkReady) return
        linkReady = true
        registry.recordConnection(currentDeviceAddress, currentDeviceName)
        listener.onTelemetryConnected(currentDeviceName, currentDeviceAddress)
        listener.onStatusChanged(appContext.getString(R.string.main_telemetry_ready))
        mainHandler.removeCallbacks(pollRunnable)
        mainHandler.postDelayed(pollRunnable, TELEMETRY_POLL_INTERVAL_MS)
    }

    // ─── Serial GATT queue ───────────────────────────────────────────────────

    private fun enqueue(op: GattOp) {
        opQueue.addLast(op)
        if (!opInFlight) runNextOp()
    }

    private fun completeOp() {
        opInFlight = false
        runNextOp()
    }

    @Suppress("DEPRECATION")
    @SuppressLint("MissingPermission")
    private fun runNextOp() {
        val gatt = bluetoothGatt ?: return
        while (!opInFlight) {
            val op = opQueue.removeFirstOrNull() ?: return
            val started = try {
                when (op) {
                    GattOp.DiscoverServices -> gatt.discoverServices()
                    GattOp.RequestMtu -> gatt.requestMtu(TELEMETRY_MTU)
                    is GattOp.EnableNotifications -> {
                        gatt.setCharacteristicNotification(op.characteristic, true)
                        val descriptor = op.characteristic.getDescriptor(CCC_DESCRIPTOR_UUID)
                        if (descriptor == null) {
                            markLinkReady()
                            false
                        } else {
                            descriptor.value = if (op.characteristic.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0 &&
                                op.characteristic.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY == 0
                            ) {
                                BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
                            } else {
                                BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                            }
                            gatt.writeDescriptor(descriptor)
                        }
                    }
                    is GattOp.Write -> {
                        op.characteristic.value = op.value
                        gatt.writeCharacteristic(op.characteristic)
                    }
                }
            } catch (_: SecurityException) {
                false
            }
            // A started op completes in its callback; a refused one is skipped so the queue never stalls
            opInFlight = started
            if (isDebug && !started) Log.d(LOG_TAG, "GATT op skipped: ${op.javaClass.simpleName}")
        }
    }

    // ─── Picker scan ─────────────────────────────────────────────────────────

    private val discoveryScanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult?) {
            result?.let(::reportDiscovered)
        }

        override fun onBatchScanResults(results: MutableList<ScanResult>?) {
            results?.forEach(::reportDiscovered)
        }

        override fun onScanFailed(errorCode: Int) {
            Log.w(LOG_TAG, "Discovery scan failed code=$errorCode")
            isDiscovering = false
        }
    }

    @SuppressLint("MissingPermission")
    private fun reportDiscovered(result: ScanResult) {
        val device = result.device ?: return
        // Advertised name ("BLE-VOGE-302275") is available even for never-bonded devices
        val name = result.scanRecord?.deviceName?.takeIf { it.isNotBlank() }
            ?: try { device.name } catch (_: SecurityException) { null }
        if (name.isNullOrBlank()) return
        discoveredDevices[device.address] = device
        discoveryCallback?.invoke(DiscoveredBike(device.address, name, result.rssi, isMotorcycleName(name)))
    }

    // ─── VOGE protocol ───────────────────────────────────────────────────────

    private fun handlePayload(value: ByteArray?) {
        val bytes = value ?: return
        val snapshots = TelemetryParser.extractTelemetrySnapshots(bytes)
        if (snapshots.isEmpty()) return
        val snapshot = snapshots.fold(TelemetrySnapshot()) { merged, next -> merged.mergedWith(next) }
        latestSnapshot = latestSnapshot.mergedWith(snapshot)
        listener.onTelemetryData(latestSnapshot)
    }

    /** 20-byte frame the vendor app sends to start periodic notifications. */
    private fun kickoffFrame() = byteArrayOf(
        0x01, 0x4A, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
        0x78, 0x70, 0x50, 0x00, 0x00, 0x00, 0x12, 0x02, 0x00, 0x07, 0x04
    )

    private fun findTelemetryCharacteristics(gatt: BluetoothGatt): TelemetryCharacteristics? {
        val legacyService = gatt.getService(LEGACY_TELEMETRY_SERVICE_UUID)
        val legacyNotify = legacyService?.getCharacteristic(LEGACY_TELEMETRY_NOTIFY_UUID)
        val legacyWrite = legacyService?.getCharacteristic(LEGACY_TELEMETRY_WRITE_UUID)
        if (legacyNotify != null && legacyWrite != null) {
            return TelemetryCharacteristics(TelemetryProtocol.LEGACY, legacyNotify, legacyWrite)
        }
        gatt.services.orEmpty().forEach { service ->
            val modernNotify = service.getCharacteristic(MODERN_TELEMETRY_NOTIFY_UUID)
            val modernWrite = service.getCharacteristic(MODERN_TELEMETRY_WRITE_UUID)
            if (modernNotify != null && modernWrite != null) {
                return TelemetryCharacteristics(TelemetryProtocol.MODERN, modernNotify, modernWrite)
            }
        }
        return null
    }

    private fun initialTelemetrySequence(): Int =
        ((System.currentTimeMillis() / 1_000L).toInt().absoluteValue and 0xFF)

    private fun applyVogeChecksum(frame: ByteArray): ByteArray {
        require(frame.size >= 2)
        frame[frame.size - 2] = 0
        var xorSum = 0
        for (b in frame) xorSum = xorSum xor (b.toInt() and 0xFF)
        frame[frame.size - 2] = xorSum.toByte()
        return frame
    }

    private fun buildTelemetryPollRequest(): ByteArray {
        val sequence = telemetrySequence and 0xFF
        telemetrySequence = (telemetrySequence + 1) and 0xFF
        val frame = ByteArray(20)
        frame[0] = 0x01
        frame[1] = 0x5A.toByte()
        frame[2] = 0x00
        frame[3] = 0x63.toByte()
        frame[4] = 0x32
        frame[5] = 0x0D
        frame[6] = 0x07
        frame[7] = sequence.toByte()
        frame[8] = 0x01
        frame[9] = 0x00
        frame[10] = 0x02
        frame[19] = 0x04
        return applyVogeChecksum(frame)
    }

    // ─── Permissions ─────────────────────────────────────────────────────────

    private fun hasConnectPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            ContextCompat.checkSelfPermission(appContext, android.Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    private fun hasScanPermission(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(appContext, android.Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(appContext, android.Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        }
}
