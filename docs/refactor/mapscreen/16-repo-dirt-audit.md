# Repo-dirt audit — what this branch actually added, and what should survive

Read-only audit of `refactor/mapscreen-split` against `main`, 2026-08-12. Every number below was
measured, not estimated; the command that produced it is named where it is not obvious. Nothing
was deleted, moved or committed — this is a proposal to approve or reject.

Baseline for all measurements: `main` = `07fe490`, `HEAD` = `e0c49d5`, merge-base diff
`git diff --stat main...HEAD` → **141 files changed, 82 480 insertions(+), 1 860 deletions(-)**.

> This file is itself 63 KB of markdown arguing that this branch has too much markdown, which is not
> lost on me. It is category (b) by its own criteria: **delete it once the cleanup in §7 has landed
> and the commit messages carry the reasoning.** Its only durable output is the eleven harvests in
> §4 and the six issues in §7 — once those exist, this document has served its purpose.

---

## 1. The GPX finding — first, separately, and better than feared

### Verdict: **not tracked, never was, no history rewrite warranted.**

The file is real and it is where the maintainer thought it was:

| | |
|---|---|
| Path | `/home/andre/Projects/Detour/detour-car-2026-08-08-1341.gpx` |
| Size | **401 747 bytes**, 4 562 `<trkpt>` elements, 4 563 `<time>` elements |
| Track | **149.34 km** driven; endpoints **136.11 km** apart — a one-way drive, not a loop |
| Git state | **untracked and ignored** |

Four independent checks, all negative:

```
git ls-files | grep -i '\.gpx'                    -> no match (nothing tracked in HEAD)
git ls-tree -r --name-only main | grep -i '\.gpx'  -> no match
git rev-list --all --objects | grep -i '\.gpx'     -> no match in any reachable object
git log --all --reflog --diff-filter=A -- '*.gpx'  -> no commit ever added one
git cat-file -e $(git hash-object <the file>)      -> NOT in object store
```

The last line is the conclusive one. `git hash-object` computes the blob id the file *would* have,
and `cat-file -e` reports it absent from the object database entirely — not merely unreachable, but
never written. A `git add` that was later reverted, an amended commit, a dropped stash and a
rebased-away commit would all have left that blob behind. None did. **The content of this file has
never been inside this repository.**

`git ls-tree -r origin/refactor/mapscreen-split | grep gpx` returns only three source files
(`gpx2route.py`, `app/.../data/Gpx.kt`, `shared/.../data/RouteGpx.kt`) — code that parses GPX, not
a recording.

### The exposure window that existed and was not realised

The file's mtime is **2026-08-12 11:41**. The `*.gpx` ignore rule landed at **`ba74e40`,
2026-08-12 20:17** — an **8 h 36 min window** in which the file sat in the working tree, untracked
and unignored, while roughly a dozen commits were made. Any `git add -A` or `git add .` in that
window would have committed it. It did not happen. `tools/mocklocation/baseline/README.md:59` even
records the file's presence at the time ("tree clean apart from untracked `.devcontainer/` and an
untracked GPX"), which is how we know it was there and how we know it was noticed.

The concern that a late `.gitignore` rule "does nothing for a file already tracked" is correct as a
general rule and **does not apply here**, precisely because the file was never tracked. The rule is
effective:

```
$ git check-ignore -v detour-car-2026-08-08-1341.gpx
.gitignore:51:*.gpx    detour-car-2026-08-08-1341.gpx
$ git add -An detour-car-2026-08-08-1341.gpx
The following paths are ignored by one of your .gitignore files: ...  (refused)
```

### What the raw endpoints would have exposed, had it been committed

Stated without coordinates, as instructed. Measured with a haversine against the three committed
route fixtures:

- The GPX's **start** is **8.97 km** from the nearest point of *any* committed route (8 973 m from
  `stop-start.txt`, 8 971 m from the other two — the three routes converge near there, so it is the
  same 9 km).
- The GPX's **end** is **109.5–123.7 km** from the nearest point of any committed route.
- The two endpoints are **136.1 km** apart from each other.

So the raw file's endpoints are **nowhere near** anything the repo publishes: one is 9 km outside
the fixtures' coverage, the other is 110 km outside it. Both are untrimmed, both carry per-point
timestamps, and one of them is the origin of a car trip beginning at 11:41 on a Saturday — which is
the profile of a home address. The 1 km trim that the route fixtures get exists to remove exactly
this, and a raw export by definition has not had it.

Two of the three routes overlap this drive's *middle* (31–33 % of `trajectcontrole` and
`urban-limits` sample points lie within 60 m of the GPX track; `stop-start` 0 %), so the raw file is
a superset of published geometry **plus** two endpoints that were never published. That is the whole
of the delta, and it was never committed.

### Recommended remediation, and its cost

| Option | Warranted? | Cost |
|---|---|---|
| **Move the file out of the working tree** into the scratchpad | **Yes — do this** | One `mv`. Zero risk. |
| Keep the `*.gpx` ignore rule | **Yes — already done** at `ba74e40` | Zero |
| `git filter-repo` / BFG history rewrite | **No** | Would be pure cost: a force-push to `origin/refactor/mapscreen-split`, every local clone invalidated, every commit SHA in `DECISION.md`, the specs, the baseline README and the route README rewritten — for a blob that is not in history. |
| Rotate / notify anyone | **No** | Nothing was published. |

**Who can see this branch:** `origin` is `git@github.com:Yimura/Detour.git`, **PUBLIC**, and
`origin/refactor/mapscreen-split` is at **`baf49f1`** — so 43 of the branch's commits are already
public. `upstream` is `maxke24/Detour`, also public, and does **not** have this branch. Six commits
are local-only (`git log origin/refactor/mapscreen-split..HEAD`). None of the 49 contains a GPX.
The exposure that already happened is the baseline evidence and the docs discussed below, not
location data.

Precedent for the practice is already written down and already being followed: two further GPX
exports (`detour-car-2026-08-10-1806.gpx`, `tc-slice.gpx`) currently live in the session scratchpad,
outside the repo, which is what `tools/mocklocation/routes/README.md` §Privacy instructs. The
working-tree file is the one outlier.

### One residual worth a decision, unrelated to the GPX

The three committed routes' free ends converge: `stop-start.txt`'s start is **13 m** from
`trajectcontrole.txt`'s end and **12 m** from `urban-limits.txt`'s end, and those two are **23 m**
apart. Three independent fixtures agreeing on one 25 m spot marks that spot as a place the
maintainer departs from and returns to. The `--trim 1000` puts the real endpoint ~0.8–1.03 km
further along (the README measures 1.02/1.03 km, 1.01/1.03 km and 0.80/0.82 km), and the road it
lies on is implied by the fixture's own direction. This is a much weaker signal than a raw export
and it is the price of using real drives at all — but it is not zero, and if it matters, the fix is
a larger `--trim` on a re-conversion, not a deletion. Flagged, not recommended.

Also minor: `tools/mocklocation/baseline/README.md` names the device serial `RFCT42HS9WY` **13
times** and a second serial `50043ff9` once, in a public repo. Hardware identifiers, low
consequence, trivially redacted if the README survives.

---

## 2. Where the weight actually is

Bytes are git object sizes at `HEAD` for the 123 files the branch **adds**
(`git diff --diff-filter=A`); lines are from `git diff --numstat`. Total tracked tree grows
**215 316 026 → 227 960 841 bytes**, a delta of **12 644 815 bytes (12.06 MiB)**; the added files
alone are **12 686 467 bytes**.

