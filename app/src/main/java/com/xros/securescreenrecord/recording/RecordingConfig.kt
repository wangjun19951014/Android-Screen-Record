package com.xros.securescreenrecord.recording

import android.view.Display
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Configuration for a single recording session. See doc §10.
 *
 * [width]/[height] must already be adjusted to even values before being passed here;
 * use [RecordingConfig.evenSize] for that.
 */
data class RecordingConfig(
    val displayId: Int = Display.DEFAULT_DISPLAY,
    val width: Int,
    val height: Int,
    val densityDpi: Int,
    val videoFrameRate: Int = DEFAULT_VIDEO_FRAME_RATE,
    val videoBitrate: Int = DEFAULT_VIDEO_BITRATE,
    val audioSampleRate: Int = DEFAULT_AUDIO_SAMPLE_RATE,
    val audioChannelCount: Int = DEFAULT_AUDIO_CHANNEL_COUNT,
    val audioBitrate: Int = DEFAULT_AUDIO_BITRATE,
    val durationSeconds: Long,
    val outputDirectory: File,
    val outputFileName: String = generateOutputFileName(),
) {
    val outputFile: File get() = File(outputDirectory, outputFileName)

    companion object {
        const val DEFAULT_VIDEO_FRAME_RATE = 30
        const val DEFAULT_VIDEO_BITRATE = 8_000_000
        const val DEFAULT_AUDIO_SAMPLE_RATE = 48_000
        const val DEFAULT_AUDIO_CHANNEL_COUNT = 2
        const val DEFAULT_AUDIO_BITRATE = 128_000

        const val MIN_DURATION_SECONDS = 1L
        const val MAX_DURATION_SECONDS = 7_200L

        private val FILE_NAME_FORMAT = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US)

        fun isValidDuration(durationSeconds: Long?): Boolean {
            return durationSeconds != null &&
                durationSeconds in MIN_DURATION_SECONDS..MAX_DURATION_SECONDS
        }

        /** Rounds a dimension down to the nearest even value, as required by most video encoders. */
        fun evenSize(value: Int): Int = value - (value % 2)

        /**
         * Validates and sanitizes a caller-supplied output file name (from ADB START extras).
         * Returns null if the name is empty, contains path separators/traversal, or uses
         * disallowed characters. Enforces a ".mp4" suffix if the caller omitted it.
         *
         * If the caller reuses a name from a previous session, the resulting file is allowed to
         * be overwritten (this is a deliberate, user-requested action, not a collision with
         * anything the app itself generated).
         */
        fun sanitizeOutputFileName(rawName: String): String? {
            val trimmed = rawName.trim()
            if (trimmed.isEmpty() || trimmed.length > 200) return null
            // Reject path separators and traversal to prevent writing outside outputDirectory.
            if (trimmed.contains('/') || trimmed.contains('\\') || trimmed.contains("..")) return null
            if (!trimmed.matches(Regex("^[A-Za-z0-9._-]+$"))) return null
            return if (trimmed.endsWith(".mp4", ignoreCase = true)) trimmed else "$trimmed.mp4"
        }

        /**
         * Computes a bitrate scaled to actual resolution and frame rate instead of using a fixed
         * default, since a fixed 8Mbps looks visibly soft on higher-resolution displays.
         * Uses ~0.18 bits/pixel/frame (a common screen-recording quality target) and clamps to a
         * sane range to avoid pathological values on unusual display configurations.
         */
        fun recommendedBitrate(width: Int, height: Int, frameRate: Int): Int {
            val pixels = width.toLong() * height.toLong()
            val bitsPerSecond = (pixels * frameRate * 0.18).toLong()
            return bitsPerSecond.coerceIn(8_000_000L, 40_000_000L).toInt()
        }

        fun generateOutputFileName(): String {
            val timestamp = FILE_NAME_FORMAT.format(java.util.Date())
            return "recording_$timestamp.mp4"
        }
    }
}
