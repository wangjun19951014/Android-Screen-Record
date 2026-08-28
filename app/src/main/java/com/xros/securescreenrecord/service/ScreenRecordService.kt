package com.xros.securescreenrecord.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.SystemClock
import android.util.DisplayMetrics
import android.util.Log
import androidx.core.app.NotificationCompat
import com.xros.securescreenrecord.MainActivity
import com.xros.securescreenrecord.R
import com.xros.securescreenrecord.diagnostic.RecordingDiagnostics
import com.xros.securescreenrecord.recording.RecordingConfig
import com.xros.securescreenrecord.recording.RecordingState
import com.xros.securescreenrecord.recording.ScreenRecorder
import com.xros.securescreenrecord.recording.StopReason
import java.io.File
import java.io.FileDescriptor
import java.io.PrintWriter
import java.util.concurrent.atomic.AtomicBoolean

/**
 * ADB-controlled foreground recording service. See doc/系统应用录屏方案.md §11.
 *
 * Not intended to be started from an Activity; ADB `am start-foreground-service` /
 * `am startservice` with [ACTION_START] / [ACTION_STOP] / [ACTION_STATUS] is the control plane.
 */
class ScreenRecordService : Service() {

    private lateinit var workerThread: HandlerThread
    private lateinit var workerHandler: Handler

    private var screenRecorder: ScreenRecorder? = null
    private var currentConfig: RecordingConfig? = null
    private val diagnostics = RecordingDiagnostics()

    @Volatile
    private var state: RecordingState = RecordingState.IDLE

    private val stopRequested = AtomicBoolean(false)

    private var autoStopRunnable: Runnable? = null

