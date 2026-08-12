# Convergence 3 — the announcement policy into `shared/`, then the phone's voice

## Status

| | |
|---|---|
| **Detail level** | **Intent + constraints.** The Work items section **requires a rewrite before use**. This is the largest single item the register produced and the least specifiable today — the policy's shape is knowable, the phone's audio behaviour is not, and pretending otherwise would produce a plan that fails on first contact with audio focus |
| **Prerequisite** | [Convergence 1](convergence-1-cheap-fixes.md) work item 1 — the iOS microphone permission. **Not** stage 3, and not convergence 2 |
| **State** | not started |
| **Preconditions captured** | 2026-08-12 against `20aa813`. Every assertion was executed. The first one **fails today on purpose**: it asserts convergence 1 has landed |
| **Chain** | [design](00-chain-design.md) · [register](../15-divergence-register.md) · prev: [convergence 2](convergence-2-section-readouts.md) · next: none — this is the end of the convergence axis as the register defined it |

## Preconditions

`chain-status.sh` does not see this file; paste the fence into a shell.

```sh
CAR=app/src/main/java/com/jellemax/detour/car

# Convergence 1 item 1 landed. FAILS TODAY — this is the ordering gate, and it is the whole
# reason §C.1 put the microphone permission first.
grep -c 'NSMicrophoneUsageDescription' iosApp/Detour/Info.plist   # expect 1

# The phone still has no speech of any kind. One file in app/ mentions TextToSpeech and it is
# car/NavVoice.kt; if this becomes 2, someone started this stage.
grep -rl 'TextToSpeech' app/src/main/java/com/jellemax/detour | wc -l   # expect 1
grep -c 'speak(' $CAR/NavScreen.kt                                # expect 6

# The ladder is written twice, identically — which is what earns it the core.
grep -c 'VOICE_FAR_M' $CAR/NavScreen.kt                           # expect 2
grep -c 'voiceFarM' iosApp/Detour/NavScreen.swift                 # expect 2

# ...to one boundary, which still disagrees: <= against ..<
grep -c 'distance <= VOICE_NOW_M' $CAR/NavScreen.kt               # expect 1
grep -c 'case ..<Self.voiceNowM' iosApp/Detour/NavScreen.swift    # expect 1

# The two sub-bugs entry 12 carries: iOS mute does not cut the utterance in flight (the single
# stop() call is the .onDisappear one), and the phone's settings copy admits the setting does
# nothing on the surface it is shown on.
grep -c 'voice.stop()' iosApp/Detour/NavScreen.swift              # expect 1
grep -c 'Turn instructions read aloud' app/src/main/java/com/jellemax/detour/ui/SettingsScreen.kt   # expect 1
grep -c 'voiceGuidance' shared/src/commonMain/kotlin/com/jellemax/detour/data/Settings.kt   # expect 4
```

## Why this stage

Register decision 1 ([`../15-divergence-register.md`](../15-divergence-register.md) §C, entries
15 and 12) is **full parity — port `NavVoice` to the phone**. The register calls it *"the one
decision in the register with no technically-correct answer"* and *"the largest single item to
come out of this register"*, and both of those shape this spec more than any code detail.

Three facts make it a two-step job rather than one:

1. **The policy is already written twice and agrees.** `800/300/80`, `spokenDistance` and the
   phase latch exist in `car/NavScreen.kt` and in `iosApp/Detour/NavScreen.swift` and are
   equivalent (entry 12). That is the *"a policy earns the core when it is written more than
   once"* rule, and decision 1 adds a **third** consumer — which is the argument for moving the
   policy to `shared/` before writing a third copy of it, not after.
2. **The phone has no speech at all.** Exactly one file under `app/` mentions `TextToSpeech`
   and it is `car/NavVoice.kt` (150 lines; the `AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK` request at
   `:41-43`, taken and released per prompt at `:138-149`). So the phone half
   is a *feature*, with a new dependency and a new failure mode — ducking the user's music — on
   the app's most-used surface.
3. **A second audio client on top of a broken first one is two bugs at once.** iOS PTT activates
   `.playAndRecord` with no microphone permission declared (§B5). §C.1 puts that first for this
   reason, and it is this spec's only precondition.

## Scope

Two steps, in this order, plus two small fixes that belong to the same entry.

**Step 1 — the announcement policy into `shared/`.** The ladder, `spokenDistance`, the phase
latch and the re-arm-on-reroute rule (`car/NavScreen.kt:272-274`). Decision and wording in the
core; **delivery per platform**, following `CircleEvents.kt` — the same precedent stage 3 is told
to copy, and for the same reason: a core that knows about `TextToSpeech`, `AVSpeechSynthesizer`
or a `CarToast` cannot be shared.

**Step 2 — the phone's `NavVoice`.** Audio focus and ducking, the `Settings.voiceGuidance`
consumer that is currently missing (three consumers, two voices — `Settings.kt:132`), and turn
announcements from the phone's nav loop.

