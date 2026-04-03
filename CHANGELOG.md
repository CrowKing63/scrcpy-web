# Changelog

All notable changes to SCRCPY-Web are documented here.
This project adheres to [Keep a Changelog](https://keepachangelog.com/en/1.1.0/) format.

## [Unreleased]

## [0.2.9] - 2026-04-03

### Fixed
- Video frozen after first frame: init segment and media frames were delivered through
  separate code paths (scope.launch vs direct call), allowing media frames to arrive at
  the browser before the init segment. All payloads now flow through the same per-session
  channel, guaranteeing in-order delivery.
- StreamSession: frameListeners now registered before enqueuing the init segment so a
  concurrent updateInitSegment() call cannot bypass the channel.
- MirrorService: removed unnecessary CoroutineScope — updateInitSegment() is now a
  plain (non-suspend) function called directly from the encoder HandlerThread.

## [0.2.8] - 2026-04-03

### Fixed
- Black screen on initial connection: race condition where the server's init segment arrived
  over WebSocket before `MediaSource.sourceopen` had fired; the segment was silently dropped
  and no SourceBuffer was ever created. Init segment is now buffered in `_pendingInitData`
  and applied inside the `sourceopen` handler.
- Video stalling after live stream buffer underrun: added `pause` event handler on the video
  element to call `play()` and resume automatically.

## [0.2.7] - 2026-04-03

### Fixed
- Black screen with no video: `trun data-offset` in fMP4 muxer was left as 0 (placeholder never
  patched), causing browsers to look for sample data at the wrong position in each media segment
- Set `tfhd` flag `default-base-is-moof` (0x020000) so the trun data-offset baseline is
  unambiguously anchored to the start of the enclosing `moof` box, as required for live streaming
- Browser-side: new fMP4 init segments sent after a pipeline restart (e.g. screen rotation) are now
  appended to the existing SourceBuffer instead of being silently dropped

## [0.2.6] - 2026-04-03

### Fixed
- Crash on server start (Android 14+): foreground service now starts with `dataSync` type from the
  button click and upgrades to `mediaProjection` type only from within the MediaProjection
  activity-result callback, satisfying the Android 14 restriction
- Crash on screen capture permission grant (Android 14+): `startForegroundService()` is now called
  from inside the `ActivityResultCallback` so `getMediaProjection()` executes in the required context
- Replaced deprecated `WindowManager.defaultDisplay.getRealMetrics()` with `DisplayManager` API

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
