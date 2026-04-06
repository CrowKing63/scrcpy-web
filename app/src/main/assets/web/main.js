'use strict';

// ── KeypadManager ─────────────────────────────────────────────────────────

/**
 * Default keypad buttons shown on first load.
 * @type {KeypadButtonConfig[]}
 */
const DEFAULT_BUTTONS = [
    { id: 'd1', label: 'Back',     type: 'nav',    action: 'back' },
    { id: 'd2', label: 'Home',     type: 'nav',    action: 'home' },
    { id: 'd3', label: 'Recents',  type: 'nav',    action: 'recents' },
    { id: 'd4', label: 'Vol +',    type: 'nav',    action: 'volumeUp' },
    { id: 'd5', label: 'Vol -',    type: 'nav',    action: 'volumeDown' },
    { id: 'd6', label: 'Power',    type: 'nav',    action: 'power' },
    { id: 'd7', label: 'Scroll ↑', type: 'scroll', dir: 'up',   delta: 120 },
    { id: 'd8', label: 'Scroll ↓', type: 'scroll', dir: 'down', delta: 120 },
];

/**
 * @typedef {Object} KeypadButtonConfig
 * @property {string}  id      - Unique identifier.
 * @property {string}  label   - Display text on the button.
 * @property {string}  type    - One of: 'nav' | 'scroll' | 'key' | 'text'.
 * @property {string}  [action]  - For type='nav': nav action name.
 * @property {string}  [dir]     - For type='scroll': 'up'|'down'|'left'|'right'.
 * @property {number}  [delta]   - For type='scroll': scroll delta value.
 * @property {number}  [keyCode] - For type='key': Android keycode integer.
 * @property {string}  [text]    - For type='text': string to type.
 */

/**
 * Manages the customisable keypad in the right sidebar.
 * Handles rendering, editing, adding, deleting buttons,
 * and dispatching WebSocket actions via the provided send callback.
 */
class KeypadManager {
    /**
     * @param {HTMLElement} gridEl  - The #keypad-grid container element.
     * @param {Function}    sendFn  - Callback to send a WebSocket message object.
     */
    constructor(gridEl, sendFn) {
        this._grid     = gridEl;
        this._send     = sendFn;
        this._editMode = false;
        this._editingId = null;
        this._longPressTimer = null;
        this._buttons  = this._load();
        this._initModal();
    }

    // ── Rendering ──────────────────────────────────────────────────────

    /** Renders all buttons from this._buttons into the grid. */
    render() {
        this._grid.innerHTML = '';
        for (const btn of this._buttons) {
            this._grid.appendChild(this._createButtonEl(btn));
        }
        if (this._editMode) this._grid.classList.add('keypad-edit-mode');
    }

    /**
     * Creates and returns a .key-btn element for the given config.
     *
     * @param {KeypadButtonConfig} btn
     * @returns {HTMLButtonElement}
     */
    _createButtonEl(btn) {
        const el = document.createElement('button');
        el.className = 'key-btn';
        el.dataset.id = btn.id;

        // Icon for scroll buttons
        let iconHtml = '';
        if (btn.type === 'scroll' && typeof ICONS !== 'undefined') {
            const iconKey = 'scroll' + btn.dir.charAt(0).toUpperCase() + btn.dir.slice(1);
            iconHtml = ICONS[iconKey] ?? '';
        }

        el.innerHTML = `
            ${iconHtml}
            <span class="key-btn-label">${this._escHtml(btn.label)}</span>
            <div class="key-edit-controls">
                <button class="key-edit-btn edit-btn" data-action="edit" data-id="${btn.id}" aria-label="Edit ${this._escHtml(btn.label)}">${typeof ICONS !== 'undefined' ? ICONS.edit : '✎'}</button>
                <button class="key-edit-btn del-btn"  data-action="del"  data-id="${btn.id}" aria-label="Delete ${this._escHtml(btn.label)}">${typeof ICONS !== 'undefined' ? ICONS.trash : '✕'}</button>
            </div>
        `;

        // Long-press to enter edit mode; short tap to execute
        el.addEventListener('pointerdown', (e) => {
            if (e.target.closest('.key-edit-btn')) return;
            this._longPressTimer = setTimeout(() => {
                this._longPressTimer = null;
                this.enterEditMode();
            }, 600);
        });

        el.addEventListener('pointerup', () => {
            if (this._longPressTimer !== null) {
                clearTimeout(this._longPressTimer);
                this._longPressTimer = null;
            }
        });

        el.addEventListener('pointercancel', () => {
            clearTimeout(this._longPressTimer);
            this._longPressTimer = null;
        });

        el.addEventListener('click', (e) => {
            const actionBtn = e.target.closest('.key-edit-btn');
            if (actionBtn) {
                e.stopPropagation();
                if (actionBtn.dataset.action === 'edit') this.openEditModal(actionBtn.dataset.id);
                if (actionBtn.dataset.action === 'del')  this.deleteButton(actionBtn.dataset.id);
                return;
            }
            if (!this._editMode) this.executeButton(btn);
        });

        return el;
    }

