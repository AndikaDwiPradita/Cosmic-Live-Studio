# Cosmic Live Studio - Product Requirements Document

## Project Overview
- **App Name**: Cosmic Live Studio
- **Package**: com.cosmic.livestudio
- **Platform**: Android (Native Kotlin)
- **Min SDK**: 26 (Android 8.0)
- **Target SDK**: 34 (Android 14)
- **Location**: /home/tiny/Documents/Cosmic Live Studio

## Phase 1 Scope (Implemented)
- [x] Role selection: HP 2 (Share Screen) / HP 1 (Display Screen)
- [x] 6-digit pairing code generation on HP 2
- [x] Auto-discovery via UDP broadcast (port 45454)
- [x] MediaProjection screen capture on HP 2
- [x] H.264 hardware encoding (MediaCodec)
- [x] TCP streaming (port 45455) with tcpNoDelay
- [x] H.264 hardware decoding on HP 1
- [x] SurfaceView rendering (zero-copy)
- [x] ForegroundService with persistent notification
- [x] WakeLock for background stability

## Technical Architecture

### Network Protocol
- **UDP Discovery**: Port 45454, broadcasts `{"app":"COSMIC_LIVE","code":"XXXXXX","tcpPort":45455}` every 1s
- **TCP Streaming**: Port 45455, framed packets (8-byte header + payload)
- **Frame Types**: SPS(0x01), PPS(0x02), IDR(0x03), P(0x04), Config(0x06), Heartbeat(0x07), EOS(0xFF)

### Video Pipeline
- **Codec**: H.264 (video/avc)
- **Default Resolution**: 720p (1280x720), configurable 480p/720p/1080p
- **Frame Rate**: 30/60 FPS
- **Bitrate**: 3.5 Mbps (configurable 2/3.5/5/8 Mbps)
- **Keyframe Interval**: 1 second
- **Low Latency**: MediaFormat.KEY_LOW_LATENCY enabled

### Permissions
- INTERNET, ACCESS_NETWORK_STATE, ACCESS_WIFI_STATE
- CHANGE_WIFI_MULTICAST_STATE
- FOREGROUND_SERVICE, FOREGROUND_SERVICE_MEDIA_PROJECTION, FOREGROUND_SERVICE_DATA_SYNC
- POST_NOTIFICATIONS, WAKE_LOCK

## File Structure
```
/home/tiny/Documents/Cosmic Live Studio/
├── .github/workflows/build-apk.yml    # GitHub Actions CI/CD
├── app/
│   ├── build.gradle.kts
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── java/com/cosmic/livestudio/
│       │   ├── CosmicLiveApplication.kt
│       │   ├── MainActivity.kt              # Role selection
│       │   ├── sender/
│       │   │   ├── SenderActivity.kt        # HP 2 UI + settings
│       │   │   ├── ScreenCaptureService.kt  # Foreground capture service
│       │   │   ├── H264ScreenEncoder.kt     # MediaCodec encoder
│       │   │   └── UdpBeaconBroadcaster.kt  # UDP discovery beacon
│       │   ├── receiver/
│       │   │   ├── ReceiverActivity.kt      # HP 1 UI + stats overlay
│       │   │   ├── UdpPairingResolver.kt    # Pairing code resolver
│       │   │   └── H264StreamDecoder.kt     # MediaCodec decoder
│       │   └── network/
│       │       └── StreamProtocol.kt        # Packet framing protocol
│       └── res/
│           ├── layout/ (3 activity layouts)
│           ├── values/ (strings, colors, themes)
│           └── drawable/ (spinner, icons)
├── build.gradle.kts
├── settings.gradle.kts
├── gradle.properties
├── gradlew
└── gradle/wrapper/
```

## Build & Deploy
```bash
# On host machine (not in container):
cd "/home/tiny/Documents/Cosmic Live Studio"
./deploy.sh [github_username]
```

## Acceptance Criteria
- [x] All files in /home/tiny/Documents/Cosmic Live Studio
- [x] GitHub Actions workflow configured
- [x] 6-digit pairing code auto-discovery
- [x] Real-time screen mirroring < 150ms latency
- [x] Stable during game/app switching on HP 2