| Path glob | Files | Lines added | Bytes | Share | Verdict | One-line reason |
|---|---|---|---|---|---|---|
| `tools/mocklocation/baseline/**` | 44 | 35 410 + 25 binaries | **9 979 301** | **78.7 %** | **CUT most, keep 330 KB** | 9.65 MB of it is screenshots and logcat whose findings are already written out in prose in its own README |
| `docs/security/asvs-…-2026-08-11.md` | 1 | 14 827 | **1 054 290** | **8.3 %** | **RELOCATE** | A 14 827-line ASVS report, unrelated to this refactor, referenced by nothing, committed inside `docs(refactor): record stage 1 complete` |
| `docs/refactor/mapscreen/*.md` | 14 | — | **839 876** | 6.6 % | **CUT 612 KB, keep 228 KB** | Eleven of the fourteen are investigation reports and rejected proposals; `DECISION.md`, the divergence register and the tiered verification checklist earn their place |
| `docs/refactor/mapscreen/plans/**` | 5 | — | **256 893** | 2.0 % | **CUT 189 KB, keep 68 KB** | Executed plans; outcomes are in the specs' Status rows. The stage-2 plan stays — a shipped source file cites its mapping table |
| `.claude/skills/**` | 24 | 3 947 | **205 037** | 1.6 % | **KEEP** | This is where the reports' durable findings were *promoted to*; it is the reason the reports can go |
| `docs/refactor/mapscreen/specs/**` | 9 | — | **134 756** | 1.1 % | **KEEP** | `chain-status.sh` reads this directory at runtime; three stages are still unstarted |
| Kotlin source + tests | 21 | — | **121 238** | 1.0 % | **KEEP** | The actual refactor: 4 new tested policy classes, 12 extracted UI files, 4 test files |
| `tools/mocklocation/routes/**` | 4 | 3 822 | **79 161** | 0.6 % | **KEEP — this is the good part** | Small, text, irreplaceable regression fixtures; see §5 |
| `docs/refactor/mapscreen/verification/**` | 1 | — | **15 915** | 0.1 % | **CUT** | A desk checklist for a stage that is done |
| CI, `CONTRIBUTING.md`, manifest, `.gitignore` | 5 (modified) | ~100 | — | — | **KEEP** | `CONTRIBUTING.md` gained the two durable placement rules; the rest is a test gate and the mock-app manifest fix |

**The headline proportion:** of 82 480 inserted lines, **3 574 (4.3 %) are code**. 35 410 are
recorded evidence, 20 900 are refactor documents, 14 827 are an unrelated security report and 3 947
are skills. The maintainer's instinct that the branch is mostly not-code is correct, and the volume
is concentrated far more narrowly than it feels: **three paths hold 93.6 % of the bytes.**

### Largest individual files

| Bytes | File | Type |
|---|---|---|
| 1 279 382 | `tools/mocklocation/baseline/urban-limits-09fddde.log` | text (logcat) |
| 1 054 290 | `docs/security/asvs-5.0.0-l2-detour-independent-2026-08-11.md` | text |
| 1 023 558 | `tools/mocklocation/baseline/trajectcontrole-b29d014.log` | text (logcat) |
| 816 630 | `tools/mocklocation/baseline/trajectcontrole-09fddde.log` | text (logcat) |
| 692 435 | `tools/mocklocation/baseline/stop-start-09fddde.log` | text (logcat) |
| 534 722 | `…/stop-start-09fddde-stop2-window.png` | **binary** |
| 456 048 | `…/stop-start-09fddde-camera-park.png` | **binary** |
| 393 518 | `…/trajectcontrole-09fddde-mid-route.png` | **binary** |
| 343 453 | `…/trajectcontrole-b29d014-mid-route.png` | **binary** |
| 342 089 | `…/urban-limits-noverpass-09fddde-standstill-window.png` | **binary** |
| 123 487 | `docs/refactor/mapscreen/15-divergence-register.md` | text |

**Text vs binary split of the branch's additions:** 25 binary files totalling **5 837 150 bytes**
(all of them PNGs in `baseline/`, 46 % of everything the branch adds), and 98 text files totalling
**6 849 317 bytes**. There is no other binary content.

### One accounting note

`docs/security/asvs-5.0.0-l2-detour-independent-2026-08-11.md` was added by **`21a02b4`
`docs(refactor): record stage 1 complete and fix four bookkeeping errors`**. A one-megabyte
security assessment rode into the branch inside a refactor-bookkeeping commit, and
`grep -rl 'asvs-5.0.0-l2-detour'` finds **no file outside `docs/security/` that references it**.
Whatever its merit, it is not part of this refactor and it is the second-largest single file the
branch adds. It should be its own PR, or its own repo, or a wiki page — decided on its own terms,
not smuggled in with a stage marker.

---

## 3. `tools/mocklocation/baseline/**` — 9.98 MB, of which ~330 KB is the point

44 files in `HEAD`, **9 979 301 bytes**. On disk the directory is currently **11 726 360 bytes**
because a sixth run is in flight (see the note at the end of this section).

### Inventory by kind

| Kind | Files | Bytes | Share of dir |
|---|---|---|---|
| `*.png` (single frames + montages) | 25 | **5 837 150** | 58.5 % |
| `*.log` (filtered logcat) | 4 | **3 812 005** | 38.2 % |
| `<route>-<sha>.tsv` (one row per captured frame) | 4 | 170 907 | 1.7 % |
| `*-stall.tsv` (per-frame-pair map RMSE) | 5 | 102 321 | 1.0 % |
| `README.md` | 1 | 51 872 | 0.5 % |
| `*-events.tsv` (derived HUD state changes) | 5 | **5 046** | 0.05 % |

### The README's own claims, quoted, and whether the files back them

The README is the strongest document in this whole audit and it **argues for its own trimming**:

> "Total directory size ~9.6 MB; trim it if that is too much for the repo, but keep the `.tsv`
> files — they are the small part and the whole point." — `README.md:148-149`

> "There is not one `Log` call in `MapScreen.kt` or `TripTrackingService.kt`, so the only record of
> what the HUD did is the pixels. **A folder of screenshots nobody can diff is worthless**, so each
> frame is reduced to signals…" — `README.md:151-155`

That is the correct instinct and it is already implemented: the pixels *were* reduced to numbers.
The 5.84 MB of PNG is the raw material for a reduction that has already been performed and
committed as 107 KB of TSV.

Claims checked against the files:

| Claim | Location | Holds? |
|---|---|---|
| "0 stalls in 3 909 frame pairs across five runs (382 + 493 + 1 003 + 1 003 + 1 028)" | `README.md:571`, Q4 | **Yes.** The five named `*-stall.tsv` files have exactly 382 / 493 / 1 003 / 1 003 / 1 028 data rows, summing to 3 909. |
| "one row per captured frame" for `<route>-<sha>.tsv` | `README.md:137` | **Yes.** 383 / 494 / 1 029 / 1 004 data rows, each one more than the matching stall file, as a per-*pair* file must be. |
| Q1: "the sign clears 3 fixes after the last successful snap … seen twice independently" | Q1 row | **Yes.** `stop-start-09fddde-events.tsv` has `SIGN-CLEARED` at fix 473 and `urban-limits-09fddde-events.tsv` at fix 612, matching the stated 470→473 and 609→612. |
| Q7–Q9 (section chip appears at fix 73, clears at fix 81, exactly two AVG events) | Q7–Q9 rows | **Yes.** `trajectcontrole-b29d014-events.tsv` contains exactly two AVG rows: `AVG-ON` fix 73 and `AVG-CLEARED` fix 81. |
| `urban-limits-noverpass` is a full fifth run | `README.md:143` | **Partly.** It has `-events.tsv`, `-stall.tsv` and two PNGs but **no `<route>-<sha>.tsv`** master file, unlike the other four. |

So the README's numbers are sound. **What has gone stale is the indexing, and the README says so
itself**, in a warning box it wrote against itself:

> "⚠ The route files changed at `ba74e40`. … Every fix index in this file was recorded against the
> route files as they stood at `5fc8e90`. `ba74e40` replaced all three, so **an index here does not
> index the current route files.**" — `README.md:12-16`

and per route: `trajectcontrole` **"Totally invalid. Different geometry, different direction,
different length. Nothing below transfers."** Its own Q7/Q8/Q9 rows are marked
**"No — recorded against the superseded route."**

This is the decisive fact for the cut. Every PNG and every logcat line in this directory was
captured against route files that **no longer exist in the tree**. They cannot be re-compared frame
by frame against a future run, because the future run replays different geometry. What survives
`ba74e40` is exactly the set of claims expressed as latencies and yes/nos — Q1 through Q6 — and
those live in the README's prose and in the 5 KB of `*-events.tsv`.

### Evidence a human will re-read, vs. bulk a sentence replaces

