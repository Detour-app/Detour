# Setting up the Waveshare ESP32-S3-Touch-LCD-2.1

Notes for getting the board itself running, and how it fits into the
motorcycle GPS project (BLE nav display) being built alongside this app.

Board: round 2.1", 480×480, capacitive touch, ESP32-S3 dual-core LX7,
16MB flash, 8MB PSRAM, WiFi + BLE5. Official wiki (blocks automated
fetches, load it in a browser):
https://www.waveshare.com/wiki/ESP32-S3-Touch-LCD-2.1

## 1. Driver

Connect over USB-C with a real data cable. The board uses a **CH343P**
USB-to-UART chip.

- Windows: install the CH343 driver (WCH's site, also linked on the wiki)
  before it shows up as a COM port.
- macOS/Linux: usually enumerates natively (`/dev/tty.usbserial-*` or
  `/dev/ttyACM*` / `/dev/ttyUSB*`). Grab the CH34x driver if it doesn't.

If it won't enumerate or flashing fails: hold **BOOT**, press and release
**RESET**, then release **BOOT** — forces download mode.

## 2. Toolchain

Two options, pick one:

**Arduino IDE** (quick to get a stock demo running):
1. Install Arduino IDE.
2. File → Preferences → Additional Board Manager URLs, add:
   `https://raw.githubusercontent.com/espressif/arduino-esp32/gh-pages/package_esp32_index.json`
3. Boards Manager → install "esp32 by Espressif Systems".
4. Tools → Board → **ESP32S3 Dev Module**, then set PSRAM: OPI PSRAM,
   Flash Size: 16MB, a large-app partition scheme, and the CH343 COM port.
5. Download Waveshare's demo/libraries from the wiki, open the matching
   example, compile, upload.

**ESP-IDF** (what the moto GPS firmware below uses):
1. Install ESP-IDF v5.x, source `export.sh` / `export.ps1`.
2. Clone the demo repo linked on the wiki for panel/touch driver reference.
3. `idf.py set-target esp32s3`, `idf.py menuconfig`, `idf.py build flash monitor`.

First boot of Waveshare's stock demo should show an LVGL UI exercising the
touchscreen — confirms display + touch (and any onboard sensors) work
before writing custom firmware.

## 3. Motorcycle GPS companion project

This screen is being turned into a handlebar-mounted nav display for Map
Roulette: it shows the same turn-by-turn info as the Wear OS watch (turn,
distance to turn, speed, speeding warning), plus extras the bigger screen
has room for — speed limit number, road name, remaining distance/ETA.

- **Phone side** (this repo): `app/src/main/java/com/jellemax/detour/ble/BleNavServer.kt`
  runs a BLE GATT peripheral that broadcasts nav state, gated behind
  Settings → External display (grants `BLUETOOTH_CONNECT` +
  `BLUETOOTH_ADVERTISE`, then advertises + notifies while navigating).
- **Firmware side**: a separate ESP-IDF + LVGL project, `moto-display`,
  connects to the phone as a BLE central and renders the round screen.
  The BLE service/characteristic UUIDs and JSON payload shape are shared
  between the two — see `BleNavServer.kt` for the source of truth.
- The firmware's display/touch panel init is left as a stub
  (`board_display.c`) to be filled in from Waveshare's official demo,
  since the exact controller/pin mapping couldn't be confirmed without
  the physical board and the wiki blocking automated fetches.

## 4. BLE protocol

The phone runs a GATT *peripheral* (`BleNavServer.kt`); the display firmware
is the *central* — it connects, negotiates a bigger MTU, and subscribes to
the characteristics it wants. This section is generated from
`BleNavServer.kt` directly (the source of truth for both sides) — if the two
disagree, the code wins.

**Service UUID**: `b17a0001-9c2e-4b8a-8f21-1f5e2a6d0e01`

| Characteristic | UUID | Direction | Properties |
|---|---|---|---|
| Nav | `b17a0002-...-0e01` | phone → display, notify | read, notify |
| Music | `b17a0003-...-0e01` | phone → display, notify | read, notify |
| Time | `b17a0004-...-0e01` | phone → display, notify | read, notify |
| Art | `b17a0005-...-0e01` | phone → display, notify | read, notify |
| Telemetry | `b17a0006-...-0e01` | display → phone, write | write, write-without-response |

All notify characteristics also expose the standard CCCD
(`00002902-0000-1000-8000-00805f9b34fb`) for subscribing.

**MTU**: the default ATT MTU is 23 bytes, too small for the nav or music JSON
payload. The central is expected to request a larger MTU (`onMtuChanged` on
the phone side) before subscribing; art in particular is re-queued once a
bigger MTU lands, since at 23 bytes the 1-byte chunk-count header can't
address enough chunks for a full cover.

**Nav** (`send`/`sendStats`/`clear`) — JSON, one object per notification:
```json
{
  "sign": 0,
  "roundaboutExit": 0,
  "street": "",
  "distanceToTurnMeters": 0.0,
  "remainingMeters": 0.0,
  "routeMeters": 0.0,
  "remainingTimeMs": null,
  "speedKmh": 0.0,
  "speedLimitKmh": null,
  "navigating": true
}
```
`navigating: false` means "tracking a trip, no active route" (`sendStats`) —
the maneuver/distance/ETA fields are zeroed and should be ignored. `clear`
instead sends `{"stop": true}`. Throttled to at most one push per second,
plus immediately on a new maneuver (`sign` change).

**Music** (`sendMusic`/`clearMusic`):
```json
{"title": "", "artist": "", "posSec": 0.0, "durSec": 0.0, "playing": true}
```
`clearMusic` instead sends `{"stop": true}` when there's no active media
session.

**Time** (`sendTime`) — pushed once per connection and then every 30 s:
```json
{"epochMs": 0, "utcOffsetMin": 0}
```
`epochMs` is UTC; `utcOffsetMin` is the phone's current (DST-adjusted) offset
— the board has no timezone database of its own and just adds it.

**Art** (`sendArt`) — not JSON: a JPEG (square-cropped and downscaled to
180×180, quality stepped down through 80/65/50/35 until it's under 24 KB),
split into notification-sized chunks, each chunk prefixed with a 3-byte
header `[frameId][chunkIndex][chunkCount]`. Sent one chunk at a time,
waiting for `onNotificationSent` before the next, since a lost chunk under
Android's own notification queue would otherwise cost the whole image. A
newly-subscribed display gets the current cover replayed immediately rather
than waiting for the next track change.

**Telemetry** (board → phone, write): the one write-direction
characteristic — the board's own GPS/IMU readings, which the phone prefers
over its own sensors when fresh (see `TripTrackingService`).
```json
{"hasSpeed": true, "speedKmh": 0.0, "hasLean": true, "leanDeg": 0.0}
```
The board writes this **every 250 ms**. The phone treats a reading older
than **2 s** as stale and falls back to its own sensors rather than freezing
on the last board value. Malformed JSON, or a value that isn't finite or is
outside a plausible range (`speedKmh` outside 0–350, `leanDeg` outside
±70°), drops the whole packet — a garbage packet means a firmware/transport
bug, and a clamped value would still be recorded downstream as a real
reading.