    override fun onCreate() {
        super.onCreate()
        workerThread = HandlerThread("ScreenRecordServiceWorker").apply { start() }
        workerHandler = Handler(workerThread.looper)
        ensureNotificationChannel()
        RecordingStatusHolder.state = RecordingState.IDLE
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        Log.i(TAG, "onStartCommand action=$action")
        when (action) {
            ACTION_START -> handleStart(intent)
            ACTION_STOP -> handleStop(StopReason.EXTERNAL_COMMAND)
            ACTION_STATUS -> Log.i(TAG, "status requested: ${statusSummary()}")
            else -> Log.w(TAG, "Unknown or missing action: $action")
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        handleStop(StopReason.SERVICE_DESTROYED)
        workerThread.quitSafely()
        super.onDestroy()
    }

    // region START

    private fun handleStart(intent: Intent) {
        if (state == RecordingState.STARTING || state == RecordingState.RECORDING) {
            Log.w(TAG, "START rejected: busy, current state=$state")
            return
        }

        val durationSeconds = if (intent.hasExtra(EXTRA_DURATION_SECONDS)) {
            intent.getLongExtra(EXTRA_DURATION_SECONDS, -1)
        } else {
            null
        }
        if (!RecordingConfig.isValidDuration(durationSeconds)) {
            Log.e(TAG, "START rejected: invalid durationSeconds=$durationSeconds")
            diagnostics.recordError(IllegalArgumentException("invalid durationSeconds=$durationSeconds"))
            return
        }

        val requestedFileName = intent.getStringExtra(EXTRA_OUTPUT_FILE_NAME)
        val outputFileName: String? = if (requestedFileName != null) {
            val sanitized = RecordingConfig.sanitizeOutputFileName(requestedFileName)
            if (sanitized == null) {
                Log.e(TAG, "START rejected: invalid outputFileName='$requestedFileName'")
                diagnostics.recordError(IllegalArgumentException("invalid outputFileName='$requestedFileName'"))
                return
            }
            // User-specified file names may overwrite an existing file of the same name; only
            // the app's own auto-generated (timestamp-based) names are guaranteed not to collide.
            val candidate = File(File(filesDir, RECORDINGS_DIR_NAME), sanitized)
            if (candidate.exists()) {
                Log.w(TAG, "outputFileName='$sanitized' already exists, will be overwritten")
            }
            sanitized
        } else {
            null
        }

        stopRequested.set(false)
        state = RecordingState.STARTING
        RecordingStatusHolder.state = state

        // Must call startForeground() before any potentially slow initialization.
        startForegroundWithNotification(buildNotification(state, null))

        workerHandler.post { initializeAndStartRecording(durationSeconds!!, outputFileName) }
    }

    private fun initializeAndStartRecording(durationSeconds: Long, outputFileName: String?) {
        diagnostics.recordStaticEnvironment(applicationContext)

        val config = try {
            buildRecordingConfig(durationSeconds, outputFileName)
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to build recording config", t)
            diagnostics.recordError(t)
            state = RecordingState.FAILED
            RecordingStatusHolder.state = state
            RecordingStatusHolder.lastError = t.message
            finishAsFailed()
            return
        }

        diagnostics.recordConfig(config, ACTION_START)
        currentConfig = config

        val recorder = ScreenRecorder(applicationContext)
        screenRecorder = recorder

        val started = recorder.start(config)
        if (!started) {
            state = RecordingState.FAILED
            RecordingStatusHolder.state = state
            diagnostics.lastFailureReason = recorder.lastFailureReason
            recorder.lastException?.let { diagnostics.recordError(it) }
            RecordingStatusHolder.lastError = recorder.lastException?.message
                ?: recorder.lastFailureReason?.name
            finishAsFailed()
            return
        }

        state = RecordingState.RECORDING
        RecordingStatusHolder.state = state
        diagnostics.sessionStartElapsedRealtime = SystemClock.elapsedRealtime()
        diagnostics.sessionDeadlineElapsedRealtime =
            diagnostics.sessionStartElapsedRealtime!! + durationSeconds * 1000
        updateNotification(buildNotification(state, config))

        val runnable = Runnable { requestStop(StopReason.AUTO_DURATION) }
        autoStopRunnable = runnable
        workerHandler.postDelayed(runnable, durationSeconds * 1000)
    }

    // endregion

    // region STOP

    private fun handleStop(reason: StopReason) {
        workerHandler.post { requestStop(reason) }
    }

    /** Idempotent stop entry point; only the first call in a session performs actual work. */
    private fun requestStop(reason: StopReason) {
        if (!stopRequested.compareAndSet(false, true)) {
            Log.i(TAG, "requestStop($reason) ignored: stop already requested for this session")
            return
        }

        autoStopRunnable?.let { workerHandler.removeCallbacks(it) }
        autoStopRunnable = null

        diagnostics.lastStopReason = reason

        val recorder = screenRecorder
        if (recorder == null || state == RecordingState.IDLE) {
            Log.i(TAG, "requestStop($reason): nothing to stop")
            stopSelfAndCleanup()
            return
        }

        if (state == RecordingState.STARTING) {
            // Cancel initialization in progress and release whatever was allocated.
            recorder.release()
            state = RecordingState.STOPPED
            RecordingStatusHolder.state = state
            stopSelfAndCleanup()
            return
        }

        state = RecordingState.STOPPING
        RecordingStatusHolder.state = state
        updateNotification(buildStoppingNotification())

        val valid = recorder.stop()
        state = RecordingState.STOPPED
        RecordingStatusHolder.state = state

        val outputFile = currentConfig?.outputFile
        diagnostics.lastFailureReason = recorder.lastFailureReason
        diagnostics.lastOutputFilePath = outputFile?.absolutePath
        diagnostics.lastOutputFileSize = outputFile?.takeIf { it.exists() }?.length()
        recorder.lastException?.let { diagnostics.recordError(it) }

        RecordingStatusHolder.lastOutputValid = valid
        RecordingStatusHolder.lastOutputFilePath = outputFile?.absolutePath
        RecordingStatusHolder.lastError = if (!valid) {
            recorder.lastException?.message ?: recorder.lastFailureReason?.name ?: "unknown"
        } else {
            null
        }

        updateNotification(buildFinalNotification(valid))
        stopSelfAndCleanup()
    }

    private fun finishAsFailed() {
        updateNotification(buildFailedNotification())
        stopSelfAndCleanup()
    }

    private fun stopSelfAndCleanup() {
        screenRecorder?.release()
        screenRecorder = null
        stopForegroundCompat()
        stopSelf()
    }

    // endregion

    // region config building

    private fun buildRecordingConfig(durationSeconds: Long, outputFileName: String?): RecordingConfig {
        val displayManager = getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        val display = displayManager.getDisplay(android.view.Display.DEFAULT_DISPLAY)
            ?: throw IllegalStateException("Default display unavailable")
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        display.getRealMetrics(metrics)

        val outputDir = File(filesDir, RECORDINGS_DIR_NAME)
        val width = RecordingConfig.evenSize(metrics.widthPixels)
        val height = RecordingConfig.evenSize(metrics.heightPixels)
        val bitrate = RecordingConfig.recommendedBitrate(width, height, RecordingConfig.DEFAULT_VIDEO_FRAME_RATE)
        Log.i(
            TAG,
            "buildRecordingConfig: rawSize=${metrics.widthPixels}x${metrics.heightPixels} " +
                "evenSize=${width}x${height} densityDpi=${metrics.densityDpi} bitrate=$bitrate " +
                "outputFileName=${outputFileName ?: "<auto>"}",
        )
        return RecordingConfig(
            width = width,
            height = height,
            densityDpi = metrics.densityDpi,
            videoBitrate = bitrate,
            durationSeconds = durationSeconds,
            outputDirectory = outputDir,
            outputFileName = outputFileName ?: RecordingConfig.generateOutputFileName(),
        )
    }

    // endregion

    // region notifications

    private fun ensureNotificationChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        )
        manager.createNotificationChannel(channel)
    }

    private fun startForegroundWithNotification(notification: android.app.Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun updateNotification(notification: android.app.Notification) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, notification)
    }

    private fun stopForegroundCompat() {
        @Suppress("DEPRECATION")
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    private fun basePendingContentIntent(): android.app.PendingIntent {
        val activityIntent = Intent(this, MainActivity::class.java)
        val flags = android.app.PendingIntent.FLAG_IMMUTABLE
        return android.app.PendingIntent.getActivity(this, 0, activityIntent, flags)
    }

    private fun buildNotification(currentState: RecordingState, config: RecordingConfig?): android.app.Notification {
        val (title, content) = when (currentState) {
            RecordingState.STARTING -> getString(R.string.notification_title_starting) to
                getString(R.string.notification_content_starting)
            RecordingState.RECORDING -> getString(R.string.notification_title_recording) to
                getString(
                    R.string.notification_content_recording,
                    config?.durationSeconds ?: 0L,
                )
            else -> getString(R.string.notification_title_starting) to
                getString(R.string.notification_content_starting)
        }
        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(content)
            .setContentIntent(basePendingContentIntent())
            .setOngoing(true)
            .build()
    }

    private fun buildStoppingNotification(): android.app.Notification {
        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(getString(R.string.notification_title_stopping))
            .setContentText(getString(R.string.notification_content_stopping))
            .setContentIntent(basePendingContentIntent())
            .setOngoing(true)
            .build()
    }

    private fun buildFinalNotification(valid: Boolean): android.app.Notification {
        val title = if (valid) {
            getString(R.string.notification_title_stopped)
        } else {
            getString(R.string.notification_title_failed)
        }
        val content = if (valid) {
            getString(R.string.notification_content_stopped)
        } else {
            getString(R.string.notification_content_failed)
        }
        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(content)
            .setContentIntent(basePendingContentIntent())
            .setOngoing(false)
            .setAutoCancel(true)
            .build()
    }

    private fun buildFailedNotification(): android.app.Notification {
        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(getString(R.string.notification_title_failed))
            .setContentText(getString(R.string.notification_content_failed))
            .setContentIntent(basePendingContentIntent())
            .setOngoing(false)
            .setAutoCancel(true)
            .build()
    }

    // endregion

    private fun statusSummary(): String = "state=$state\n${diagnostics.dumpString()}"

    override fun dump(fd: FileDescriptor, writer: PrintWriter, args: Array<out String>?) {
        writer.println("ScreenRecordService dump:")
        writer.println("  state=$state")
        writer.println(diagnostics.dumpString())
    }

    companion object {
        private const val TAG = "ScreenRecordService"

        const val ACTION_START = "com.xros.securescreenrecord.action.START"
        const val ACTION_STOP = "com.xros.securescreenrecord.action.STOP"
        const val ACTION_STATUS = "com.xros.securescreenrecord.action.STATUS"
        const val EXTRA_DURATION_SECONDS = "durationSeconds"
        const val EXTRA_OUTPUT_FILE_NAME = "outputFileName"

        private const val RECORDINGS_DIR_NAME = "recordings"
        private const val NOTIFICATION_CHANNEL_ID = "com.xros.securescreenrecord.recording"
        private const val NOTIFICATION_ID = 1
    }
}

/**
 * In-process holder exposing the latest recording status to [MainActivity] for optional
 * display. Not a control interface; ADB remains the only control plane.
 */
object RecordingStatusHolder {
    @Volatile var state: RecordingState = RecordingState.IDLE
    @Volatile var lastOutputValid: Boolean? = null
    @Volatile var lastOutputFilePath: String? = null
    @Volatile var lastError: String? = null
}
