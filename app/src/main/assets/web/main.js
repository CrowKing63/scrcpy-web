'use strict';

/**
 * SCRCPY-Web browser client.
 *
 * Manages WebSocket connection, MSE video playback, touch/pointer input
 * forwarding, navigation button handling, and settings persistence.
 */
class ScrcpyWeb {
    constructor() {
        this._ws = null;
        this._mediaSource = null;
        this._sourceBuffer = null;
        this._segmentQueue = [];
        this._pendingInitData = null;
        this._pendingSegments = [];
        this._frameCount = 0;
        this._fpsInterval = null;
        this._liveEdgeTimer = null;
        this._reconnectDelay = 1000;
        this._hideBarTimer = null;
        this._pointers = new Map();

        this._initIcons();
        this._connect();
        this._initControlBar();
        this._initKeyboard();
        this._initSettings();
        this._fetchDeviceInfo();
    }

    // ── WebSocket connection ────────────────────────────────────────────

    /**
     * Opens a WebSocket connection to the server's /stream endpoint.
     * Handles binary fMP4 frames and JSON status messages.
     * Auto-reconnects with exponential backoff on disconnect.
     */
    _connect() {
        this._updateConnectionUI('connecting');
        const url = `ws://${location.host}/stream`;
        const ws = new WebSocket(url);
        ws.binaryType = 'arraybuffer';
        this._ws = ws;

        ws.onopen = () => {
            this._reconnectDelay = 1000;
            this._initMSEPlayer();
        };

        ws.onmessage = (event) => {
            if (event.data instanceof ArrayBuffer) {
                this._handleBinaryMessage(event.data);
            } else {
                try {
                    const msg = JSON.parse(event.data);
                    if (msg.type === 'permission_needed') {
                        this._updateConnectionUI('permission_needed');
                    }
                } catch (_) { /* ignore malformed text */ }
            }
        };

        ws.onerror = () => { /* onclose will fire next */ };

        ws.onclose = () => {
            this._teardownMSE();
            document.getElementById('connection-status').className = 'status-dot disconnected';
            this._scheduleReconnect();
        };
    }

    /**
     * Schedules a reconnect attempt with exponential backoff (max 5 seconds).
     */
    _scheduleReconnect() {
        this._updateConnectionUI('disconnected');
        setTimeout(() => this._connect(), this._reconnectDelay);
        this._reconnectDelay = Math.min(this._reconnectDelay * 1.5, 5000);
    }

    // ── MSE player ─────────────────────────────────────────────────────

    /**
     * Initialises the Media Source Extensions player.
     * Creates a MediaSource, attaches it to the video element, and waits
     * for the init segment from the server to add the SourceBuffer.
     */
    _initMSEPlayer() {
        if (!('MediaSource' in window)) {
            console.error('MediaSource API not supported in this browser.');
            return;
        }
        this._pendingInitData = null;
        this._mediaSource = new MediaSource();
        const video = document.getElementById('video-player');
        video.src = URL.createObjectURL(this._mediaSource);

        this._mediaSource.addEventListener('sourceopen', () => {
            // If an init segment arrived before sourceopen fired, apply it now.
            if (this._pendingInitData) {
                const data = this._pendingInitData;
                this._pendingInitData = null;
                this._addSourceBuffer(data);
            }
        });

        // Resume playback if the live stream temporarily stalls.
        video.addEventListener('pause', () => {
            if (this._sourceBuffer && this._mediaSource?.readyState === 'open') {
                video.play().catch(() => {});
            }
        });

        this._startFpsCounter();
    }

    /**
     * Handles a binary WebSocket message.
     * Byte 0 is the message type; bytes 1–4 are the payload length (uint32 BE).
     *
     * @param {ArrayBuffer} buffer Raw message bytes.
     */
    _handleBinaryMessage(buffer) {
        const view = new DataView(buffer);
        const type = view.getUint8(0);
        const payload = buffer.slice(5);

        if (type === 0x01) {
            // Init segment — add SourceBuffer and append
            this._pendingSegments = [];
            this._addSourceBuffer(payload);
        } else if (type === 0x02) {
            this._frameCount++;
            if (!this._sourceBuffer) {
                // SourceBuffer not ready yet — hold up to 120 frames
                this._pendingSegments.push(payload);
                if (this._pendingSegments.length > 120) this._pendingSegments.shift();
                return;
            }
            // Flush any frames buffered before SourceBuffer was created
            if (this._pendingSegments.length > 0) {
                for (const seg of this._pendingSegments) this._segmentQueue.push(seg);
                this._pendingSegments = [];
            }
            this._appendBuffer(payload);
        }
    }

