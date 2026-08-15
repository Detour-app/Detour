#!/usr/bin/env bash
#
# Fail if a raw text field is given a secret-ish label.
#
# Why this exists: #7 was one missing line on an OutlinedTextField - no mask, no
# keyboardOptions - and nothing in the toolchain noticed. There is no Robolectric, no
# compose-ui-test and no androidTest source set in this repo, so a credential field that
# leaks to the IME cache compiles clean, passes every test, and ships. SecretTextField
# makes the right thing easy; this makes the wrong thing loud.
#
# Matches a raw OutlinedTextField/BasicTextField/TextField constructor whose next few lines
# mention a secret-ish word. SecretTextField and CredentialTextField do not match the
# constructor pattern, so a converted call site is silent.
#
# Read-only: greps the working tree. Exit 0 clean, 1 on a finding, 2 on misuse.
set -euo pipefail

if [ "$#" -gt 0 ]; then
    echo "usage: $(basename "$0")            # no arguments; run from anywhere" >&2
    exit 2
fi

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../../.." && pwd)"
cd "$ROOT"

# The component itself necessarily wraps a raw OutlinedTextField.
SELF="app/src/main/java/com/jellemax/detour/ui/SecureFields.kt"
SECRET_RE='secret|password|passphrase|token|api[-_ ]?key|credential'
WINDOW=8

hits=""
while IFS= read -r f; do
    [ "$f" = "$SELF" ] && continue
    found=$(awk -v file="$f" -v re="$SECRET_RE" -v w="$WINDOW" '
        /OutlinedTextField\(|BasicTextField\(|[^A-Za-z]TextField\(/ {
            if (collecting && tolower(buf) ~ re) printf "%s:%d\n", file, start
            start = NR; buf = $0; collecting = 1; next
        }
        collecting {
            buf = buf "\n" $0
            if (NR - start >= w) {
                if (tolower(buf) ~ re) printf "%s:%d\n", file, start
                collecting = 0
            }
        }
        END { if (collecting && tolower(buf) ~ re) printf "%s:%d\n", file, start }
    ' "$f") || true
    [ -n "$found" ] && hits="$hits$found"$'\n'
done < <(git ls-files '*.kt')

hits=$(printf '%s' "$hits" | sed '/^$/d')

if [ -n "$hits" ]; then
    echo "A raw text field is collecting something secret-ish:" >&2
    printf '%s\n' "$hits" >&2
    cat >&2 <<'EOF'

Use SecretTextField (masked, password IME, reveal toggle, autofill) or
CredentialTextField (not masked, but no autocorrect and a chosen keyboard type),
both in app/src/main/java/com/jellemax/detour/ui/SecureFields.kt.

Without keyboardOptions a field ships as ordinary prose: predictive text runs over
the value and it can land in the keyboard's personalised learning dictionary, which
several third-party IMEs sync off-device. PasswordVisualTransformation alone does
not change this - it only affects what is drawn.

See ASVS 5.0.0 V6.2.6 and V6.2.7, CWE-549, and issue #7.
EOF
    exit 1
fi

echo "OK: no raw text field is labelled as collecting a secret."
