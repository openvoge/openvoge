package dev.vogy.app

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.text.format.Formatter
import android.util.Log

object CameraWifiDetector {
    private const val TAG = "CameraWifiDetector"
    const val CAMERA_DEFAULT_HOST = "192.72.1.1"

    // Common dashcam/action-cam SSID prefixes used by VOGE and Novatek/AIT chipsets
    private val KNOWN_CAMERA_PREFIXES = listOf(
        "VOGE", "MOTO", "CAR", "DV", "MS8336", "AIT", "NOVATEK", "ACTIONCAM", "CAM_"
    )

    enum class NetworkType {
        NO_WIFI,
        COMMON_WIFI,
        CAMERA_WIFI,
        UNKNOWN_WIFI
    }

    data class WifiStatus(
        val type: NetworkType,
        val ssid: String?,
        val gatewayIp: String?,
        val hasInternet: Boolean,
        val description: String
    )

    /**
     * Inspects the current Wi-Fi network to determine if it is the motorcycle dashcam Wi-Fi
     * or a common home/office network.
     */
    fun getWifiStatus(context: Context, savedSsid: String? = null): WifiStatus {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return WifiStatus(NetworkType.NO_WIFI, null, null, false, context.getString(R.string.wifi_status_no_service))

        // Find active Wi-Fi network
        val wifiNetwork = cm.allNetworks.firstOrNull { network ->
            val caps = cm.getNetworkCapabilities(network) ?: return@firstOrNull false
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
        }

        if (wifiNetwork == null) {
            return WifiStatus(NetworkType.NO_WIFI, null, null, false, "Wi‑Fi desconectado")
        }

        val caps = cm.getNetworkCapabilities(wifiNetwork)
        val hasInternet = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
        val ssid = getConnectedSsid(context, caps)
        val gatewayIp = getGatewayIp(context)

        // Case 1: Saved SSID explicitly matches
        if (!savedSsid.isNullOrBlank() && ssid != null && ssid.equals(savedSsid, ignoreCase = true)) {
            return WifiStatus(
                type = NetworkType.CAMERA_WIFI,
                ssid = ssid,
                gatewayIp = gatewayIp,
                hasInternet = hasInternet,
                description = context.getString(R.string.wifi_status_camera_saved, ssid)
            )
        }

        // Case 2: Gateway is explicitly the camera host 192.72.1.1
        if (gatewayIp == CAMERA_DEFAULT_HOST) {
            return WifiStatus(
                type = NetworkType.CAMERA_WIFI,
                ssid = ssid,
                gatewayIp = gatewayIp,
                hasInternet = hasInternet,
                description = context.getString(R.string.wifi_status_camera_gateway)
            )
        }

        // Case 3: SSID matches known camera patterns
        if (ssid != null) {
            val upper = ssid.uppercase()
            if (KNOWN_CAMERA_PREFIXES.any { upper.startsWith(it) }) {
                return WifiStatus(
                    type = NetworkType.CAMERA_WIFI,
                    ssid = ssid,
                    gatewayIp = gatewayIp,
                    hasInternet = hasInternet,
                    description = context.getString(R.string.wifi_status_camera_ssid, ssid)
                )
            }
        }

        // Case 4: Has validated internet connection -> Definitely a common home/office Wi-Fi
        if (hasInternet) {
            return WifiStatus(
                type = NetworkType.COMMON_WIFI,
                ssid = ssid,
                gatewayIp = gatewayIp,
                hasInternet = true,
                description = if (ssid != null) context.getString(R.string.wifi_status_common_internet_ssid, ssid) else context.getString(R.string.wifi_status_common_internet)
            )
        }

        // Case 5: No internet, but gateway is not 192.72.1.1 and SSID doesn't match known prefixes
        return if (gatewayIp != null && !gatewayIp.startsWith("192.72.1.")) {
            WifiStatus(
                type = NetworkType.COMMON_WIFI,
                ssid = ssid,
                gatewayIp = gatewayIp,
                hasInternet = false,
                description = context.getString(R.string.wifi_status_local, gatewayIp)
            )
        } else {
            WifiStatus(
                type = NetworkType.UNKNOWN_WIFI,
                ssid = ssid,
                gatewayIp = gatewayIp,
                hasInternet = false,
                description = context.getString(R.string.wifi_status_unknown)
            )
        }
    }

    fun isCameraWifi(context: Context, savedSsid: String? = null): Boolean {
        val status = getWifiStatus(context, savedSsid)
        return status.type == NetworkType.CAMERA_WIFI
    }

    fun getConnectedSsid(context: Context, caps: NetworkCapabilities? = null): String? {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && caps != null) {
                val wifiInfo = caps.transportInfo as? WifiInfo
                val raw = wifiInfo?.ssid
                if (!raw.isNullOrBlank() && raw != "<unknown ssid>") {
                    return raw.trim('"', ' ')
                }
            }
            @Suppress("DEPRECATION")
            val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            @Suppress("DEPRECATION")
            val raw = wm?.connectionInfo?.ssid
            if (!raw.isNullOrBlank() && raw != "<unknown ssid>") {
                return raw.trim('"', ' ')
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error getting Wi-Fi SSID: ${e.message}")
        }
        return null
    }

    @Suppress("DEPRECATION")
    fun getGatewayIp(context: Context): String? {
        return try {
            val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return null
            val gateway = wm.dhcpInfo?.gateway ?: return null
            if (gateway == 0) return null
            Formatter.formatIpAddress(gateway)
        } catch (e: Exception) {
            Log.w(TAG, "Error getting gateway IP: ${e.message}")
            null
        }
    }
}
