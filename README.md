# Android-Screen-Record
To address the restriction on screen recording in Android caused by the FLAG_SECURE tag, this method implements recording of FLAG_SECURE interfaces at the app level, relying on system application permissions.

# User Guide
## action

| action | intent |
|---|---|
| `com.xros.securescreenrecord.action.START` | start record |
| `com.xros.securescreenrecord.action.STOP` | stop record |
| `com.xros.securescreenrecord.action.STATUS` | query status |

## start record
```bash
adb shell am start-foreground-service  -n com.xros.securescreenrecord/.service.ScreenRecordService -a com.xros.securescreenrecord.action.START --el durationSeconds 30 --es outputFileName my_recording.mp4
```

## stop recprd
```bash
adb shell am startservice -n com.xros.securescreenrecord/.service.ScreenRecordService -a com.xros.securescreenrecord.action.STOP
```
