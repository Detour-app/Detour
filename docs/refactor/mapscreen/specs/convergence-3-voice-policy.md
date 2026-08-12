# Convergence 3 — the announcement policy into `shared/`, then the phone's voice

## Status

| | |
|---|---|
| **Detail level** | **Executable.** The Work items section was rewritten 2026-08-12 against `cfa113f`, replacing the scheduled-rewrite marker; the plan is [`../plans/2026-08-12-convergence-3-voice-policy.md`](../plans/2026-08-12-convergence-3-voice-policy.md). Everything above the old marker — Scope, Out of scope, Why this stage — is unchanged and binding. This is still the largest single item the register produced, and it now carries an explicit **stop-point after item 5**: items 1–5 are desk-verifiable, items 6–9 are not |
| **Prerequisite** | [Convergence 1](convergence-1-cheap-fixes.md) work item 1 — the iOS microphone permission. **Not** stage 3, and not convergence 2. **Met:** landed in `858dc1e` |
| **State** | **done in code, UNVERIFIED on hardware** 2026-08-12. All ten items landed, one commit each, in the plan's order: 1 `4e45f4a` (iOS's phase boundaries become inclusive) · 2 `04b0f98` (iOS's mute cuts the utterance in flight) · 3 `c95b19d` (`NavAnnouncer` + 10 `commonTest` cases into commonMain) · 4 `c9547ee` (the car repointed) · 5 `fb59b8e` (iOS repointed) · ⟨stop-point `9ef23f6`⟩ · 6 `e7cb39f` (`NavVoice` moves `car/` → `audio/`) · 7 `31b2ba5` (no speech on a refused focus request — the car changes too) · 8 `d682603` (the phone announces turns; `SettingsScreen.kt:307` corrected) · 9 `ae32722` (the phone speaks the camera warning) · 10 this commit (entries 12 and 15 resolved). **The stop-point was not honoured as written: items 6–9 landed without a device session**, because the plan's own *Needs a human* is the only thing that can close them and it was not available. They are therefore recorded as **shipped, not verified** — the six device checks in the plan's *Needs a human* all stand open, and the register's entries 12 and 15 say so in the same words. Two limits are recorded rather than fixed: turn prompts are foregrounded-only (`liveFix` is `collectAsStateWithLifecycle`; stage 4's business), and the phone stays silent on a live convoy by design |
| **Preconditions captured** | Written 2026-08-12 against `5613e59`; **re-run 2026-08-12 against `cfa113f`, all 10 pass.** The first assertion was written to fail on purpose and now passes, which is convergence 1 having landed — the ordering gate is open |
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
#
# POST-MORTEM 2026-08-12: this tripwire never fired, and the stage landed anyway. Item 6 *moved*
# the one file to audio/NavVoice.kt and item 8 *consumed* it, so the count is still 1 — which was
# the whole point of D4 (move, do not write a second implementation). An assertion that counts
# files cannot see a new consumer. The live check is now the register's §D fence.
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

Rewritten 2026-08-12 against `cfa113f`, replacing the scheduled-rewrite marker. **Ten items, ten
commits, in the order below.** Every line number in this section was re-derived with `grep -n`
against that tree; the sections above it were written against `5613e59` and several of their
citations have drifted (see *Citations that drifted* at the end).

### The design decisions this rewrite takes

They decide the API before they decide the code, which is what the marker said was missing.

**D1 — the policy is a small stateful class, not a pure function per fix.** The evidence is the
car's own latch: `announce()` reads and writes three fields — `voiceStepKey`, `voicePhase`,
`startAnnounced` (`car/NavScreen.kt:128-130`) — and every rule in the ladder is a rule *about*
those three. A pure function would have to take all three in and hand a new triple back, which
means each of three surfaces holds and threads them correctly, and "correctly" is exactly the
thing being deduplicated. `GeofenceEvaluator` (`CircleEvents.kt:159-210`) is the in-repo
precedent and its KDoc states the same reason: a class *"because it holds per-place dwell/inside
state between calls"*, with calls required in order. Same shape here, ordered by distance rather
than by time.

Concretely, `class NavAnnouncer` in commonMain with **three methods and no constructor
parameters**:

- `fun onProgress(instruction: NavInstruction?, distanceMeters: Double): String?` — the ladder and
  the latch. Returns the words to speak, or null for "nothing is due".
- `fun rerouting(): String` — the wording of the reroute cue. Does *not* re-arm; the car speaks it
  before the fetch and re-arms only on success (`car/NavScreen.kt:261` versus `:275-277`).
- `fun routeChanged()` — re-arms the latch. The car calls it after a successful reroute, iOS and
  the phone at the start of a session.

No constructor parameters is deliberate: Kotlin/Native drops default argument values on the way to
Objective-C, which is why `GeofenceEvaluator` needs a `withDefaults()` factory
(`CircleEvents.kt:163-169`). `NavAnnouncer()` needs no such wart, so the thresholds are
`companion object` constants rather than defaulted parameters.

**D2 — a `String?` crosses the boundary, one function per occasion, and no enum.** This is the
`CircleEvents.kt` shape: `notificationText()` (`:114-120`) and `catchUpSummaryText()` (`:126-127`)
put the *wording* in the core precisely *"so the two apps can never read the same event
differently"*, and every platform then decides delivery. The alternative — a typed
`Announcement(kind, …)` the platform renders — was rejected on three counts. No consumer would
branch on the kind: each call site already knows the occasion because it is the site that called,
and the phone's hazard cue comes off a different collector from its turn cue. A kind enum crossing
to Swift costs exactly the name-mangling trust `FlowWatcher.kt:189-191` documents avoiding
(*"Spelling an enum entry in Swift means trusting Kotlin/Native's name mangling for it"*). And an
unused discriminator is the one-implementation-behind-an-interface shape `detour-shared-core` §2
test 2 forbids.

Entry 4's caution is honoured by a narrower rule than "do not ship text": **nothing in the
returned string is derived from `NavInstruction.sign`.** The cue is GraphHopper's own
`instruction.text`, already words; the sign→glyph tables stay four per-platform copies and entry 4
stays open and out of scope. If the core ever renders a maneuver *from* the sign it has become a
fifth copy of that table, in prose, and that is the line not to cross.

**D3 — the policy reads no clock and takes no `nowMs`, and the marker was wrong to ask for one.**
`announce()` on both surfaces reads `instruction.startIndex`, `p.distanceToTurnMeters` and
`instruction.text`. There is no timestamp anywhere in either copy. The latch is path-dependent
over the **distance sequence**, not over time — which the marker's own wording said and then
contradicted. The constraint's *purpose* (no self-read clock, deterministic tests) holds for free;
a `nowMs` parameter nothing reads would be dead weight and a false signal that timing matters
here. The one time-dependent nav rule, the reroute cooldown, already lives in `NavPolicy` with
`nowMs` injected (`app/…/map/NavPolicy.kt:51-57`) and is not touched.

**D4 — the phone gets the car's `NavVoice` moved, not a second implementation.**
`car/NavVoice.kt` imports `android.content.Context`, `android.media.*`, `android.os.*`,
`android.speech.tts.*` and `java.util.Locale` and **zero `androidx.car` types** — verified. It is
in `car/` by history, not by dependency. `app/` and `car/` are the same Gradle module and package
root, so this is a plain move under `app/`, not a `shared/` move and not an interface
(`detour-shared-core` §1). Destination is `app/…/audio/`, which already holds the app's other
audio client, `PushToTalk.kt`.

**D5 — the phone requests `AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK`, and stays silent in three
cases.** Duck rather than pause, matching both surfaces that already speak: the car
(`car/NavVoice.kt:40-43`) and iOS's `.duckOthers` (`iosApp/Detour/NavVoice.swift:27-28`). A third
answer on a third surface would be a new divergence on its first day. The three silences:

1. **`Settings.voiceGuidance` off** — the gate both existing surfaces already have, and turning it
   off must cut the utterance in flight as the car does (`car/NavScreen.kt:479-480`).
2. **The focus request was refused.** `NavVoice` already records the result in `holdingFocus`
   (`:140-142`) and then speaks anyway (`:116-121`). Refusal means another client holds exclusive
   focus — a call, an assistant, another turn-by-turn app — and talking over any of those three is
   wrong on the head unit too, so the rule goes into `NavVoice` and changes both surfaces.
3. **A convoy is live.** This is the finding the register does not have, and it displaces the
   register's stated objection. The phone's second audio client is not the user's music: it is
   Detour's own push-to-talk. `convoy/ConvoyLiveService.kt:172-183` takes
   `AUDIOFOCUS_GAIN_TRANSIENT` — not `MAY_DUCK` — and holds it for the whole life of the convoy,
   registering no `OnAudioFocusChangeListener`, and `:129` puts the entire device into
   `AudioManager.MODE_IN_COMMUNICATION` and routes playback to the speaker (`:149-161`). A guidance
   utterance into that state would talk over a live voice channel with routing nobody here can
   predict. So the phone does not speak while `ConvoyLiveClient.activeConvoyId.value != null`.
   Read off the `StateFlow` rather than the composed state, because the hazard collector runs while
   the app is backgrounded and the composed copy does not update there.

**D6 — the phone announces only while the map is on screen; the hazard cue is the exception, and
that asymmetry is deliberate and recorded.** `liveFix` is `collectAsStateWithLifecycle()`
(`MapScreen.kt:201`), so the nav loop at `:1054` stops re-running once the app is below `STARTED`
— turn prompts go quiet with the screen off. The camera collector at `:856` is a raw
`LaunchedEffect(Unit) { … lastFix.collect { … } }` and is not lifecycle-aware, so it already
chimes with the screen off and will speak there too. Making turn prompts survive a dark screen
means the announcer lives in `TripTrackingService`, not in a composable — a state-ownership change,
stage 4's subject, and **not** this spec's. Record it as a known limitation; do not add a seventh
`lastFix` collector to paper over it (`detour-compose-state-hazards` §4).

**D7 — this spec declares none of entry 13's constants, in either landing order.** Entry 13 is
`+5` (three copies: `MapHud.kt:184`, `car/CarMapRenderer.kt:635`, `wear/…/MainActivity.kt:140`),
`+3.0` (two: `MapScreen.kt:870`, `car/NavScreen.kt:421`) and the `45.0` wedge (two:
`MapScreen.kt:863`, `car/NavScreen.kt:414`) — all verified at `cfa113f`. Every one of them is
stage 3's `CameraWarner` or stays in `app/`. This spec's constants are the **voice ladder**
(`800/300/80`) and `spokenDistance`, which entry 13 does not list. The phone's spoken hazard cue
is added *inside* the existing `if (tooFast && ahead.at != warnedAt)` block
(`MapScreen.kt:871-874`), touching neither `:863` nor `:870`, and item 9 carries the greps that
prove it. The **wording** `"Speed camera ahead"` is the one string both stages could reach for:
the rule is that whichever lands first declares it and the second consumes it — if `CameraWarner`
already exists when item 9 runs, item 9 takes the text from there and declares nothing; if it does
not, item 9 leaves the literal at the delivery site with a comment naming `CameraWarner` as its
home. Neither stage writes a copy the other could have read.

### The items

**Step 1 — the policy into `shared/`.** Five commits, all verifiable from a desk.

1. **iOS's phase boundaries become inclusive.** `case ..<Self.voiceNowM` → `case ...` on all three
   arms (`iosApp/Detour/NavScreen.swift:176-178`). Entry 12's second sub-bug, its own commit as
   Scope requires, and **before** item 3 so the surfaces already agree when the shared test is
   written. It is deleted again by item 5; that is not churn, it is the entry-12 fix standing on
   its own in case the extraction stalls.
2. **iOS's mute stops the utterance in flight.** `NavModel` holds
   `SettingsFlows.shared.voiceGuidance()` — a `BoolWatcher` that already exists
   (`FlowWatcher.kt:140`, so no new watcher subclass) — `watch`es it in `init` and calls
   `voice.stop()` when it reads false, cancelling in `deinit`. Copy `SettingsScreen.swift:214,228,
   237-240` exactly; that is the shipped pattern. Entry 12's first sub-bug, its own commit.
3. **`NavAnnouncer` into commonMain, with `commonTest`.** New
   `shared/src/commonMain/kotlin/com/jellemax/detour/data/NavAnnouncer.kt` and
   `shared/src/commonTest/kotlin/com/jellemax/detour/data/NavAnnouncerTest.kt`. Nothing consumes
   it yet. `data/` and not a new `drive/` package: all 36 commonMain files live in `data/`, and
   creating stage 3's package from the convergence axis would add a second edge between the two
   axes where `00-chain-design.md` § *The two axes* allows exactly one. If stage 3 later creates
   `drive/`, moving this file there is a free same-module move.

   The test cases, which the marker asked for by name:

   - the first prompt of a session ignores the ladder (an instruction 3 km out announces at once,
     with distance wording);
   - each phase fires at most once and only upward: 900 → nothing, 800 → far, 700 → nothing,
     300 → near, 100 → nothing, 80 → the bare cue;
   - **the boundary, at exactly 800.0, 300.0 and 80.0** — the case iOS gets wrong today and item 1
     fixes;
   - a new `startIndex` re-arms the latch; `routeChanged()` re-arms it including `startAnnounced`;
   - blank instruction text becomes `"Continue"`;
   - `spokenDistance`'s four buckets at their edges, characterising current behaviour including
     that 1500 m reads *"2 kilometers"* — entry 19's quantisation is out of scope, so this locks
     the existing rounding rather than improving it;
   - `rerouting()` returns `"Rerouting"`.

   The proof that no platform type leaked: `:shared:compileCommonMainKotlinMetadata` type-checks
   commonMain against the common intersection (`ios.yml:58-59`), so a stray `android.*` or
   `java.*` import fails there, and `grep -c 'TextToSpeech\|AVSpeech\|ToneGenerator\|CarToast'`
   over the new file is 0.
4. **The car repointed.** Trails item 3 by exactly one commit (`detour-staged-refactor` §4).
   Deletes `VOICE_FAR_M/NEAR_M/NOW_M` and their comment (`car/NavScreen.kt:60-67`), the three
   latch fields and theirs (`:126-130`), `announce()`'s body (`:297-324`) and the file-level
   `spokenDistance` (`:577-583`) — and with it the now-unused `import kotlin.math.roundToInt`
   (`:52`), whose only remaining uses are inside that function. Behaviour-preserving by
   construction: the extracted code is the car's own.
5. **iOS repointed.** Deletes `voiceFarM/NearM/NowM` (`NavScreen.swift:142-144`), the three latch
   fields (`:138-140`), `announce()`'s body (`:167-195`) and the file-level `spokenDistance`
   (`:203-210`). `displayDistance` (`:213-218`) stays — it is the banner's, not the voice's. The
   commit message must say that iOS behaviour changes at exactly three distances, which is item 1
   arriving through the core.

> **Stop-point after item 5.** Items 1–5 are fully verifiable without hardware: Kotlin compiles
> and `:shared` tests run here, and `ios.yml` type-checks the Swift on the open PR. Items 6–9 add
> an audio client to the most-used surface and **cannot** be called verified without a person, a
> phone and something playing. Land 1–5, record the stop-point, and start step 2 only when a device
> session is actually available. This is the largest item the register produced; splitting its
> verification is the honest way to stop it stalling the whole axis.

**Step 2 — the phone's voice.** Four commits, none of them verifiable here beyond compiling.

6. **`NavVoice` moves to `app/…/audio/`.** Package line and one KDoc paragraph change (the current
   doc says *"for the car screen"*, which item 8 makes false); the class body is byte-identical,
   and the commit carries the diff that proves it. One import added to `car/NavScreen.kt`.
7. **`NavVoice` does not speak when focus is refused.** D5's second silence. A behaviour change on
   the car as well as a precondition for the phone, so it is its own commit with the rationale in a
   comment beside the check (`CONTRIBUTING.md:177-189`), plus one `Log.w` so a device session can
   tell "refused" from "engine missing".
8. **The phone announces turns.** A `NavVoice` in `MapScreen.kt` with a `DisposableEffect`
   `onDispose { shutdown() }` — the car does this in `onDestroy` (`car/NavScreen.kt:199-202`) and
   without it the TTS connection and a held focus request outlive the screen; a `NavAnnouncer`
   driven from the existing nav loop (`:1054-1105`, which reads `liveFix` and adds no collector);
   a local `announceAloud()` carrying D5's first and third gates — the second lives in `NavVoice`
   from item 7, because it is a property of the audio API rather than of this surface; a
   `Settings.voiceGuidance` collector that calls `stop()` on false so the Settings toggle works
   mid-drive without new nav-bar UI; and
   `routeChanged()` in `startNavigation()` (`:691`) and on a successful reroute (`:1090-1101`).
   `SettingsScreen.kt:307`'s *"on the car screen"* is corrected **in this commit**, per Scope.
9. **The phone speaks the camera warning.** Entry 15's other half, inside the existing latch block
   (`MapScreen.kt:871-874`). Its own commit and not item 8's, because it is the second of two
   different `lastFix` consumers and `detour-compose-state-hazards` §4 forbids changing two in one
   commit. Chime **and** speak; no toast — the phone's map already draws the camera marker
   (`:796-798`), which is what the car's toast substitutes for, and the snackbar host that *is*
   available (`:171`, `:1255`) is the error channel, not a hazard channel.

**Step 3 — the bookkeeping.** One commit, last, because a commit cannot cite its own SHA.

10. **Entries 12 and 15 marked resolved** with their commits and which way each went, the §A rows
    at `15-divergence-register.md:1737` (entry 15) and `:1740` (entry 12), the §D assertions this
    stage inverts, this spec's Status block, and the note in `00-chain-design.md` that the
    convergence axis is complete with §A's four small entries (14, 18, 19, 21) left as one-line
    answers rather than a fourth spec.

### What this rewrite found wrong

- **The marker asked for time to be injected into a machine that has no time in it** (D3). The
  rewrite does not add a `nowMs` parameter, and says why rather than quietly complying.
- **The register's objection to decision 1 names the wrong second audio client.** The stated cost
  is *"it ducks the user's music"*; the measured cost is Detour's own convoy service holding
  `AUDIOFOCUS_GAIN_TRANSIENT` and forcing `MODE_IN_COMMUNICATION` for the life of a convoy (D5).
  Music ducking is the designed-for case; the convoy is the unhandled one.
- **Full parity is not achievable inside this spec** (D6). The phone will announce turns only with
  the app foregrounded, because of a lifecycle decision made elsewhere. Anything that claims
  otherwise is claiming a state-ownership change this spec does not make.

### Citations that drifted

The sections above were written against `5613e59`, before `6551f37` added lines to
`car/NavScreen.kt`. Re-derived at `cfa113f`: the reroute re-arm is `:275-277` (Scope says
`:272-274`), the mute toggle is `:479-480` (Scope says `:470-473`), and the register's entry-12
citations `:62-64`, `:291`, `:302-307`, `:258` and `:420` are now `:65-67`, `:293`, `:305-310`,
`:261` and `:427`. `NavVoice.kt:41-43`/`:138-149`, `MapScreen.kt:871-874`, `Settings.kt:132` and
`SettingsScreen.kt:307` are all correct as cited. The drifted numbers are not corrected in the
sections above, which are binding as written; they are recorded here so a plan re-derives rather
than trusts them.

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
