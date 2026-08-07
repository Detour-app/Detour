#!/usr/bin/env bash
# Render every Play declaration video from its raw take, one at a time.
#
# Always go through this rather than calling record_permission_video.sh by
# hand: that script defaults FINAL to permission-video.mp4, so a bare
# `caption <some-other-raw>` silently overwrites the trip-recording video with
# whatever footage you passed. Running two renders at once is just as bad —
# they write the same file and the result is a truncated mess.
set -euo pipefail
cd "$(dirname "$0")/.."

if pgrep -x ffmpeg >/dev/null; then
    echo "an ffmpeg is already running; refusing to start another" >&2
    exit 1
fi

render() {  # render <raw> <final> [captions-file]
    local raw="$1" final="$2" caps="${3:-}"
    [[ -f "$raw" ]] || { echo "missing raw: $raw" >&2; return 1; }
    echo "==> $final"
    CAPTIONS_FILE="$caps" FINAL="$final" \
        ./tools/record_permission_video.sh caption "$raw"
    ffprobe -v error -show_entries format=duration -of csv=p=0 "$final" \
        >/dev/null || { echo "$final did not encode cleanly" >&2; return 1; }
}

render docs/play/permission-video-raw.mp4 docs/play/permission-video.mp4
render docs/play/navigation-video-raw.mp4 docs/play/navigation-video.mp4 \
       docs/play/captions-navigation.txt
render docs/play/convoy-video-raw.mp4 docs/play/convoy-video.mp4 \
       docs/play/captions-convoy.txt

echo
for f in docs/play/permission-video.mp4 docs/play/navigation-video.mp4 \
         docs/play/convoy-video.mp4; do
    printf '%-38s %6ss  %s\n' "$f" \
        "$(ffprobe -v error -show_entries format=duration -of csv=p=0 "$f" | cut -d. -f1)" \
        "$(stat -c %s "$f" | numfmt --to=iec)"
done
