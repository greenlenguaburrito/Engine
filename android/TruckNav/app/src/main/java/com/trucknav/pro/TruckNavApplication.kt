package com.trucknav.pro

import android.app.Application
import com.tomtom.sdk.TomTomSdk

class TruckNavApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // One-time SDK bootstrap required before any TomTom map/search/routing
        // module is touched. See https://docs.tomtom.com/navigation/android/getting-started/project-setup
        TomTomSdk.initialize(this)
    }
}
