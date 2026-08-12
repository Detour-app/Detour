# Convergence 2 — the car and iOS trajectcontrole readouts

## Status

| | |
|---|---|
| **Detail level** | **Intent + constraints.** The Work items section **requires a rewrite before use** — see the marker below. It cannot be written yet, and not for want of effort: the readouts consume a type that does not exist until stage 3 chooses it |
| **Prerequisite** | [Stage 3](stage-3-hazard-machines-to-shared.md) complete — **this is the convergence axis' one dependency on the structure axis.** Convergence 1 is not a prerequisite |
| **State** | not started · blocked on stage 3, which is itself blocked on replay route (i). **Preconditions re-run 2026-08-12 against `3928ce0`, after convergence 1 landed: the two stage-3 interlock assertions still fail (no `drive/` package in commonMain, zero `SectionAverageTracker`) and the other eight all pass unchanged.** That is the interlock working, not staleness — convergence 1 touched nothing this stage reads |
| **Preconditions captured** | 2026-08-12 against `5613e59`. Every assertion was executed; the first two **fail today by design** — they are the interlock, and they are what tells you stage 3 has actually landed the tracker rather than something adjacent to it |
| **Chain** | [design](00-chain-design.md) · [register](../15-divergence-register.md) · prev: [convergence 1](convergence-1-cheap-fixes.md) · next: [convergence 3](convergence-3-voice-policy.md) |

## Preconditions

`chain-status.sh` does not see this file; paste the fence into a shell. The two failing
assertions are the gate, not staleness — re-read
[`00-chain-design.md`](00-chain-design.md) § *The two axes* if that distinction is not obvious.

```sh
CAR=app/src/main/java/com/jellemax/detour/car
M=app/src/main/java/com/jellemax/detour/ui/MapScreen.kt

# Stage 3 landed machine 1, in commonMain, where iOS can reach it. FAILS TODAY (0 / absent).
test -d shared/src/commonMain/kotlin/com/jellemax/detour/drive && echo stage3-done
grep -rl 'SectionAverageTracker' shared/src/commonMain/kotlin | wc -l   # expect 1

# The car still fetches the section data and throws it away — the three-line half of this
# stage. If this stops being 0, someone did the car readout already.
grep -rl '\.sections' $CAR | wc -l                                 # expect 0
grep -c 'result.cameras' $CAR/NavScreen.kt                         # expect 1
grep -c 'fun updateHud(speedKmh: Double, limitKmh: Double?)' $CAR/CarMapRenderer.kt   # expect 1

# iOS has never seen this data at all, even though SpeedCameras is commonMain.
grep -rl 'SpeedCameras' iosApp | wc -l                             # expect 0

# The phone's readout still exists to copy, and still carries the average and its limit as
# two separate values — the shape decision 2 says not to export.
grep -c 'SectionAverageChip' app/src/main/java/com/jellemax/detour/ui/MapHud.kt   # expect 2
grep -c 'averageKmh = sectionAvgKmh' $M                            # expect 1
grep -c 'sectionAvgKmh' $M                                         # expect 5

# The iOS FlowWatcher cost this stage pays: one subclass per element type.
grep -c '^class .*Watcher' shared/src/iosMain/kotlin/com/jellemax/detour/data/FlowWatcher.kt   # expect 9
```

## Why this stage

Register decision 2 ([`../15-divergence-register.md`](../15-divergence-register.md) §C, entry
11) is **all three surfaces get the trajectcontrole average**. That decision does two things,
and only one of them belongs to stage 3.

What belongs to stage 3 is the *destination and the shape*: `SectionAverageTracker` goes to
commonMain because iOS cannot consume anything in `app/`, and its output is a public contract
for three consumers rather than a phone detail. That is recorded in stage 3's **Consumed
decisions** section and is not this spec's business.

What belongs here is the *readouts*, and the register is explicit that they are feature work
after the extraction, not part of it: *"The car is cheap: it already fetches the section data and
discards it. iOS is the real work — SwiftUI readout plus the watcher — and is feature work after
stage 3 lands the tracker, not part of it."* Nothing in items 4–6 of §C.1 may share a commit
with the extraction it depends on.

