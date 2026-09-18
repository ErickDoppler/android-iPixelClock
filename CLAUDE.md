# CLAUDE.md

iPixel Clock: an Android clock that drives an iPixel BLE LED matrix. A foreground
service renders frames and pushes them over Bluetooth; the settings UI is a
cyberpunk web page the app serves itself, embedded in a WebView on the phone and
reachable from any browser on the LAN.

Kotlin, self-drawn Views, no Compose, no AppCompat. `minSdk 21` (Android 5.0),
targetSdk 35. One dependency: `androidx.core-ktx`. The HTTP/WebSocket server is
hand-rolled; the web UI is plain ES5 and flexbox so an un-updated Android 5
WebView renders it.

`applicationId` and source package are both `com.example.ipixelclock`.

## Build and deploy

```
./gradlew.bat assembleDebug          # JDK 17 at C:\workenv\jdk, pinned in gradle.properties
./deploy.sh                          # build + install + launch on the tablet
./deploy.sh log                      # ...then follow logcat
```

Test device: **LG-V500** at `192.168.1.108:5555`, Android 9 / API 28,
armeabi-v7a. adb is `C:\workenv\platform-tools\adb.exe`; the SDK is
`C:\workenv\AndroidStudio`. There is no `C:\workenv\jdk-23.0.2` on this machine
whatever the yacht-compass notes say.

The tablet covers neither the Android 5 legacy BLE-scan path nor the API 31+
runtime-permission path. Those need emulators before the project is called done.

## Rules

- **`led/IPixelHub.kt` is shared verbatim** with `android-yacht-compass` and the
  other panel apps — the protocol was reverse-engineered and measured once, and is
  not to be re-derived. Only the package line may differ. The single current
  divergence is the additive `brightness` / `setBrightness()`, which still needs
  back-porting. Any further change goes to every copy.
- **Never require a panel.** The app must install, run, preview and configure with
  no hardware, no Bluetooth permission and the radio off. Bluetooth is touched
  only when the user presses DETECT. A simulated frame is always captioned as
  simulated — never let a preview pass for real output.
- **Never let the web interface fall open.** With no password set, remote access
  is refused, not permitted. The password is settable only from the app.
- **Frame encoding is measured, not assumed** (`led/PanelTuning.kt`). The tuned
  default wins by 50× on a 96×16 E15 and loses by 8× on a 144×16 running firmware
  21.17. Do not hardcode a winner; do not remove the probe.
- **Orientation swaps the canvas, not just the output.** At 90°/270° the scene is
  composed at the swapped size so the layout can respond to being tall. Anything
  that draws must ask the canvas for its own width and height and nothing else.
- **No `java.time`**, so API 21 needs no desugaring. `Calendar` and `TimeZone`.
- **Always `Locale.US`** for anything the panel renders — default-locale `%02d`
  produces Eastern Arabic numerals on some devices and the bitmap fonts have no
  glyphs for them.
- **Fonts are original designs.** The named inspirations are trademarked
  typefaces; nothing is traced from them and no font files ship in the APK.
- Guard every API-gated call with `Build.VERSION.SDK_INT`, and prefer the older
  overload where referencing a newer class in a signature would drag it into a
  class that has to load on Android 5.

## Layout

```
ClockService        foreground service; owns the hub, renderer, settings, server
MainActivity        native shell: preview, start/stop, detect, the one password field
led/IPixelHub       the shared BLE driver (verbatim)
led/PanelTuning     measures the fastest frame encoding per panel size
led/LedPages        only the driver's SCREEN OFF sign-off frame
render/             PixelCanvas, FrameRenderer, Orientation, PanelTarget
font/               PixelFont model + the bitmap font registry
face/               ClockFace layout and formatting, ColorModes
web/                WebServer (HTTP + WS), Auth, Api + Schema
assets/web/         index.html, app.css, app.js, login.html, locked.html
```

The web UI is **data-driven from `/api/schema`**: adding a font, transition or
background in Kotlin makes it appear in the picker with no HTML change. Keep it
that way.

Settings are one immutable `Settings` object serialised as one JSON blob, which is
also exactly the `/api/state` and `/api/settings` shape. No mapping layer.

## Caveats worth remembering

- Port 80 cannot be bound by an unprivileged Android app. 8080 with fallbacks.
- A foreground service typed `connectedDevice` is refused on Android 14+ without a
  Bluetooth runtime grant; `MainActivity` asks first and the service catches the
  failure rather than crashing.
- Sessions are in memory, so restarting the service signs every browser out.
- On a panel tuned to the PNG path, a full-screen background will cost
  throughput — the frame stops compressing. Re-measure when backgrounds land.
