#!/usr/bin/env bash
# Put a GUI display within reach of the devcontainer. Source it, do not run it:
#
#     source .devcontainer/scripts/gui-env.sh
#
# Exports WAYLAND_DISPLAY, XDG_RUNTIME_DIR and DISPLAY, starting Xwayland if a
# caller needs X11. Idempotent: sourcing it twice starts nothing twice.
#
# Nothing here changes devcontainer.json, and nothing here is needed to build or
# test the app — it exists for the two tools that draw windows, the Android
# emulator and the Desktop Head Unit.
#
# ## Why there is no mount to add
#
# VS Code already bind-mounts the host's Wayland socket into the container, at a
# generated path rather than the usual one:
#
#     /run/user/1000/wayland-1  ->  /tmp/vscode-wayland-<uuid>.sock
#
# So Wayland works out of the box; what is missing is only the two environment
# variables that point at it, and they are missing because `docker exec` runs a
# non-interactive shell that VS Code's remoteEnv never reaches. Hence a shim
# rather than a config change. The uuid differs per container instance, so the
# socket has to be discovered rather than hard-coded.
#
# ## Why Xwayland is started here rather than assumed
#
# The container has no X server of its own. Wayland-native clients need none,
# but neither tool this exists for is Wayland-native:
#
#   - the Android emulator is Qt-based and takes the X11 path here;
#   - the DHU statically links an SDL from its 2022 build, which reports
#     "SDL_Init failed: wayland not available".
#
# Xwayland connects to the host compositor as an ordinary Wayland client, so an
# X11 app in the container lands on the host desktop like any other window.
#
# Deliberately no `set -u`/`set -e`: this file is sourced, so shell options set
# here would leak into the caller's interactive shell and stay there.

_gui_warn() { printf 'gui-env: %s\n' "$1" >&2; }

# --- Wayland ---------------------------------------------------------------

_gui_sock="$(ls -1 /tmp/vscode-wayland-*.sock 2>/dev/null | head -1 || true)"
if [ -n "${_gui_sock}" ]; then
    # Wayland requires XDG_RUNTIME_DIR to contain the socket. VS Code puts it in
    # /tmp, so that is the directory to name — not a runtime dir of our own.
    export XDG_RUNTIME_DIR=/tmp
    export WAYLAND_DISPLAY="$(basename "${_gui_sock}")"
else
    _gui_warn "no /tmp/vscode-wayland-*.sock found — is this container running under VS Code on a Wayland host?"
fi

# --- Xwayland --------------------------------------------------------------

: "${GUI_X_DISPLAY:=:9}"

_gui_xwayland_up() {
    command -v xdpyinfo >/dev/null 2>&1 || return 1
    DISPLAY="${GUI_X_DISPLAY}" xdpyinfo >/dev/null 2>&1
}

if _gui_xwayland_up; then
    export DISPLAY="${GUI_X_DISPLAY}"
elif command -v Xwayland >/dev/null 2>&1 && [ -n "${_gui_sock}" ]; then
    # Xwayland aborts with "Owner of /tmp/.X11-unix should be set to root" when
    # that directory belongs to the container user, which is how it ships here.
    # Correcting it is what the sudo is for; it is not otherwise needed.
    if [ ! -d /tmp/.X11-unix ] || [ "$(stat -c %u /tmp/.X11-unix 2>/dev/null)" != "0" ]; then
        sudo mkdir -p /tmp/.X11-unix 2>/dev/null || true
        sudo chown root:root /tmp/.X11-unix 2>/dev/null || true
        sudo chmod 1777 /tmp/.X11-unix 2>/dev/null || true
    fi
    nohup Xwayland "${GUI_X_DISPLAY}" >/tmp/xwayland.log 2>&1 &
    for _ in 1 2 3 4 5 6 7 8 9 10; do
        _gui_xwayland_up && break
        sleep 0.5
    done
    if _gui_xwayland_up; then
        export DISPLAY="${GUI_X_DISPLAY}"
    else
        _gui_warn "Xwayland did not come up on ${GUI_X_DISPLAY}; see /tmp/xwayland.log"
        _gui_warn "the usual cause is a missing libEGL — run .devcontainer/scripts/setup-gui.sh"
    fi
else
    _gui_warn "Xwayland is not installed — run .devcontainer/scripts/setup-gui.sh if you need X11"
fi

unset _gui_sock
