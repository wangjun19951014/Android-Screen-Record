package com.xros.securescreenrecord

import android.os.Bundle
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.xros.securescreenrecord.service.RecordingStatusHolder

/**
 * Optional status display only. Not a recording control entry point; the service is
 * controlled exclusively via ADB (see doc/系统应用录屏方案.md §14).
 */
class MainActivity : AppCompatActivity() {

    private lateinit var statusValue: TextView
    private lateinit var outputFileValue: TextView
    private lateinit var lastErrorValue: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        statusValue = findViewById(R.id.statusValue)
        outputFileValue = findViewById(R.id.outputFileValue)
        lastErrorValue = findViewById(R.id.lastErrorValue)
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    private fun refreshStatus() {
        statusValue.text = RecordingStatusHolder.state.name
        outputFileValue.text = RecordingStatusHolder.lastOutputFilePath ?: "-"
        lastErrorValue.text = RecordingStatusHolder.lastError ?: "-"
    }
}