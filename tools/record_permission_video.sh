#!/usr/bin/env bash
# Records the background-location justification video Play Console asks for,
# and burns the captions in. See docs/PLAY_LOCATION_DECLARATION.md for what the
# reviewer needs to see and why.
#
#   ./tools/record_permission_video.sh            # record, then caption
#   ./tools/record_permission_video.sh caption in.mp4   # caption an existing take
#
# Needs a real device on adb (the emulator's screenrecord is unreliable) and
# ffmpeg on the host.
set -euo pipefail

# The debug variant by default: it carries the .debug applicationId suffix, so
# it installs alongside the real app and `pm clear` below cannot touch the trip
# history in it. Pass a different id as APP_ID=... to record the release build,
# but only on a phone whose history you are willing to lose.
APP_ID="${APP_ID:-io.github.maxke24.detour.debug}"
OUT_DIR="docs/play"
RAW="${RAW:-$OUT_DIR/permission-video-raw.mp4}"
FINAL="${FINAL:-$OUT_DIR/permission-video.mp4}"
# A second video is needed for the Navigation box on the foreground-service
# form; point CAPTIONS_FILE at a "start:end:text" list to caption that one
# with the same pipeline. See docs/PLAY_LOCATION_DECLARATION.md.
CAPTIONS_FILE="${CAPTIONS_FILE:-}"
FONT="/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf"

# start,end,text — the shot list from docs/PLAY_LOCATION_DECLARATION.md.
# Edit the times to match your take before running the caption step.
# "|" starts a new caption line; keep each line under ~30 characters so it
# fits the width of a portrait phone frame.
CAPTIONS=(
  "0:10:Detour - first launch,|no permissions granted"
  "10:21:Standard runtime prompts:|location, activity, notifications"
  "21:31:The app's own disclosure, shown|BEFORE the background request"
  "31:42:Android's background location|setting - Allow all the time"
  "42:58:The phone is now moving|at driving speed"
  "58:72:Trip starts on its own -|time, distance, speed, cornering g"
  "72:92:Detour is no longer in the|foreground. Recording continues."
  "92:104:Back in the app - timer and distance|moved on while it was in background"
  "104:125:Trip saved to history with its|route, distance and speed"
)

die() { echo "$*" >&2; exit 1; }

step() {
  echo
  echo "=== $1"
  shift
  for line in "$@"; do echo "    $line"; done
  read -rp "    press enter when done > " _
}

caption() {
  local src="$1"
  [[ -f "$src" ]] || die "no such file: $src"
  [[ -f "$FONT" ]] || die "font not found: $FONT (install fonts-dejavu-core)"

  if [[ -n "$CAPTIONS_FILE" ]]; then
    [[ -f "$CAPTIONS_FILE" ]] || die "no such captions file: $CAPTIONS_FILE"
    mapfile -t CAPTIONS < <(grep -v '^\s*\(#\|$\)' "$CAPTIONS_FILE")
    echo "using ${#CAPTIONS[@]} captions from $CAPTIONS_FILE"
  fi

  # Captions live in a black strip added below the frame, never on top of the
  # UI — a caption over a permission dialog hides the very thing the reviewer
  # is checking.
  # Scale first, then pad and draw. Doing it the other way round runs every
  # drawtext over a full 1440-wide frame and turns a two-minute render into a
  # half-hour one.
  local filter="scale=900:-2,pad=iw:ih*10/9:0:0:black" sep=","
  for c in "${CAPTIONS[@]}"; do
    local start="${c%%:*}" rest="${c#*:}"
    local end="${rest%%:*}" text="${rest#*:}"
    text="${text//:/\\:}"
    text="${text//\'/}"
    text="${text//|/$'\n'}"
    filter+="${sep}drawtext=fontfile=${FONT}:text='${text}'"
    filter+=":fontcolor=white:fontsize=w/30:line_spacing=10"
    filter+=":x=(w-text_w)/2:y=h-(h/20)-text_h/2"
    filter+=":enable='between(t,${start},${end})'"
    sep=","
  done

  # -r 30 matters more than it looks. screenrecord writes variable-rate video
  # against a 90000 Hz timebase, and a take with a constantly animating map
  # comes out around 77 fps — more than twice the frames of a take that mostly
  # sits on static UI. Without a cap, that clip encodes for the better part of
  # an hour and lands in the hundreds of megabytes. 30 fps is plenty for a
  # screen demo.
  echo "burning captions into $FINAL"
  ffmpeg -y -hide_banner -loglevel error -i "$src" -vf "$filter" \
    -r 30 -c:v libx264 -preset medium -crf 23 -pix_fmt yuv420p -an "$FINAL"
  echo "done: $FINAL"
  echo "upload unlisted to YouTube, paste the link in the Play declaration."
}

if [[ "${1:-}" == "caption" ]]; then
  caption "${2:-$RAW}"
  exit 0
fi

command -v ffmpeg >/dev/null || die "ffmpeg not on PATH"
adb get-state >/dev/null 2>&1 || die "no device on adb. plug a phone in (usb debugging on)."
mkdir -p "$OUT_DIR"

adb shell pm list packages | grep -qx "package:$APP_ID" \
  || die "$APP_ID is not installed. run: ./gradlew :app:installDebug"

cat <<EOF
Recording the Play background-location video from $APP_ID.

Turn on a do-not-disturb profile first so no notification from another app
lands in the frame. If two Detour icons show in the launcher, start the one
this script just cleared - the other is your real install.

EOF
read -rp "press enter to reset app data and start > " _

echo "clearing $APP_ID data so the permission prompts fire fresh"
adb shell pm clear "$APP_ID" >/dev/null

adb shell screenrecord --bit-rate 8000000 --time-limit 180 /sdcard/detour-perm.mp4 &
REC_PID=$!
sleep 2
echo "recording."

step "shot 1 - first launch (about 10s)" \
  "open Detour from the launcher" \
  "let the map screen appear, do not tap anything yet"

step "shot 2 - three system prompts, then our disclosure (about 20s)" \
  "location prompt: Precise, 'While using the app'" \
  "physical activity prompt: Allow" \
  "notifications prompt: Allow" \
  "the 'Record rides in the background' disclosure now appears - this is the" \
  "  shot the reviewer is looking for, so hold on it long enough to read" \
  "tap Allow, then choose 'Allow all the time' in the system dialog"

step "shot 3 - background the app (about 15s)" \
  "start a trip from the map, so there is something to record" \
  "press home" \
  "leave the launcher on screen so it is obvious the app is not in front"

step "shot 4 - trip records in the background (about 20s)" \
  "walk or drive far enough for the track to move - a minute on foot is fine" \
  "pull down the shade to show the ongoing-trip notification" \
  "keep the shade open a few seconds"

step "shot 5 - the result (about 20s)" \
  "reopen Detour, go to history, open the trip just recorded" \
  "show the route shape, distance and speed" \
  "go back to the map and show the uncovered fog-of-war area"

echo "stopping recording"
adb shell pkill -INT screenrecord || true
wait "$REC_PID" 2>/dev/null || true
sleep 3

adb pull /sdcard/detour-perm.mp4 "$RAW"
adb shell rm /sdcard/detour-perm.mp4
echo "raw take: $RAW"

echo
echo "check the take, then fix the caption times at the top of this script"
echo "to match what you actually recorded, and run:"
echo "  $0 caption $RAW"
