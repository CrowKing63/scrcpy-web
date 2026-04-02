# Changelog

All notable changes to SCRCPY-Web are documented here.
This project adheres to [Keep a Changelog](https://keepachangelog.com/en/1.1.0/) format.

## [Unreleased]

## [0.1.0] - 2026-04-02

### Added
- Android foreground service with embedded Ktor/Netty HTTP + WebSocket server
- H264 screen capture via MediaProjection + MediaCodec (async mode)
- Pure-Kotlin fMP4 muxer for Safari MSE compatibility (ISO 14496-12)
- Touch injection via AccessibilityService (tap, swipe, scroll)
- Navigation actions: Back, Home, Recents, Lock Screen
- Auto-start on boot via BootReceiver
- Dark glassmorphism web UI optimised for Apple Vision Pro Safari
- MSE video player with SourceBuffer queue management and buffer trimming
- Pointer/touch event forwarding with 60 Hz throttle
- Keyboard shortcut forwarding with Android keycode mapping
- In-browser settings: resolution scale, bitrate, max FPS
- LocalStorage persistence for browser settings
- Auto-reconnect with exponential backoff
- REST API: device info, start/stop capture endpoints
- GitHub Actions workflow for automated APK release on tag push
