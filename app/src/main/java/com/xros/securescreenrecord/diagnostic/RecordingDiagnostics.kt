package com.xros.securescreenrecord.diagnostic

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Process
import android.util.Log
import com.xros.securescreenrecord.recording.FailureReason
import com.xros.securescreenrecord.recording.RecordingConfig
import com.xros.securescreenrecord.recording.StopReason
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Collects and formats diagnostic information for a recording session, per doc §15.
 * Does not affect recording behavior; purely observational, exposed via dump().
 */
class RecordingDiagnostics {

    private val timestampFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    var lastAction: String? = null
    var lastDurationSeconds: Long? = null
    var sessionStartElapsedRealtime: Long? = null
    var sessionDeadlineElapsedRealtime: Long? = null
    var lastStopReason: StopReason? = null
    var lastFailureReason: FailureReason? = null
    var lastOutputFilePath: String? = null
    var lastOutputFileSize: Long? = null
    var lastErrorMessage: String? = null
    var lastErrorClassName: String? = null

    fun recordStaticEnvironment(context: Context): String {
        val uid = Process.myUid()
        val sdkInt = Build.VERSION.SDK_INT
        val appInfo: ApplicationInfo? = try {
            context.packageManager.getApplicationInfo(context.packageName, 0)
        } catch (e: PackageManager.NameNotFoundException) {
            null
        }
        val isSystemApp = appInfo?.let { it.flags and ApplicationInfo.FLAG_SYSTEM != 0 } ?: false
        // FLAG_PRIVILEGED (0x8000) is a hidden ApplicationInfo flag not exposed in the public
        // SDK; check it via the raw bit value instead of the constant.
        val isPrivApp = appInfo?.let { it.flags and 0x8000 != 0 } ?: false

        val permissions = listOf(
            "android.permission.CAPTURE_SECURE_VIDEO_OUTPUT",
            "android.permission.CAPTURE_VIDEO_OUTPUT",
            "android.permission.CAPTURE_AUDIO_OUTPUT",
            "android.permission.ACCESS_SURFACE_FLINGER",
        )
        val permissionResults = permissions.joinToString(", ") { permission ->
            val granted = context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
            "$permission=$granted"
        }

        val summary = buildString {
            appendLine("uid=$uid sdkInt=$sdkInt isSystemApp=$isSystemApp isPrivApp=$isPrivApp")
            appendLine("permissions: $permissionResults")
        }
        Log.i(TAG, summary)
        return summary
    }

    fun recordConfig(config: RecordingConfig, action: String) {
        lastAction = action
        lastDurationSeconds = config.durationSeconds
        Log.i(
            TAG,
            "config: displayId=${config.displayId} size=${config.width}x${config.height} " +
                "densityDpi=${config.densityDpi} durationSeconds=${config.durationSeconds} " +
                "outputFile=${config.outputFile.absolutePath}",
        )
    }

    fun recordError(t: Throwable) {
        lastErrorClassName = t.javaClass.name
        lastErrorMessage = t.message
        Log.e(TAG, "recording error", t)
    }

    fun dumpString(): String {
        return buildString {
            appendLine("RecordingDiagnostics:")
            appendLine("  lastAction=$lastAction")
            appendLine("  lastDurationSeconds=$lastDurationSeconds")
            appendLine("  sessionStartElapsedRealtime=$sessionStartElapsedRealtime")
            appendLine("  sessionDeadlineElapsedRealtime=$sessionDeadlineElapsedRealtime")
            appendLine("  lastStopReason=$lastStopReason")
            appendLine("  lastFailureReason=$lastFailureReason")
            appendLine("  lastOutputFilePath=$lastOutputFilePath")
            appendLine("  lastOutputFileSize=$lastOutputFileSize")
            appendLine("  lastErrorClassName=$lastErrorClassName")
            appendLine("  lastErrorMessage=$lastErrorMessage")
        }
    }

    companion object {
        private const val TAG = "RecordingDiagnostics"
    }
}