    /**
     * Adds a SourceBuffer using the H264 Baseline MIME type and appends
     * the fMP4 init segment.
     *
     * @param {ArrayBuffer} initData fMP4 init segment bytes.
     */
    _addSourceBuffer(initData) {
        if (!this._mediaSource || this._mediaSource.readyState !== 'open') {
            // sourceopen has not fired yet — buffer the init segment and apply it then.
            this._pendingInitData = initData;
            return;
        }
        if (this._sourceBuffer) {
            // Pipeline restarted — append new init segment to existing SourceBuffer.
            this._appendBuffer(initData);
            return;
        }

        const mimeType = 'video/mp4; codecs="avc1.42E01E"';
        if (!MediaSource.isTypeSupported(mimeType)) {
            console.error('MIME type not supported:', mimeType);
            return;
        }

        this._sourceBuffer = this._mediaSource.addSourceBuffer(mimeType);
        this._sourceBuffer.mode = 'sequence';

        this._sourceBuffer.addEventListener('updateend', () => {
            this._flushQueue();
            this._trimBuffer();
        });

        this._sourceBuffer.addEventListener('error', (e) => {
            console.error('SourceBuffer error:', e);
        });

        this._appendBuffer(initData);
        this._updateConnectionUI('connected');
        document.getElementById('connection-status').className = 'status-dot connected';

        const video = document.getElementById('video-player');
        video.play().catch(() => { /* autoplay policy — muted so should succeed */ });
    }

    /**
     * Appends an fMP4 segment to the SourceBuffer, or queues it if the
     * SourceBuffer is currently updating.
     *
     * @param {ArrayBuffer} data Segment bytes.
     */
    _appendBuffer(data) {
        if (!this._sourceBuffer) return;
        if (this._sourceBuffer.updating) {
            this._segmentQueue.push(data);
        } else {
            try {
                this._sourceBuffer.appendBuffer(data);
            } catch (e) {
                console.warn('appendBuffer error:', e);
                this._segmentQueue.push(data);
            }
        }
    }

    /** Drains the segment queue when the SourceBuffer is ready. */
    _flushQueue() {
        if (!this._sourceBuffer || this._sourceBuffer.updating) return;
        if (this._segmentQueue.length === 0) return;
        const next = this._segmentQueue.shift();
        try {
            this._sourceBuffer.appendBuffer(next);
        } catch (e) {
            console.warn('flushQueue error:', e);
        }
    }

    /**
     * Removes buffered video data more than 2 seconds behind the current
     * playback position to prevent unbounded memory growth.
     */
    _trimBuffer() {
        const video = document.getElementById('video-player');
        if (!this._sourceBuffer || this._sourceBuffer.updating) return;
        const buffered = this._sourceBuffer.buffered;
        if (buffered.length === 0) return;
        const bufEnd = buffered.end(buffered.length - 1);
        // Trim everything more than 2 s behind the live edge (not currentTime,
        // which can be 0 before first play and would never trigger trimming).
        const trimTo = bufEnd - 2;
        if (trimTo > buffered.start(0)) {
            try {
                this._sourceBuffer.remove(0, trimTo);
            } catch (_) { /* ignore */ }
        }
        // If playback has fallen more than 1 s behind the live edge, jump ahead.
        if (bufEnd - video.currentTime > 1.5) {
            video.currentTime = bufEnd - 0.1;
        }
    }

    /** Releases MediaSource and clears SourceBuffer references. */
    _teardownMSE() {
        this._sourceBuffer = null;
        this._segmentQueue = [];
        this._pendingInitData = null;
        this._pendingSegments = [];
        if (this._mediaSource) {
            try { this._mediaSource.endOfStream(); } catch (_) { /* ignore */ }
            this._mediaSource = null;
        }
        clearInterval(this._fpsInterval);
    }

    // ── FPS counter ─────────────────────────────────────────────────────

    /** Starts a 1-second interval that updates the FPS counter in the status bar. */
    _startFpsCounter() {
        this._frameCount = 0;
        clearInterval(this._fpsInterval);
        this._fpsInterval = setInterval(() => {
            document.getElementById('fps-counter').textContent = `${this._frameCount} fps`;
            this._frameCount = 0;
        }, 1000);
    }

    // ── Input handling ──────────────────────────────────────────────────