**Never re-read: 14 of the 25 PNGs (2 939 451 bytes) are cited by nothing.** Checked by grepping
the README for each filename and for its distinctive suffix:

`urban-limits-noverpass-…-standstill-window` · `trajectcontrole-b29d014-chip-window` ·
`trajectcontrole-b29d014-t0083-chip-cleared` · `trajectcontrole-b29d014-t0078-chip-value` ·
`trajectcontrole-09fddde-t0100-motorway-no-sign` · `urban-limits-09fddde-t0030-sign-120-on` ·
`stop-start-09fddde-t0010-hud-on` · `urban-limits-noverpass-…-t0060-urban-no-sign` ·
`stop-start-09fddde-t0096-sign-30-on` · `trajectcontrole-09fddde-t0300-inside-section-no-chip` ·
`urban-limits-09fddde-t0636-sign-cleared-held-set-ran-out` ·
`stop-start-09fddde-t0668-sign-cleared-at-speed` ·
`urban-limits-09fddde-t0300-sign-120-in-traffic` ·
`trajectcontrole-09fddde-t0548-standstill-hud-faded`

Not one of these is referenced from any document. They are 2.9 MB of PNG whose entire informational
content is a row in an `events.tsv` that already exists.

**Cited, but replaced by the sentence that cites it: the other 11 PNGs (2 897 699 bytes).** Every
citation is of the form "the number is X, and here is a picture of X":

- `…-stop2-window.png` (534 722 B) — cited as "The easing is in `…-stop2-window.png`: 28 → 8 → 19 →
  16 km/h and then gone". **The four numbers are in the sentence.**
- `…-camera-park.png` (456 048 B) — cited for "276.7 km → 328.2 km across the stop". **Both numbers
  are in the sentence.**
- `…-bearing-hold.png` (56 257 B) — cited for "no north-up snap", which `-stall.tsv`'s
  **RMSE exactly 0 for five consecutive pairs** already proves numerically and more strongly.
- `…-mid-route.png` ×2 (736 971 B) — cited for the *absence* of speed-camera markers. A negative
  observation; `avg_blue`/`sign_red` are 0 across all 494 rows of the TSV.
- `…-chip-values.png` (69 549 B) — cited for "Ø 121 settling to Ø 120". **Both values are in the
  sentence and both AVG rows are in `-events.tsv`.**

