# Installation Guide

This guide walks you through installing SCRCPY-Web on your Android device wirelessly — no USB cable required.

> **Requirement**: Android 11+ (API 30+) for wireless ADB debugging.
> For Android 10, a USB cable is required for the initial ADB pairing.

---

## Option A — Windows Installer (Recommended)

The Windows installer bundle includes a portable ADB and a guided setup script. No prior ADB knowledge is needed.

### Download

1. Go to the [Releases](../../releases) page.
2. Download `scrcpy-web-vX.Y.Z-windows-installer.zip`.
3. Extract the zip to any folder.

The extracted folder contains:
```
install.bat          ← double-click to run
adb/
  adb.exe
  AdbWinApi.dll
  AdbWinUsbApi.dll
scrcpy-web-vX.Y.Z.apk
```

### Run the Installer

Double-click `install.bat`. The script will guide you through each step in your chosen language (English, Korean, Japanese, Chinese, Spanish).

**Steps performed by the installer:**

| Step | Action |
|------|--------|
| 1 | Enable Developer Options on your phone |
| 2 | Enable Wireless Debugging |
| 3 | Pair PC with phone (skip if already paired) |
| 4 | Connect PC to phone over Wi-Fi |
| 5 | Install the APK |

---

## Option B — Manual Install via ADB

Use this if you already have ADB installed or are on macOS/Linux.

### 1. Enable Developer Options

1. Open **Settings → About phone**.
2. Tap **Build number** 7 times rapidly.
3. You will see: *"You are now a developer!"*
4. Go back to **Settings → Developer options** and confirm it is enabled.

### 2. Enable Wireless Debugging (Android 11+)

1. In **Developer options**, tap **Wireless debugging**.
2. Toggle **Wireless debugging** ON.
3. Note the **IP address and port** shown (e.g. `192.168.1.5:38417`).

### 3. Pair Your PC (first time only)

1. Inside the **Wireless debugging** screen, tap **Pair device with pairing code**.
2. Note the temporary **pairing IP:port** and **6-digit code** shown.
3. On your PC, run:

```bash
adb pair <pairing-ip>:<pairing-port>
# Enter the 6-digit code when prompted
```

Example:
```bash
adb pair 192.168.1.5:37425
# Enter pairing code: 123456
```

### 4. Connect

Back on the **main Wireless debugging screen**, note the connect IP:port and run:

```bash
adb connect 192.168.1.5:38417
# Expected output: connected to 192.168.1.5:38417
```

### 5. Install the APK

```bash
adb install -r scrcpy-web-vX.Y.Z.apk
```

Expected output:
```
Performing Streamed Install
Success
```

---

## After Installation

1. Open **SCRCPY-Web** on your phone.
2. Grant the requested permissions:
   - **Screen Capture** — tap *Grant Screen Capture Permission* → Allow.
   - **Accessibility Service** — tap *Enable Accessibility Service* → find SCRCPY-Web → toggle on.
3. The app displays your phone's local IP address (e.g. `192.168.1.5:8080`).
4. On any browser on the same Wi-Fi, navigate to that address.

---

## Troubleshooting

| Problem | Solution |
|---------|----------|
| `adb: error: failed to connect` | Phone and PC must be on the same Wi-Fi network. |
| Pairing code expired | The code is valid for ~2 minutes. Tap *Pair device* again to get a new one. |
| `INSTALL_FAILED_UPDATE_INCOMPATIBLE` | Uninstall the existing app first: `adb uninstall com.scrcpyweb` |
| Screen stays black in browser | Tap *Grant Screen Capture Permission* in the app and allow the system dialog. |
| Touch input not working | Enable the Accessibility Service in phone Settings → Accessibility → SCRCPY-Web. |
| After phone reboot, stream stops | Open the SCRCPY-Web app and tap *Allow* on the screen capture dialog once. |

---

## Android 10 (USB Pairing)

Android 10 does not support wireless ADB pairing. Use USB for the initial setup:

```bash
# Connect phone via USB, enable USB debugging in Developer options
adb devices           # confirm device appears
adb install -r scrcpy-web-vX.Y.Z.apk
adb tcpip 5555        # switch ADB to TCP mode
adb connect 192.168.1.5:5555   # connect wirelessly for future use
```

After this first setup, USB is no longer needed.
