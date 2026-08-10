package com.trucknav.pro

import android.os.Bundle
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/**
 * Shown instead of silently dying when the app crashes, so a crash can be read and
 * reported (screenshot/copy) without needing adb/logcat access to the device.
 */
class CrashActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val details = intent.getStringExtra(EXTRA_DETAILS).orEmpty()

        val textView = TextView(this).apply {
            text = "TruckNav crashed. Please copy or screenshot this and report it:\n\n$details"
            setTextIsSelectable(true)
            setPadding(32, 96, 32, 96)
            textSize = 12f
        }
        setContentView(ScrollView(this).apply { addView(textView) })
    }

    companion object {
        const val EXTRA_DETAILS = "crash_details"
    }
}
