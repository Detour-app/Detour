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

The reason a script would not help yet is structural: **the container's `/dev`
is a tmpfs populated at container start**, so USB hotplug never reaches it. A
phone plugged in after the container started does not exist in the container's
device tree, and every AOAP mode switch re-enumerates it to a new device number
that likewise does not appear. `--privileged` does not fix this — it grants
access to nodes that exist, not nodes that appear later. The fix is a bind mount
of `/dev/bus/usb`, which is a `devcontainer.json` change and the only one this
whole investigation actually justified.

Until that mount exists and the hang after `Attached!` is understood, a
`setup-dhu.sh` would be a script that cannot do its job. See
maxke24/Detour#37, which is blocked on exactly this.
