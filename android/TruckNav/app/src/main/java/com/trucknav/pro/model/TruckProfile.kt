package com.trucknav.pro.model

import kotlin.math.roundToInt

/**
 * The commercial vehicle's legal dimensions, used to keep every calculated
 * route truck-legal (bridge height, weight limits, hazmat-restricted zones).
 */
data class TruckProfile(
    val weightLbs: Int = 80_000,
    val heightFt: Double = 13.5,
    val lengthFt: Double = 53.0,
    val hazmat: Boolean = false
) {
    val weightKg: Int
        get() = (weightLbs * KG_PER_LB).roundToInt()

    val heightCm: Int
        get() = (heightFt * CM_PER_FT).roundToInt()

    val lengthCm: Int
        get() = (lengthFt * CM_PER_FT).roundToInt()

    companion object {
        private const val KG_PER_LB = 0.453592
        private const val CM_PER_FT = 30.48
    }
}