**Two fixes from entry 12 that are not decisions**, each its own commit:

- iOS's mute must stop the utterance in flight, as the car's does
  (`car/NavScreen.kt:470-473`). Today the only `voice.stop()` on iOS is the `.onDisappear` one.
- The `<=` / `..<` boundary must be made to agree. It is a measure-zero difference in the field
  and a guaranteed difference in any characterisation test written against one surface — which
  is exactly what step 1 will write.

**And one documentation defect.** `SettingsScreen.kt:307` currently tells the user the setting
applies *"on the car screen"*, which is honest today and false the moment step 2 lands. That
edit belongs to step 2's commit, not to a later cleanup — the register makes the same point
about `README.md:383-385` in entry 1.

## Out of scope

- **The camera-chime fallback (entry 1) and the `+3.0` / `45.0` thresholds (entry 13).** They are
  stage 3's `CameraWarner`, recorded in its **Consumed decisions** section. This spec may consume
  a warning decision; it must not re-derive when to warn.
- **Whether the phone's hazard announcement hangs off the inline latch or off `CameraWarner`.**
  Both are delivery sites for a decision made elsewhere, so this is sequencing hygiene rather
  than a dependency: if stage 3 has landed, deliver from the machine's warning decision; if it
  has not, deliver next to the existing `toneGen` call (`MapScreen.kt:871-874`). Either way the
  policy is not this spec's to invent, and this spec does **not** wait on stage 3.
- **Wear.** No `:shared` dependency, no audio.
- **Entry 19's distance quantisation.** It touches the same banner text and is a separate,
  low-stakes product answer.
- **The PTT audio path beyond convergence 1's permission fix.** §B5's other two fixes and §B6's
  convoy gaps are bugs on their own tracks.

## Work items

> **Rewrite this section before use.** Run `superpowers:brainstorming` against this spec once
> convergence 1's permission item has landed. Step 1's shape is nearly specifiable already; step
> 2's is not, and the reason is worth stating rather than papering over: what the phone should do
> when another app holds audio focus, when the rider is on a call, and when music is playing
> through a helmet intercom are product answers nobody has given, and they decide the API before
> they decide the code.

What the rewrite must produce:

1. **Step 1's signature**, with time injected rather than read — the phase latch is
   path-dependent over a distance sequence and a machine that reads its own clock cannot be
   tested deterministically. Same constraint as stage 3's machines, same reason.
2. **The `commonTest` cases** for the ladder, including the boundary the two surfaces currently
   disagree on. Write them before either surface is repointed.
3. **The delivery interface per surface**, and the proof that no platform type leaked into
   commonMain.
4. **Step 2's audio-focus behaviour**, stated as decisions: does the phone duck or pause, does it
   speak at all when the screen is off, what happens on a phone call. `car/NavVoice.kt:41-43` and
   `:138-149` are the reference implementation and the only written precedent in the repo.
5. **Commit boundaries**: the shared policy, each surface repointed, the two entry-12 fixes, and
   the phone feature — none of them sharing a commit, and the car's repoint trailing the
   extraction by exactly one commit (`detour-staged-refactor` §4).

## Done criteria and verification

- [ ] The announcement policy exists once, in `shared/`, with `commonTest` coverage, and both
      existing surfaces call it. No `TextToSpeech`, `AVSpeechSynthesizer` or toast type anywhere
      near it.
- [ ] The phone announces turns, honours `Settings.voiceGuidance`, and handles audio focus.
- [ ] `SettingsScreen.kt`'s description no longer says the setting is car-only.
- [ ] iOS mute stops the utterance in flight; the phase boundary agrees across surfaces.
- [ ] Register entries 12 and 15 marked **resolved** with their commits and which way they went.

Verification, and this is the stage where the tiers bite hardest:

- **Step 1** — Tier 0 plus `:shared` tests on both JVM and Kotlin/Native (`ios.yml:64-68`). The
  policy is pure once time is injected, so this is the cheapest part to verify and the part most
  likely to be assumed rather than tested.
- **Step 2** — the phone's nav session with audio: Tier 2 at minimum (replay route (ii) exists,
  so announcements *can* be driven from a desk), and a real ride or a paired intercom for the
  ducking question, which no replay can answer. `detour-staged-refactor` §5 puts a navigation
  session at Tier 3.
- **The iOS fixes** — a Mac with Xcode 16 (`CONTRIBUTING.md:9`). If unavailable, record them as
  unverified.

## Next stage

None. This is the last item in the register's §C.1 order.

When it closes, the register's four decisions are all discharged and §A's remaining
*needs-a-human* entries (14, 18, 19, 21) are what is left of the convergence axis — four one-line
answers, not a chain. Record that in [`../15-divergence-register.md`](../15-divergence-register.md)
rather than starting a fourth spec for them, and note in
[`00-chain-design.md`](00-chain-design.md) that the axis is complete. The register's own warning
applies to any successor: *"If this list grows past six, the register has stopped working."*