**Logs: 3 812 005 bytes, ~85 % platform chatter.** Tag histogram of the two largest:
`I FusedLocation` 2 818 and 3 924 lines, `D RenderEngine` 820 and 2 110, plus `keystore2`,
`PhoneInterfaceManager`, `minksocket`, `[WEATHER]SourceFile`, `Routine@Core` — Samsung OEM noise.
The part the README says the log is *for* — "the log doubles as a timestamped per-fix timeline" — is
the repeated `E MockLocation: passive provider is not a test provider` line, and that is only
**750 / 967 / 984 / 1 442 lines, 13–16 % of each file**. The timeline it yields is what produced the
measured 1.018–1.022 s cadence, and that number is written in the README (§"Measured: the replay
runs at ~1.02 s per fix", with the per-run figures 1.0224, 1.018, 1.0189, 1.02030). **The
conclusion is retained; the 3.8 MB it was derived from is not needed to hold it.**

Checked and clean: the committed logs contain **no coordinates** (`grep -cE '50\.[0-9]{3,}[ ,]+4\.[0-9]{3,}'`
→ 0 on all four), no `Location[` dumps, no accuracy values, and no device serials. The filter that
produced them stripped the position data. So the logs are bulk, not a privacy problem.

### Recommended keep-set for `baseline/`

| Keep | Files | Bytes |
|---|---|---|
| `README.md` | 1 | 51 872 |
| `*-events.tsv` | 5 | 5 046 |
| `*-stall.tsv` | 5 | 102 321 |
| `<route>-<sha>.tsv` | 4 | 170 907 |
| **Total** | **15** | **330 146** |

Cut: 25 PNG + 4 log = **29 files, 9 649 155 bytes**. The directory goes from 9 979 301 to
**330 146 bytes — a 96.7 % reduction that loses no stated finding.**

Optional compromise if the maintainer wants any pixels at all: re-add the two smallest cited
montages, `…-bearing-hold.png` (56 257) and `…-chip-values.png` (69 549), for **+125 806 bytes**.
I do not recommend it — the RMSE column and the two AVG rows are better evidence than either
image — but it is cheap and it is the version of this cut least likely to be regretted.

### In-flight work — do not touch

A sixth run is being recorded right now — `stop-start` at `fca3c35`. `git status` shows `README.md`
**modified** and ten untracked files, growing while this audit was written. On-disk the directory is
**11 747 284 bytes** against 9 979 301 committed; the `-stall.tsv` row counts now sum to **4 155**,
not the README's 3 909, because of the extra 246-row run.

**Every command in §7 is scoped to committed paths only and touches none of these.**

Two observations about the in-flight output, offered as the reason to apply the policy *to it* rather
than only to what is already committed:

- Its ten files total **1 761 718 bytes**, of which **1 530 564 is a single logcat** and 209 841 is
  five PNGs — and those PNGs are **15–52 KB each**, against 56–535 KB for the committed set. Someone
  has already started capturing at a smaller scale, which is the right instinct and confirms the old
  PNGs were oversized rather than information-dense.
- The one logcat is **1.53 MB for a 762-fix route** — larger than any of the four committed logs
  relative to its length. Left in, it re-inflates the directory by 4.6× what the whole proposed
  keep-set weighs.

So: land that run, then apply the same PNG/log policy to its output in the same commit. Otherwise the
directory simply refills and this audit has to be written again.

Separately, a **second ASVS document appeared while this audit was being written**:
`docs/security/asvs-5.0.0-l2-detour-addendum-post-migration-2026-08-12.md`, 21 046 bytes, untracked.
Small, and this time an addendum rather than a full report — but it confirms that `docs/security/`
is accumulating on a refactor branch. §2's recommendation applies to both: they belong on their own
branch, decided on their own merits.

---

## 4. `docs/refactor/**` — 29 files, 1 247 440 bytes

### What is load-bearing, established mechanically before reading anything

Four of these documents are **cited from committed tooling or committed source**, so cutting them
silently breaks something:

| Cited document | Cited by | Form of the citation | Effect |
|---|---|---|---|
| `12-eval-risk-sequencing.md` | `detour-compose-state-hazards/SKILL.md` | line range `:48-159` | **keep the file** |
| `plans/2026-08-12-stage-2-pure-extractions.md` | `app/…/map/CameraAuthority.kt` KDoc | by name, "the table is in the stage-2 plan" | **keep the file** |
| `13-surface-independence-audit.md` | `detour-shared-core/SKILL.md` (×2) | by path | repoint, then cut |
| `plans/2026-08-12-stage-1-mechanical-split.md` | `detour-file-split/SKILL.md` | line range `:207-216` | drop the path, then cut |

A committed Kotlin file citing a plan document by name is the strongest single argument in this
audit against treating `docs/refactor/` as uniformly disposable.

And one whole directory is **read at runtime**: `chain-status.sh:68` sets
`SPECS="docs/refactor/$CHAIN/specs"` and parses the `Status:` row plus the precondition greps out of
each spec. Running it now produces live output for all eight stages. **`specs/` is not documentation,
it is input to a committed script.**

`DECISION.md` and `tools/mocklocation/{routes,baseline}/README.md` also link into the reports;
`DECISION.md:69` is an "Index of reports" that enumerates them. Any cut must edit that index in the
same commit or it becomes a list of dead links.

Two references are **already dead**: `15-divergence-register.md` and `specs/00-chain-design.md` both
point at `docs/refactor/mapscreen/scripts/check-divergences.sh`, and that path does not exist
(`ls` → No such file or directory; `git ls-files` → nothing). A promised checker was never written.

### Chain state, from `chain-status.sh` itself

| Spec | Status |
|---|---|
| `stage-0-verification-baseline` | partially done — tasks 2–4 deferred |
| `stage-1-mechanical-split` | **done** — `MapScreen.kt` 3204 → 1549 across 12 commits |
| `stage-2-pure-extractions` | **done** — 5 commits, suite 18 → 50 tests |
| `stage-3-hazard-machines-to-shared` | **not started** — preconditions pass, ready to plan |
| `stage-4-state-ownership` | **not started**, optional |
| `convergence-1-cheap-fixes` | **done** in code, four device checks open |
| `convergence-2-section-readouts` | **not started**, blocked on stage 3 |
| `convergence-3-voice-policy` | **done** in code, six device checks open, stop-point not honoured |

Three stages are unstarted. Their specs are forward-looking and must survive.

### The staleness that makes some of these actively misleading

`MapScreen.kt` went **3 197 → 1 658 lines** on this branch (`git show main:…| wc -l` vs `wc -l`), a
48 % reduction, with code moved into twelve new files. The reports cite it by line number **318
times**:

| Document | `MapScreen.kt:NNN` citations | Provably out of range (> 1 658) |
|---|---|---|
| `00-inventory.md` | 65 | 5 |
| `02-proposal-compose-state-holders.md` | 36 | 1 |
| `15-divergence-register.md` | 32 | 0 |
| `11-eval-fit-maintainability.md` | 24 | 3 |
| `05-proposal-mvi-reducer.md` | 21 | 1 |
| `plans/…-stage-0-verification-baseline.md` | 16 | 1 |
| `13-surface-independence-audit.md` | 15 | 1 |
| `plans/…-stage-2-pure-extractions.md` | 14 | 0 |
| `06-proposal-hexagonal.md` | 14 | 0 |
| `03-proposal-viewmodel-uistate.md` | 13 | 0 |
| `04-proposal-domain-services.md` | 11 | 0 |
| `01-proposal-mechanical-split.md` | 10 | 0 |
| others (9 files) | 33 | 0 |
| **total** | **318** | **12** |

Twelve citations point past the end of the file — e.g. `13-surface-independence-audit.md:258`
cites `MapScreen.kt:3188` and `05-proposal-mvi-reducer.md:445` cites `:1689`, in a 1 658-line file.
Those are provable. The remaining 306 are worse in practice: they resolve to *a* line, just not the
line meant, so a reader following one lands on unrelated code with no error to warn them. A further
**166 citations of `NavScreen.kt`**, 83 of `CarMapRenderer.kt` and 80 of `SpinScreen.kt` sit in the
same reports, and `NavScreen.kt` was also modified on this branch (192 lines changed).

The register documents the problem in its own header, which is the cleanest possible statement of
why the older reports are now a liability:

> "Where audit 13 cites a `MapScreen.kt` line number, that citation is stale — the file went
> 3204 → 1553 lines across stages 1 and 2 — so every line number below was re-derived with
> `grep -n` rather than carried forward." — `15-divergence-register.md:6-8`

So the register fixed its own citations and left audit 13's broken. And commit `21a02b4` made the
retention policy explicit: *"The historical analysis reports keep their original wording: they are
the record of what was believed at the time, not instructions."* That is a defensible policy for a
document nobody will open, and a hazard for one sitting in `docs/` under a name like
`13-surface-independence-audit.md`. **A stale citation in a repo is not neutral bulk — it is a wrong
answer waiting to be trusted.**

### Classification

Sizes are exact git object sizes.

A full read of all 29 files produced two corrections to my first pass. **Two documents I had
classified as working material are load-bearing and must be kept**, and three carry open
obligations against already-shipped code.

#### (a) Durable — keep in the repo

| File | Bytes | Why |
|---|---|---|
| `DECISION.md` | 26 826 | The decision record: two stop-points reached, five patterns scored, six contradictions resolved, "Not doing", "Never in one commit", and the one user-visible decision. The *reasoning* behind the resolutions exists only here. |
| `15-divergence-register.md` | 123 487 | 22 entries: **3 fully resolved, 5 partially, 14 with no code change**; §B 6 bugs, **4 fixed, 2 open (B4, two-thirds of B5)**; §C all 4 product decisions taken, of §C.1's six-item order **4 done, 2 blocked**. Declared input to stage 3, which has not started. |
| **`12-eval-risk-sequencing.md`** | **77 411** | **Correction — this is not an evaluation, it is the tiered manual verification checklist.** `:486` Tier 0, `:502` Tier 1, `:614` Tier 2, `:660` Tier 3 — ~90 named observable behaviours with the code that produces them, and it exists nowhere else. The specs name a tier *from this file* six times, and `detour-compose-state-hazards/SKILL.md:326` cites `:48-159` directly. Cutting it would silently un-define the verification vocabulary the whole chain uses. |
| `specs/**` (9 files) | 134 756 | Parsed at runtime by `chain-status.sh`; three stages unstarted. Five are durable in their own right (`00-chain-design`, `stage-0` — 0d still open, `stage-3`, `stage-4`, `convergence-2`); the other four are the Status record and cost 45 KB to keep, which is cheaper than a partial directory the tool would misreport. |
| **`plans/2026-08-12-stage-2-pure-extractions.md`** | **67 991** | **Correction — a shipped source file cites it.** `CameraAuthority.kt`'s KDoc reads *"the table is in the stage-2 plan"*, and that ten-row write-site → `Action` mapping is exactly the enumeration `specs/stage-4` demands and does not contain. Keep, or move the table into the KDoc first. |
| **subtotal** | **430 471** | 13 files |

#### (b) Working material — served its purpose, now dead weight

| File | Bytes | Why it can go |
|---|---|---|
| `00-inventory.md` | 82 598 | An inventory of a file that no longer has that shape; 65 stale citations, the highest count in the tree. **Harvest H12/H14/H15 first.** |
| `01-proposal-mechanical-split.md` | 47 608 | Chosen and **executed** as stage 1. Loses nothing. |
| `02-…compose-state-holders.md` | 59 060 | Not chosen. Loses nothing. |
| `03-…viewmodel-uistate.md` | 54 053 | **Rejected** explicitly in `DECISION.md` § Not doing and `specs/stage-4` § Forbidden. Loses nothing. |
| `04-…domain-services.md` | 60 731 | The winner, partly executed. Its unique content is a 101-row line-by-line classification of a 3 193-line file that no longer exists — stale and unreproducible. |
| `05-…mvi-reducer.md` | 56 472 | Variant A rejected, variant B shipped as `CameraAuthority`. **Harvest the SM1 state table first.** |
| `06-…hexagonal.md` | 66 222 | Rejected as a programme; its *rule* is now in `CONTRIBUTING.md`. **Harvest the roundabout gotcha.** |
| `10-eval-fact-audit.md` | 44 898 | An audit of the six proposals; once they go it is orphaned. Its output is `DECISION.md:92`. **Harvest three facts.** |
| `11-eval-fit-maintainability.md` | 63 791 | Scoring input; scores are in `DECISION.md:245`. **The richest harvest in the directory — do not delete before §4's list.** |
| `13-surface-independence-audit.md` | 30 244 | Superseded in substance by the register, which re-derived its facts *because* its citations were stale. **Cite-check + harvest first.** |
| `14-candidate-skills.md` | 46 475 | Proposed seven skills that all **now exist** under `.claude/skills/`, precondition scripts included. The deadest file in the set. |
| `plans/2026-08-11-stage-0-…md` | 35 666 | Tasks 1/5/6/7 done, 2–3 superseded by the real routes and baseline. **Task 4 (0d) never ran — harvest its one design decision.** |
| `plans/2026-08-12-stage-1-…md` | 15 038 | Executed; `detour-file-split` §6 already reproduces its argument in full. **Cite-check, then loses nothing.** |
| `plans/2026-08-12-convergence-1-…md` | 43 591 | Executed. Its live half is four open device checks → issue (see (c)). |
| `plans/2026-08-12-convergence-3-…md` | 94 607 | Executed, stop-point breached. Its live half is ten open device checks and three defects → issues (see (c)). |
| `verification/stage-1-desk-checklist.md` | 15 915 | A log for a completed stage; its screenshots already point into a wiped scratchpad. Holds one unfiled bug → issue (see (c)). |
| **subtotal (cut)** | **816 969** | **16 files** (11 reports + 4 plans + 1 checklist) |

Four of the nine specs (`stage-1`, `stage-2`, `convergence-1`, `convergence-3`) are also spent
content, but they are kept regardless: they cost 45 KB, `chain-status.sh` globs the directory, and a
chain missing its completed stages reports as an incomplete chain rather than a finished one.

#### (c) Belongs elsewhere — file the issue *before* deleting

| File | Bytes | Where it belongs |
|---|---|---|
| `docs/security/asvs-…-2026-08-11.md` | 1 054 290 | Its own PR or the wiki. Unrelated to this refactor, referenced by nothing, rode in on a stage-marker commit. |
| `verification/stage-1-desk-checklist.md` | 15 915 | **A GitHub issue, urgently** — it holds a reproduced, cause-narrowed, unfiled bug (below). Its screenshots already point into a wiped `/tmp/claude-1000/…` scratchpad, so half its evidence is gone. |
| `plans/2026-08-12-convergence-3-voice-policy.md` | 94 607 | **A GitHub issue** — ten open device checks against four *shipped* commits on the app's most-used surface. |
| `plans/2026-08-12-convergence-1-cheap-fixes.md` | 43 591 | **A GitHub issue** — four open device checks against shipped code. |

### What is lost, honestly — and where each finding should go instead

This is the part that decides whether the cut is safe. Ranked by cost. **Everything above the line
must be harvested into its named destination in the same commit that deletes the file**, or the cut
loses a finding that exists nowhere else in the repo.

1. **`verification/stage-1-desk-checklist.md` — an unfiled, reproduced defect.** Dismissing the
   full-screen search dialog leaves the MapLibre surface with a persistent **~14 % grey wash**
   (`rgb(223,234,240)` → `rgb(187,196,201)` at the same map pixel) that survives panning, sheet
   expand/collapse and radius changes, and clears only on process restart. Reproduced **2 of 2** from
   a fresh process. Ruled out by experiment: **not** the fog overlay (fog off removes the fog and the
   wash remains; `FogView.onDraw` returns early when inactive) and **not** the spin-sheet scrim.
   Established as **pre-existing, not a split regression**, by diffing the moved `SearchDialog`
   against `main` (byte-identical modulo `private`→`internal`) and by `MapLibreMap.kt` being
   untouched on this branch. **In no issue (all 11 checked), no doc, no commit message.**
   → **File as a GitHub bug issue. This is the single highest-value item in the whole cut.**
   The same file also records that **item 10, rotation, was never performed** — auto-rotate was on,
   so `settings put system user_rotation` was ignored. The one desk check that would have exercised
   the five `rememberSaveable` values was skipped, and stage 1 is nonetheless recorded as verified.
   `specs/stage-4` evidence item 4 asks for exactly that measurement. → **Note it in stage 1's
   `Status:` row and in `specs/stage-4`.**

2. **`plans/2026-08-12-convergence-3-voice-policy.md` — three defects found while executing.**
   (i) **The Hub round trip tears down and rebuilds the TTS engine**: `navVoice` is `remember`ed in
   `MapScreen`'s composition and `AppRoot` swaps screens with a bare `AnimatedContent`, so leaving
   the map runs a full `TextToSpeech.shutdown()` and returning constructs a second engine — *"an OEM
   engine that does not survive repeated create/shutdown cycles fails silently and looks exactly
   like the setting being off."* (ii) **An audio-focus leak path**: `stopNavigation()` is not called
   on disposal, so `onDispose` is the only thing that returns focus; navigating with music and then
   going to the Hub without pressing Exit leaks it, and *"a leaked focus request shows up as
   permanently quiet music and nothing in this repo can detect it."* (iii) **`activeConvoyId` is a
   proxy**, not the same as "`ConvoyLiveService` is running" (`FriendsScreen.kt:678-683` says so),
   so both edges need checking. Plus the statement of record: *"Nothing in items 6–9 was heard."*
   → **One GitHub issue with the ten device checks; the three defects as their own issues.**

3. **`11-eval-fit-maintainability.md` — five findings, none duplicated anywhere.**
   - **F-C, the most consequential:** `internal` is module-scoped, so anything stage 3 moves to
     `commonMain` that `app/` needs must be `public` — and Kotlin `public` in `commonMain` is
     **exported into the Objective-C framework header**. `SectionAverageTracker`, `CameraWarner` and
     `SpeedLimitTracker` therefore join the iOS framework's public ABI the day they land, before any
     Swift consumes them. Verified absent from all 7 skills, from `CONTRIBUTING.md` and from
     `specs/stage-3`. → **`specs/stage-3` as a constraint, and `detour-shared-core/SKILL.md`.**
   - **F-E:** a why-comment about to be split across a module boundary — `MapScreen.kt`'s
     touch-listener rationale (*"a camera-move listener can't be used for this: the frame loop moves
     the camera every frame"*) is Android `View` code that stays in `app/`, while the resume policy it
     explains moves to `shared/`. `CONTRIBUTING.md` protects comments from deletion and says nothing
     about separating them from what they explain. → **a line in `CONTRIBUTING.md`.**
   - **F-H:** the 02/03/05 rollout is not deferred, it is **impossible on the merits** —
     `SettingsScreen.kt` is `var page` plus 21 `collectAsStateWithLifecycle` reads, so a holder or VM
     over it is 100 % pass-through. *"This codebase has no ViewModels not because nobody got around
     to it, but because the singleton-plus-`remember` shape genuinely fits eighteen of its nineteen
     screens."* → **`DECISION.md` § Not doing** (it is the strongest sentence available for that
     section).
   - **The overridden recommendation:** convergence #5 said the six pure helpers' tests belong in
     `shared/commonTest`; stage 2 put all 32 in `app/src/test`. Defensible now that stage 0a made
     `build.yml` run `:app:testDebugUnitTest`, but **the reversal is recorded nowhere.** → **a line
     in stage 2's `Status:` row.**
   - The measurement that `MapScreen.kt` has **14 commits total, 6 of the last 8 also touching
     `shared/…/data`, 4 also `car/`, 3 also `iosApp/`** — the empirical basis for "the unit of work is
     a cross-surface feature, not a file". → **`CONTRIBUTING.md` or `detour-shared-core`.**

4. **`plans/2026-08-12-convergence-1-cheap-fixes.md`** — the four device checks, including one real
   limitation recorded nowhere else: *"a DHU reports a modern car API level, so this exercises the
   `setTripText` branch — the level-1 colour-only path is not reachable at a desk and stays untested
   by anything."* → **the same GitHub issue as item 2.** Its spec also holds a durable lesson about
   fence-writing: `grep -c 'playAndRecord'` went 1→2 **because the fix's own doc comment matched the
   pattern** — *"the assertion was counting a code construct with a pattern that also matches a
   comment."* → **`detour-staged-refactor/SKILL.md`.**

5. **`00-inventory.md`** — three hazards beyond the six classes `detour-compose-state-hazards`
   carries: **H14** `serverConfig` (`RoutingServer.load()`) is a **composition-time snapshot, never
   re-read**, so a server-config change mid-session is invisible to eight readers until the
   composition restarts; **H15** `spinJob`'s `CancellationException` rethrow ordering plus
   `finally { spinning = false }` is what makes "press spin while spinning" show no error; **H12**
   `stopNavigation()` writes `camTargetBearing`, a nav-lifecycle function reaching into camera state.
   → **`detour-compose-state-hazards/SKILL.md`** (H12 also belongs in `specs/stage-4`, which is about
   exactly that ownership question).

6. **`13-surface-independence-audit.md`** — the per-file `commonMain` consumer table (the only record
   that `SpinPicker.kt` is consumed by car and iOS but **not** the phone, and that
   `RoundTripPlanner.kt` is Android-only), the feature-parity matrix, and the sorting of the eight
   `withContext(Dispatchers.IO)` wrappers into **6 vestigial** (they wrap already-`suspend` Ktor
   calls) **and 2 genuine** (`SyncClient.sync`, `ExploredArea.load` — the latter because
   `ExploredArea.kt:50` is not `suspend`). `detour-shared-core` has the constraints qualitatively;
   the sorting is only here. → **`detour-shared-core/SKILL.md`**, which also has two citations to
   repoint.

7. **`05-proposal-mvi-reducer.md`** — SM1's **four-state × 14-transition camera-authority table**
   (`Following`/`Parked`/`Free`/`NavDriven`, including that `NavDriven` runs the frame loop even with
   `followMe == false`). `CameraAuthority.kt`'s KDoc covers actions, not the state matrix, and
   `specs/stage-4` needs the matrix. → **`CameraAuthority.kt` KDoc or `specs/stage-4`.** Also SM2's
   insight that the Overpass fallback yields a usable route **and** an error string simultaneously —
   a state `spinning: Boolean` + `error: String?` cannot express.

8. **`10-eval-fact-audit.md`** — **`com.jellemax.detour.data` is the same package name in both
   `shared/src/commonMain` and `app/src/main/java`**, so a reader cannot tell from the package which
   module a symbol is in (a real stage-3 trap, in no skill and no spec); **there is no Gradle version
   catalog** (`gradle/libs.versions.toml` does not exist; every version is an inline string literal),
   which makes every "pin to the existing version" step a literal string edit; and recomposition
   after each 1 Hz fix is **bursty, not continuous**. → **`detour-shared-core` (packages),
   `CONTRIBUTING.md` (no catalog), `detour-compose-state-hazards` (bursty).**

9. **`14-candidate-skills.md`** — the **rejected-skills table** (eight skills deliberately not built
   and why, including *"a skill of line numbers would be wrong within one commit and would be
   trusted while wrong"* — which is this audit's §4 thesis, written before the fact) and the eval
   design for the seven that exist. → **a short § in `detour-staged-refactor/SKILL.md`.**

10. **`plans/2026-08-11-stage-0-verification-baseline.md`** — Task 4 (0d) is the last unexecuted
    stage-0 item and this is its only design: **hold the fetch `Job` in a `remember` outside the
    effect, not inside it, because the effect is keyed on `navigating` and restarts, and a fetch in
    flight must not be forgotten or re-issued just because navigation started.** `specs/stage-0`
    only says "port the car's pattern", and the car's version lives in `lifecycleScope`, which has
    no equivalent here. → **`specs/stage-0-verification-baseline.md`, work item 0d.**

11. **`06-proposal-hexagonal.md`** — the beyond-`MapScreen` extraction map (**only ~150–200 of
    `TripTrackingService.kt`'s 1 333 lines are core**; the convoy protocol/transport split is
    explicitly unattempted) and one concrete gotcha:
    `Maneuver.Builder(TYPE_ROUNDABOUT_ENTER_AND_EXIT_CCW).build()` **throws without an exit number**
    (`docs/ANDROID_AUTO.md:92-100`), so a shared `Maneuver` enum still leaves real work in the car
    adapter. → **`detour-shared-core/SKILL.md`.**

**Loses nothing at all:** `01-proposal-mechanical-split.md`, `02-proposal-compose-state-holders.md`,
`03-proposal-viewmodel-uistate.md`, `plans/2026-08-12-stage-1-mechanical-split.md` (after the
cite-fix), and `04-proposal-domain-services.md` (its one unique artefact is a line-by-line
classification of a file that no longer exists).

### Two corrections to make whichever way this goes

- **`12-eval-risk-sequencing.md:491` and `:761-762`** still require `git show -M -C` rename detection
  as a done-criterion. `detour-file-split` §6, `detour-staged-refactor` §5 and `14-candidate-skills`
  all record that this is impossible on a modified file. Three documents currently point readers at
  those two wrong lines. Since this file is now in the **keep** set, fix them.
- **`specs/stage-0-verification-baseline.md:9`'s `Status:` row is stale** — it says tasks 2–4 are
  deferred, but the routes and baseline landed afterwards (`5fc8e90`, `e313a28`); only 0d is open.
  This is the third instance of the Status-drift failure that `detour-staged-refactor` §6 exists to
  prevent, and `chain-status.sh` reports that row verbatim.

### Findings already safe — do not spend effort preserving these

- The stage-2 carve-outs (`GroupSpinRules`' call site unchanged; no GPS replay run) — stage 2's
  `Status:` row and `DECISION.md`.
- Why route (iii) `off-route.txt` will never exist — `tools/mocklocation/routes/README.md`, at
  length, cross-referenced from `DECISION.md`.
- The unexplained early section clear at fix 81 with all five alternatives ruled out — 
  `baseline/README.md` and stage 3's `Status:` row. The most valuable single finding of the session,
  and it is **not** in `docs/refactor/`.
- The refutation of `maxke24/Detour#22`'s stated mechanism (clipping a section at the entry end
  *rejects* both relations against the 200 m `MIN_SPAN_M`, so *"clipping loses a section; it does not
  shorten one"* — **#22 needs correcting, not closing**) — `specs/stage-3`, which is kept.
- The stage-4 / `maxke24/Detour#21` collision (both rewrite the same nine camera write sites, so one
  must wait) and the quantified cause: **`CAM_POS_EPS_DEG = 2e-6` is ~0.22 m of latitude at 51°N, so
  at 60 fps the epsilon gate stalls the camera at roughly 35–50 km/h** — `specs/stage-4`, kept.
- The three `start-replay.sh` bugs — fixed in the committed script, described in
  `baseline/README.md:104-113`.

### Three findings that are safe today but should not live where they do

These are in `baseline/README.md`, which this proposal keeps but shrinks. They are product facts, not
baseline facts, and they will be lost the day anyone trims that README:

- **The Overpass no-backoff defect** — ~143 requests for one 17 km route, **136 of them retries into
  a refusal**; `center` only advances on a *successful* fetch (`MapScreen.kt:854-858`) so the first
  refusal leaves the radius trigger permanently true; exactly **one** fallback mirror
  (`RoadRoulette.kt:33-34`) and it was unusable all day; `connectTimeoutMillis = 5_000` against a
  mirror that takes 7 s to refuse. The README explicitly declines to file it. → **a GitHub issue.**
- **The trip `distanceMeters` inflation** — ×90 to ×315, non-deterministic: **the same route on the
  same device inflated ×273 once and ×315 the next**, while `topSpeedMps` stayed faithful, which
  confines the fault to the `distance += last.distanceTo(location)` accumulator in
  `onTripLocation`. → **a GitHub issue.** This is a user-visible defect on the trip card.
- **The ColorOS `MANAGE_APP_OPS_MODES` block** — `appops set … mock_location` and `pm grant` are both
  refused on the OnePlus 11 because the adb shell does not hold those permissions on that OEM; a
  human must set Developer options → Select mock location app by hand. → **`detour-adb/SKILL.md`**,
  as a device-class fact that will recur.
- **The `SectionAverageChip` calibration hazard** — `avg_blue` keys on `onTertiaryContainer`, which is
  the chip's text colour *only while the average is at or under the posted limit*; a red chip
  (`errorContainer`, once `averageKmh > limitKmh`) is invisible to that column, and it only failed to
  bite because neither E40 relation carries a `maxspeed` tag. → **`detour-gps-replay/SKILL.md`**, or
  it will bite whoever builds the next marker.

- The stage-2 carve-outs (`GroupSpinRules`' call site unchanged; no GPS replay run) — in stage 2's
  `Status:` row and `DECISION.md`.
- Why route (iii) `off-route.txt` will never exist — in `tools/mocklocation/routes/README.md`, at
  length, and cross-referenced from `DECISION.md`.
- The unexplained early section clear at fix 81 — in `baseline/README.md` with all five
  alternatives ruled out, and in stage 3's `Status:` row. This is the most valuable single finding
  produced by the whole session and it is **not** in `docs/refactor/`; it is safe.
- The `SectionAverageChip` red-chip calibration hazard (`avg_blue` keys on `onTertiaryContainer`,
  invisible once `averageKmh > limitKmh`) — in `baseline/README.md:173-182`. Safe, and worth
  promoting into `detour-gps-replay/SKILL.md` since it will bite the next person who builds a marker.
- The three `start-replay.sh` bugs (`grep -q` SIGPIPE under `pipefail`; missing `files/`; `run-as`
  cwd) — fixed in the committed script and described in `baseline/README.md:104-113`. Safe.
- The ColorOS `MANAGE_APP_OPS_MODES` block on the OnePlus 11 — in `baseline/README.md`. **Should be
  promoted into `detour-adb/SKILL.md`**: it is a device-class fact that will recur, and it is
  currently only in a baseline README that this proposal shrinks but keeps.
- The Overpass no-backoff mechanism (~143 requests for one 17 km route, 136 of them retries into a
  refusal; one unusable fallback mirror; `connectTimeoutMillis = 5_000` against a mirror that takes
  7 s to refuse) — in `baseline/README.md:492-517`. **This should be a GitHub issue**; it is a live
  product defect that the README explicitly declines to file, and it will be lost the day anyone
  trims that README.

---

## 5. `tools/mocklocation/routes/**` — the part that is worth keeping

Confirmed on every count the maintainer cares about.

| File | Bytes | Lines | Type |
|---|---|---|---|
| `trajectcontrole.txt` | **27 854** | 1 466 | `ASCII text` |
| `urban-limits.txt` | **27 398** | 1 442 | `ASCII text` |
| `stop-start.txt` | **14 478** | 762 | `ASCII text` |
| `README.md` | **9 431** | 152 | `ASCII text` |
| **total** | **79 161** | | |

**Small:** 79 161 bytes — **0.6 %** of what this branch adds, and **0.8 %** of the
`baseline/` directory alone. The three route files together are smaller than one of the fourteen
uncited screenshots.

**Text and diffable:** `file` reports plain ASCII. The format is one `lon lat` pair per line, one
line per replay interval, no timestamps and no speed column — a git diff of a route change is
readable.

**Genuinely reusable, and verified rather than asserted.** `README.md` measures each route from the
file itself on 2026-08-12: lengths (27.28 / 23.49 / 9.70 km), means and maxima, standstill runs by
fix index (one 8 s, one 19 s, and four of 12/42/15/11 s), and the longest continuous run above
7.0 m/s per route (474 s, 577 s, 180 s) so each can be shown to clear the auto-start gate. Speed
cameras in range are counted against Overpass per route (5 / 1 / 1 within `WARN_METERS`), with an
earlier non-reproducible claim of "68 in the bounding box" explicitly corrected to 45/66 rather than
carried forward. The average-speed geometry is re-verified against the OSM API: relation `15682532`
is a **full transit, west → east, 8.00 km in 382 s, mean 75.4 km/h — the value a correct section
average should settle at**, with device nodes 13 m and 0 m off the track. That last number is a
regression assertion, not a description.

**Reproducible in method, irreplaceable in fact.** The conversion is a committed, deterministic
script (`.claude/skills/detour-gps-replay/scripts/gpx2route.py`, no RNG, no clock dependence) that
prints statistics only and never coordinates — verified: every `print()` in it emits counts, lengths
and speeds. But no retained GPX reproduces any committed route. Best overlap from the two scratchpad
exports is 86 % of `urban-limits` sample points within 40 m, 45 % for `trajectcontrole`, 23 % for
`stop-start`, and the scratchpad is session-scoped and will be wiped. **The committed `.txt` files
are the only durable record of these three drives.** Delete them and the only way back is to drive
them again — and `stop-start`'s four traffic-light stops cannot be synthesised, which is the whole
reason the README gives for using real drives at all.

**Contrast with the rest, plainly.** For **79 161 bytes** the repo gets three reproducible drives
with named, measured assertions attached — the thing that lets a future refactor be *shown* not to
have broken existing behaviour. For **9 979 301 bytes** the `baseline/` directory gets one
recording session against route files that have since been replaced, whose transferable conclusions
fit in a README and 107 KB of TSV. **The fixtures are 126× cheaper than the evidence captured
against them, and they are the half that still works.** Keeping the routes while cutting the
screenshots is not a compromise between two goods; it is keeping the asset and discarding its
packaging.

---

## 6. Proposed end state

### `docs/refactor/mapscreen/`

```
docs/refactor/mapscreen/
├── DECISION.md                          26 826   (index of reports rewritten)
├── 12-eval-risk-sequencing.md           77 411   (the Tier 0-3 verification checklist)
├── 15-divergence-register.md           123 487   (keep until stage 3 lands, then → issues)
├── 16-repo-dirt-audit.md                         (this file)
├── plans/
│   └── 2026-08-12-stage-2-…md          67 991   (cited by CameraAuthority.kt's KDoc)
└── specs/                             134 756   (9 files, read by chain-status.sh)
```

**1 247 440 → 430 471 bytes**, 29 → 13 files. Removed: 11 reports, 4 plans, 1 verification
checklist.

Later, once stage 3 lands: the register's surviving entries become issues and it goes too, taking
`docs/refactor/mapscreen/` to ~307 KB. Not part of this pass.

### `tools/mocklocation/`

```
tools/mocklocation/
├── routes/                          79 161   (4 files — unchanged, this is the asset)
├── baseline/                       330 146   (15 files: README + 14 TSV)
└── src/                                      (unchanged)
```

**10 058 462 → 409 307 bytes**, 48 → 19 files.

### Totals

| | Bytes | Files |
|---|---|---|
| Branch adds today | **12 686 467** | 123 |
| `baseline/` PNG + logcat | −9 649 155 | −29 |
| `docs/refactor/` working material | −816 969 | −16 |
| `docs/security/` ASVS report (relocated) | −1 054 290 | −1 |
| **Branch adds after cleanup** | **1 166 053** | **77** |

**A 90.8 % reduction.** Whole tracked tree: **227 960 841 → 216 440 427 bytes.**

What remains: 430 471 bytes of decision records, specs and the verification checklist; 330 146 of
baseline conclusions; 205 037 of skills; 121 238 of Kotlin; 79 161 of route fixtures. The refactor's
paper trail ends up roughly 5× its own code, which for a chain with three stages still to run is
defensible — where 78 % of a branch being screenshots of a superseded route file is not.

No stated finding is lost **provided the eleven harvests in §4 are done in the same commits.** That
is the condition on this entire proposal, not a footnote to it.

If only one thing is done, do the `baseline/` PNG + logcat cut: **9.65 MB, 76 % of the whole
problem, in one commit, losing nothing that is written down anywhere.**

---

## 7. The commands — ready to run, NOT run

Nothing below has been executed. Run them in order; each block is one commit.

### Step 0 — prerequisites, before any deletion

```sh
# (a) Get the raw GPX out of the working tree. The only GPX action needed.
mkdir -p "$SCRATCH" && mv detour-car-2026-08-08-1341.gpx "$SCRATCH"/

# (b) Wait for the in-flight stop-start-2cbc5aa run to be committed, or these
#     paths will fight it. Confirm with:
git status --porcelain tools/mocklocation/baseline/
```

### Step 1 — file the issues, before anything is deleted

Six issues, from §4. These are the findings with no home in the repo:

```sh
gh issue create -R maxke24/Detour -t "Map surface stays ~14% dimmed after the search dialog closes" \
  -b "..."   # body from verification/stage-1-desk-checklist.md: the two RGB samples, reproduced 2/2,
             # fog and scrim both ruled out by experiment, established pre-existing not a split regression
gh issue create -R maxke24/Detour -t "Trip distanceMeters inflates x90-x315, non-deterministically"
gh issue create -R maxke24/Detour -t "Overpass: no backoff after a refusal, and one unusable fallback mirror"
gh issue create -R maxke24/Detour -t "Voice guidance: TTS engine torn down and rebuilt on a Hub round trip"
gh issue create -R maxke24/Detour -t "Voice guidance: audio focus leaks when leaving the map while navigating"
gh issue create -R maxke24/Detour -t "Device verification outstanding for convergence 1 and 3 (14 checks)"
```

Also correct, don't close, `maxke24/Detour#22` — `specs/stage-3` shows its stated mechanism is wrong.

### Step 2 — harvest into the durable homes, and fix the citations

Manual edits, no command. One commit. **This is the gate on everything after it.**

Harvests, per §4: H12/H14/H15 → `detour-compose-state-hazards`; F-C and the roundabout
`Maneuver.Builder` gotcha → `specs/stage-3` and `detour-shared-core`; F-E and the no-version-catalog
fact → `CONTRIBUTING.md`; F-H → `DECISION.md` § Not doing; the `Dispatchers.IO` 6-vestigial/2-genuine
sorting and the `commonMain`/`app` package collision → `detour-shared-core`; SM1's four-state camera
table → `CameraAuthority.kt` KDoc or `specs/stage-4`; 0d's "hold the `Job` outside the effect" →
`specs/stage-0`; the fence-writing lesson and the rejected-skills table → `detour-staged-refactor`;
the `SectionAverageChip` red-chip hazard → `detour-gps-replay`; the ColorOS `appops` block →
`detour-adb`; the convergence-#5 test-placement reversal → stage 2's `Status:` row.

Citations to repoint or fix:

- `.claude/skills/detour-shared-core/SKILL.md` — repoint both citations of
  `13-surface-independence-audit.md` at `CONTRIBUTING.md` and `15-divergence-register.md`.
- `.claude/skills/detour-file-split/SKILL.md` — it already reproduces
  `plans/2026-08-12-stage-1-mechanical-split.md:207-216` in full; drop the dangling path.
- `12-eval-risk-sequencing.md:491` and `:761-762` — remove the `git show -M -C` rename-detection
  criterion. **This file is kept**, and three documents point at those two lines.
- `specs/stage-0-verification-baseline.md:9` — its `Status:` row still says tasks 2–4 are deferred;
  the routes and baseline landed at `5fc8e90`/`e313a28`. Only 0d is open.

`.claude/skills/detour-compose-state-hazards/SKILL.md`'s citation of
`12-eval-risk-sequencing.md:48-159` needs **no** change — that file stays.

Verify nothing else points into the cut set:

```sh
grep -rn -e 'mapscreen/0[0-9]-' -e 'mapscreen/1[0134]-' \
  -e 'plans/2026-08-11' -e 'plans/2026-08-12-stage-1' -e 'plans/2026-08-12-convergence' \
  -e 'mapscreen/verification/' \
  --include='*.md' --include='*.sh' --include='*.py' --include='*.yml' --include='*.kt' . \
  | grep -v '^\./docs/refactor/mapscreen/'
# must print nothing before proceeding
```

### Step 3 — the baseline cut (the big one, 9 649 155 bytes)

```sh
git rm tools/mocklocation/baseline/*.png
git rm tools/mocklocation/baseline/*.log

# Optional compromise: keep the two smallest cited montages (+125 806 bytes)
#   git checkout HEAD -- tools/mocklocation/baseline/stop-start-09fddde-bearing-hold.png \
#                        tools/mocklocation/baseline/trajectcontrole-b29d014-chip-values.png

# Then edit tools/mocklocation/baseline/README.md: the Artifacts table's PNG and .log
# rows, and the 18 inline "…-<topic>.png" citations, become statements of
# the number rather than pointers to a file. The measured values are already in the prose,
# so this is deletion of pointers, not of findings. Keep the ⚠ ba74e40 warning box and
# the whole "named quantities" Q1-Q9 table verbatim — that table is the deliverable.
git add tools/mocklocation/baseline/README.md
git commit -m "chore(tools): keep the baseline's numbers, drop the pixels they were read from

The Q1-Q9 table, the events/stall/frame TSVs and the README's prose hold every
conclusion. The 25 PNGs and four logcat dumps they were derived from do not:
14 of the 25 PNGs are cited by nothing, the 11 that are cited are cited by
sentences that already state the number, and 85% of the logcat is Samsung
platform chatter. All of it was captured against route files that ba74e40
replaced, so none of it can be re-compared frame by frame anyway.

9 979 301 -> 330 146 bytes."
```

### Step 4 — relocate the ASVS report (1 054 290 bytes)

```sh
git rm docs/security/asvs-5.0.0-l2-detour-independent-2026-08-11.md
git commit -m "chore(docs): take the ASVS report out of the refactor branch

A 14 827-line independent ASVS L2 assessment arrived inside 21a02b4,
a stage-1 bookkeeping commit, and nothing in the repo references it.
It is the second-largest file this branch adds and it is unrelated to
the MapScreen refactor. It should land on its own terms."
```

Preserve the content first — it is a real document, just not this branch's:
`git show HEAD:docs/security/asvs-5.0.0-l2-detour-independent-2026-08-11.md > ~/asvs-l2.md`

### Step 5 — the docs/refactor cut (816 969 bytes)

```sh
git rm docs/refactor/mapscreen/00-inventory.md \
       docs/refactor/mapscreen/01-proposal-mechanical-split.md \
       docs/refactor/mapscreen/02-proposal-compose-state-holders.md \
       docs/refactor/mapscreen/03-proposal-viewmodel-uistate.md \
       docs/refactor/mapscreen/04-proposal-domain-services.md \
       docs/refactor/mapscreen/05-proposal-mvi-reducer.md \
       docs/refactor/mapscreen/06-proposal-hexagonal.md \
       docs/refactor/mapscreen/10-eval-fact-audit.md \
       docs/refactor/mapscreen/11-eval-fit-maintainability.md \
       docs/refactor/mapscreen/13-surface-independence-audit.md \
       docs/refactor/mapscreen/14-candidate-skills.md
git rm docs/refactor/mapscreen/plans/2026-08-11-stage-0-verification-baseline.md \
       docs/refactor/mapscreen/plans/2026-08-12-stage-1-mechanical-split.md \
       docs/refactor/mapscreen/plans/2026-08-12-convergence-1-cheap-fixes.md \
       docs/refactor/mapscreen/plans/2026-08-12-convergence-3-voice-policy.md
git rm -r docs/refactor/mapscreen/verification
# NOT removed: 12-eval-risk-sequencing.md (the Tier 0-3 checklist), specs/,
#              plans/2026-08-12-stage-2-pure-extractions.md (CameraAuthority.kt cites it)

# Then edit DECISION.md: replace the "Index of reports" section (DECISION.md:69) with a
# line recording that the reports existed, what they concluded, and that they are
# recoverable at e0c49d5. Also drop the two dead references to
# docs/refactor/mapscreen/scripts/check-divergences.sh from 15-divergence-register.md
# and specs/00-chain-design.md — that script was never written.
git add docs/refactor/mapscreen/DECISION.md \
        docs/refactor/mapscreen/15-divergence-register.md \
        docs/refactor/mapscreen/specs/00-chain-design.md
git commit -m "chore(docs): retire the refactor's working material

Eleven investigation reports, four executed plans and a desk checklist for a
finished stage. What they decided is in DECISION.md; what shipped is in each
spec's Status row; what recurs is now in .claude/skills/ and CONTRIBUTING.md;
what was only ever a defect is now an issue.

They are also actively misleading: they cite MapScreen.kt by line number 318
times and the file went 3197 -> 1658 lines on this branch. Twelve citations
point past the end of the file; the rest resolve to the wrong line silently.
15-divergence-register.md already re-derived audit 13's facts for this reason.

Kept: 12-eval-risk-sequencing.md, which is not an evaluation but the only copy
of the Tier 0-3 verification checklist every spec's done-criteria name; and the
stage-2 plan, whose write-site mapping table CameraAuthority.kt cites by name.

Recoverable at e0c49d5. 1 247 440 -> 430 471 bytes."
```

### Step 6 — verify nothing regressed

```sh
bash .claude/skills/detour-staged-refactor/scripts/chain-status.sh   # all 8 stages still resolve
grep -rn 'docs/refactor' --include='*.md' --include='*.sh' . | grep -v '^\./docs/refactor/'
git ls-tree -r -l HEAD | awk '{s+=$4} END {print s}'   # expect ~216 440 427
                                                       # (227 960 841 today, minus the three cuts,
                                                       #  plus this audit and the harvest edits)
grep -c 'RFCT42HS9WY' tools/mocklocation/baseline/README.md   # 13 today; redact if desired
```

### Not proposed

- **No history rewrite.** No `filter-repo`, no `BFG`, no force-push. There is nothing in history to
  remove: the GPX blob was never written to the object database, and everything else being cut is
  documentation whose presence in history is harmless and whose recoverability at `e0c49d5` is the
  point.
- **No `pm clear`, no device changes.** Out of scope.
- **No touching `tools/mocklocation/routes/`.** That is the asset.
