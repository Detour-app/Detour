#!/usr/bin/env bash
#
# The free half of Tier 0: the greps that belong on every commit touching this app's state
# machinery. Run it after each commit, not once at the end.
#
# Why a script: each check is a comparison against the base commit, not a number you can read
# off the working tree. "The rememberUpdatedState count must not drop" is meaningless without
# the before-count, so doing this by hand means two greps and a subtraction per file, which is
# exactly the kind of arithmetic that gets skipped on commit nine of twelve. Deleting a
# rememberUpdatedState is a behaviour change with no compiler signal at all.
#
# What it deliberately does NOT run: `./gradlew :app:assembleDebug :app:assembleRelease` and
# the two test tasks. They are the other half of Tier 0 and they are not optional — but they
# are single commands whose output you have to read, they take minutes, and in this project
# Gradle runs inside the devcontainer. Run them yourself; this script prints the reminder.
#
# Read-only: `git diff` / `git show` and greps. Changes no files and no git state.
set -euo pipefail

usage() {
    cat >&2 <<'EOF'
usage: tier0-greps.sh <base> [file...]

  base    the commit to compare against, e.g. the commit before this stage's first commit.
  file    files to check. Defaults to every .kt file changed in <base>..HEAD.

Exits non-zero if a rememberUpdatedState count dropped, a new class owns a CoroutineScope,
or a Dispatchers reference appeared in shared/src/commonMain.
EOF
    exit 2
}

[ "$#" -ge 1 ] || usage
if [ "$1" = "-h" ] || [ "$1" = "--help" ]; then usage; fi

ROOT="$(git rev-parse --show-toplevel)"
cd "$ROOT"

BASE="$1"
shift
git rev-parse --verify --quiet "$BASE^{commit}" >/dev/null \
    || { echo "error: $BASE is not a commit" >&2; exit 2; }

if [ "$#" -gt 0 ]; then
    files=("$@")
else
    mapfile -t files < <(git diff --name-only "$BASE..HEAD" -- '*.kt')
fi
if [ "${#files[@]}" -eq 0 ]; then
    echo "no .kt files changed in $BASE..HEAD — nothing to check"
    exit 0
fi

fails=0
countfile() { grep -c "$1" "$2" 2>/dev/null || true; }
countbase() { git show "$BASE:$2" 2>/dev/null | grep -c "$1" || true; }

echo "== rememberUpdatedState must not drop (a deletion is a behaviour change) =="
for f in "${files[@]}"; do
    [ -f "$f" ] || continue
    before="$(countbase 'rememberUpdatedState' "$f")"
    after="$(countfile 'rememberUpdatedState' "$f")"
    [ "$before" = 0 ] && [ "$after" = 0 ] && continue
    if [ "$after" -lt "$before" ]; then
        printf 'FAIL  %s: %s -> %s\n' "$f" "$before" "$after"
        fails=$((fails + 1))
    else
        printf 'PASS  %s: %s -> %s\n' "$f" "$before" "$after"
    fi
done

echo
echo "== a new non-Compose class must not own a scope; hand it one =="
# rememberCoroutineScope() is excluded: it is the correct Compose idiom and it contains the
# literal 'CoroutineScope(', so the raw grep from the skill flags every composable that uses
# it. What this is looking for is a plain class constructing its own scope.
scope_hits() { grep -n 'CoroutineScope(' "$1" 2>/dev/null | grep -v 'rememberCoroutineScope' || true; }
mapfile -t added < <(git diff --name-only --diff-filter=A "$BASE..HEAD" -- '*.kt')
if [ "${#added[@]}" -eq 0 ]; then
    echo "(no files added in this range)"
else
    for f in "${added[@]}"; do
        [ -f "$f" ] || continue
        hits="$(scope_hits "$f")"
        if [ -n "$hits" ]; then
            printf 'FAIL  %s owns a CoroutineScope:\n' "$f"
            printf '%s\n' "$hits" | sed 's/^/        /'
            fails=$((fails + 1))
        else
            printf 'PASS  %s\n' "$f"
        fi
    done
fi

echo
echo "== the shared core has no dispatchers; I/O is handed in =="
hits="$(grep -rn 'Dispatchers' shared/src/commonMain 2>/dev/null || true)"
if [ -n "$hits" ]; then
    echo "FAIL  shared/src/commonMain references Dispatchers:"
    printf '%s\n' "$hits" | sed 's/^/        /'
    fails=$((fails + 1))
else
    echo "PASS  shared/src/commonMain: 0"
fi

echo
echo "== listeners: additions and removals should move together =="
for f in "${files[@]}"; do
    [ -f "$f" ] || continue
    add_b="$(countbase 'addOn.*Listener' "$f")"
    add_a="$(countfile 'addOn.*Listener' "$f")"
    rem_b="$(countbase 'removeOn.*Listener' "$f")"
    rem_a="$(countfile 'removeOn.*Listener' "$f")"
    [ "$add_b" = 0 ] && [ "$add_a" = 0 ] && continue
    if [ "$add_a" -gt "$add_b" ] && [ "$rem_a" -le "$rem_b" ]; then
        printf 'WARN  %s: add %s -> %s but remove %s -> %s\n' "$f" "$add_b" "$add_a" "$rem_b" "$rem_a"
    else
        printf 'ok    %s: add %s -> %s, remove %s -> %s\n' "$f" "$add_b" "$add_a" "$rem_b" "$rem_a"
    fi
done

echo
echo "== effect declarations touched in this range (read these; a key list IS behaviour) =="
touched="$(git diff -U0 "$BASE..HEAD" -- '*.kt' \
    | grep -E '^[+-][^+-].*(LaunchedEffect\(|DisposableEffect\()' || true)"
if [ -n "$touched" ]; then
    printf '%s\n' "$touched" | sed 's/^/    /'
    echo
    echo "    For each key added or removed, answer in the commit message: what does"
    echo "    restarting this coroutine destroy, and what stops updating? An effect whose"
    echo "    body holds coroutine-local accumulators loses them on every restart, and no"
    echo "    test in this repo can reach them. A key-list change never shares a commit with"
    echo "    a move of that effect's body."
else
    echo "(no LaunchedEffect/DisposableEffect declaration lines changed)"
fi

cat <<'EOF'

Still owed, and not run here (Gradle runs in the devcontainer):
    ./gradlew :app:assembleDebug :app:assembleRelease      # R8 catches what debug does not
    ./gradlew :app:testDebugUnitTest :shared:testDebugUnitTest
For a pure move, also: detour-file-split/scripts/check-no-added-lines.sh <base> <source-file>
None of this proves behaviour. A change to a lastFix consumer or to the camera earns Tier 2 —
a GPS replay A/B against the baseline. If you cannot run it, say the change is unverified.
EOF

exit $((fails > 0 ? 1 : 0))
