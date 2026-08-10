package com.trucknav.pro

import android.app.Application
import com.tomtom.sdk.common.configuration.buildSdkConfiguration
import com.tomtom.sdk.init.TomTomSdk

class TruckNavApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // One-time SDK bootstrap required before the map display module is used.
        TomTomSdk.initialize(this, buildSdkConfiguration(this, BuildConfig.TOMTOM_API_KEY))
    }
}