The asymmetry is the point of giving this its own spec. The car half is three lines. The iOS
half is a SwiftUI readout on a surface with no `SpeedCameras` caller today, a `FlowWatcher`
subclass if the element type is new, and a verification story that needs a Mac. Folding that into
stage 3 would make the structure axis wait on Xcode.

## Scope

- **Car**: keep `result.sections` at `car/NavScreen.kt:396-401` instead of discarding it, drive
  the shared tracker with the same fix stream the camera warner uses, and surface the average in
  the car HUD.
- **iOS**: consume the tracker from SwiftUI and show the average during a section, matching what
  `SectionAverageChip` does on the phone (`ui/MapHud.kt:234-250`) — a running average that turns
  red once it exceeds the section limit.
- **Phone**: point the existing readout at the shared tracker if stage 3 has not already done so,
  and nothing else. The phone's behaviour is the reference and must not change here.

## Out of scope

- **The extraction itself.** Stage 3, machine 1.
- **Entry 18 — whether a readout is visible at a standstill.** The register records this as a
  constraint on the tracker, not a decision for it: the tracker emits a value, each surface
  decides whether to show it. If this stage makes the tracker emit "no section" in order to hide
  a chip, it has made entry 18's decision by accident. Whether the car's HUD fades like the
  phone's is a separate one-line product answer where *"leave both"* is defensible.
- **The car HUD's other four readouts.** Entry 11 records the real concern — the head unit is
  already at speed, posted limit, ETA card and action strip, and a fifth readout at arm's length
  may be too much. Decision 2 settled *that the car gets the average*; it did not redesign the
  HUD. Layout is in scope for this stage's own brainstorm; re-opening adoption is not.
- **Wear.** It has no `:shared` dependency by design (stage 3 § Out of scope) and draws no
  section UI.

## Work items

> **Rewrite this section before use.** Run `superpowers:brainstorming` against this spec once
> stage 3 has landed `SectionAverageTracker`, with its actual signature in hand, then write the
> plan. This marker is a scheduled decision, not an unfinished section — the same convention
> stages 3 and 4 use, and for the same reason: the thing this section must describe does not
> exist yet.

What the rewrite must produce:

1. **Per surface, who drives the tracker.** Stage 3's constraint is that no machine owns a
   `CoroutineScope`; the caller drives it. On the car that is `NavScreen`'s fix handling; on iOS
   it is whatever object owns the `CLLocationManager` stream. Name them.
2. **The element type actually chosen by stage 3, and whether it needs a new `FlowWatcher`
   subclass.** There are nine today (verified). Decision 2 asks for the average and the posted
   limit as *one* value precisely so this stage adds at most one.
3. **Where each readout sits**, and for the car, which of the two drawing surfaces it uses —
   the `RoutingInfo`/`NavigationTemplate` path or the renderer's HUD.
4. **The verification per surface**, including what cannot be verified without hardware.
5. **The commit boundaries**: car and iOS never share one, and neither shares one with stage 3's
   extraction.

## Done criteria and verification

- [ ] The car keeps `result.sections`, drives the shared tracker, and shows the average.
- [ ] iOS shows the average during a section, red over the section limit.
- [ ] `grep -rl 'SpeedCameras' iosApp | wc -l` is no longer 0.
- [ ] No new `expect` declaration in `Platform.kt`, and no `Dispatchers.*` in commonMain — both
      are stage 3's constraints and this stage is their first real consumer test.
- [ ] Entry 11 marked **resolved** in the register with the commits and which way it went.

Verification: the car readout is a `lastFix` consumer on a nav screen — Tier 2, replay against
the stage-0 baseline, which by then exists (stage 3 could not have landed otherwise). Route (i)
is the one that enters and exits a gantry, so it is the only route that exercises this at all.
iOS gets no Tier 2 equivalent: `ios.yml:58-68` type-checks commonMain and runs `commonTest` on
JVM and Kotlin/Native, which covers the tracker but nothing about a SwiftUI readout. Say so
rather than implying the CI green covers the feature.

## Next stage

→ [`convergence-3-voice-policy.md`](convergence-3-voice-policy.md)

Its preconditions do **not** depend on this spec or on stage 3 — only on convergence 1's
microphone-permission item. If this spec is blocked, convergence 3 is still runnable.
