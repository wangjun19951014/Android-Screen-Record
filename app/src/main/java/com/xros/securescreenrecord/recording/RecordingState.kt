package com.xros.securescreenrecord.recording

/** Lifecycle state of a recording session, transitioned only on the service's serial worker thread. */
enum class RecordingState {
    IDLE,
    STARTING,
    RECORDING,
    STOPPING,
    STOPPED,
    FAILED,
}

/** Why a stop was requested. See doc §6.3. */
enum class StopReason {
    AUTO_DURATION,
    EXTERNAL_COMMAND,
    MEDIA_RECORDER_ERROR,
    SERVICE_DESTROYED,
}

/** Failure classification surfaced in diagnostics and dump(). See doc §15. */
enum class FailureReason {
    SECURITY_UID_OR_PERMISSION,
    VIRTUAL_DISPLAY_CREATION_FAILED,
    SURFACE_OR_DISPLAY_HAL_ERROR,
    REMOTE_SUBMIX_INIT_FAILED,
    MEDIA_CODEC_INIT_FAILED,
    STOP_FAILED,
    OUTPUT_FILE_UNWRITABLE,
    INVALID_DURATION,
    UNKNOWN,
}
