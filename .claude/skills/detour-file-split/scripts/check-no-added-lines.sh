#!/usr/bin/env bash
#
# The zero-added-lines proof: a pure move must add NO lines to the file it moved out of.
#
# Why this exists as a script: it is the one verification that actually works here, it is run
# after every commit rather than once at the end, and it is easy to typo into a check that
# always passes. `grep -c '^+'` counts the `+++` file header and so never returns 0;
# `git diff <base> HEAD` and `git diff <base>..HEAD` differ if you are mid-rebase; forgetting
# `--` before the path silently reinterprets it as a revision. Each mistake produces a number
# that looks like a result.
#
# Why not rename detection: `git show -M -C` structurally cannot fire for this workflow. -M
# needs a DELETED blob to pair with an added one, and the source file is only ever modified.
# -C needs blob similarity above a threshold, and the "imports last" rule guarantees every new
# file carries import lines with no corresponding deletion in the source, diluting similarity
# below it. This was tested down to a 0% threshold during stage 1 and still reported separate
# M and A entries. Zero added lines is also the stronger guarantee: a 90% rename match still
# permits a tenth of the block to have changed.
#
# Read-only: runs `git diff`. Changes no files and no git state.
set -euo pipefail

usage() {
    cat >&2 <<'EOF'
usage: check-no-added-lines.sh <base> <source-file> [more-source-files...]

  base          the commit the split started from, e.g. the SHA before the first move
                commit, or a tag. Compared as <base>..HEAD.
  source-file   the file(s) symbols are being moved OUT of. Each must have zero added
                lines until the final import-cleanup commit.

Exits non-zero if any named file gained a line.
EOF
    exit 2
}

[ "$#" -ge 2 ] || usage
if [ "$1" = "-h" ] || [ "$1" = "--help" ]; then usage; fi

ROOT="$(git rev-parse --show-toplevel)"
cd "$ROOT"

BASE="$1"
shift
git rev-parse --verify --quiet "$BASE^{commit}" >/dev/null \
    || { echo "error: $BASE is not a commit" >&2; exit 2; }

fails=0
for f in "$@"; do
    if [ ! -e "$f" ]; then
        echo "error: no such file: $f (a moved-out-of file should still exist)" >&2
        fails=$((fails + 1))
        continue
    fi
    # [^+] excludes the +++ header line; the same trick on - excludes ---.
    diff_out="$(git diff "$BASE..HEAD" -- "$f")"
    added="$(printf '%s\n' "$diff_out" | grep -c '^+[^+]' || true)"
    removed="$(printf '%s\n' "$diff_out" | grep -c '^-[^-]' || true)"
    if [ "$added" -eq 0 ]; then
        printf 'PASS  %s: 0 added, %s removed\n' "$f" "$removed"
    else
        printf 'FAIL  %s: %s ADDED, %s removed\n' "$f" "$added" "$removed"
        fails=$((fails + 1))
    fi
done

printf '\nfiles touched across %s..HEAD:\n' "$BASE"
git diff --stat "$BASE..HEAD" | sed 's/^/  /'

if [ "$fails" -ne 0 ]; then
    cat >&2 <<'EOF'

A non-zero added count means a moved block was retyped, re-indented, or a "small fix" rode
along. Find it with:
    git diff <base>..HEAD -- <file> | grep '^+[^+]'
If the added lines are the import cleanup, this check has served its purpose and the split is
at its final commit — say so explicitly rather than ignoring the failure.
EOF
    exit 1
fi
