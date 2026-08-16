# Optional developer tooling for the devcontainer

Nothing here runs automatically and nothing here is needed to build, test or
lint Detour. Each script is opt-in, run by hand, inside the container, by the
one developer who wants it. The image ships without any of these packages
because a display stack and a ~1.5 GB Android system image are not worth
imposing on someone who only builds.

| Script | What it does |
|---|---|
| `gui-env.sh` | **Source, don't run.** Points the shell at the host's display: exports `WAYLAND_DISPLAY`, `XDG_RUNTIME_DIR` and `DISPLAY`, starting Xwayland if it is not already up. |
| `setup-gui.sh` | Installs the display stack — Xwayland, EGL/GLX/Mesa. Once per container. |
| `setup-avd.sh` | Installs the emulator and an `android-35` `google_apis` system image, then creates an AVD. Once per container. |
| `start-avd.sh` | Boots the AVD with its window on the host desktop. |

Typical first run:

```sh
.devcontainer/scripts/setup-gui.sh
.devcontainer/scripts/setup-avd.sh
.devcontainer/scripts/start-avd.sh
```

## Why there is no devcontainer.json change

**VS Code already forwards Wayland.** It bind-mounts the host socket into the
container under a generated name:

```
/run/user/1000/wayland-1  ->  /tmp/vscode-wayland-<uuid>.sock
```

Verified by launching a Wayland-native client inside the container and finding
its window in the host compositor's client list — not by inference from the
mount table. What is missing is only the two environment variables pointing at
it, because `docker exec` runs a non-interactive shell that never sees VS Code's
`remoteEnv`. That is what `gui-env.sh` supplies, and it discovers the socket
rather than hard-coding it, because the uuid changes per container instance.

GPU and KVM need no change either: `--privileged` in `devcontainer.json` already
passes `/dev/kvm` and `/dev/dri/renderD128` through, both world-writable.

## Measured on the machine this was built on

Facts, so a future reader can tell a regression from a difference of hardware:

- `emulator -accel-check` → `KVM (version 12) is installed and usable`
- `glxinfo -B` through Xwayland → `AMD Radeon 780M Graphics (radeonsi)`,
  `direct rendering: Yes`, OpenGL 4.6 / Mesa 25.2.8
- AVD `android-35 google_apis x86_64` boots to the launcher,
  `sys.boot_completed=1`, window on the host desktop
- the running emulator appears to the **host** adb server as `emulator-5554`,
  alongside a physically connected phone

## Two traps worth knowing before you debug

**`/tmp/.X11-unix` ships owned by the container user**, and Xwayland refuses to
start against that with `Owner of /tmp/.X11-unix should be set to root`, aborting
with a backtrace that looks much worse than the cause. `gui-env.sh` corrects the
ownership; that is the only reason it uses `sudo`.

**A missing `libEGL.so.1` kills Xwayland at startup**, again with a backtrace,
and a missing GLX kills SDL clients later with `Couldn't find matching render
driver`. Both come from `setup-gui.sh`; if you install packages by hand, install
those.

## The Desktop Head Unit is not here yet

There is deliberately no `setup-dhu.sh`. The DHU installs cleanly
(`sdkmanager "extras;google;auto"`, plus `libc++1` and `libc++abi1`) and, over
USB accessory transport, gets as far as:

```
Found device '…' in accessory mode (vid=18d1, pid=2d01)
Attaching to USB device... Attached!
```

with the phone confirming *"Android Auto — Connected to your vehicle"*. It then
hangs without projecting, and the ADB-tunnel transport (`adb forward tcp:5277`)
never establishes a session at all.

Getting that far took the `/dev/bus/usb` bind mount now in `devcontainer.json`.
Before it, the container's `/dev` was a tmpfs frozen at container start, so a
phone plugged in afterwards did not exist in the container's device tree and DHU
reported `Couldn't find/access compatible USB device` while the phone was
plainly present on the host. With the mount, hotplug is live: DHU discovers the
phone, switches it into accessory mode itself (`supports AOAPv2, starting
accessory mode…`), and attaches. That part is solved and stays solved.

**What is not solved is what happens next.** After `Attached!` the DHU prints its
banner — its shutdown path — and exits, with no window and no projection on the
phone. Measured with the mount in place, DHU left running with no timeout, on a
verified display (Xwayland up, `glxinfo` reporting direct rendering, and the
earlier `SDL_CreateWindowRenderer failed` gone once GLX was installed).

Everything environmental has been eliminated: transport, device visibility,
display, GLX, `glibc` 2.39 ≥ the required 2.32, `libc++`, `libusb` beside the
binary, "Add new vehicles to Android Auto" enabled, head unit server running,
both USB and ADB transports. What remains is the DHU itself: **2.0 is the only
version Google ships, it is a 2022-03-30 build negotiating protocol 1.7, and the
phone runs Android Auto 17.3**. The phone's own logs would say more, but this
OPLUS build suppresses gearhead logging entirely — zero lines across all logcat
buffers even with Android Auto's "force debug logging" on.

So a `setup-dhu.sh` would install a tool that cannot currently project, and
maxke24/Detour#37 stays blocked on verification. The more promising route for
that issue is **Android Automotive OS**: Detour's `car/` code uses the Car App
Library, whose apps run natively on AAOS as well as over projection, and the SDK
ships `system-images;android-3x;android-automotive*`. That needs no phone, no
DHU and no USB at all. Whether Detour's manifest can target it is unchecked.
