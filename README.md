# OpenVOGE

**An open-source Android companion app for VOGE motorcycles.** Live Bluetooth telemetry and the bike's Wi‑Fi dashcam, in one app with a bold neo-brutalist design.

OpenVOGE is an unofficial replacement for the stock VOGE app. It talks to the motorcycle and its camera directly. No account, no cloud, no tracking.

> ⚠️ Unofficial project. Not affiliated with, endorsed by or supported by VOGE or Loncin. Use at your own risk and never interact with the app while riding.

---

## Features

### Dashboard
- **Live telemetry over Bluetooth LE:** speed, RPM, gear, fuel level, engine temperature, battery voltage, trip distance, trip time and total mileage.
- **Segmented speedometer** and RPM bar with redline, both in km/h or mph (tap AVG SPEED to switch units).
- **All-time top speed per bike:** tap MAX SPEED to flip between this ride's maximum and the bike's all-time record. The record is stored per motorcycle (by Bluetooth address) and only written when it's broken.

### Connecting to the bike
- **You choose what to connect to.** CONNECT opens a sheet with:
  - **Saved:** bikes you've connected to before. One can be marked **AUTO**.
  - **Nearby:** a live Bluetooth scan with signal strength. VOGE bikes are listed first.
  - **Remember & auto-connect:** turn it on before picking a bike to make it the AUTO bike.
- **Nothing connects on its own** unless a bike is marked AUTO. The AUTO bike reconnects whenever it comes back in range.
- The banner dot blinks while connecting and turns solid when connected.

### Bike camera
- **Live preview** from the motorcycle's Wi‑Fi dashcam. The viewer adapts to the camera's real aspect ratio.
- **Fullscreen** landscape viewing.
- **Real resolution and FPS**, measured from the live stream. Nothing is shown while disconnected.
- **Recording continues with the screen off** and the app in the background. Only the Stop button (in the app or in the notification) ends it. Video decoding pauses while you're not looking, to save battery.
- Record and photo are disabled until the camera is connected.

### Look & feel
- Neo-brutalist UI: hard shadows, bold borders, Space Grotesk and Space Mono typefaces.
- **Light, dark and auto** themes.
- **Four accent colors** (lime, blue, orange, grey). The launcher icon follows the accent you pick.
- **English and Spanish.**

---

## Compatibility

| Component | Tested with |
|---|---|
| Motorcycle | VOGE bikes advertising as `BLE-VOGE-xxxxxx` |
| Dashcam | MS8336 Wi‑Fi camera (`wifi_camera_xxxx_xxxx`, host `192.72.1.1`) |
| Phone | Android 7.0+ (API 24); developed and tested on API 36 |

Other VOGE models that use the same telemetry service should work. Reports and fixes for other models are welcome.

---

## Getting started

### Build and install

```bash
git clone https://github.com/openvoge/openvoge.git
cd openvoge
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Requirements: JDK 17 and Android SDK 36 (Android Studio Ladybug or newer works out of the box).

### First ride
1. Turn on the bike and open OpenVOGE. **Close the official VOGE app first.** The bike accepts only one connection at a time.
2. Tap **CONNECT**, pick your bike under **Nearby** and, if you like, enable **Remember & auto-connect**.
3. For the camera, join the dashcam's Wi‑Fi in Android settings, then tap **CAM**. With camera auto-connect on, the preview starts by itself.

### Permissions

| Permission | Why |
|---|---|
| Nearby devices (`BLUETOOTH_SCAN`, `BLUETOOTH_CONNECT`) | Find and connect to the bike |
| Location (Android 11 and below only) | Required by Android for Bluetooth scanning on older versions |
| Notifications | The "Recording" notification with its Stop button |
| Foreground service, wake lock | Keep the camera session alive while recording with the screen off |

---

## How it works

### Bluetooth telemetry
The bike exposes a GATT service with a notify characteristic (`ab9938d5-c354-4e2b-94f4-364e16ebcd33`) and a write characteristic (`6052202a-2928-4131-a2d0-456d5673ed2f`). After connecting, the app:

1. Discovers services and enables notifications.
2. Sends the vendor kickoff frame (`01 4A … 07 04`), which starts the telemetry stream.
3. Sends a `0x5A` poll frame every second, like the official app does.

Each step starts from the previous Bluetooth callback through a serial GATT queue, with no sleeps or retry timers.

The bike advertises with a **random BLE address** that differs from its classic Bluetooth pairing address. That's why OpenVOGE connects to the device found by scanning instead of the paired one.

### Camera
The dashcam is driven over HTTP at `192.72.1.1` (`/cgi-bin/Config.cgi`) and streams RTSP from `/liveRTSP/v1`. The app routes the stream through a small local RTSP proxy that sanitizes the camera's non-standard headers before handing it to ExoPlayer. The process is bound to the camera's Wi‑Fi network, so traffic goes there even though the network has no internet.

### Project layout

```
app/src/main/java/dev/vogy/app/
├── MainActivity.kt                  UI: dashboard, camera, sheets
├── DashboardViewModel.kt            UI state, connection phase, top-speed tracking
├── MotorcycleTelemetryController.kt BLE scan, event-driven GATT connection, VOGE protocol
├── TelemetryParser.kt / Models      Decoding telemetry frames
├── BikeRegistry.kt                  Saved bikes, AUTO bike, per-bike top speed
├── CameraController.kt              Camera HTTP control, RTSP discovery, heartbeat
├── CameraSession.kt                 App-scoped camera session that outlives the screen
├── CameraRecordingService.kt        Foreground service for screen-off recording
├── RtspProxy.kt                     Local RTSP header-sanitizing proxy
├── MjpegReader.kt                   MJPEG fallback stream
├── CameraWifiDetector.kt            Camera Wi‑Fi detection
└── SpeedometerView.kt / GearPodView.kt   Custom gauges
```

Built with Kotlin, AndroidX, Material Components and Media3 ExoPlayer (RTSP).

---

## Known limitations

- **Camera record command:** the MS8336 camera did not accept the record commands tried so far. Live preview works; starting a recording from the app is still being worked out.
- **Photo capture** is not implemented yet. The button is a placeholder.
- Aggressive battery savers (e.g. Samsung) can still kill background apps. Set OpenVOGE's battery usage to **Unrestricted** for reliable screen-off recording.

---

## Contributing

Issues and pull requests are welcome, especially:
- Telemetry captures from other VOGE models
- The dashcam's record and photo commands
- Translations
- UI/UX improvements or a full redesign

Please add both English and Spanish strings for any user-facing text.

## License

[GNU LGPL v2.1](LICENSE)