    // ── Edit mode ──────────────────────────────────────────────────────

    /** Enters edit mode: shows per-button edit/delete controls. */
    enterEditMode() {
        this._editMode = true;
        this._grid.classList.add('keypad-edit-mode');
        const editBtn = document.getElementById('btn-edit-keypad');
        const addBtn  = document.getElementById('btn-add-key');
        if (editBtn) editBtn.classList.add('active');
        if (addBtn)  addBtn.style.display = '';
    }

    /** Exits edit mode: hides per-button controls. */
    exitEditMode() {
        this._editMode = false;
        this._grid.classList.remove('keypad-edit-mode');
        const editBtn = document.getElementById('btn-edit-keypad');
        const addBtn  = document.getElementById('btn-add-key');
        if (editBtn) editBtn.classList.remove('active');
        if (addBtn)  addBtn.style.display = 'none';
    }

    // ── Execute ────────────────────────────────────────────────────────

    /**
     * Executes a button's action by sending the appropriate WebSocket message.
     *
     * @param {KeypadButtonConfig} btn - The button config to execute.
     */
    executeButton(btn) {
        switch (btn.type) {
            case 'nav':
                this._send({ type: 'nav', action: btn.action });
                break;
            case 'scroll': {
                const delta = btn.delta ?? 120;
                let dx = 0, dy = 0;
                if (btn.dir === 'up')    dy = -delta;
                if (btn.dir === 'down')  dy = +delta;
                if (btn.dir === 'left')  dx = -delta;
                if (btn.dir === 'right') dx = +delta;
                this._send({ type: 'scroll', x: 0.5, y: 0.5, dx, dy });
                break;
            }
            case 'key':
                this._send({ type: 'key', keyCode: btn.keyCode, action: 'down' });
                setTimeout(() => this._send({ type: 'key', keyCode: btn.keyCode, action: 'up' }), 50);
                break;
            case 'text':
                this._typeText(btn.text ?? '');
                break;
        }
    }

    /**
     * Sends a sequence of key events to type the given string.
     * Maps common ASCII characters to Android keycodes where possible.
     *
     * @param {string} text - The text string to type.
     */
    _typeText(text) {
        // Basic ASCII → Android keycode mapping for printable chars
        const ASCII_MAP = {
            ' ':62, 'a':29,'b':30,'c':31,'d':32,'e':33,'f':34,'g':35,'h':36,
            'i':37,'j':38,'k':39,'l':40,'m':41,'n':42,'o':43,'p':44,'q':45,
            'r':46,'s':47,'t':48,'u':49,'v':50,'w':51,'x':52,'y':53,'z':54,
            '0':7,'1':8,'2':9,'3':10,'4':11,'5':12,'6':13,'7':14,'8':15,'9':16,
            '\n':66,
        };
        let delay = 0;
        for (const ch of text.toLowerCase()) {
            const kc = ASCII_MAP[ch];
            if (kc) {
                setTimeout(() => {
                    this._send({ type: 'key', keyCode: kc, action: 'down' });
                    setTimeout(() => this._send({ type: 'key', keyCode: kc, action: 'up' }), 30);
                }, delay);
                delay += 80;
            }
        }
    }

    // ── CRUD ───────────────────────────────────────────────────────────

    /**
     * Deletes a button by id and re-renders the grid.
     *
     * @param {string} id - The button id to delete.
     */
    deleteButton(id) {
        this._buttons = this._buttons.filter(b => b.id !== id);
        this._save();
        this.render();
    }

    // ── Modal ──────────────────────────────────────────────────────────

