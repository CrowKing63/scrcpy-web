# CLAUDE.md — SCRCPY-Web

> Detailed reference: `docs/claude-reference.md`

## Project Overview

**SCRCPY-Web** — Android app with embedded Ktor/Netty web server. Mirrors and controls phone screen from any browser (primary: Apple Vision Pro Safari) over local Wi-Fi, no PC required.

- **Package**: `com.scrcpyweb` | **Target SDK**: Android 16 (API 36) | **minSdk**: 29
- **Languages**: Kotlin (Android), Vanilla JS (Web Frontend)

---

## Language Policy

> All code, comments, KDoc, JSDoc, XML resources, README, and web UI text must be **English**.
> Korean and other languages are translations only (`res/values-ko/strings.xml`).

- Commit messages: [Conventional Commits](https://www.conventionalcommits.org/) in **English**

---

## Architecture

```
Android Phone (Server)
  MirrorService (Foreground)
    └─ Ktor Server (HTTP + WebSocket, :8080)
    └─ MediaProjection → MediaCodec H264 → FMP4Muxer → WebSocket binary frames

Browser (Vision Pro Safari)
  MSE Player ← WebSocket binary (fMP4 segments)
  Touch/Pointer Events → WebSocket JSON → AccessibilityService.dispatchGesture()
```

| File | Responsibility |
|------|----------------|
| `capture/ScreenCapture.kt` | MediaProjection lifecycle |
| `capture/VideoEncoder.kt` | MediaCodec H264 async encoding |
| `capture/FMP4Muxer.kt` | NAL units → fMP4 (ISO 14496-12) |
| `server/WebServer.kt` | Ktor HTTP + WebSocket |
| `server/StreamSession.kt` | WS session, fMP4 relay, input parsing |
| `service/TouchInjectionService.kt` | AccessibilityService gesture injection |
| `service/MirrorService.kt` | Foreground Service, pipeline wiring |
| `assets/web/` | HTML/JS/CSS frontend bundled into APK |

---

## Build Commands

```bash
./gradlew assembleDebug       # Debug build
./gradlew assembleRelease     # Release build
./gradlew installDebug        # Install on device
./gradlew compileDebugKotlin  # Kotlin check only
./gradlew lintDebug           # Lint
```

Gradle **8.11** | AGP **8.7.x** | Kotlin **2.0.x** | Java **17**

---

## Critical Constraints

### MediaProjection (Android 14+)
- Token invalidated on process kill or reboot — user must tap "Allow" once after reboot.
- **Cannot** be auto-approved via accessibility service (OS blocks by design).

### visionOS Safari + MSE
- **MSE with fMP4 is the default decoder** — WebCodecs is unstable on visionOS.
- MIME type: `video/mp4; codecs="avc1.42E01E"` (H264 Baseline Profile).

### fMP4 Muxer
- Pure Kotlin, no external libraries. Reference: ISO 14496-12 (ISOBMFF).
- Annex B (00 00 00 01 + NALU) → AVCC (4-byte length prefix + NALU).
- Init segment: `ftyp + moov`. Media segments: `moof + mdat` per frame.

### Accessibility Service
- `accessibilityEventTypes=""` — gesture injection only, no event monitoring.

---

## Coding Conventions

### Kotlin
- KDoc on all public classes/methods (English)
- Kotlin official code style (`kotlin.code.style=official`)
- Coroutines for async; no blocking on main thread
- `MediaCodec` on dedicated `HandlerThread` (async callback mode)
- Release resources in reverse initialization order

### Web Frontend
- Vanilla JS only — no frameworks, no build tools
- JSDoc on all public methods (English); ES2020+
- CSS custom properties for all design tokens

---

## WebSocket Protocol

**Server → Client** (binary):
```
byte 0:    0x01=init_segment (ftyp+moov)  |  0x02=media_segment (moof+mdat)
bytes 1-4: data length (uint32, big-endian)
bytes 5+:  fMP4 data
```

**Client → Server** (JSON text):
```json
{"type":"touch",  "action":"down|move|up", "x":0.5, "y":0.3, "id":0}
{"type":"nav",    "action":"back|home|recents|power|volumeUp|volumeDown"}
{"type":"scroll", "x":0.5, "y":0.5, "dx":0, "dy":-120}
{"type":"key",    "keyCode":66, "action":"down|up"}
{"type":"config", "bitrate":4000000, "maxFps":30, "scale":0.5}
```

---

## Internationalization

- English strings: `res/values/strings.xml` — source of truth, never replace with translations.
- Add language: copy to `res/values-{locale}/strings.xml` and translate.
- Supported: `en` (default), `ko`.

---

## Release Process

```bash
# Update CHANGELOG.md first, then:
git tag vX.Y.Z && git push origin main vX.Y.Z
```

GitHub Actions builds release APK and creates a GitHub Release automatically.
