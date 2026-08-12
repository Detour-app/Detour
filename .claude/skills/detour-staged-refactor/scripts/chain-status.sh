#!/usr/bin/env bash
#
# Report where a refactor chain actually is: each stage's Status line, and whether that
# stage's own Preconditions block passes against the tree right now.
#
# Why this exists: the protocol says to run a stage's preconditions before writing its plan,
# and again the moment the previous stage lands. Both are skipped because both mean copying a
# dozen assertions out of a Markdown fence by hand. This repo has already paid for that twice
# — a completed stage whose spec still read "not started", and a precondition that was wrong
# when written (`leadingSpinIndex # expect 1`, true value 2) and would have declared a
# current spec stale on its first honest run.
#
# It derives everything rather than trusting the Status blocks, because the Status blocks have
# drifted before. The assertions it runs are the ones written in the specs themselves, so this
# script never becomes a second copy that can rot separately.
#
# How it judges: an assertion ending in `# expect N`, `# expect >= N` or `# expect: word` is
# compared to the command's first line of output. Anything else — notably `# expect line 213`
# — is reported as INFO, on purpose: line numbers drifting by a constant is expected drift,
# not staleness. Staleness is a symbol that is missing or a count that changed.
#
# A precondition failure is a hypothesis, not a verdict: either the code moved (the spec is
# stale, rewrite it) or the assertion was wrong when written (fix the assertion in its own
# commit and say why in the Status block). Do not adapt a plan to the drift.
#
# Read-only in intent: it executes the shell inside each spec's Preconditions fence, which in
# this chain is greps, `wc`, `test` and `ls`. Use --dry-run to print the blocks instead of
# running them if you do not trust a spec you have not read.
set -euo pipefail

usage() {
    cat >&2 <<'EOF'
usage: chain-status.sh [stage] [--chain NAME] [--dry-run]

  stage       run only this stage (0-4, or a spec filename fragment) and EXIT NON-ZERO if
              its preconditions fail. Use this as the gate before writing that stage's plan.
              With no stage, every spec is reported and the exit status is 0.
  --chain     chain directory under docs/refactor/, default: mapscreen
  --dry-run   print each Preconditions block without executing it
EOF
    exit 2
}

CHAIN=mapscreen
STAGE=""
DRY=0
while [ "$#" -gt 0 ]; do
    case "$1" in
        -h | --help) usage ;;
        --dry-run) DRY=1 ;;
        --chain) [ "$#" -ge 2 ] || usage; CHAIN="$2"; shift ;;
        -*) usage ;;
        *) [ -z "$STAGE" ] || usage; STAGE="$1" ;;
    esac
    shift
done

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../../.." && pwd)"
cd "$ROOT"

SPECS="docs/refactor/$CHAIN/specs"
if [ ! -d "$SPECS" ]; then
    echo "error: no chain at $SPECS" >&2
    echo "       If the chain was completed or abandoned, this skill is archaeology — say" >&2
    echo "       so rather than executing a protocol for work that is over." >&2
    exit 1
fi

mapfile -t files < <(ls "$SPECS"/stage-*.md 2>/dev/null | sort)
if [ "${#files[@]}" -eq 0 ]; then
    echo "error: $SPECS contains no stage-*.md" >&2
    exit 1
fi

