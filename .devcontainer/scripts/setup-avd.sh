#!/usr/bin/env bash
# Install the Android emulator and create an AVD to run Detour on. Opt-in:
# nothing calls this, and the image ships without any of it, because a ~1.5 GB
# system image is not something to impose on a developer who only builds.
#
#     .devcontainer/scripts/setup-avd.sh [avd-name]
#
# Run inside the devcontainer. Idempotent: sdkmanager skips installed packages
# and an existing AVD of the same name is left alone.
set -euo pipefail

AVD_NAME="${1:-detour}"

# google_apis, not google_apis_playstore and not the bare AOSP image: the app
# reads Play Services' fused location provider, which the AOSP image does not
# carry. The playstore variant would also work but adds the Play Store and
# cannot be rooted, and nothing here needs either.
SYSTEM_IMAGE="system-images;android-35;google_apis;x86_64"

# Matches compileSdk 35 in app/build.gradle.kts. If that moves, move this.
PLATFORM="platforms;android-35"

echo "== Installing emulator runtime dependencies =="
# libpulse0 is not optional and `-no-audio` does not avoid it: the emulator's
# qemu binary links libpulse.so.0 and dies at startup without it, with
# "error while loading shared libraries" and no other clue. Not part of
# setup-gui.sh because it is audio, not display — an emulator needs it even
# headless.
sudo apt-get update -qq
sudo apt-get install -y -qq --no-install-recommends libpulse0

echo "== Checking KVM =="
# The emulator runs unaccelerated without this and is unusable. /dev/kvm is
# passed through by the container's --privileged, so the check is about the host.
if [ ! -e /dev/kvm ]; then
    echo "ERROR: /dev/kvm is not present in the container." >&2
    echo "The host needs KVM, and the user needs access to it." >&2
    exit 1
fi

echo "== Installing emulator and system image (this is the ~1.5 GB step) =="
# `yes |` is how the licence prompts get answered, and it exits non-zero on
# SIGPIPE the moment sdkmanager stops reading — which is not a failure, but
# under `set -o pipefail` it fails the pipeline and `set -e` then exits the
# script with no output at all. The Dockerfile hits the same thing and says so.
# Take sdkmanager's own status out of PIPESTATUS instead of trusting the pipe.
set +o pipefail
yes 2>/dev/null | sdkmanager "emulator" "${SYSTEM_IMAGE}" "${PLATFORM}" >/dev/null
sdk_rc=${PIPESTATUS[1]}
set -o pipefail
if [ "${sdk_rc}" -ne 0 ]; then
    echo "ERROR: sdkmanager failed (exit ${sdk_rc})." >&2
    exit 1
fi
echo "installed."

echo
echo "== Verifying acceleration =="
"${ANDROID_HOME}/emulator/emulator" -accel-check

echo
echo "== Creating the AVD =="
if avdmanager list avd 2>/dev/null | grep -q "Name: ${AVD_NAME}$"; then
    echo "AVD '${AVD_NAME}' already exists — leaving it alone."
else
    echo no | avdmanager create avd \
        -n "${AVD_NAME}" \
        -k "${SYSTEM_IMAGE}" \
        -d pixel_6 >/dev/null
    echo "created '${AVD_NAME}'."
fi

echo
echo "Next:"
echo "    .devcontainer/scripts/setup-gui.sh      # once, if you have not already"
echo "    .devcontainer/scripts/start-avd.sh ${AVD_NAME}"
