package com.xros.securescreenrecord.recording

import android.content.Context
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaCodecInfo
import android.media.MediaRecorder
import android.os.Build
import android.util.Log
import java.io.IOException

/**
 * Owns the MediaRecorder + VirtualDisplay lifecycle for a single recording session.
 * All public methods are expected to be called from a single serial worker thread
 * (see ScreenRecordService) and are idempotent with respect to [state].
 */
class ScreenRecorder(private val context: Context) {

    var state: RecordingState = RecordingState.IDLE
        private set

    var lastFailureReason: FailureReason? = null
        private set

    var lastException: Throwable? = null
        private set

    private var config: RecordingConfig? = null
    private var mediaRecorder: MediaRecorder? = null
    private var virtualDisplay: VirtualDisplay? = null

    /**
     * Starts recording per doc §12.1. Returns true on success. On failure, [state] becomes
     * FAILED, [lastFailureReason] and [lastException] are populated, and any partially
     * initialized resources are released.
     */
    fun start(recordingConfig: RecordingConfig): Boolean {
        if (state != RecordingState.IDLE) {
            Log.w(TAG, "start() called while state=$state, ignoring (not idempotent restart)")
            return false
        }
        config = recordingConfig
        state = RecordingState.STARTING
        lastFailureReason = null
        lastException = null

        try {
            recordingConfig.outputDirectory.apply {
                if (!exists() && !mkdirs()) {
                    throw IOException("Failed to create output directory: $absolutePath")
                }
            }

            val recorder = createConfiguredMediaRecorder(recordingConfig)
            mediaRecorder = recorder

            val recorderSurface = recorder.surface

            val displayManager = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
            val vd = displayManager.createVirtualDisplay(
                VIRTUAL_DISPLAY_NAME,
                recordingConfig.width,
                recordingConfig.height,
                recordingConfig.densityDpi,
                recorderSurface,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC or
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_SECURE or
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            )
            if (vd == null) {
                lastFailureReason = FailureReason.VIRTUAL_DISPLAY_CREATION_FAILED
                throw IllegalStateException("createVirtualDisplay() returned null")
            }
            virtualDisplay = vd
            Log.i(
                TAG,
                "createVirtualDisplay() succeeded: size=${recordingConfig.width}x${recordingConfig.height} " +
                    "densityDpi=${recordingConfig.densityDpi}",
            )

            recorder.start()
            Log.i(TAG, "MediaRecorder.start() succeeded, output=${recordingConfig.outputFile.absolutePath}")
            state = RecordingState.RECORDING
            return true
        } catch (t: Throwable) {
            Log.e(TAG, "start() failed", t)
            if (lastFailureReason == null) {
                lastFailureReason = classifyFailure(t)
            }
            lastException = t
            state = RecordingState.FAILED
            releaseInternal()
            return false
        }
    }

    /**
     * Stops recording per doc §12.2. Returns true if the output file exists and is non-empty.
     * Idempotent: calling stop() when not RECORDING/STARTING is a no-op that returns false.
     */
    fun stop(): Boolean {
        if (state != RecordingState.RECORDING && state != RecordingState.STARTING) {
            Log.w(TAG, "stop() called while state=$state, ignoring")
            return false
        }
        state = RecordingState.STOPPING
        Log.i(TAG, "stop() begin")

        var stopSucceeded = true
        try {
            virtualDisplay?.surface = null
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to clear virtual display surface", t)
        }

        try {
            mediaRecorder?.stop()
            Log.i(TAG, "MediaRecorder.stop() succeeded")
        } catch (t: Throwable) {
            // Very short recordings can fail to stop cleanly; the output file may be invalid.
            Log.e(TAG, "MediaRecorder.stop() failed", t)
            lastFailureReason = FailureReason.STOP_FAILED
            lastException = t
            stopSucceeded = false
        }

        releaseInternal()

        val outputFile = config?.outputFile
        val validOutput = stopSucceeded && outputFile != null && outputFile.exists() && outputFile.length() > 0L
        if (!validOutput && outputFile != null && outputFile.exists() && outputFile.length() == 0L) {
            outputFile.delete()
        }
        Log.i(
            TAG,
            "stop() end: validOutput=$validOutput outputFile=${outputFile?.absolutePath} " +
                "size=${outputFile?.takeIf { it.exists() }?.length()}",
        )

        state = RecordingState.STOPPED
        return validOutput
    }

