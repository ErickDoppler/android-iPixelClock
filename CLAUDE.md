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
./build.sh                           # -> out/ipixel-clock-debug.apk
./deploy.sh                          # build + install + launch on the tablet
./deploy.sh log                      # ...then follow logcat
```

`build.sh` / `build.cmd` resolve the JDK themselves, from `tools/toolchain.env`
(written by `download-tools.sh` / `.cmd`) or from `JAVA_HOME` and the usual
system locations. **Do not pin `org.gradle.java.home` in `gradle.properties`** —
it was pinned once and made the repository unbuildable on any other machine.
Bare `./gradlew.bat assembleDebug` still works here because `JAVA_HOME` is
already a JDK 17, but the scripts are the supported path.

On this machine the toolchain scripts download nothing: they find
`C:\workenv\jdk` (OpenJDK 17.0.15) and `C:\workenv\AndroidStudio` (platform 35,
build-tools 35.0.0) and only write `local.properties` and `tools/`. There is no
`C:\workenv\jdk-23.0.2` whatever the yacht-compass notes say.

Test device: **LG-V500** at `192.168.1.108:5555`, Android 9 / API 28,
armeabi-v7a. adb is `C:\workenv\platform-tools\adb.exe`.

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
- **Frame encoding is measured, not assumed** (`led/PanelTuning.kt`). The answer
  depends on the phone's radio, not the panel's firmware: live `0x0000` runs at
  ~10 fps on a BT 5 handset and ~1 fps on a 2013 BT 4.0 one, on the same panel.
  Do not hardcode a winner; do not remove the probe. See "The frame-rate trap".
- **Orientation swaps the canvas, not just the output.** At 90°/270° the scene is
  composed at the swapped size so the layout can respond to being tall. Anything
  that draws must ask the canvas for its own width and height and nothing else.
- **No `java.time`**, so API 21 needs no desugaring. `Calendar` and `TimeZone`.
- **Always `Locale.US`** for anything the panel renders — default-locale `%02d`
  produces Eastern Arabic numerals on some devices and the bitmap fonts have no
  glyphs for them.
- **Fonts are original designs.** The named inspirations are trademarked
  typefaces; nothing is traced from them and no font files ship in the APK.
- **New fonts are drawn, not encoded.** Add them as ASCII art in
  `font/ArtFonts.kt` via `PixelFontArt.font`, never as hex column tables — a
  wrong nibble in `0x3F1E` is invisible in review. SYSTEM and NARROW stay
  numeric only because they are carried over from the driver and already proven.
- **Effects are functions of progress, never of frame count**
  (`fx/Transitions.kt`). The panel's frame rate varies by an order of magnitude
  between generations and wanders within one; anything that advances per frame
  will run at the wrong speed and stutter on dropped frames.
- **The message runner is render-thread state behind two flags.** `MessageRunner`
  is triggered from web threads and the app, and `showNow()` / `cancel()` set an
  `AtomicBoolean` each — they never touch the run itself. `plan()` is pure and
  allocates no pixel masks, which is what makes it safe to call from `state()`
  on every settings push. Anything new that reaches in from off the render
  thread goes through a flag too.
- **Never hand a live render buffer to another thread.** `FrameRenderer` redraws
  one canvas in place. Anything read off the render thread — `/api/preview.png`
  is the case that bit — takes a copy under `frameLock`, or it will occasionally
  catch the frame between `clear()` and the face being drawn.
- **Keep work off the driver's thread.** `IPixelHub` pulls frames by calling
  `renderFrame()` on its own thread and is a strict one-frame-at-a-time
  pipeline, so every millisecond spent in that callback is a millisecond the
  panel is not written to. It must do nothing but copy the finished frame.
  Compositing belongs on the render thread, preview fan-out on the preview
  thread. Never put a socket write on either of them.
- **Measure the panel, not yourself.** `countPanelFrame()` is called from the
  driver's pull and is the only honest throughput number; `countFrame()` counts
  composited frames and will happily read 15 fps while the panel takes 2.
  `PanelTuning` must use the former, and turns dedupe off while probing or it
  measures how still the clock is instead of how fast the panel is.
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
font/               PixelFont model, PixelFontArt (ASCII-art builder), ArtFonts, registry
face/               ClockFace layout, per-cell transition state, ColorModes
fx/                 DigitTransition + the 21 digit-change effects
msg/                the custom message: MessageArt, the 23 arrival effects, MessageRunner
web/                WebServer (HTTP + WS), Auth, Api + Schema
assets/web/         index.html, app.css, app.js, login.html, locked.html

download-tools.sh/.cmd  toolchain fetcher; reuses whatever is installed
build.sh/.cmd           build + copy to out/; resolves the JDK itself
deploy.sh               build + install + launch + logcat, over Wi-Fi
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

## The frame-rate trap

`PanelTuning` must time each candidate from **its own first frame**, never from
the moment it was selected. The driver holds all traffic for three seconds after
the ROM erase, the probe begins inside that hold, and timing from selection
charges the dead time to whichever candidate runs first — always the driver's
tuned default. That bug scored the live `0x0000` path at 3.6 fps on the S62 and
picked a 4.6 fps alternative, when the same path measured 10.3 fps minutes
later. yacht-compass, which hardcodes the default, was getting 11.3.

The right answer genuinely differs by phone: live `0x0000` is ~10 fps on a BT 5
radio and ~1 fps on a 2013 BT 4.0 one, because a 6921-byte raw frame needs the
LE 2M PHY and a 250-byte PNG does not. So keep the probe — just keep it honest.
