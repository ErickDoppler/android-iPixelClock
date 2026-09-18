# iPixel Clock

An Android clock that drives an **iPixel BLE LED matrix** — a background service
pushes frames to the panel over Bluetooth, and everything about the clock face is
configured either from the app on the phone or from a browser anywhere on the
network.

**No panel is required to use it.** The app installs, runs, previews and
configures on a bare phone with the Bluetooth radio switched off; it renders to a
simulated panel and shows you exactly what would be on the wall. You only need
real hardware for real light, and the app says so rather than pretending.

---

## What it does

| | |
|---|---|
| **Service** | Foreground service; keeps the panel running with the screen off and the app swiped away |
| **Panel** | Auto-detects size from the panel's own firmware reply; 96×16, 144×16 and the rest of the iPixel type table |
| **Mounting** | 0° / 90° / 180° / 270° plus mirroring — a panel hung as a **vertical banner** composes as a tall scene and gets a stacked layout, not a sideways one |
| **Brightness** | Live 0–100 on the panel's own hardware control, plus a day/night automatic mode |
| **Web UI** | Cyberpunk settings page served by the app itself, password protected, with a live preview of the panel |
| **App** | Panel preview, start/stop, detect, and a single password field — the rest of the UI is the web page, embedded |

---

## Getting to it

The app prints its own address. From any browser on the same network:

```
http://<phone-ip>:8080
```

**Port 80 is not available.** Android refuses to let an unprivileged app bind any
port below 1024, on every version — there is no `setcap` or `authbind` to grant an
exception. The server takes **8080** by preference, then 8000, then 8888, then the
port it last managed to get, then a random one in 6500–7500. Whatever it lands on
is shown in the app and in the notification.

On a rooted phone you can forward 80 to it yourself, outside the app:

```bash
iptables -t nat -A PREROUTING -p tcp --dport 80 -j REDIRECT --to-port 8080
```

### The password

Set in the **app**, in the single password field at the top. One input, no
confirmation.

- It can only be set on the phone. A browser session can never change it, so
  getting in once is not enough to lock the owner out.
- Only a salted, iterated SHA-256 hash is stored; the plaintext never touches disk.
- **Until a password is set, remote access is refused outright** — the web
  interface is closed, not open. It does not fall open on misconfiguration.
- Failed logins are throttled per source address with a growing delay.
- The app's own embedded view comes from `127.0.0.1` and is never asked to log in.
- Sessions live in memory, so restarting the clock signs everyone out.

---

## Build

Toolchain on the machine this was developed on:

| | |
|---|---|
| JDK | `C:\workenv\jdk` — OpenJDK 17.0.15 |
| Android SDK | `C:\workenv\AndroidStudio` |
| adb | `C:\workenv\platform-tools\adb.exe` |

`local.properties` (gitignored) points at the SDK; `gradle.properties` pins
`org.gradle.java.home`.

```bash
./gradlew.bat assembleDebug
```

Build, install and launch on the test tablet in one go:

```bash
./deploy.sh
```

`./deploy.sh log` follows the app's logcat afterwards. `DEVICE=<serial>` overrides
the target.

- **minSdk 21** (Android 5.0), targetSdk 35, Kotlin, self-drawn Views.
- No `java.time` anywhere, so no core-library desugaring is needed at API 21.
- Dependencies: `androidx.core-ktx` and nothing else. The HTTP/WebSocket server is
  hand-rolled and the UI is plain ES5 and flexbox, so an un-updated Android 5
  WebView renders it.

---

## The iPixel driver

`led/IPixelHub.kt` is copied **verbatim** from `android-yacht-compass`, where the
protocol was reverse-engineered and tuned against real hardware. It is shared
byte-for-byte across every app in this family that drives a panel — do not
re-derive it, and port fixes back.

**This copy has one additive divergence**: `brightness` / `setBrightness()`, so the
panel can be dimmed live. The shared copy only sent a fixed level once at
connection. It still needs back-porting to yacht-compass to restore the rule.

### Frame encoding is measured, not assumed

The driver's tuned default is the live-canvas path (opcode `0x0000` behind DIY
mode), which on the 96×16 E15 it was measured against is the clear winner — about
10 ms a frame against roughly 500 ms for the stored-image path.

**That does not generalise.** Measured here on a 144×16 (type 135, MCU 21.17 /
BLE 1.13):

| Encoding | fps |
|---|---|
| `0x0000` live canvas *(the tuned default)* | **1.0** |
| `0x0002` raw RGB888 | 0.6 |
| `0x0002` PNG *(the legacy fallback)* | **7.3 – 8.6** |

The exact inverse, and an eightfold difference. Transfer time is ~30 ms either
way, so this is the panel's own processing, not the radio — nothing on the phone
side changes it.

So `led/PanelTuning.kt` probes each candidate for a few seconds the first time it
sees a panel size, keeps the fastest, and remembers it in
`shared_prefs/ipixel_tuning.xml`. It stops early once one is comfortably fast, so
an E15-class panel settles on its best setting within seconds. SYSTEM → TRANSPORT
DIAGNOSTICS shows what won, with a RE-MEASURE button.

The driver's transport knobs can still be pinned by hand, which suppresses the
auto-tune:

```bash
adb shell "run-as com.example.ipixelclock sh -c 'cat > shared_prefs/ipixel_lab.xml'" <<'EOF'
<?xml version='1.0' encoding='utf-8' standalone='yes' ?>
<map>
    <int name="frame_mode" value="0" />
    <boolean name="rsp" value="true" />
</map>
EOF
```

Keys: `frame_mode` (0 PNG / 1 raw / 2 live), `rsp`, `gate`, `hi_prio`, `chunk`,
`diy`, `skip_wipe`, `cam_len`, `cam_acks`, `dedupe`, `floor_ms`, `phy2m`,
`auto_fallback`. Absent keys keep the compiled default.

### Reading the driver's log

```bash
adb logcat -s IPixelHub:I
```

It reports its own configuration on connect (`READY mode=…`), the auto-detected
panel (`panel reports 144x16 (type 135)`), per-command timings split into
`transfer` and `panel`, and an fps line every ten frames. `(LEGACY MODE)` in the
status string means the panel refused the live path and the driver fell back,
which is correct behaviour rather than a fault.

---

## Mounting a vertical banner

The panel's framebuffer is always its native landscape size — a 144×16 module is
144×16 to the firmware however it is screwed to the wall. Set the rotation to
match the mount and the **scene** is composed at 16×144 instead, so the layout can
see that it is tall and stack the digits. The orientation transform is the last
step before the frame goes out.

The previews show the scene as it will appear on the wall, not the panel's
physical strip — those are not the same picture on a vertical mount, and the
preview's job is to answer "what will I see".

---

## Status

Working end to end, on hardware:

- the service, the foreground notification and boot start
- panel detection, auto-tuning, brightness, orientation including vertical
- the simulated panel and the no-panel reminder
- the web server, password protection, the live WebSocket preview
- the app shell with its native preview
- a `SYSTEM` / `NARROW` bitmap clock face with solid, gradient, animated-gradient,
  rainbow and per-digit colouring, and the visibility policies

Still to come:

- the rest of the font library — MATRIX, GALACTIC, STARSHIP, TERMINAL, SEGMENT,
  DOTMATRIX, HAIRLINE, BLOCK, ARCADE, CLASSY
- digit-change effects beyond fade and switch
- the procedural backgrounds, and image / GIF / video backgrounds
- date, weather, sunrise and sunset, with a city picker

Note for the backgrounds work: on a panel that tunes to the PNG path, a
full-screen background costs real throughput, because the frame no longer
compresses. Worth re-measuring then.
