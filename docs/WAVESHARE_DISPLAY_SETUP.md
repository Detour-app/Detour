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

- **Phone side** (this repo): `app/src/main/java/com/jellemax/maproulette/ble/BleNavServer.kt`
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
