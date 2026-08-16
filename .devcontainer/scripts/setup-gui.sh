#!/usr/bin/env bash
# Install the display stack the emulator and the DHU need. Opt-in: nothing calls
# this, and the image does not carry these packages, because building or testing
# the app needs none of them.
#
#     .devcontainer/scripts/setup-gui.sh
#
# Run inside the devcontainer. Idempotent — apt skips what is already present.
set -euo pipefail

# Every package here was added because something measurably failed without it,
# not to be thorough:
#
#   xwayland        the X server itself; the container has none
#   libegl1
#   libegl-mesa0    Xwayland aborts at startup with "Couldn't open libEGL.so.1"
#   libgl1
#   libglx-mesa0    without GLX the DHU fails with "SDL_CreateWindowRenderer
#   libgl1-mesa-dri failed: Couldn't find matching render driver"; with it,
#   libgbm1         glxinfo reports the host GPU and direct rendering
#   x11-utils       xdpyinfo, which gui-env.sh uses to test whether X is up
PACKAGES=(
    xwayland
    libegl1
    libegl-mesa0
    libgl1
    libglx-mesa0
    libgl1-mesa-dri
    libgbm1
    x11-utils
)

echo "Installing the display stack (${#PACKAGES[@]} packages)…"
sudo apt-get update -qq
sudo apt-get install -y --no-install-recommends "${PACKAGES[@]}"

echo
echo "Done. Check it with:"
echo "    source .devcontainer/scripts/gui-env.sh && glxinfo -B | head -5"
echo
echo "A working result names the host GPU and says 'direct rendering: Yes'."
echo "Measured on one machine, for reference: AMD Radeon 780M via /dev/dri/renderD128."
