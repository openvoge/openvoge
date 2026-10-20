package dev.vogy.app

import android.content.Context
import androidx.core.content.edit
import org.json.JSONArray
import org.json.JSONObject

/** A motorcycle the user has connected to before. */
data class SavedBike(
    val address: String,
    val name: String,
    val lastConnectedMillis: Long
)

/**
 * History of connected motorcycles plus the single one (if any) the user chose to auto-connect.
 * Nothing connects on its own unless [autoConnectAddress] is set.
 */
class BikeRegistry(context: Context) {

    companion object {
        private const val PREFS_NAME = "bike_registry"
        private const val KEY_BIKES = "bikes"
        private const val KEY_AUTO_ADDRESS = "auto_connect_address"
        private const val MAX_HISTORY = 8
    }

    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var autoConnectAddress: String?
        get() = prefs.getString(KEY_AUTO_ADDRESS, null)
        set(value) = prefs.edit { putString(KEY_AUTO_ADDRESS, value) }

    /** Most recently connected first. */
    fun bikes(): List<SavedBike> {
        val raw = prefs.getString(KEY_BIKES, null) ?: return emptyList()
        return try {
            val array = JSONArray(raw)
            (0 until array.length()).map { i ->
                val o = array.getJSONObject(i)
                SavedBike(o.getString("address"), o.optString("name"), o.optLong("last"))
            }.sortedByDescending { it.lastConnectedMillis }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun autoConnectBike(): SavedBike? {
        val address = autoConnectAddress ?: return null
        return bikes().firstOrNull { it.address.equals(address, ignoreCase = true) }
    }

    fun recordConnection(address: String, name: String) {
        val updated = listOf(SavedBike(address, name, System.currentTimeMillis())) +
            bikes().filterNot { it.address.equals(address, ignoreCase = true) }
        save(updated.take(MAX_HISTORY))
    }

    fun forget(address: String) {
        save(bikes().filterNot { it.address.equals(address, ignoreCase = true) })
        if (autoConnectAddress.equals(address, ignoreCase = true)) autoConnectAddress = null
    }

    /** All-time top speed (km/h) recorded for the bike with this Bluetooth address. */
    fun topSpeedKmh(address: String): Int = prefs.getInt(topSpeedKey(address), 0)

    fun recordTopSpeed(address: String, kmh: Int) {
        prefs.edit { putInt(topSpeedKey(address), kmh) }
    }

    private fun topSpeedKey(address: String) = "top_speed_" + address.uppercase()

    private fun save(bikes: List<SavedBike>) {
        val array = JSONArray()
        bikes.forEach {
            array.put(JSONObject().put("address", it.address).put("name", it.name).put("last", it.lastConnectedMillis))
        }
        prefs.edit { putString(KEY_BIKES, array.toString()) }
    }
}
