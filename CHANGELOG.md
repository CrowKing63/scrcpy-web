# Changelog

All notable changes to SCRCPY-Web are documented here.
This project adheres to [Keep a Changelog](https://keepachangelog.com/en/1.1.0/) format.

## [Unreleased]

## [2.1.14] - 2026-04-08

### Fixed
- **MediaProjection dialog troubleshooting:** Added detailed window enumeration and bounds logging to `TouchInjectionService` to help debug auto-tap failures on devices with non-standard window layouts.
- **Samsung One UI share-mode selection:** Refined the multi-step click logic for Samsung's Android 16+ share-mode dropdown to ensure more reliable selection of "Full screen" mode.

## [2.1.13] - 2026-04-07

### Fixed
- **Samsung One UI Android 16+ MediaProjection dropdown:** The auto-tap service now handles the new collapsed share-mode dropdown by clicking the default "앱 하나 공유" (Single app) label to reveal the "전체 화면 공유" (Full screen) option before selection. This ensures consistent one-tap mirroring on devices with the updated system UI.

## [2.1.12] - 2026-04-07

### Added
- **Samsung One UI multi-step auto-tap:** The accessibility service now automatically selects the "전체 화면 공유" (Full screen) share mode in Samsung's multi-step MediaProjection consent dialog, then clicks the confirmation button. This enables a truly one-tap experience for users on Galaxy devices.

## [2.1.11] - 2026-04-07

### Fixed
- **Auto-tap clicking dropdown instead of positive button:** Replaced tree-traversal based sibling search with **screen-coordinate alignment**. Now uses `getBoundsInScreen()` to find a clickable non-cancel node at the same Y position as the cancel button, reliably skipping dropdowns and other controls above the button bar regardless of DOM nesting depth.
- **Fallback text matching hitting dropdown labels:** `tryClickButtonByExactText()` now filters `findAccessibilityNodeInfosByText()` results to **exact text matches only**, preventing substring hits like "화면 공유" matching "전체 화면 공유" in the share-mode dropdown.

## [2.1.10] - 2026-04-07

### Fixed
- **Auto-tap not clicking positive button on Samsung One UI:** Cancel and positive buttons are individually wrapped in container views, so the direct-sibling search missed the positive button. Now walks up to 2 ancestor levels and searches descendants (depth-limited to 2) via `findNonCancelClickable()`, correctly finding wrapped buttons without reaching unrelated elements like dropdowns.
- **Fallback text matching clicking dropdown instead of button:** Removed ambiguous entries ("화면 공유", "공유", "Share") from `KNOWN_POSITIVE_TEXTS` that substring-matched dropdown labels (e.g. "전체 화면 공유"), causing the dropdown to reopen instead of clicking the action button.

## [2.1.9] - 2026-04-07

### Fixed
- **Auto-tap not finding system dialog on separate window:** On some OEMs (Samsung One UI), the MediaProjection consent dialog appears in a separate interactive window rather than the app's active window. Added `flagRetrieveInteractiveWindows` to accessibility config and `collectWindowRoots()` method to scan all interactive system windows. Now searches for the button across all accessible windows instead of only `rootInActiveWindow`.
- **Missing debug logging for auto-tap troubleshooting:** Added debug logs at `enableAutoTap()` and in `onAccessibilityEvent()` flow so auto-tap behavior can be traced in logcat (`adb logcat -s TouchInjection`) for easier debugging on different OEM devices.

## [2.1.8] - 2026-04-07

### Fixed
- **Resilient auto-tap for multi-step dialogs:** Replaced text-matching based button detection with a **cancel-button-anchor approach**. Now finds the cancel/deny button (highly consistent across OEMs) and clicks its sibling — the positive action button. Eliminates need to enumerate all possible positive button texts ("Allow", "화면 공유", "Screen share", etc.) which vary by OEM, locale, and dialog step. Works correctly for all button layouts as long as cancel and positive buttons are siblings.
- **Text fallback:** Kept text-based matching as fallback for unusual dialog layouts where cancel and positive buttons are not siblings.

## [2.1.7] - 2026-04-07

### Fixed
- **Multi-step MediaProjection consent dialogs:** Auto-tap now handles OEM variants like Samsung One UI that show a 2-step dialog (select share mode → "Next" → "Allow"). Added debounce guard to prevent redundant processing of rapid `TYPE_WINDOW_CONTENT_CHANGED` events. Supports additional button text variants: "Next", "다음", "Start", "시작", "Entire screen", "전체 화면".
- **OEM compatibility:** Removed package name filter restriction (`com.android.systemui`), allowing the service to detect system dialogs across OEM variants (Samsung, Pixel, AOSP).

## [2.1.6] - 2026-04-07

### Added
- **Web dashboard:** Browser now shows a dashboard interface with device info (model, Android version, battery, IP) and "Start Mirroring" button instead of auto-connecting to stream. Device info refreshes every 5 seconds while on the dashboard.
- **On-demand mirroring:** Users can click "Start Mirroring" on the dashboard to request capture, which launches the MediaProjection permission flow on the phone. Enables graceful handling of screen lock and permission token expiry.
- **Auto-tap MediaProjection "Allow" button:** When the browser requests mirroring, the AccessibilityService automatically detects and clicks the "Allow" button on the system permission dialog (supports English and Korean locales across Android 10-14+), eliminating the need for manual interaction on the phone.
- **Transparent Activity for permission flow:** New `ProjectionRequestActivity` launched from background to show the MediaProjection consent dialog with automatic screen wake-up (`setTurnScreenOn(true)`), enabling seamless permission requests even when the phone is locked.
- **AccessibilityService event monitoring:** Service now listens for `TYPE_WINDOW_STATE_CHANGED` events to detect the system permission dialog and auto-tap the button with a 10-second safety timeout.
- **Capture failure notifications:** Browser now receives `capture_failed` messages from the server, allowing proper error handling and retry UI on the dashboard.

### Changed
- **WebSocket protocol extended:** New message type `request_capture` for on-demand mirroring requests, replacing auto-connect flow. Device info API now includes `isCapturing` and `isAccessibilityEnabled` fields for dashboard status display.
- **Accessibility service configuration:** Updated to monitor `typeWindowStateChanged` events for permission dialog detection while maintaining gesture-only mode for regular input.

## [2.1.5] - 2026-04-06

### Fixed
- **Restart Capture button silent failure:** When the MediaProjection token is spent (single-use per Android spec), `restart_capture` now responds with `permission_needed` instead of a silent no-op, so the browser immediately shows the "Permission Required" screen with correct instructions to tap Allow in the app.
- **Spurious auto-restart after capture stop:** Keyframe timeout timer is now cancelled when `capture_stopped` is received, preventing an automatic `restart_capture` attempt 10 seconds later when the capture pipeline is already known to be stopped.

## [2.1.4] - 2026-04-06

### Fixed
- **Black screen after long idle:** When no clients were connected for an extended period, the Android virtual display stopped producing frames for a static screen, causing `requestKeyframe()` to have no effect on reconnect. Added a 10-second keyframe timeout in the browser client: if no IDR frame arrives after receiving the init segment, the client automatically sends `{"type":"restart_capture"}` to force a fresh encoder pipeline.
- **Stalled encoder not restarted:** `onRestartCapture` now restarts the capture pipeline even when `isCapturing` is `true`, recovering from stalled encoders without requiring the user to manually stop and restart screen sharing. `startCapture()` already calls `stopCapture()` internally so the transition is clean.

## [2.1.3] - 2026-04-06

### Fixed
- **Regression: first connection broken after v2.1.2:** Init segment was gated behind `isCapturing`, preventing SourceBuffer creation on first load. Now always sends the init segment and additionally sends `capture_stopped` when capture is not active, so the UI can show the correct prompt.
- **`video.play()` AbortError on Safari:** Moved `play()` call from `_addSourceBuffer` (where `readyState` is `HAVE_METADATA`) to `_trimBuffer` (fired after a real media segment is appended), ensuring `readyState >= HAVE_FUTURE_DATA` before playback starts.

## [2.1.2] - 2026-04-06

### Added
- **Capture state tracking:** `StreamSession` now exposes `isCapturing` flag so new clients that connect while capture is stopped immediately receive a `capture_stopped` notification instead of waiting on a black screen.

## [2.1.1] - 2026-04-06

### Fixed
- **Black screen on some devices:** Hardware encoders that do not set `BUFFER_FLAG_KEY_FRAME` on IDR frames caused the browser's keyframe gate to never open. Added `containsIdrNalu()` fallback that inspects raw Annex B bytes for NALU type 5, correctly classifying keyframes regardless of codec flag reliability.

## [2.0.5] - 2026-04-05

### Fixed
- **Reconnection with black screen:** Removed stale keyframe caching that was sent to new clients alongside live P-frames, causing H264 decoder to fail when P-frames reference intermediate frames the client never received. Fresh IDR from `requestKeyframe()` (~33 ms) eliminates reference gap cleanly without performance penalty.

## [2.0.4] - 2026-04-05

### Fixed
- **Reconnection after idle:** New clients now receive fMP4 segments with sequence starting at 1 (not mid-stream), fixing MSE rejection on Safari after period of no connections
- **Screen lock handling:** `onStopped` callback now properly cleans up encoder/muxer pipeline, preventing zombie state on subsequent restart
- **Wake lock expiry:** Removed 4-hour timeout on `PARTIAL_WAKE_LOCK`, now released only by `stopCapture()` — prevents CPU sleep during long mirroring sessions
- **Encoder crash recovery:** Added try-catch in MediaCodec callback to prevent `onEncodedFrame` exception from silently killing frame production

### Added
- **Remote capture restart:** Browser can now restart screen sharing using saved MediaProjection token after screen lock, without user interaction on phone (if token still valid)
- **WebSocket restart command:** New `{"type":"restart_capture"}` message type for programmatic capture restart from web clients or keypad buttons

## [2.0.3] - 2026-04-05

### Fixed
- **Clicking unresponsive:** Restored single-click functionality by appending a 1-pixel `lineTo` offset in `TouchInjectionService.injectTap`; zero-length `moveTo` strokes were dropping in newer Android versions
- **Duplicate event listeners:** Added a guard flag (`_inputHandlersReady`) to `main.js`'s WebSocket connection handler, preventing listener accumulation (and double-clicks) during reconnections

## [2.0.1] - 2026-04-05

### Fixed
- **Kotlin compilation error:** Replaced unresolved `AccessibilityNodeInfo.ACTION_SELECT_ALL` with
  its integer constant value `0x00100000` to fix release build compilation

## [2.0.0] - 2026-04-05

### Added
- **Numeric keypad UI:** New toggle button (123) in the keypad header that reveals a 3×4 numeric
  input panel for PIN entry and text field interactions on-device
- **Long-press context menus:** Right-click (contextmenu event) now dispatches a 600 ms stationary
  press to trigger Android context menus

### Fixed
- **Black screen on initial connection:** Implement keyframe caching server-side (`lastKeyframe` in
  StreamSession); newly connecting clients receive the cached IDR frame immediately after init segment,
  enabling video display without waiting for the next periodic keyframe request
- **Screen lock stopping capture:** Added `PARTIAL_WAKE_LOCK` acquisition (4-hour timeout) in
  MirrorService and `FLAG_KEEP_SCREEN_ON` flag in MainActivity when capturing, preventing Android
  from stopping MediaProjection when the device locks
- **Drag/scroll unresponsive:** Implemented tap vs. swipe discrimination server-side: track pointer
  down position; if displacement ≥ 2% screen fraction, dispatch swipe; otherwise dispatch single tap
- **Limited keyboard shortcuts:** Expanded KEY_MAP with Delete, Home, End, PageUp/Down, F1–F10;
  added CTRL_MAP for Ctrl/Meta+C/V/X/Z/A shortcuts (copy, paste, cut, undo, selectAll)
- **No text input support:** Added `injectKey(keyCode, metaState)` for character-by-character input
  via Android keycode; implemented character-to-keycode mapping with shift modifier support;
  enabled `canRetrieveWindowContent` in accessibility config for text field node interaction

## [1.3.1] - 2026-04-04

### Fixed
- **Unnecessary P-frame filtering:** Removed JS-level keyframe gating (`_waitingForKeyframe` flag
  and timeout). The browser's H264 decoder naturally discards P-frames that arrive before the
  first IDR, so the application-level gate was redundant and sometimes dropped valid frames.
- **Video stall without recovery:** Added `waiting` event handler (`_waitingHandler`) that
  automatically seeks to the latest buffered position (0.1s before live edge) when playback
  stalls. This resumes streaming immediately without requiring user intervention.
- **Pause listener accumulation:** Fixed `_initMSEPlayer()` to call `removeEventListener` before
  `addEventListener` for `pause` and `waiting` events, preventing duplicate handlers from
  accumulating across MSE reinitializations.
- **Blob URL memory leaks:** Now explicitly revoke the previous blob URL with `URL.revokeObjectURL()`
  before creating a new MediaSource attachment, preventing orphaned blob URLs from consuming memory.

## [1.3.0] - 2026-04-04

### Fixed
- **Reconnection stability regression from v1.2.0:** The cached keyframe packet (`lastKeyframePacket`)
  contained stale fMP4 data with old sequence numbers and timestamps. When sent to newly reconnecting
  clients after the init segment, the timestamp discontinuity confused the MSE decoder, especially
  on Safari, causing black screens or stuttering. The cached keyframe mechanism has been removed;
  the fresh IDR from `requestKeyframe()` arrives within ~50 ms and provides correct sequence numbers.
- **MSE pipeline reset side effects:** Fix 3 (v1.2.0) was too aggressive — it called `_teardownMSE()`
  + `_initMSEPlayer()` on every new init segment, accumulating `pause` event listeners on the video
  element and leaking blob URLs. Reverted to simpler approach: append new init segments to the
  existing SourceBuffer. SPS/PPS deduplication in `VideoEncoder` ensures init segments only occur
  when codec parameters actually change (e.g., rotation).
- **Live-edge tracking stuttering:** Removed `playbackRate = 1.1` acceleration logic (caused stuttering
  in some browsers). Now uses simple threshold-based seeking: buffer kept at 3s behind live edge;
  hard seek to 0.3s behind edge only when playback falls >2s behind. Eliminates visible playback
  jumps and rate changes.

## [1.2.0] - 2026-04-04

### Fixed
- **Streaming freeze regression from v1.1.0:** Keyframe gating was too aggressive — some Android
  encoders re-emit `BUFFER_FLAG_CODEC_CONFIG` after a sync frame request, causing repeated init
  segment broadcasts that kept resetting the keyframe gate. Now `VideoEncoder` deduplicates SPS/PPS
  with `contentEquals()` so `onSpsAvailable` only fires when parameters actually change.
- **Keyframe gate safety timeout:** Added a 500 ms fallback timer that automatically lifts the
  keyframe gate if no type-0x03 frame arrives. Ensures streaming works even on devices whose
  encoders do not set `BUFFER_FLAG_KEY_FRAME` on IDR frames.

## [1.1.0] - 2026-04-04

### Fixed
- **Black screen on reconnection:** When Safari is closed and reopened, the WebSocket now reconnects
  with immediate video display instead of a blank black screen. Server now caches the most recent
  keyframe and sends it to every new client immediately, and the encoder requests an IDR frame
  on-demand when a client connects (no longer relies solely on 2-second periodic IDR requests).
- **Keyframe gating on client:** Media segments arriving before the first keyframe are now discarded
  so the MSE decoder never receives non-decodable P-frames after reconnection or pipeline restart.
  New message type `0x03` distinguishes keyframes (`0x02` is P-frame) for client-side filtering.
- **MSE pipeline reset on new init segment:** When the encoder restarts (e.g., rotation), the client
  now tears down and rebuilds the entire MediaSource and SourceBuffer instead of appending the new
  init segment to the existing SourceBuffer. This prevents Safari decoder state confusion and ensures
  clean playback across pipeline restarts.
- **Stuttering and frame jumps:** Live-edge tracking (`_trimBuffer`) now uses graduated catch-up:
  playback-rate acceleration (1.1x) for moderate lag (1–2.5s behind) instead of hard seeks,
  and only hard-seeks when severely behind (>2.5s). This eliminates visible jumps during
  normal streaming. Buffer trim window increased from 2s to 4s for better stability.

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