    /**
     * Attaches pointer event listeners to the video element.
     * Converts client coordinates to normalised (0–1) values before sending.
     */
    _initInputHandlers() {
        const video = document.getElementById('video-player');
        let lastMoveTime = 0;
        const THROTTLE_MS = 16; // ~60 Hz

        video.addEventListener('pointerdown', (e) => {
            e.preventDefault();
            video.setPointerCapture(e.pointerId);
            const { x, y } = this._normalise(e, video);
            this._pointers.set(e.pointerId, { x, y });
            this._send({ type: 'touch', action: 'down', x, y, id: e.pointerId });
        });

        video.addEventListener('pointermove', (e) => {
            e.preventDefault();
            const now = Date.now();
            if (now - lastMoveTime < THROTTLE_MS) return;
            lastMoveTime = now;
            const { x, y } = this._normalise(e, video);
            this._send({ type: 'touch', action: 'move', x, y, id: e.pointerId });
        });

        video.addEventListener('pointerup', (e) => {
            e.preventDefault();
            const { x, y } = this._normalise(e, video);
            this._pointers.delete(e.pointerId);
            this._send({ type: 'touch', action: 'up', x, y, id: e.pointerId });
        });

        video.addEventListener('pointercancel', (e) => {
            this._pointers.delete(e.pointerId);
        });

        video.addEventListener('wheel', (e) => {
            e.preventDefault();
            const { x, y } = this._normalise(e, video);
            this._send({ type: 'scroll', x, y, dx: e.deltaX, dy: e.deltaY });
        }, { passive: false });
    }

    /**
     * Normalises a pointer/mouse event's position relative to the video element.
     *
     * @param {PointerEvent|WheelEvent} e  The input event.
     * @param {HTMLVideoElement}        el The target element.
     * @returns {{ x: number, y: number }} Normalised coordinates in [0, 1].
     */
    _normalise(e, el) {
        const rect = el.getBoundingClientRect();
        return {
            x: Math.max(0, Math.min(1, (e.clientX - rect.left) / rect.width)),
            y: Math.max(0, Math.min(1, (e.clientY - rect.top)  / rect.height)),
        };
    }

    // ── Control bar ──────────────────────────────────────────────────────

    /** Wires up the navigation button click handlers and auto-hide behaviour. */
    _initControlBar() {
        const bar = document.getElementById('control-bar');
        const show = () => {
            bar.classList.remove('hidden');
            clearTimeout(this._hideBarTimer);
            this._hideBarTimer = setTimeout(() => bar.classList.add('hidden'), 3000);
        };

        document.addEventListener('pointermove', show);
        document.addEventListener('pointerdown', show);

        const nav = (action) => () => this._send({ type: 'nav', action });
        document.getElementById('btn-back').addEventListener('click',       nav('back'));
        document.getElementById('btn-home').addEventListener('click',       nav('home'));
        document.getElementById('btn-recents').addEventListener('click',    nav('recents'));
        document.getElementById('btn-vol-up').addEventListener('click',     nav('volumeUp'));
        document.getElementById('btn-vol-down').addEventListener('click',   nav('volumeDown'));
        document.getElementById('btn-power').addEventListener('click',      nav('power'));
        document.getElementById('btn-rotate').addEventListener('click',     nav('rotate'));
        document.getElementById('btn-fullscreen').addEventListener('click', () => {
            document.documentElement.requestFullscreen?.();
        });
        document.getElementById('btn-settings').addEventListener('click', () => {
            document.getElementById('settings-panel').classList.replace('hidden', 'visible');
        });
    }

    // ── Keyboard ─────────────────────────────────────────────────────────

    /** Captures keydown/keyup events and maps them to Android keycodes. */
    _initKeyboard() {
        const KEY_MAP = {
            'Backspace': 67, 'Enter': 66, 'Escape': 111,
            'ArrowLeft': 21, 'ArrowRight': 22, 'ArrowUp': 19, 'ArrowDown': 20,
            'Tab': 61, ' ': 62,
        };
        const handler = (action) => (e) => {
            const keyCode = KEY_MAP[e.key];
            if (keyCode) {
                e.preventDefault();
                this._send({ type: 'key', keyCode, action });
            }
        };
        document.addEventListener('keydown', handler('down'));
        document.addEventListener('keyup',   handler('up'));
    }

    // ── Settings ─────────────────────────────────────────────────────────

