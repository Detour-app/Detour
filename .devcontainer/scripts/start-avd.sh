#!/usr/bin/env bash
# Boot an AVD with its window on the host desktop.
#
#     .devcontainer/scripts/start-avd.sh [avd-name] [extra emulator args…]
#
# Run inside the devcontainer, after setup-avd.sh and setup-gui.sh.
#
# The emulator registers with the *host's* adb server, because the container
# runs --network=host. So `adb devices` on the host lists it as emulator-5554
# alongside any physical phone, and the GPS replay harness in
# .claude/skills/detour-gps-replay works against it unchanged.
set -euo pipefail

AVD_NAME="${1:-detour}"
shift || true

cd "$(dirname "${BASH_SOURCE[0]}")/../.."
# shellcheck source=/dev/null
source .devcontainer/scripts/gui-env.sh

if [ -z "${DISPLAY:-}" ]; then
    echo "ERROR: no X display. Run .devcontainer/scripts/setup-gui.sh first." >&2
    exit 1
fi

if ! avdmanager list avd 2>/dev/null | grep -q "Name: ${AVD_NAME}$"; then
    echo "ERROR: no AVD named '${AVD_NAME}'. Run .devcontainer/scripts/setup-avd.sh first." >&2
    exit 1
fi

# swiftshader_indirect rather than 'host': the container reaches the GPU through
# /dev/dri/renderD128 and glxinfo confirms direct rendering, but the emulator's
# own GL path is a separate question from Xwayland's and is not verified here.
# Swap in `-gpu host` if you want to try it — it is one argument.
echo "Booting '${AVD_NAME}' on ${DISPLAY}…"
exec "${ANDROID_HOME}/emulator/emulator" \
    -avd "${AVD_NAME}" \
    -gpu swiftshader_indirect \
    -no-snapshot \
    -no-audio \
    "$@"
