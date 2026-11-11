"""One summary.tsv line from one row's raw captures (battery-profile.sh).

    python3 -I summarize.py <row> <package> <pid> <uid> <seconds> <out-dir>

Every number comes from a file battery-profile.sh left in <out-dir>, so a summary can be
recomputed, or a parser fixed, without re-running the profile.
"""

import re
import sys
from pathlib import Path


def ticks_per_s(atrace: str, pid: str, seconds: int) -> str:
    # "<comm>-<tid> (<tgid>) [cpu] flags ts: tracing_mark_write: B|<pid>|Choreographer#doFrame <vsync id>"
    # The main thread is the one whose tid equals the pid. Only the top-level slice counts:
    # "Choreographer#doFrame - resynced to ..." is nested inside one and would double it.
    m = re.search(r"entries-in-buffer/entries-written: (\d+)/(\d+)", atrace)
    if m and m.group(1) != m.group(2):
        print(f"warning: atrace buffer wrapped ({m.group(1)}/{m.group(2)} entries kept); "
              "ticks_per_s is not reported", file=sys.stderr)
        return "n/a"
    frame = re.compile(rf"-{pid}\s+\(\s*{pid}\).*B\|{pid}\|Choreographer#doFrame \d+\s*$")
    n = sum(1 for line in atrace.splitlines() if frame.search(line))
    return f"{n / seconds:.1f}"


def main_cpu(top: str, pid: str) -> str:
    # top -H -b -n 2: the second block is the average over the -d interval.
    rows = [line.split() for line in top.splitlines()]
    main = [r for r in rows if len(r) >= 2 and r[0] == pid]
    if not main:
        return "n/a"
    return main[-1][1]


def surface_fps(timestats: str, package: str, seconds: int) -> str:
    # Per-layer blocks start with "layerName = ..." and carry "totalFrames = N". The map is a
    # SurfaceView, so its layer is named after the package with "SurfaceView" in it; when there
    # is none, fall back to the app's busiest layer.
    best_sv, best_any = 0, 0
    layer = ""
    for line in timestats.splitlines():
        line = line.strip()
        if line.startswith("layerName"):
            layer = line.split("=", 1)[1].strip()
        elif line.startswith("totalFrames") and package in layer:
            frames = int(line.split("=", 1)[1].strip())
            best_any = max(best_any, frames)
            if "SurfaceView" in layer:
                best_sv = max(best_sv, frames)
    frames = best_sv or best_any
    return f"{frames / seconds:.1f}" if frames else "n/a"


def checkin_times(checkin: str, uid: str) -> tuple[str, str]:
    # Checkin rows: 9,<uid>,l,wl,<name>,<full_ms>,f,...,<partial_ms>,p,... (each time field
    # precedes its type letter) and 9,<uid>,l,sr,<sensor handle>,<ms>,<count>,...
    wl_ms = sr_ms = 0
    for line in checkin.splitlines():
        f = line.strip().split(",")
        if len(f) < 6 or f[1] != uid or f[2] != "l":
            continue
        if f[3] == "wl" and "p" in f[5:]:
            partial = f[f.index("p", 5) - 1]
            wl_ms += int(partial) if partial.isdigit() else 0
        elif f[3] == "sr":
            sr_ms += int(f[5]) if f[5].isdigit() else 0
    return f"{wl_ms / 1000:.1f}", f"{sr_ms / 1000:.1f}"


def main() -> None:
    row, package, pid, uid, seconds, out = sys.argv[1:7]
    secs = int(seconds)
    d = Path(out)
    read = lambda name: (d / f"{row}-{name}").read_text(errors="replace")
    wl, sr = checkin_times(read("batterystats-checkin.txt"), uid)
    print("\t".join([
        row,
        package,
        "no" if row == "navigated" else "yes",
        seconds,
        ticks_per_s(read("atrace.txt"), pid, secs),
        main_cpu(read("top.txt"), pid),
        surface_fps(read("timestats.txt"), package, secs),
        wl,
        sr,
    ]))


if __name__ == "__main__":
    main()