# Pull the fenced sh block that follows "## Preconditions", joining backslash continuations.
extract_block() {
    awk '
        /^## Preconditions/ { inSec = 1; next }
        inSec && /^```/     { if (inFence) exit; inFence = 1; next }
        inSec && /^## /     { exit }
        inFence             { print }
    ' "$1" | awk '
        { line = line $0 }
        /\\$/ { sub(/\\$/, " ", line); next }
        { print line; line = "" }
        END { if (line != "") print line }
    '
}

judge() { # judge <expect-spec> <first-output-line> -> prints PASS/FAIL/INFO verdict word
    local want="$1" got="$2" op num
    case "$want" in
        '>='* | '<='* | '>'* | '<'*)
            op="$(printf '%s' "$want" | sed 's/[^<>=].*//')"
            num="$(printf '%s' "$want" | sed 's/^[<>=]*[[:space:]]*//' | awk '{print $1}')"
            case "$got" in '' | *[!0-9-]*) echo INFO; return ;; esac
            case "$num" in '' | *[!0-9-]*) echo INFO; return ;; esac
            if awk "BEGIN { exit !($got $op $num) }"; then echo PASS; else echo FAIL; fi
            ;;
        *[!0-9]* | '')
            # A word expectation such as "present"; a phrase such as "line 213" is INFO.
            want="$(printf '%s' "$want" | awk '{print $1}')"
            if [ -n "$want" ] && printf '%s' "$want" | grep -qE '^[a-z][a-z0-9-]*$'; then
                [ "$got" = "$want" ] && echo PASS || echo FAIL
            else
                echo INFO
            fi
            ;;
        *) [ "$got" = "$want" ] && echo PASS || echo FAIL ;;
    esac
}

overall_fail=0
for spec in "${files[@]}"; do
    name="$(basename "$spec")"
    if [ -n "$STAGE" ] && [ "$name" != "$STAGE" ]; then
        case "$name" in
            stage-"$STAGE"-*) ;;
            *"$STAGE"*) ;;
            *) continue ;;
        esac
    fi

    status="$(grep -m1 '^| \*\*State\*\*' "$spec" | sed 's/^| \*\*State\*\* | //; s/ |$//')"
    printf '\n=== %s\n    Status: %s\n' "$name" "${status:-(no Status row)}"

    block="$(extract_block "$spec")"
    if [ -z "$block" ]; then
        echo "    (no Preconditions block)"
        continue
    fi
    if [ "$DRY" = 1 ]; then
        printf '%s\n' "$block" | sed 's/^/    | /'
        continue
    fi

    pass=0 fail=0 info=0
    while IFS= read -r line; do
        [ -n "${line// /}" ] || continue
        case "$line" in \#*) continue ;; esac
        cmd="${line%%#*}"
        want="$(printf '%s' "$line" | sed -n 's/.*#[[:space:]]*expect[:]\{0,1\}[[:space:]]*//p')"
        [ -n "${cmd// /}" ] || continue
        # A bare `M=path` line has to run in THIS shell, or every later assertion that uses
        # $M silently greps nothing and reports a false failure.
        if [[ "$cmd" =~ ^[[:space:]]*[A-Za-z_][A-Za-z0-9_]*=[^[:space:]]*[[:space:]]*$ ]]; then
            eval "$cmd"
            continue
        fi
        # `test -d X && echo baseline-present` states its expectation in the echo. Treat that
        # as the assertion it is, rather than printing it as unjudged noise.
        if [ -z "$want" ]; then
            want="$(printf '%s' "$cmd" |
                sed -n 's/.*&&[[:space:]]*echo[[:space:]]*\([A-Za-z0-9_-]*\).*/\1/p')"
        fi
        out="$(eval "$cmd" 2>/dev/null | head -1 || true)"
        out="$(printf '%s' "$out" | tr -d '\r' | sed 's/^[[:space:]]*//; s/[[:space:]]*$//')"
        verdict="$(judge "$want" "$out")"
        case "$verdict" in
            PASS) pass=$((pass + 1)) ;;
            FAIL) fail=$((fail + 1)) ;;
            *) info=$((info + 1)) ;;
        esac
        printf '    %-4s %-72s -> %s\n' "$verdict" "$(printf '%s' "$cmd" | cut -c1-72)" "${out:-<no output>}"
    done <<<"$block"

    printf '    %d pass, %d fail, %d informational\n' "$pass" "$fail" "$info"
    if [ "$fail" -gt 0 ]; then
        echo "    => preconditions FAIL. Two hypotheses, test both: the code moved (stale"
        echo "       spec — re-brainstorm and rewrite it), or the assertion was wrong when"
        echo "       written (fix it in its own commit and record why in Status)."
        overall_fail=1
    else
        echo "    => preconditions pass"
    fi
done

cat <<'EOF'

Line numbers drifting by a constant is expected drift, not staleness — re-derive ranges with
grep -n against the current file. Staleness is a symbol that is missing or a count that
changed. And when a stage lands: update its Status block the same day, write the stop-point
sentence into DECISION.md if you stopped at one, then run the NEXT stage's preconditions and
record the result there.
EOF

# Only gate when a single stage was named; a chain report is a report, and later stages are
# expected to fail their preconditions until the stages before them land.
if [ -n "$STAGE" ] && [ "$overall_fail" -ne 0 ]; then
    exit 1
fi
