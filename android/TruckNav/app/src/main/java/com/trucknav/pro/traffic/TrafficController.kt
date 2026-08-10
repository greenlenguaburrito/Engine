package com.trucknav.pro.traffic

import com.tomtom.sdk.map.display.TomTomMap

/**
 * Toggles the live traffic-flow coloring and incident icons on the map.
 * See https://docs.tomtom.com/navigation/android/guides/navigation/traffic
 */
class TrafficController(private val map: TomTomMap) {

    var isEnabled: Boolean = false
        private set

    fun toggle(): Boolean {
        if (isEnabled) disable() else enable()
        return isEnabled
    }

    fun enable() {
        map.showTrafficFlow()
        map.showTrafficIncidents()
        isEnabled = true
    }

    fun disable() {
        map.hideTrafficFlow()
        map.hideTrafficIncidents()
        isEnabled = false
    }
}
