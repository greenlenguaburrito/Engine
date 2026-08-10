package com.trucknav.pro

import android.app.Application
import android.content.Intent
import android.os.Process
import com.tomtom.sdk.common.configuration.buildSdkConfiguration
import com.tomtom.sdk.init.TomTomSdk
import java.io.PrintWriter
import java.io.StringWriter
import kotlin.system.exitProcess

class TruckNavApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        installCrashHandler()

        // TomTomSdk.initialize is documented @WorkerThread. Running it on a background
        // thread but joining it keeps Application startup blocked until it's done, so no
        // Activity/MapFragment can touch the SDK before it's initialized.
        val configuration = buildSdkConfiguration(this, BuildConfig.TOMTOM_API_KEY)
        Thread {
            TomTomSdk.initialize(this, configuration)
        }.apply {
            start()
            join()
        }
    }

    private fun installCrashHandler() {
        Thread.setDefaultUncaughtExceptionHandler { _, throwable ->
            try {
                val writer = StringWriter()
                throwable.printStackTrace(PrintWriter(writer))
                startActivity(
                    Intent(this, CrashActivity::class.java).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                        putExtra(CrashActivity.EXTRA_DETAILS, writer.toString())
                    }
                )
            } catch (_: Throwable) {
                // Best-effort: fall through to killing the process below either way.
            }
            Process.killProcess(Process.myPid())
            exitProcess(1)
        }
    }
}
