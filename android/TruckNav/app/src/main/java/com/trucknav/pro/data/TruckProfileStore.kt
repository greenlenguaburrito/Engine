package com.trucknav.pro.data

import android.content.Context
import androidx.core.content.edit
import com.trucknav.pro.model.TruckProfile

/** Persists the driver's truck dimensions/hazmat setting across app launches. */
class TruckProfileStore(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun load(): TruckProfile = TruckProfile(
        weightLbs = prefs.getInt(KEY_WEIGHT, TruckProfile().weightLbs),
        heightFt = prefs.getFloat(KEY_HEIGHT, TruckProfile().heightFt.toFloat()).toDouble(),
        lengthFt = prefs.getFloat(KEY_LENGTH, TruckProfile().lengthFt.toFloat()).toDouble(),
        hazmat = prefs.getBoolean(KEY_HAZMAT, TruckProfile().hazmat)
    )

    fun save(profile: TruckProfile) {
        prefs.edit {
            putInt(KEY_WEIGHT, profile.weightLbs)
            putFloat(KEY_HEIGHT, profile.heightFt.toFloat())
            putFloat(KEY_LENGTH, profile.lengthFt.toFloat())
            putBoolean(KEY_HAZMAT, profile.hazmat)
        }
    }

    private companion object {
        const val PREFS_NAME = "truck_profile"
        const val KEY_WEIGHT = "weight_lbs"
        const val KEY_HEIGHT = "height_ft"
        const val KEY_LENGTH = "length_ft"
        const val KEY_HAZMAT = "hazmat"
    }
}