    /** Opens the editor modal to add a new button. */
    openAddModal() {
        this._editingId = null;
        document.getElementById('modal-title').textContent = 'Add Button';
        document.getElementById('key-label-input').value = '';
        document.getElementById('key-action-type').value = 'nav';
        document.getElementById('nav-action-select').value = 'back';
        document.getElementById('scroll-dir-select').value = 'up';
        document.getElementById('scroll-delta-input').value = '120';
        document.getElementById('key-code-input').value = '';
        document.getElementById('key-text-input').value = '';
        document.getElementById('btn-modal-delete').style.display = 'none';
        this._showParams('nav');
        document.getElementById('key-editor-modal').classList.add('open');
        document.getElementById('key-label-input').focus();
    }

    /**
     * Opens the editor modal to edit an existing button.
     *
     * @param {string} id - The button id to edit.
     */
    openEditModal(id) {
        const btn = this._buttons.find(b => b.id === id);
        if (!btn) return;
        this._editingId = id;
        document.getElementById('modal-title').textContent = 'Edit Button';
        document.getElementById('key-label-input').value = btn.label;
        document.getElementById('key-action-type').value = btn.type;
        document.getElementById('btn-modal-delete').style.display = '';

        if (btn.type === 'nav')    document.getElementById('nav-action-select').value   = btn.action ?? 'back';
        if (btn.type === 'scroll') {
            document.getElementById('scroll-dir-select').value   = btn.dir ?? 'up';
            document.getElementById('scroll-delta-input').value  = btn.delta ?? 120;
        }
        if (btn.type === 'key')    document.getElementById('key-code-input').value  = btn.keyCode ?? '';
        if (btn.type === 'text')   document.getElementById('key-text-input').value  = btn.text ?? '';

        this._showParams(btn.type);
        document.getElementById('key-editor-modal').classList.add('open');
        document.getElementById('key-label-input').focus();
    }

    /** Closes the modal without saving. */
    _closeModal() {
        document.getElementById('key-editor-modal').classList.remove('open');
        this._editingId = null;
    }

    /**
     * Shows only the params panel matching the given type.
     *
     * @param {string} type - One of 'nav'|'scroll'|'key'|'text'.
     */
    _showParams(type) {
        for (const t of ['nav', 'scroll', 'key', 'text']) {
            const el = document.getElementById(`params-${t}`);
            if (el) el.classList.toggle('active', t === type);
        }
    }

    /** Reads the modal form and saves the button config. */
    _saveModal() {
        const label = document.getElementById('key-label-input').value.trim();
        if (!label) {
            document.getElementById('key-label-input').focus();
            return;
        }
        const type = document.getElementById('key-action-type').value;

        /** @type {KeypadButtonConfig} */
        const config = { label, type };

        if (type === 'nav') {
            config.action = document.getElementById('nav-action-select').value;
        } else if (type === 'scroll') {
            config.dir   = document.getElementById('scroll-dir-select').value;
            config.delta = parseInt(document.getElementById('scroll-delta-input').value) || 120;
        } else if (type === 'key') {
            const kc = parseInt(document.getElementById('key-code-input').value);
            if (!kc) { document.getElementById('key-code-input').focus(); return; }
            config.keyCode = kc;
        } else if (type === 'text') {
            config.text = document.getElementById('key-text-input').value;
        }

        if (this._editingId) {
            const idx = this._buttons.findIndex(b => b.id === this._editingId);
            if (idx !== -1) this._buttons[idx] = { ...this._buttons[idx], ...config };
        } else {
            config.id = crypto.randomUUID();
            this._buttons.push(config);
        }

        this._save();
        this.render();
        this._closeModal();
    }

    /** Initialises all modal event listeners. */
    _initModal() {
        document.getElementById('btn-modal-cancel')?.addEventListener('click',  () => this._closeModal());
        document.getElementById('btn-modal-cancel2')?.addEventListener('click', () => this._closeModal());
        document.getElementById('modal-backdrop')?.addEventListener('click',    () => this._closeModal());
        document.getElementById('btn-modal-save')?.addEventListener('click',    () => this._saveModal());
        document.getElementById('btn-modal-delete')?.addEventListener('click',  () => {
            if (this._editingId) this.deleteButton(this._editingId);
            this._closeModal();
        });

        document.getElementById('key-action-type')?.addEventListener('change', (e) => {
            this._showParams(e.target.value);
        });

        // Keypad header buttons
        document.getElementById('btn-edit-keypad')?.addEventListener('click', () => {
            if (this._editMode) this.exitEditMode();
            else this.enterEditMode();
        });
        document.getElementById('btn-add-key')?.addEventListener('click', () => this.openAddModal());
    }