    /** Loads settings from localStorage, wires slider changes, and sets up close button. */
    _initSettings() {
        const stored = {
            resolution: parseInt(localStorage.getItem('resolution') ?? '75'),
            bitrate:    parseInt(localStorage.getItem('bitrate')    ?? '4'),
            fps:        parseInt(localStorage.getItem('fps')        ?? '30'),
        };

        const rRange = document.getElementById('range-resolution');
        const bRange = document.getElementById('range-bitrate');
        const fRange = document.getElementById('range-fps');
        rRange.value = stored.resolution;
        bRange.value = stored.bitrate;
        fRange.value = stored.fps;

        this._updateSettingLabels(stored.resolution, stored.bitrate, stored.fps);

        const onChange = () => {
            const resolution = parseInt(rRange.value);
            const bitrate    = parseInt(bRange.value);
            const fps        = parseInt(fRange.value);
            localStorage.setItem('resolution', resolution);
            localStorage.setItem('bitrate', bitrate);
            localStorage.setItem('fps', fps);
            this._updateSettingLabels(resolution, bitrate, fps);
            this._send({ type: 'config', scale: resolution / 100, bitrate: bitrate * 1_000_000, maxFps: fps });
        };

        rRange.addEventListener('input', onChange);
        bRange.addEventListener('input', onChange);
        fRange.addEventListener('input', onChange);

        document.getElementById('btn-close-settings').addEventListener('click', () => {
            document.getElementById('settings-panel').classList.replace('visible', 'hidden');
        });
    }

    /**
     * Updates the displayed setting value labels next to each slider.
     *
     * @param {number} res     Resolution percentage.
     * @param {number} bitrate Bitrate in Mbps.
     * @param {number} fps     Frames per second.
     */
    _updateSettingLabels(res, bitrate, fps) {
        document.getElementById('resolution-value').textContent = `${res}%`;
        document.getElementById('bitrate-value').textContent    = `${bitrate} Mbps`;
        document.getElementById('fps-value').textContent        = `${fps}`;
    }

    // ── Connection UI ────────────────────────────────────────────────────

    /**
     * Transitions between the connection, mirror, and permission screens.
     *
     * @param {'connecting'|'connected'|'permission_needed'|'disconnected'} state
     */
    _updateConnectionUI(state) {
        const connectionScreen = document.getElementById('connection-screen');
        const mirrorScreen     = document.getElementById('mirror-screen');
        const permissionScreen = document.getElementById('permission-screen');
        const statusText       = document.getElementById('connect-status-text');

        const hide = (...els) => els.forEach(el => { el.classList.add('hidden'); el.classList.remove('active'); });
        const show = (el)     => { el.classList.remove('hidden'); el.classList.add('active'); };

        switch (state) {
            case 'connecting':
                statusText.textContent = 'Connecting…';
                show(connectionScreen); hide(mirrorScreen, permissionScreen);
                break;
            case 'connected':
                hide(connectionScreen, permissionScreen);
                show(mirrorScreen);
                this._initInputHandlers();
                break;
            case 'permission_needed':
                hide(connectionScreen, mirrorScreen);
                show(permissionScreen);
                break;
            case 'disconnected':
                statusText.textContent = 'Reconnecting…';
                hide(mirrorScreen, permissionScreen);
                show(connectionScreen);
                break;
        }
    }

    // ── Device info ──────────────────────────────────────────────────────

    /** Fetches and displays device metadata from /api/device-info. */
    async _fetchDeviceInfo() {
        try {
            const res = await fetch('/api/device-info');
            if (!res.ok) return;
            const info = await res.json();
            const el = document.getElementById('device-info');
            el.textContent = `${info.model} · Android ${info.androidVersion}`;
            el.classList.remove('hidden');
        } catch (_) { /* server may not be ready yet */ }
    }

    /** Sends a POST to /api/start-capture to trigger MediaProjection. */
    async _requestCapture() {
        try {
            await fetch('/api/start-capture', { method: 'POST' });
        } catch (_) { /* ignore */ }
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    /**
     * Serialises an object to JSON and sends it over the WebSocket.
     *
     * @param {object} obj Message payload.
     */
    _send(obj) {
        if (this._ws && this._ws.readyState === WebSocket.OPEN) {
            this._ws.send(JSON.stringify(obj));
        }
    }

    /** Injects SVG icons from ICONS into the control bar buttons. */
    _initIcons() {
        const map = {
            'btn-back': 'back', 'btn-home': 'home', 'btn-recents': 'recents',
            'btn-vol-up': 'volumeUp', 'btn-vol-down': 'volumeDown',
            'btn-power': 'power', 'btn-rotate': 'rotate',
            'btn-fullscreen': 'fullscreen', 'btn-settings': 'settings',
        };
        for (const [id, icon] of Object.entries(map)) {
            const el = document.getElementById(id);
            if (el && typeof ICONS !== 'undefined') el.innerHTML = ICONS[icon] ?? '';
        }
    }
}

// ── Capture request button ────────────────────────────────────────────────
document.addEventListener('DOMContentLoaded', () => {
    const app = new ScrcpyWeb();
    document.getElementById('btn-request-capture')?.addEventListener('click', () => {
        app._requestCapture();
    });
});
