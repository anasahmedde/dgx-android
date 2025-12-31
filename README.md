# DIGIX Video Player - Android Application

A digital signage video player application for Android devices. This app automatically downloads and plays videos assigned to the device from a central management server, with support for video rotation, offline playback, and real-time synchronization.

## Features

### Core Functionality
- **Automatic Video Sync**: Downloads videos assigned to the device from the DIGIX server
- **Smart Incremental Sync**: Only downloads new videos and removes unassigned ones (no full re-download)
- **Offline Playback**: Continues playing cached videos when internet is unavailable
- **Loop Playback**: Automatically loops through all assigned videos continuously

### Video Display
- **Custom Rotation**: Supports 0°, 90°, 180°, 270° rotation per video
- **Fit Modes**: Cover, Contain, and Fill display modes
- **Smooth Transitions**: Fade effect between videos to hide rotation changes
- **Full Screen**: Immersive full-screen playback with hidden system UI

### Device Management
- **Heartbeat**: Sends online status to server every 60 seconds
- **Auto-Registration**: Automatically registers device with server on first launch
- **Boot Start**: Option to auto-start on device boot

## Requirements

- Android 7.0 (API 24) or higher
- Internet connection for initial setup and sync
- Storage permission for video caching

## Configuration

### Server URL
The API server URL is configured in `FullScreenPlayerActivity.java`:

```java
private static final String BASE = "http://34.248.112.237:8005";
```

Change this to your DIGIX backend server address.

### Timing Configuration

| Setting | Default | Description |
|---------|---------|-------------|
| Heartbeat Interval | 60 seconds | How often device reports online status |
| Rotation Poll | 10 seconds | How often to check for rotation changes |
| Sync Check | 60 seconds | How often to check for new video assignments |
| Online Threshold | 60 seconds | Server-side timeout before marking device offline |

## API Endpoints Used

| Endpoint | Method | Description |
|----------|--------|-------------|
| `/device/{id}/videos/downloads` | GET | Get list of assigned videos with download URLs |
| `/device/{id}/download_status` | GET | Check if sync is needed |
| `/device/{id}/download_update` | POST | Mark sync as complete |
| `/device/{id}/online_update` | POST | Send heartbeat |
| `/device/{id}/rotation` | GET | Get rotation settings for videos |

## Project Structure

```
app/src/main/java/com/example/videoplayer/
├── FullScreenPlayerActivity.java   # Main video player activity
├── MainActivity.java               # Launch activity
├── VideoViewActivity.java          # Alternative video view
└── BootReceiver.java              # Boot broadcast receiver

app/src/main/res/
├── layout/
│   ├── activity_fullscreen_player.xml
│   ├── activity_main.xml
│   └── activity_videoview.xml
└── values/
    ├── colors.xml
    ├── strings.xml
    └── themes.xml
```

## Key Components

### FullScreenPlayerActivity
The main activity handling:
- Video downloading with resume support
- ExoPlayer initialization and playback
- Rotation and fit mode transformations
- Background sync polling
- Online heartbeat reporting

### Smart Video Sync
```java
// Compares local files with server assignments
// Downloads only NEW videos
// Deletes only REMOVED videos
// Keeps existing videos intact
smartSyncVideos(mainDir, deviceId);
```

### Rotation Transition
```java
// Pre-emptive fade before video transition
// 1. Detect upcoming transition (250ms before end)
// 2. Fade out current video
// 3. Apply next video's rotation while hidden
// 4. Fade in when new video starts
startTransitionWatcher();
```

## Build Instructions

### Prerequisites
- Android Studio Arctic Fox or newer
- JDK 11 or higher
- Android SDK 34

### Build Steps

1. Clone the repository:
   ```bash
   git clone <repository-url>
   cd dgx-android-master
   ```

2. Open in Android Studio:
   - File → Open → Select project folder

3. Update server URL in `FullScreenPlayerActivity.java`:
   ```java
   private static final String BASE = "http://your-server:8005";
   ```

4. Build the APK:
   - Build → Build Bundle(s) / APK(s) → Build APK(s)

5. Install on device:
   ```bash
   adb install app/build/outputs/apk/debug/app-debug.apk
   ```

### Release Build

1. Create a keystore (if not exists):
   ```bash
   keytool -genkey -v -keystore digix-release.keystore -alias digix -keyalg RSA -keysize 2048 -validity 10000
   ```

2. Configure signing in `app/build.gradle`

3. Build release APK:
   - Build → Generate Signed Bundle / APK

## Permissions

```xml
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
<uses-permission android:name="android.permission.RECEIVE_BOOT_COMPLETED" />
<uses-permission android:name="android.permission.WAKE_LOCK" />
```

## Troubleshooting

### Videos Not Downloading
- Check internet connectivity
- Verify server URL is correct
- Check device is registered on server
- Look for errors in Logcat with tag `FullScreenPlayer`

### Rotation Not Applied
- Ensure rotation is set in DIGIX dashboard
- Wait for rotation poll interval (10 seconds)
- Check Logcat for rotation fetch errors

### Device Showing Offline
- Verify heartbeat is being sent (check Logcat)
- Check server's online threshold setting
- Ensure network allows POST requests to server

### App Crashes on Start
- Verify minimum SDK version (API 24)
- Check storage permissions are granted
- Review crash logs in Logcat

## Video Storage

Videos are stored in the app's private directory:
```
/data/data/com.example.videoplayer/files/videos/
```

Temporary downloads:
```
/data/data/com.example.videoplayer/files/videos_temp/
```

## Dependencies

```gradle
implementation 'androidx.media3:media3-exoplayer:1.2.0'
implementation 'androidx.media3:media3-ui:1.2.0'
implementation 'androidx.appcompat:appcompat:1.6.1'
implementation 'com.google.android.material:material:1.10.0'
```

## License

Proprietary - DIGIX Digital Signage System

## Support

For issues and feature requests, contact the DIGIX development team.

---

**Version**: 1.0.0  
**Last Updated**: December 2024