    // ── Persistence ────────────────────────────────────────────────────

    /**
     * Loads buttons from localStorage, falling back to DEFAULT_BUTTONS.
     *
     * @returns {KeypadButtonConfig[]}
     */
    _load() {
        try {
            const raw = localStorage.getItem('scrcpy_keypad_buttons');
            if (raw) return JSON.parse(raw);
        } catch (_) { /* corrupted storage — fall through */ }
        return DEFAULT_BUTTONS.map(b => ({ ...b }));
    }

    /** Persists the current button list to localStorage. */
    _save() {
        try {
            localStorage.setItem('scrcpy_keypad_buttons', JSON.stringify(this._buttons));
        } catch (_) { /* storage full — ignore */ }
    }

    // ── Utilities ──────────────────────────────────────────────────────

    /**
     * Escapes a string for safe insertion into HTML.
     *
     * @param {string} str
     * @returns {string}
     */
    _escHtml(str) {
        return str.replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;').replace(/"/g,'&quot;');
    }
}

// ── ScrcpyWeb ─────────────────────────────────────────────────────────────

/**
 * SCRCPY-Web browser client.
 *
 * Manages WebSocket connection, MSE video playback, touch/pointer input
 * forwarding, sidebar collapse/expand, and the customisable keypad.
 */
