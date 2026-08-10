package com.trucknav.pro.data

import android.content.Context
import androidx.core.content.edit
import com.trucknav.pro.model.LatLng
import org.json.JSONArray
import org.json.JSONObject

data class RecentDestination(val label: String, val position: LatLng)

/** Remembers the last few destinations that were successfully routed to, most-recent-first. */
class RecentDestinationsStore(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun load(): List<RecentDestination> {
        val raw = prefs.getString(KEY_RECENTS, null) ?: return emptyList()
        return try {
            val array = JSONArray(raw)
            (0 until array.length()).map { i ->
                val entry = array.getJSONObject(i)
                RecentDestination(
                    label = entry.getString("label"),
                    position = LatLng(entry.getDouble("lat"), entry.getDouble("lng"))
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun record(label: String, position: LatLng) {
        val updated = listOf(RecentDestination(label, position)) +
            load().filterNot { it.label == label }
        val array = JSONArray()
        updated.take(MAX_RECENTS).forEach { destination ->
            array.put(
                JSONObject().apply {
                    put("label", destination.label)
                    put("lat", destination.position.latitude)
                    put("lng", destination.position.longitude)
                }
            )
        }
        prefs.edit { putString(KEY_RECENTS, array.toString()) }
    }

    private companion object {
        const val PREFS_NAME = "recent_destinations"
        const val KEY_RECENTS = "recents_json"
        const val MAX_RECENTS = 8
    }
}
