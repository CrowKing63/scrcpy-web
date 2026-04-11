# SCRCPY-Web — Project Context

## Project Overview

**SCRCPY-Web** is an Android application that mirrors and controls an Android device's screen from any web browser over local Wi-Fi — no PC required for streaming. It uses an embedded Ktor web server to deliver H.264/fMP4 video over WebSocket to browser clients, and receives touch input (tap, swipe, scroll) back from the browser via the same WebSocket connection.

The project is designed with Apple Vision Pro Safari (spatial display) as a primary target, but supports all major browsers with MSE + H.264 capabilities (Chrome, Edge, Firefox, Safari on iOS/macOS).

**Current version:** 2.3.6 (versionCode 44)
**License:** Apache 2.0

## Architecture

```
Android Phone (Ktor Server)  <---H264/fMP4 over WS--->  Browser (MSE Player)
Browser (Pointer Events)     <---Touch JSON over WS---> Android Phone (TouchInjectionService)
```

### Key Components

| Component | Description |
|-----------|-------------|
| `MirrorService` | Foreground service managing MediaProjection screen capture |
| `ScreenCapture` | Captures the device screen via Android MediaProjection API |
| `VideoEncoder` | Encodes captured frames to H.264 using MediaCodec |
| `FMP4Muxer` | Multiplexes encoded H.264 into fragmented MP4 (fMP4) segments for MSE |
| `WebServer` (Ktor) | Embedded Netty-based HTTP/WS server serving the web UI and handling sessions |
| `StreamSession` | Manages per-client WebSocket connections and video streaming |
| `TouchInjectionService` | Accessibility service that injects touch/gesture events into the Android system |
| `BootReceiver` | Auto-starts the server on device boot |

### Directory Structure

```
scrcpy-web/
├── app/src/main/
│   ├── kotlin/com/scrcpyweb/
│   │   ├── capture/       # Screen capture and video encoding
│   │   ├── server/        # Ktor web server and WebSocket handlers
│   │   ├── service/       # Android services (MirrorService, TouchInjectionService, BootReceiver)
│   │   └── ui/            # Android UI activities
│   ├── assets/web/        # Web frontend (index.html, main.js, style.css, icons.js)
│   ├── res/               # Android resources (layouts, strings, drawables)
│   └── AndroidManifest.xml
├── installer/             # Guided installers (install.bat, install.sh, install.ps1)
├── docs/                  # Documentation (installation.md, claude-reference.md)
├── .github/               # GitHub Actions CI/CD workflows
├── build.gradle.kts       # Root Gradle build file
├── settings.gradle.kts    # Gradle settings
└── gradle.properties      # Gradle properties
```

## Technologies & Dependencies

| Technology | Version | Purpose |
|------------|---------|---------|
| Kotlin | 2.0.21 | Primary language |
| Android Gradle Plugin | 8.7.3 | Build system |
| JDK | 17 | Compilation target |
| Android SDK | API 36 (compileSdk), API 29 (minSdk) | Android platform |
| Ktor Server | 3.1.1 | Embedded HTTP + WebSocket server (Netty) |
| kotlinx-coroutines | 1.9.0 | Asynchronous programming |
| kotlinx-serialization | 2.0.21 | JSON serialization |
| AndroidX Core/AppCompat | 1.15.0 / 1.7.0 | Android compatibility |
| Material Components | 1.12.0 | UI components |

## Building and Running

### Prerequisites
- JDK 17
- Android SDK with API 36
- Android device or emulator

### Commands

```bash
# Debug build
./gradlew assembleDebug

# Install on connected device
./gradlew installDebug

# Release build
./gradlew assembleRelease

# Run Kotlin compilation check only (fast)
./gradlew compileDebugKotlin

# Run lint
./gradlew lintDebug
```

### Release Signing
Release builds use environment variables for signing:
- `RELEASE_KEYSTORE_PATH` — Path to the keystore file
- `RELEASE_KEYSTORE_PASSWORD` — Keystore password
- `RELEASE_KEY_ALIAS` — Key alias
- `RELEASE_KEY_PASSWORD` — Key password

Without these, the build falls back to the debug keystore.

## Development Conventions

### Code Style
- **Kotlin**: Follow the [Kotlin official code style](https://kotlinlang.org/docs/coding-conventions.html). KDoc on all public classes and methods.
- **JavaScript**: ES2020+, JSDoc on all public functions, no frameworks or build tools.
- **XML**: Standard Android XML formatting.
- **All code, comments, and documentation must be in English.**

### Commit Messages
The project uses [Conventional Commits](https://www.conventionalcommits.org/):
```
feat: add MJPEG fallback for incompatible browsers
fix: release VirtualDisplay on MediaProjection stop
docs: update Quick Start instructions
refactor: extract buffer trimming to separate method
```

### Pull Request Process
1. Fork the repository and create a feature branch
2. Make your changes with appropriate tests
3. Ensure `./gradlew lintDebug` passes
4. Submit a pull request with a clear description of the change

### Localization
- Android strings: `res/values/strings.xml` (English default), with locale-specific folders (e.g., `values-ko/`, `values-ja/`)
- Web UI: Currently embedded in `index.html` (English); future versions will use a `locales/{lang}.json` system

## Configuration

Streaming settings are configurable via the browser UI (gear icon) or the Android app:

| Setting | Range | Default |
|---------|-------|---------|
| Resolution Scale | 25% – 100% | 75% |
| Bitrate | 1 – 8 Mbps | 4 Mbps |
| Max FPS | 15 – 60 | 30 |
| Server Port | Custom | 8080 |

## Known Limitations

- **MediaProjection re-auth on reboot**: After the phone reboots, you must tap "Allow" once in the system dialog (Android OS security requirement).
- **Screen rotation**: Restarting capture is required when the screen orientation changes.
- **Single-user**: One screen capture session; multiple browsers can view but only one capture stream runs at a time.