class ScrcpyWeb {
    constructor() {
        this._ws = null;
        this._mediaSource = null;
        this._sourceBuffer = null;
        this._segmentQueue = [];
        this._pendingInitData = null;
        this._pendingSegments = [];
        this._waitingForKeyframe = false;
        this._frameCount = 0;
        this._fpsInterval = null;
        this._liveEdgeTimer = null;
        this._reconnectDelay = 1000;
        this._flushTimer = null;
        this._objectURL = null;
        this._droppedFrameLogged = false;
        this._pauseHandler = () => {
            if (this._sourceBuffer && this._mediaSource?.readyState === 'open') {
                document.getElementById('video-player').play().catch(() => {});
            }
        };
        this._waitingHandler = () => {
            // Video stalled waiting for data — seek to the latest available
            // frame so playback resumes from the live edge immediately.
            const vid = document.getElementById('video-player');
            if (!this._sourceBuffer) return;
            const buf = this._sourceBuffer.buffered;
            if (buf.length > 0) {
                vid.currentTime = buf.end(buf.length - 1) - 0.1;
            }
        };
        this._pointers = new Map();

        this._initIcons();
        this._initSidebars();
        this._connect();
        this._initKeyboard();
        this._initSettings();
        this._fetchDeviceInfo();

        const grid = document.getElementById('keypad-grid');
        this._keypadManager = new KeypadManager(grid, (msg) => this._send(msg));
        this._keypadManager.render();

        this._initNumpad();
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
                    } else if (msg.type === 'capture_stopped') {
                        this._setCaptureStopped(true);
                    } else if (msg.type === 'capture_started') {
                        this._setCaptureStopped(false);
                    }
                } catch (_) { /* ignore malformed text */ }
            }
        };

        ws.onerror = () => { /* onclose will fire next */ };

        ws.onclose = () => {
            this._teardownMSE();
            document.getElementById('connection-status').className = 'status-dot disconnected';
            document.getElementById('connection-status-label').textContent = 'Disconnected';
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

        // Revoke the previous blob URL to avoid memory leaks.
        if (this._objectURL) {
            URL.revokeObjectURL(this._objectURL);
        }
        this._objectURL = URL.createObjectURL(this._mediaSource);
        video.src = this._objectURL;

        this._mediaSource.addEventListener('sourceopen', () => {
            // If an init segment arrived before sourceopen fired, apply it now.
            if (this._pendingInitData) {
                const data = this._pendingInitData;
                this._pendingInitData = null;
                this._addSourceBuffer(data);
            }
        });

        // Resume playback if the live stream temporarily stalls.
        // Use stable references so listeners do not accumulate
        // across multiple _initMSEPlayer() calls.
        video.removeEventListener('pause', this._pauseHandler);
        video.addEventListener('pause', this._pauseHandler);
        video.removeEventListener('waiting', this._waitingHandler);
        video.addEventListener('waiting', this._waitingHandler);

        this._startFpsCounter();
    }

    /**
     * Handles a binary WebSocket message.
     * Byte 0 is the message type (0x01 init, 0x02 P-frame, 0x03 keyframe);
     * bytes 1–4 are the payload length (uint32 BE).
     *
     * @param {ArrayBuffer} buffer Raw message bytes.
     */
    _handleBinaryMessage(buffer) {
        const view = new DataView(buffer);
        const type = view.getUint8(0);
        const payload = buffer.slice(5);

        if (type === 0x01) {
            // Init segment — create or reuse SourceBuffer.
            // Raise the keyframe gate: Safari's MSE is stricter than Chrome/Edge
            // and will error (black screen) if P-frames arrive before the first IDR.
            // Discard all inter-frames until the first IDR clears the gate.
            console.log(`[Stream] init segment received (${payload.byteLength} bytes)`);
            this._pendingSegments = [];
            this._waitingForKeyframe = true;
            this._addSourceBuffer(payload);
        } else if (type === 0x02 || type === 0x03) {
            // 0x02 = P-frame, 0x03 = keyframe (IDR).
            if (type === 0x03) {
                // First IDR received — lower the gate so frames start flowing.
                console.log(`[Stream] keyframe received (${payload.byteLength} bytes) — gate open`);
                this._waitingForKeyframe = false;
            }

            // Drop inter-frames that arrive before the first IDR.
            // Chrome/Edge silently discard these at the decoder level, but Safari's
            // MSE implementation may throw a SourceBuffer error on premature P-frames,
            // permanently black-screening clients that join a live stream mid-flight.
            if (this._waitingForKeyframe) {
                if (!this._droppedFrameLogged) {
                    console.warn('[Stream] dropping inter-frame — waiting for first IDR');
                    this._droppedFrameLogged = true;
                }
                return;
            }
            this._droppedFrameLogged = false;

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
     * Parses the avcC box inside an fMP4 init segment and returns the
     * correct avc1 codec string (e.g. "avc1.64001F" for High Profile
     * Level 3.1).  Falls back to Baseline Level 3.0 if the box is not
     * found.
     *
     * @param {ArrayBuffer} initData fMP4 init segment bytes.
     * @returns {string} Codec string like "avc1.XXYYZZ".
     */
    _parseCodecString(initData) {
        const bytes = new Uint8Array(initData);
        // Search for the 'avcC' box type: 0x61='a' 0x76='v' 0x63='c' 0x43='C'
        for (let i = 0; i < bytes.length - 7; i++) {
            if (bytes[i] === 0x61 && bytes[i + 1] === 0x76 &&
                bytes[i + 2] === 0x63 && bytes[i + 3] === 0x43 &&
                bytes[i + 4] === 1) {
                // avcC payload after 'avcC' type (4 bytes):
                //   [configVersion=1(1)][profile(1)][compat(1)][level(1)]
                // configVersion is at i+4, profile starts at i+5.
                const profile = bytes[i + 5];
                const compat  = bytes[i + 6];
                const level   = bytes[i + 7];
                const hex = (v) => v.toString(16).padStart(2, '0').toUpperCase();
                return `avc1.${hex(profile)}${hex(compat)}${hex(level)}`;
            }
        }
        return 'avc1.42E01E'; // fallback: Baseline Profile Level 3.0
    }

    /**
     * Adds a SourceBuffer with the codec string derived from the init
     * segment's avcC box and appends the fMP4 init segment.
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
            // Pipeline restarted (e.g., rotation) — append new init segment
            // to the existing SourceBuffer.  SPS/PPS deduplication in
            // VideoEncoder ensures this only happens when codec parameters
            // actually change.
            this._appendBuffer(initData);
            return;
        }

        const codecStr = this._parseCodecString(initData);
        const mimeType = `video/mp4; codecs="${codecStr}"`;
        console.log('[MSE] Detected codec:', codecStr, '| MIME:', mimeType);
        if (!MediaSource.isTypeSupported(mimeType)) {
            console.error('[MSE] MIME type not supported:', mimeType);
            return;
        }

        this._sourceBuffer = this._mediaSource.addSourceBuffer(mimeType);
        this._sourceBuffer.mode = 'sequence';

        this._sourceBuffer.addEventListener('updateend', () => {
            this._flushQueue();
            this._trimBuffer();
        });

        this._sourceBuffer.addEventListener('error', () => {
            console.error('SourceBuffer error — resetting pipeline, waiting for next init segment');
            this._sourceBuffer = null;
            this._segmentQueue = [];
            this._pendingSegments = [];
        });

        this._appendBuffer(initData);
        this._updateConnectionUI('connected');
        document.getElementById('connection-status').className = 'status-dot connected';
        document.getElementById('connection-status-label').textContent = 'Connected';

        const video = document.getElementById('video-player');
        video.play().catch((err) => { console.warn('[MSE] play() rejected:', err.name); });
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
                // No updateend will fire after a throw, so schedule a retry
                // to keep the pipeline alive.
                this._segmentQueue.push(data);
                this._scheduleFlush();
            }
        }
    }

    /**
     * Schedules a deferred flush attempt so the pipeline recovers when
     * appendBuffer throws (no updateend fires in that case).
     */
    _scheduleFlush() {
        if (this._flushTimer) return;
        this._flushTimer = setTimeout(() => {
            this._flushTimer = null;
            this._flushQueue();
        }, 100);
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
            // Drop the failed segment (likely corrupt or QuotaExceeded)
            // and schedule another attempt for remaining items.
            this._scheduleFlush();
        }
    }

    /**
     * Trims old buffered data and keeps playback near the live edge.
     *
     * Removes data more than 2 seconds behind the live edge to prevent
     * unbounded memory growth.  When playback falls more than 1 second
     * behind the live edge, seeks to 0.1 seconds behind it so the
     * displayed frame is always near-real-time.
     */
    _trimBuffer() {
        const video = document.getElementById('video-player');
        if (!this._sourceBuffer || this._sourceBuffer.updating) return;
        const buffered = this._sourceBuffer.buffered;
        if (buffered.length === 0) return;
        const bufEnd = buffered.end(buffered.length - 1);

        // Trim everything more than 2 s behind the live edge.
        const trimTo = bufEnd - 2;
        if (trimTo > buffered.start(0)) {
            try {
                this._sourceBuffer.remove(0, trimTo);
            } catch (_) { /* ignore */ }
            return; // remove() triggers another updateend
        }

        // Keep playback at the live edge — seek when >1 s behind.
        // Also handle Safari's known bug where currentTime stays stuck at 0
        // in 'sequence' SourceBuffer mode.
        if (video.currentTime === 0 && bufEnd > 0.5) {
            video.currentTime = bufEnd - 0.1;
        } else if (bufEnd - video.currentTime > 1) {
            video.currentTime = bufEnd - 0.1;
        }
    }

    /** Releases MediaSource and clears SourceBuffer references. */
    _teardownMSE() {
        this._sourceBuffer = null;
        this._segmentQueue = [];
        this._pendingInitData = null;
        this._pendingSegments = [];
        clearTimeout(this._flushTimer);
        this._flushTimer = null;
        if (this._mediaSource) {
            try { this._mediaSource.endOfStream(); } catch (_) { /* ignore */ }
            this._mediaSource = null;
        }
        if (this._objectURL) {
            URL.revokeObjectURL(this._objectURL);
            this._objectURL = null;
        }
        clearInterval(this._fpsInterval);
    }

    // ── FPS counter ─────────────────────────────────────────────────────

    /** Starts a 1-second interval that updates the FPS counter in the left sidebar. */
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
        if (this._inputHandlersReady) return;
        this._inputHandlersReady = true;

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

        // Right-click (or long-press on touch) → Android long-press / context menu.
        video.addEventListener('contextmenu', (e) => {
            e.preventDefault();
            const { x, y } = this._normalise(e, video);
            this._send({ type: 'touch', action: 'long_press', x, y, id: 0 });
        });
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

    // ── Keyboard ─────────────────────────────────────────────────────────

    /**
     * Captures keydown/keyup events and forwards them to Android.
     *
     * - Navigation and function keys are mapped via KEY_MAP.
     * - Ctrl/Meta+C/V/X/Z/A are sent as nav actions (copy/paste/cut/undo/selectAll)
     *   and handled on the server via AccessibilityNodeInfo actions.
     * - Single printable characters are forwarded as key events so the server can
     *   append them to the focused text field via ACTION_SET_TEXT.
     */
    _initKeyboard() {
        /** Navigation / function key → Android keycode. */
        const KEY_MAP = {
            'Backspace': 67, 'Enter': 66,  'Escape': 111, 'Delete': 112,
            'ArrowLeft': 21, 'ArrowRight': 22, 'ArrowUp': 19, 'ArrowDown': 20,
            'Tab': 61,  ' ': 62,
            'Home': 122, 'End': 123, 'PageUp': 92, 'PageDown': 93,
            'F1': 131, 'F2': 132, 'F3': 133, 'F4': 134,  'F5': 135,
            'F6': 136, 'F7': 137, 'F8': 138, 'F9': 139,  'F10': 140,
        };

        /** Ctrl/Meta + key → nav action forwarded to AccessibilityNodeInfo. */
        const CTRL_MAP = {
            'c': 'copy', 'v': 'paste', 'x': 'cut', 'z': 'undo', 'a': 'selectAll',
        };

        /** Letter key → Android keycode (KEYCODE_A = 29). */
        const letterCode = (ch) => {
            const n = ch.toLowerCase().codePointAt(0) - 97; // 'a'=0
            return (n >= 0 && n < 26) ? 29 + n : null;
        };

        /** Digit key → Android keycode (KEYCODE_0 = 7). */
        const digitCode = (ch) => {
            const n = ch.codePointAt(0) - 48; // '0'=0
            return (n >= 0 && n <= 9) ? 7 + n : null;
        };

        let ctrl = false, meta = false, shift = false;

        document.addEventListener('keydown', (e) => {
            if (e.key === 'Control') { ctrl  = true; return; }
            if (e.key === 'Meta')    { meta  = true; return; }
            if (e.key === 'Shift')   { shift = true; return; }

            // Ctrl / Meta shortcut → nav action (copy, paste, etc.)
            if (ctrl || meta) {
                const action = CTRL_MAP[e.key.toLowerCase()];
                if (action) {
                    e.preventDefault();
                    this._send({ type: 'nav', action });
                    return;
                }
            }

            // Navigation / function key
            const mappedCode = KEY_MAP[e.key];
            if (mappedCode) {
                e.preventDefault();
                const metaState = (shift ? 0x01 : 0) | (ctrl ? 0x1000 : 0);
                this._send({ type: 'key', keyCode: mappedCode, action: 'down', metaState });
                return;
            }

            // Printable single character (letter or digit) — no modifier active
            if (e.key.length === 1 && !ctrl && !meta) {
                const kc = letterCode(e.key) ?? digitCode(e.key);
                if (kc !== null) {
                    e.preventDefault();
                    const metaState = shift ? 0x01 : 0;
                    this._send({ type: 'key', keyCode: kc, action: 'down', metaState });
                }
            }
        });

        document.addEventListener('keyup', (e) => {
            if (e.key === 'Control') { ctrl  = false; return; }
            if (e.key === 'Meta')    { meta  = false; return; }
            if (e.key === 'Shift')   { shift = false; return; }

            const mappedCode = KEY_MAP[e.key];
            if (mappedCode) {
                this._send({ type: 'key', keyCode: mappedCode, action: 'up', metaState: 0 });
                return;
            }
            if (e.key.length === 1 && !ctrl && !meta) {
                const kc = letterCode(e.key) ?? digitCode(e.key);
                if (kc !== null) {
                    this._send({ type: 'key', keyCode: kc, action: 'up', metaState: 0 });
                }
            }
        });
    }

    // ── Sidebars ─────────────────────────────────────────────────────────

    /**
     * Wires collapse/expand toggles for left and right sidebars.
     * Persists collapsed state to localStorage under 'leftCollapsed' / 'rightCollapsed'.
     */
    _initSidebars() {
        const setup = (sidebarId, toggleId, storageKey, arrowId) => {
            const sidebar = document.getElementById(sidebarId);
            const toggle  = document.getElementById(toggleId);
            const arrow   = document.getElementById(arrowId);
            if (!sidebar || !toggle) return;

            // Inject chevron icon
            if (arrow && typeof ICONS !== 'undefined') {
                arrow.innerHTML = ICONS.chevronLeft ?? '';
            }

            // Restore persisted state
            if (localStorage.getItem(storageKey) === 'true') {
                sidebar.classList.add('collapsed');
            }

            toggle.addEventListener('click', () => {
                const collapsed = sidebar.classList.toggle('collapsed');
                localStorage.setItem(storageKey, collapsed);
            });
        };

        setup('left-sidebar',  'left-toggle',  'leftCollapsed',  'left-arrow');
        setup('right-sidebar', 'right-toggle', 'rightCollapsed', 'right-arrow');
    }

    // ── Settings ─────────────────────────────────────────────────────────

    /** Loads settings from localStorage and wires slider change handlers. */
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

    /**
     * Requests capture (re)start.  Prefers WebSocket when the connection is
     * open so the server can reply inline; falls back to the REST endpoint.
     */
    async _requestCapture() {
        if (this._ws && this._ws.readyState === WebSocket.OPEN) {
            this._send({ type: 'restart_capture' });
        } else {
            try {
                await fetch('/api/start-capture', { method: 'POST' });
            } catch (_) { /* ignore */ }
        }
    }

    // ── Numpad ────────────────────────────────────────────────────────────

    /**
     * Builds the inline numpad panel and wires the toggle button.
     *
     * Each numpad button sends a `key` type message with the corresponding
     * Android key code.  The panel is hidden by default and can be toggled
     * with the "123" button in the keypad header.
     *
     * Key codes: digits use KEYCODE_0–9 (7–16), ⌫ = 67 (Backspace), ↵ = 66 (Enter).
     */
    _initNumpad() {
        /** @type {Array<[string, number]>} [label, androidKeyCode] */
        const KEYS = [
            ['7', 14], ['8', 15], ['9', 16],
            ['4', 11], ['5', 12], ['6', 13],
            ['1',  8], ['2',  9], ['3', 10],
            ['⌫', 67], ['0',  7], ['↵', 66],
        ];

        const grid = document.getElementById('numpad-grid');
        if (!grid) return;

        for (const [label, keyCode] of KEYS) {
            const btn = document.createElement('button');
            btn.className   = 'numpad-btn';
            btn.textContent = label;
            btn.addEventListener('click', () => {
                this._send({ type: 'key', keyCode, action: 'down', metaState: 0 });
                setTimeout(() => this._send({ type: 'key', keyCode, action: 'up', metaState: 0 }), 50);
            });
            grid.appendChild(btn);
        }

        document.getElementById('btn-numpad-toggle')?.addEventListener('click', () => {
            const panel = document.getElementById('numpad-panel');
            panel?.classList.toggle('hidden');
        });
    }

    // ── Capture state overlay ─────────────────────────────────────────────

    /**
     * Shows or hides the "screen locked" overlay that appears when
     * MediaProjection stops (e.g. due to the Android screen locking).
     *
     * @param {boolean} stopped True to show the overlay, false to hide it.
     */
    _setCaptureStopped(stopped) {
        document.getElementById('capture-stopped-overlay')
            ?.classList.toggle('hidden', !stopped);
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

    /**
     * Injects SVG icons into the system control buttons in the left sidebar.
     */
    _initIcons() {
        if (typeof ICONS === 'undefined') return;
        const map = {
            'btn-rotate':     'rotate',
            'btn-fullscreen': 'fullscreen',
            'btn-edit-keypad': 'edit',
            'btn-add-key':     'add',
        };
        for (const [id, icon] of Object.entries(map)) {
            const el = document.getElementById(id);
            if (el) el.innerHTML = ICONS[icon] ?? '';
        }
        document.getElementById('btn-fullscreen')?.addEventListener('click', () => {
            document.documentElement.requestFullscreen?.();
        });
        document.getElementById('btn-rotate')?.addEventListener('click', () => {
            this._send({ type: 'nav', action: 'rotate' });
        });
    }
}

// ── Bootstrap ─────────────────────────────────────────────────────────────
document.addEventListener('DOMContentLoaded', () => {
    const app = new ScrcpyWeb();
    document.getElementById('btn-request-capture')?.addEventListener('click', () => {
        app._requestCapture();
    });
    // "Restart Capture" button on the capture-stopped overlay.
    document.getElementById('btn-restart-capture')?.addEventListener('click', () => {
        app._requestCapture();
    });
});
