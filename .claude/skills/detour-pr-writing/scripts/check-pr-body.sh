#!/usr/bin/env bash
#
# Lint a PR description against the shape this repo uses.
#
# Why this exists as a script: the failure mode is not a missing section, it is a body that
# reads fine sentence by sentence and is twice as long as it needs to be. Process narration
# is invisible while you are writing it — you are the one person who finds your own process
# interesting — and obvious to everyone else. A grep catches the phrasings that give it away
# faster than re-reading ever does.
#
# This is a linter, not a judge. Every hit is a question, not a verdict: a flagged line can
# be exactly right. Read what it says and decide. It exits non-zero only when something is
# structurally missing, never on a phrasing hit alone.
#
# Read-only: reads one file, writes nothing.
#
# usage: check-pr-body.sh <body.md>
set -euo pipefail

if [ "$#" -ne 1 ] || [ "$1" = "-h" ] || [ "$1" = "--help" ]; then
    echo "usage: $(basename "$0") <body.md>" >&2
    echo "  Lints a PR description. Phrasing hits are advisory; missing structure fails." >&2
    exit 2
fi

BODY="$1"
[ -r "$BODY" ] || { echo "cannot read $BODY" >&2; exit 2; }

fails=0
note() { printf '  %s\n' "$1"; }

hits() {   # label, regex
    local label="$1" re="$2" out
    out=$(grep -nEi "$re" "$BODY" || true)
    if [ -n "$out" ]; then
        printf '\n%s\n' "$label"
        printf '%s\n' "$out" | sed 's/^/    /' | cut -c1-110
        return 0
    fi
    return 1
}

echo "=== $BODY ==="

words=$(wc -w < "$BODY")
printf '\nLength: %s words' "$words"
if [ "$words" -gt 600 ]; then
    printf '  <- over ~600; something in here is probably narration\n'
else
    printf '\n'
fi

# --- structure: these can actually fail the check -------------------------------------

if ! grep -qEi '^\s*(closes|fixes|resolves) #[0-9]+' "$BODY"; then
    printf '\nNo "Closes #NN" line.\n'
    note 'If this PR resolves an issue, say so on the first line so the linkage is real.'
    note 'If it genuinely resolves none, ignore this.'
    fails=$((fails + 1))
fi

if ! grep -qE '^\s*\|.*\|' "$BODY"; then
    printf '\nNo table found.\n'
    note 'Lead with measured before/after where there is anything to measure — a benchmark,'
    note 'a value read off the running system, a count. If the change genuinely has nothing'
    note 'measurable (a rename, a doc edit), this is fine to ignore.'
    fails=$((fails + 1))
fi

if ! grep -qEi '^#+ *(known limits|not proven|limitations|what is not)' "$BODY"; then
    printf '\nNo "Known limits" section.\n'
    note 'What is unproven, what it cost, what is untested. Keep it even when the news is'
    note 'good: a reviewer who finds an unstated limit themselves stops trusting the rest.'
    fails=$((fails + 1))
fi

# --- phrasing: advisory only ----------------------------------------------------------

found_phrasing=0

hits 'Process narration — the reviewer is reviewing the diff, not your process:' \
    '\b(earlier draft|initially (I|believed|thought)|(I|we) (then )?(corrected|realised|realized|discovered)|after review|review (caught|found)|re-?(ran|measured|measurement)|it (emerged|turned out)|during (the )?review|my (earlier|original|first) (claim|approach|explanation))\b' \
    && found_phrasing=1

hits 'Telling the reader what to be impressed by — let the numbers do it:' \
    '\b(worth pausing|the headline|for the first time|significant(ly)? (improve|better)|substantial improvement|one of the more (impactful|important)|it is worth (noting|remembering)|notably)\b' \
    && found_phrasing=1

hits 'Hedging — either state it, or move it under Known limits:' \
    '\b(arguably|it could be said|(I|we) believe|presumably|seems to (be|suggest)|somewhat|fairly (clearly|obviously)|to some extent)\b' \
    && found_phrasing=1

hits 'Narrating the measurement instead of reporting it:' \
    '\b(instrumentation was (added|removed|re-?applied)|then re-?measured|numbers were re-?earned|measured again|a second (run|pass) confirmed)\b' \
    && found_phrasing=1

# --- follow-ups -----------------------------------------------------------------------

refs=$(grep -oE '#[0-9]+' "$BODY" | sort -u | wc -l)
if [ "$refs" -le 1 ]; then
    printf '\nOnly one issue referenced.\n'
    note 'If the work turned up problems that are out of scope, file them and point at them'
    note 'with one line each saying WHY they are not in this PR. A reason ("needs a head'
    note 'unit to verify") reads very differently from "deferred".'
fi

if grep -qEi '\bdeferred\b' "$BODY"; then
    printf '\n"deferred" appears.\n'
    note 'Prefer the actual reason. "Needs a head unit to verify" says something; "deferred"'
    note 'says only that you chose not to.'
fi

# --- verdict --------------------------------------------------------------------------

printf '\n'
if [ "$fails" -gt 0 ]; then
    echo "$fails structural item(s) missing — see above. Each may be legitimately N/A."
    exit 1
fi
if [ "$found_phrasing" -eq 1 ]; then
    echo "Structure OK. Phrasing hits above are advisory — read them and decide."
else
    echo "OK: structure present, no narration phrasings found."
fi