    /** Releases all resources unconditionally. Safe to call multiple times. */
    fun release() {
        releaseInternal()
        state = RecordingState.IDLE
    }

    private fun releaseInternal() {
        try {
            virtualDisplay?.release()
        } catch (t: Throwable) {
            Log.w(TAG, "Error releasing VirtualDisplay", t)
        }
        virtualDisplay = null

        try {
            mediaRecorder?.reset()
        } catch (t: Throwable) {
            Log.w(TAG, "Error resetting MediaRecorder", t)
        }
        try {
            mediaRecorder?.release()
        } catch (t: Throwable) {
            Log.w(TAG, "Error releasing MediaRecorder", t)
        }
        mediaRecorder = null
    }

    private fun createConfiguredMediaRecorder(cfg: RecordingConfig): MediaRecorder {
        return MediaRecorder().apply {
            try {
                setAudioSource(MediaRecorder.AudioSource.REMOTE_SUBMIX)
                Log.i(TAG, "setAudioSource(REMOTE_SUBMIX) succeeded")
            } catch (t: Throwable) {
                lastFailureReason = FailureReason.REMOTE_SUBMIX_INIT_FAILED
                Log.e(TAG, "setAudioSource(REMOTE_SUBMIX) failed", t)
                throw t
            }
            setVideoSource(MediaRecorder.VideoSource.SURFACE)
            Log.i(TAG, "setVideoSource(SURFACE) succeeded")

            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setOutputFile(cfg.outputFile.absolutePath)

            setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            setAudioSamplingRate(cfg.audioSampleRate)
            setAudioChannels(cfg.audioChannelCount)
            setAudioEncodingBitRate(cfg.audioBitrate)
            Log.i(
                TAG,
                "audio configured: sampleRate=${cfg.audioSampleRate} channels=${cfg.audioChannelCount} " +
                    "bitrate=${cfg.audioBitrate}",
            )

            setVideoEncoder(MediaRecorder.VideoEncoder.H264)
            setVideoSize(cfg.width, cfg.height)
            setVideoFrameRate(cfg.videoFrameRate)
            setVideoEncodingBitRate(cfg.videoBitrate)
            applyHighQualityProfileIfAvailable()
            Log.i(
                TAG,
                "video configured: size=${cfg.width}x${cfg.height} fps=${cfg.videoFrameRate} " +
                    "bitrate=${cfg.videoBitrate}",
            )

            // Auxiliary safety cap only; the service's own timer drives the actual stop.
            setMaxDuration((cfg.durationSeconds * 1000).toInt())

            try {
                prepare()
                Log.i(TAG, "MediaRecorder.prepare() succeeded")
            } catch (t: Throwable) {
                if (lastFailureReason == null) {
                    lastFailureReason = FailureReason.MEDIA_CODEC_INIT_FAILED
                }
                Log.e(TAG, "MediaRecorder.prepare() failed", t)
                throw t
            }
        }
    }

    /**
     * Requests H.264 High Profile / Level 5.1 when the platform supports it (API 24+). This
     * improves perceived sharpness at the same bitrate compared to the encoder's default
     * profile/level. Failures are non-fatal; the encoder falls back to its default profile.
     */
    private fun MediaRecorder.applyHighQualityProfileIfAvailable() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return
        try {
            setVideoEncodingProfileLevel(
                MediaCodecInfo.CodecProfileLevel.AVCProfileHigh,
                MediaCodecInfo.CodecProfileLevel.AVCLevel51,
            )
            Log.i(TAG, "setVideoEncodingProfileLevel(High, Level51) succeeded")
        } catch (t: Throwable) {
            Log.w(TAG, "setVideoEncodingProfileLevel failed, using encoder default profile", t)
        }
    }

    private fun classifyFailure(t: Throwable): FailureReason {
        return when (t) {
            is SecurityException -> FailureReason.SECURITY_UID_OR_PERMISSION
            is IOException -> FailureReason.OUTPUT_FILE_UNWRITABLE
            else -> FailureReason.UNKNOWN
        }
    }

    companion object {
        private const val TAG = "ScreenRecorder"
        private const val VIRTUAL_DISPLAY_NAME = "SecureScreenRecord-VirtualDisplay"
    }
}
