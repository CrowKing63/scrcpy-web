# Claude Reference — SCRCPY-Web

> Detailed reference extracted from CLAUDE.md. Not loaded automatically — read when needed.

---

## Key Dependencies

```kotlin
// Embedded HTTP + WebSocket server
"io.ktor:ktor-server-core:3.1.1"
"io.ktor:ktor-server-netty:3.1.1"
"io.ktor:ktor-server-websockets:3.1.1"
"io.ktor:ktor-server-content-negotiation:3.1.1"
"io.ktor:ktor-serialization-kotlinx-json:3.1.1"

// Coroutines
"org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0"

// AndroidX
"androidx.core:core-ktx:1.15.0"
"androidx.appcompat:appcompat:1.7.0"
"com.google.android.material:material:1.12.0"
```

---

## Project Structure

```
scrcpy-web/
├── app/src/main/
│   ├── kotlin/com/scrcpyweb/
│   │   ├── ui/MainActivity.kt
│   │   ├── service/MirrorService.kt, TouchInjectionService.kt, BootReceiver.kt
│   │   ├── capture/ScreenCapture.kt, VideoEncoder.kt, FMP4Muxer.kt
│   │   └── server/WebServer.kt, StreamSession.kt
│   ├── res/values/strings.xml (EN), values-ko/strings.xml (KO)
│   └── assets/web/index.html, main.js, style.css, icons.js
├── .github/workflows/release.yml
├── docs/
└── build.gradle.kts, settings.gradle.kts, gradle.properties
```

---

## Implementation Notes

### MirrorService — Screen Rotation
- `savedProjectionResultCode` and `savedProjectionData` stored after first `startCapture()`.
- Call `handleConfigChange()` from `MainActivity.onConfigurationChanged` to restart pipeline.
- Restart order: `stopCapture()` → update dimensions → `startCapture(savedResultCode, savedData)`.

### VideoEncoder — SPS/PPS Extraction
- SPS/PPS delivered as codec-specific data (`BUFFER_FLAG_CODEC_CONFIG`) on first output buffer.
- Arrive in Annex B format: `[00 00 00 01 SPS_NALU 00 00 00 01 PPS_NALU]`.
- `parseSpsAndPps()` strips start codes before passing raw bytes to `FMP4Muxer`.

### FMP4Muxer — Timescale
- Uses 90 kHz timescale (standard for video RTP/fMP4).
- `pts`/`dts` inputs in **microseconds** (`MediaCodec.BufferInfo.presentationTimeUs`).
- Sequence number increments per `muxFrame()` call, written into `mfhd`.

### StreamSession — Per-Client Channel
- Each client gets a `Channel<ByteArray>(capacity = 60)` to avoid head-of-line blocking.
- `frameListeners` map (session ID → lambda): populated on connect, removed on disconnect.
- `sendFrameToAll()` uses `trySend` (non-blocking drop) to avoid back-pressure stalls.

### Web Frontend — MSE Buffer Management
- `SourceBuffer.mode = 'sequence'` — timestamps ignored; segments appended in order.
- Buffer trim: remove everything >2s behind `video.currentTime` on each `updateend`.
- Segment queue (`_segmentQueue`) drained on `updateend` to handle SourceBuffer busy states.

### SharedPreferences Keys (`"scrcpy_prefs"`)
| Key | Type | Default | Description |
|-----|------|---------|-------------|
| `scale` | Float | 0.75 | Resolution multiplier (0.25–1.0) |
| `bitrate` | Int | 4_000_000 | Encoder bitrate in bps |
| `fps` | Int | 30 | Target encoder frame rate |

---

## Hooks & Automation

Configured in `.claude/settings.json`.

### PostToolUse hooks (Write/Edit)
| Trigger | Reminder |
|---------|---------|
| `**.kt` | KDoc in English, Kotlin style, MediaCodec HandlerThread, reverse-release order |
| `**.js` | JSDoc in English, no frameworks, ES2020+, no build tools |
| `**/values/strings.xml` | English-only source of truth; translations go in `values-{locale}/` |
| `**/layout/*.xml` | Material3 components only; all text via `@string/` resources |
| `**build.gradle.kts` | Check dependency versions match; Java 17 compatibility |

### PreToolUse hooks (Bash)
| Trigger | Reminder |
|---------|---------|
| `git commit` | Conventional Commits in English; update CHANGELOG before release |
| `git tag` | Tag format `v{major}.{minor}.{patch}`; pushes trigger GitHub Actions APK build |
| `gradlew assembleRelease` | Ensure signing config is set; debug keystore is placeholder only |

---

## Risk Mitigations

| Risk | Mitigation |
|------|-----------|
| visionOS Safari MSE incompatibility | MJPEG fallback (JPEG frames over WS) |
| fMP4 muxer complexity | Implement MJPEG mode first, add fMP4 later |
| MediaProjection token expiry | Notification-based easy restart prompt |
| Ktor memory overhead | Can replace with NanoHTTPD if needed |
| H264 encoding heat | Dynamic resolution/FPS/bitrate adjustment |
