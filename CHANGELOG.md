# Changelog

All notable changes to SCRCPY-Web are documented here.
This project adheres to [Keep a Changelog](https://keepachangelog.com/en/1.1.0/) format.

## [Unreleased]

## [0.4.0] - 2026-04-03

### Changed
- **Web UI Redesign:** Replaced floating control bar with persistent 3-column layout
  - Left sidebar: live streaming stats (FPS, latency, connection status) and stream settings
    (resolution, bitrate, max FPS)
  - Center: video player
  - Right sidebar: customizable keypad with add/edit/delete buttons via modal
- Sidebars are collapsible with state persisted to localStorage
- Keypad buttons stored in localStorage with 8 default buttons (back, home, recents, vol+, vol-, power, scroll↑, scroll↓)
- Edit mode: long-press button (600ms) or click pencil icon to enter; overlay controls for edit/delete per button
- Support for custom button actions: navigation, scroll, keyboard (by Android keycode), text input
- Responsive design: sidebars hide on mobile (< 768px) for touch-friendly portrait mode
- Vision Pro Safari tablet landscape: full 3-column layout with optimal use of horizontal space

### Removed
- Bottom auto-hide navigation bar (control bar)
- Right-side slide-in settings panel (moved to left sidebar)

## [0.3.1] - 2026-04-03

### Fixed
- Video freezing on static screens: encoder now sets `KEY_REPEAT_PREVIOUS_FRAME_AFTER`
  so MediaCodec keeps producing frames even when VirtualDisplay sends no new content
- Periodic IDR (keyframe) requests every 2 seconds ensure newly connected clients
  always receive a decodable frame quickly
- `appendBuffer` throw killing the MSE pipeline: a failed append no longer blocks
  the segment queue — a deferred retry via `setTimeout` keeps the pipeline alive
- `SourceBuffer` error event now resets the pipeline state (`_sourceBuffer = null`,
  queues cleared) so the next init segment from the server can re-establish playback

## [0.3.0] - 2026-04-03

### Fixed
- Media frames arriving before SourceBuffer is created were silently dropped;
  up to 120 frames are now held in `_pendingSegments` and flushed the moment
  the SourceBuffer becomes available, so the first I-frame is never lost
- `_trimBuffer` was anchored to `video.currentTime` which is 0 before playback
  starts, so trimming never fired → buffer grew unboundedly → `QuotaExceededError`
  killed the SourceBuffer; trimming is now anchored to the live-edge (`bufEnd`)
- Added live-edge catch-up: if playback falls more than 1.5 s behind the live
  edge (e.g. after a buffer stall), `currentTime` is snapped to `bufEnd - 0.1 s`

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
