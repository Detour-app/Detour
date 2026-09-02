# OBD2 Stage 1 — probe helper + cappedFixDtSec — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Collapse `Obd2Connection.pollLoop`'s two copy-pasted probe-and-latch state machines into one tested helper, and unify `TripTrackingService`'s two identical `1..15_000 ms` fix-gap guards into one helper — so Stage 2 can add PID 0144 as a third probe input without a third copy.

**Architecture:** `pollLoop` currently inlines two ad-hoc `when` ladders (`throttlePid`: 0145→0111, `fuelPid`: 015E→0110) that have drifted apart. Replace both with `probePidCycle(...)`, a pure synchronous function that polls the current target, advances a `PidProbe` sealed-state latch, and returns `(newState, PollResult?)`. It has no `delay`/loop so it is unit-testable against `ByteArrayInputStream` doubles exactly like the existing `pollPid` tests. Separately, `cappedFixDtSec(nowMs, lastMs)` extracts the "gap in 1..15 s or drop it" rule the fuel integrator and `secondsOverLimit` both hand-roll.

**Tech Stack:** Kotlin, Android (`:app`), JUnit4 + `org.junit.Assert.*`, Gradle.

**Spec:** `docs/superpowers/specs/2026-09-02-obd2-fuel-accuracy-design.md` (Stage 1 section)

## Global Constraints

- **No version bump.** Pure refactor. `app/build.gradle.kts` `versionName` stays `1.94.0`. `versionCode` is CI-stamped — never touched.
- **One work item ⇒ one commit.** No item spans two commits; no commit spans two items.
- **The behaviour delta rides alone.** Wiring the *throttle* probe through the helper changes its behaviour (it gains a cycle budget; a both-unsupported throttle slot stops polling 0145 harmlessly every cycle). That change lands in its own commit (Task 3), never mixed with the zero-delta fuel wiring (Task 2).
- **No `ObdTelemetry` field change** in this stage.
- **Do not touch** the trace-distance gate at `TripTrackingService.kt:~1332` (`location.time - last.time in 1..15_000`) — it keys off the GPS fix clock deliberately and Stage 3 revisits that path.
- Branch: `refactor/obd2-probe-helper`, already created off `fix/obd2-connection-lifecycle`, already holds the design-doc commit. PR base = `fix/obd2-connection-lifecycle` (see spec "PR stacking").
- Commit message trailers on every commit:
  ```
  Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
  Claude-Session: https://claude.ai/code/session_01AA6YEKTr59Gb2ZZQdwkhoo
  ```

## Verification commands

- Single test class: `./gradlew :app:testDebugUnitTest --tests "com.jellemax.detour.obd2.Obd2ConnectionTest"`
- Full unit suites: `./gradlew :app:testDebugUnitTest :shared:testDebugUnitTest`
- R8 / release: `./gradlew :app:assembleDebug :app:assembleRelease`
- Lint: `./gradlew :app:lintDebug`

## File Structure

| File | Responsibility after this stage |
| --- | --- |
| `app/src/main/java/com/jellemax/detour/obd2/Obd2Connection.kt` | Gains `PidProbe` sealed interface, `ProbeCycle` data class, `probePidCycle(...)`; `pollLoop`'s two probe ladders replaced by two `probePidCycle` calls; `FUEL_PROBE_MAX_CYCLES` renamed `PID_PROBE_MAX_CYCLES` |
| `app/src/test/java/com/jellemax/detour/obd2/Obd2ConnectionTest.kt` | Gains a `probePidCycle` test group (latch primary / latch fallback / both unsupported / timeout keeps probing / budget exhaustion / null fallback / latched keeps polling) |
| `app/src/main/java/com/jellemax/detour/tracking/TripTrackingService.kt` | Gains top-level `internal fun cappedFixDtSec(nowMs, lastMs): Double?`; the fuel-integrator and `secondsOverLimit` gap guards call it |
| `app/src/test/java/com/jellemax/detour/tracking/CappedFixDtSecTest.kt` | New — covers the helper's boundary cases |

---

## Task 1: `PidProbe` + `probePidCycle` helper (added, not yet wired)

**Files:**
- Modify: `app/src/main/java/com/jellemax/detour/obd2/Obd2Connection.kt`
  - rename `FUEL_PROBE_MAX_CYCLES` → `PID_PROBE_MAX_CYCLES` (declaration `:92` + its two uses in the fuel block `:337`,`:341` + the comment `:328`, `:91-92`)
  - add `PidProbe`, `ProbeCycle`, `probePidCycle` next to `PollResult` (`:271`)
- Test: `app/src/test/java/com/jellemax/detour/obd2/Obd2ConnectionTest.kt`

**Interfaces:**
- Consumes: existing `Obd2Connection.pollPid(input, output, pid): PollResult`, `Obd2Connection.PollResult(bytes: List<Int>?, answered: Boolean)`.
- Produces:
  - `Obd2Connection.PidProbe` — `sealed interface` with `data class Probing(val cycles: Int = 0)`, `data class Latched(val pid: String)`, `data object Unsupported`.
  - `Obd2Connection.ProbeCycle(val state: PidProbe, val result: PollResult?)`.
  - `internal fun Obd2Connection.probePidCycle(input: InputStream, output: OutputStream, state: PidProbe, primary: String, fallback: String?, maxCycles: Int): ProbeCycle`
  - `const val Obd2Connection.PID_PROBE_MAX_CYCLES = 5`

- [ ] **Step 1: Write the failing tests**

Add to `Obd2ConnectionTest.kt` (the helper lives in the `object`, so call it `Obd2Connection.probePidCycle(...)` like the other tests call `Obd2Connection.pollPid(...)`).

**Multi-poll cases need a sequential stream, not `streamOf("resp1>resp2>")`:** after the primary poll's header mismatch, `pollPid` calls `drainStalePrompts`, which swallows an already-buffered second `>`-frame — so a concatenated two-response `ByteArrayInputStream` leaves the fallback poll reading nothing. The existing suite solves this with `GatedInputStream`; add a self-advancing variant near it (~line 137):

```kotlin
/** Serves each response only after the previous one is fully read AND the
 *  stream has reported empty at least once — models ELM327 responses arriving
 *  over the wire one at a time, so [Obd2Connection.pollPid]'s drainStalePrompts
 *  (which bails the instant available() is 0) cannot sweep up a response that
 *  "hasn't arrived yet". */
private class SequentialResponseStream(responses: List<String>) : InputStream() {
    private val chunks = ArrayDeque(responses.map { it.toByteArray(Charsets.US_ASCII) })
    private var current: ByteArray = chunks.removeFirstOrNull() ?: ByteArray(0)
    private var pos = 0
    private var sawEmpty = false

    override fun available(): Int {
        if (pos < current.size) return current.size - pos
        if (sawEmpty && chunks.isNotEmpty()) {
            current = chunks.removeFirst()
            pos = 0
            sawEmpty = false
            return current.size
        }
        sawEmpty = true
        return 0
    }

    override fun read(): Int {
        if (pos >= current.size && available() <= 0) return -1
        return current[pos++].toInt() and 0xFF
    }
}
```

Then the probe test group:

```kotlin
// --- probePidCycle: probe-and-latch state machine (#103) ------------------

private val PROBING = Obd2Connection.PidProbe.Probing()

@Test
fun probeLatchesToPrimaryOnADataFrame() {
    val input = streamOf("41 5E 00 40\r\r>") // a 015E fuel-rate frame
    val cycle = Obd2Connection.probePidCycle(
        input, ByteArrayOutputStream(), PROBING,
        primary = Obd2Pids.PID_FUEL_RATE, fallback = Obd2Pids.PID_MAF,
        maxCycles = Obd2Connection.PID_PROBE_MAX_CYCLES,
    )
    assertEquals(Obd2Connection.PidProbe.Latched(Obd2Pids.PID_FUEL_RATE), cycle.state)
    assertEquals(listOf(0x00, 0x40), cycle.result?.bytes)
}

@Test
fun probeFallsBackToSecondaryWhenPrimaryAnswersUnsupported() {
    // Primary (015E) answers "NO DATA"; the fallback (0110) frame arrives only
    // after that poll returns, so the same cycle re-polls and latches it.
    val input = SequentialResponseStream(listOf("NO DATA\r\r>", "41 10 1A F0\r\r>"))
    val cycle = Obd2Connection.probePidCycle(
        input, ByteArrayOutputStream(), PROBING,
        primary = Obd2Pids.PID_FUEL_RATE, fallback = Obd2Pids.PID_MAF,
        maxCycles = Obd2Connection.PID_PROBE_MAX_CYCLES,
    )
    assertEquals(Obd2Connection.PidProbe.Latched(Obd2Pids.PID_MAF), cycle.state)
    assertEquals(listOf(0x1A, 0xF0), cycle.result?.bytes)
}

@Test
fun probeGoesUnsupportedWhenBothPidsAnswerUnsupported() {
    val input = SequentialResponseStream(listOf("NO DATA\r\r>", "NO DATA\r\r>"))
    val cycle = Obd2Connection.probePidCycle(
        input, ByteArrayOutputStream(), PROBING,
        primary = Obd2Pids.PID_THROTTLE_REL, fallback = Obd2Pids.PID_THROTTLE,
        maxCycles = Obd2Connection.PID_PROBE_MAX_CYCLES,
    )
    assertEquals(Obd2Connection.PidProbe.Unsupported, cycle.state)
    assertNull(cycle.result)
}

@Test
fun probeGoesUnsupportedWhenPrimaryUnsupportedAndNoFallback() {
    // lambda (0144) has no alternative PID — fallback is null.
    val input = streamOf("NO DATA\r\r>")
    val cycle = Obd2Connection.probePidCycle(
        input, ByteArrayOutputStream(), PROBING,
        primary = "0144", fallback = null,
        maxCycles = Obd2Connection.PID_PROBE_MAX_CYCLES,
    )
    assertEquals(Obd2Connection.PidProbe.Unsupported, cycle.state)
}

@Test
fun probeKeepsProbingOnABareTimeoutWithinBudget() {
    val input = ByteArrayInputStream(ByteArray(0)) // primary times out
    val cycle = Obd2Connection.probePidCycle(
        input, ByteArrayOutputStream(), Obd2Connection.PidProbe.Probing(cycles = 1),
        primary = Obd2Pids.PID_FUEL_RATE, fallback = Obd2Pids.PID_MAF,
        maxCycles = Obd2Connection.PID_PROBE_MAX_CYCLES,
    )
    assertEquals(Obd2Connection.PidProbe.Probing(cycles = 2), cycle.state)
    assertNull(cycle.result)
}

@Test
fun probeForcesResolutionWhenTheCycleBudgetIsSpent() {
    // Budget spent and primary still just times out: force the fallback, and
    // with the fallback also silent, give up rather than probe 015E forever.
    val input = ByteArrayInputStream(ByteArray(0))
    val cycle = Obd2Connection.probePidCycle(
        input, ByteArrayOutputStream(),
        Obd2Connection.PidProbe.Probing(cycles = Obd2Connection.PID_PROBE_MAX_CYCLES - 1),
        primary = Obd2Pids.PID_FUEL_RATE, fallback = Obd2Pids.PID_MAF,
        maxCycles = Obd2Connection.PID_PROBE_MAX_CYCLES,
    )
    assertEquals(Obd2Connection.PidProbe.Unsupported, cycle.state)
}

@Test
fun probeInLatchedStateJustPollsTheLatchedPidEveryCycle() {
    val input = streamOf("41 10 1A F0\r\r>")
    val cycle = Obd2Connection.probePidCycle(
        input, ByteArrayOutputStream(),
        Obd2Connection.PidProbe.Latched(Obd2Pids.PID_MAF),
        primary = Obd2Pids.PID_FUEL_RATE, fallback = Obd2Pids.PID_MAF,
        maxCycles = Obd2Connection.PID_PROBE_MAX_CYCLES,
    )
    assertEquals(Obd2Connection.PidProbe.Latched(Obd2Pids.PID_MAF), cycle.state)
    assertEquals(listOf(0x1A, 0xF0), cycle.result?.bytes)
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew :app:testDebugUnitTest --tests "com.jellemax.detour.obd2.Obd2ConnectionTest"`
Expected: compile failure — `Obd2Connection.PidProbe` / `probePidCycle` / `PID_PROBE_MAX_CYCLES` unresolved.

- [ ] **Step 3: Rename the constant**

In `Obd2Connection.kt`, rename `FUEL_PROBE_MAX_CYCLES` to `PID_PROBE_MAX_CYCLES` at its declaration (`:92`) and both references inside the existing fuel block (`:337`, `:341`). Widen the doc comment above it (`:90-92`) from "the fuel-PID probe" to "a probe-and-latch PID slot":

```kotlin
// How many cycles a probe-and-latch PID slot gets to reach a verdict. A clone
// that silently ignores an unsupported PID (answered == false) would otherwise
// re-poll it — eating a read timeout — for the whole drive; after this many
// cycles the probe forces the fallback, then gives up.
private const val PID_PROBE_MAX_CYCLES = 5
```

- [ ] **Step 4: Add the helper**

Immediately after the `PollResult` declaration (`:271`) and its doc comment, add:

```kotlin
/** Per-connection state of one probe-and-latch PID slot.
 *  - [Probing] — still deciding; [cycles] counts probe attempts so far.
 *  - [Latched] — settled on [pid]; poll it every cycle from here.
 *  - [Unsupported] — neither the primary nor the fallback PID answered; stop
 *    asking for the life of this connection. */
internal sealed interface PidProbe {
    data class Probing(val cycles: Int = 0) : PidProbe
    data class Latched(val pid: String) : PidProbe
    data object Unsupported : PidProbe
}

/** The outcome of one [probePidCycle]: the slot's new [state] and this cycle's
 *  reading for it. [result] is null only while [PidProbe.Unsupported] (nothing
 *  is polled) or on a bare timeout that left the slot still [PidProbe.Probing]. */
internal data class ProbeCycle(val state: PidProbe, val result: PollResult?)

/**
 * One poll cycle of a probe-and-latch PID slot (#103) — the shared shape the
 * throttle probe (0145 → 0111) and the fuel probe (015E → 0110) both need, and
 * that the commanded-lambda probe (0144, no fallback) will reuse.
 *
 * While [PidProbe.Probing]:
 * - poll [primary]; a data frame latches the slot to it;
 * - an *answered* "unsupported" (NO DATA / header mismatch) re-polls [fallback]
 *   the same cycle — data latches it, an answered-unsupported gives up
 *   ([PidProbe.Unsupported]); a null [fallback] (lambda) gives up immediately;
 * - a bare read timeout latches nothing: stay [PidProbe.Probing] and retry next
 *   cycle, until [maxCycles] attempts have been spent, after which the slot is
 *   forced through the fallback and then to [PidProbe.Unsupported] rather than
 *   eating a timeout on every cycle for the rest of the drive.
 */
internal fun probePidCycle(
    input: InputStream,
    output: OutputStream,
    state: PidProbe,
    primary: String,
    fallback: String?,
    maxCycles: Int,
): ProbeCycle = when (state) {
    is PidProbe.Unsupported -> ProbeCycle(state, null)
    is PidProbe.Latched -> ProbeCycle(state, pollPid(input, output, state.pid))
    is PidProbe.Probing -> {
        val cycles = state.cycles + 1
        val budgetSpent = cycles >= maxCycles
        val primaryResult = pollPid(input, output, primary)
        when {
            primaryResult.bytes != null -> ProbeCycle(PidProbe.Latched(primary), primaryResult)
            !primaryResult.answered && !budgetSpent -> ProbeCycle(PidProbe.Probing(cycles), null)
            fallback == null -> ProbeCycle(PidProbe.Unsupported, null)
            else -> {
                val fallbackResult = pollPid(input, output, fallback)
                when {
                    fallbackResult.bytes != null -> ProbeCycle(PidProbe.Latched(fallback), fallbackResult)
                    fallbackResult.answered || budgetSpent -> ProbeCycle(PidProbe.Unsupported, null)
                    else -> ProbeCycle(PidProbe.Probing(cycles), null)
                }
            }
        }
    }
}
```

- [ ] **Step 5: Run the tests to verify they pass**

Run: `./gradlew :app:testDebugUnitTest --tests "com.jellemax.detour.obd2.Obd2ConnectionTest"`
Expected: PASS, all classes. The inline fuel block still compiles (it now reads `PID_PROBE_MAX_CYCLES`). `probePidCycle` is unused so far — an "unused" warning is expected and fine.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/jellemax/detour/obd2/Obd2Connection.kt \
        app/src/test/java/com/jellemax/detour/obd2/Obd2ConnectionTest.kt
git commit -m "$(cat <<'EOF'
refactor(obd2): probePidCycle() — one probe-and-latch primitive (#103)

Adds the PidProbe sealed state + probePidCycle() next to PollResult, with
unit coverage. Not wired into pollLoop yet. Renames FUEL_PROBE_MAX_CYCLES
to PID_PROBE_MAX_CYCLES since it is about to gate three slots, not one.

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01AA6YEKTr59Gb2ZZQdwkhoo
EOF
)"
```

---

## Task 2: Wire the fuel probe through `probePidCycle` (zero behaviour delta)

**Files:**
- Modify: `app/src/main/java/com/jellemax/detour/obd2/Obd2Connection.kt` — `pollLoop`, the `fuelPid` declaration (`:294-295`), the probe block (`:330-348`), and the fuel parse (`:356-359`).

**Interfaces:**
- Consumes: `probePidCycle`, `PidProbe`, `ProbeCycle` from Task 1.
- Produces: nothing new — `pollLoop` internals only.

**Equivalence argument (why this is zero-delta — verify while editing):**
`fuelResult` is read *only* at the `directLph` / `mafGps` lines; it is **not** in the empty-poll watchdog (that checks speed/throttle/rpm only). The helper's `Probing`-state branches map one-to-one onto the current `when` ladder (data → latch primary; answered-unsupported or budget → try MAF; MAF data → latch MAF; MAF answered-unsupported or budget → `Unsupported`; else keep probing). The only representational change — a bare-timeout cycle returns `result = null` instead of the timed-out `PollResult` — is unobservable because `fuelProbe` is not `Latched` on that cycle, so both `directLph` and `mafGps` are null regardless.

- [ ] **Step 1: Replace the `fuelPid` state declaration**

In `pollLoop`, replace (`:290-295`):

```kotlin
    // Fuel rate: null = undecided, "" = neither PID supported (stop asking),
    // else [Obd2Pids.PID_FUEL_RATE] (direct) or [Obd2Pids.PID_MAF] (the
    // estimate). Probed and latched once per connection, same reasoning as
    // throttlePid.
    var fuelPid: String? = null
    var fuelProbeCycles = 0
```

with:

```kotlin
    // Fuel rate: probe 015E (a direct ECU L/h reading), fall back to 0110 (MAF,
    // turned into an estimate). Latched once per connection — a vehicle that
    // reports neither won't start mid-drive, and re-probing both every cycle is
    // a permanent extra request on the 1 Hz loop.
    var fuelProbe: PidProbe = PidProbe.Probing()
```

- [ ] **Step 2: Replace the fuel probe block**

Replace this block (`pollLoop`, `:323-348`):

```kotlin
            // Fuel is polled before speed so speed stays the last poll before the
            // telemetry publish (see the comment on `parseSpeed`). null = still
            // probing, "" = neither PID supported (stop asking). One transient
            // timeout must not latch "": that value never retries, so it's only
            // set once a poll actually *answered* it as unsupported, or the probe
            // budget (FUEL_PROBE_MAX_CYCLES) is spent — which also bounds the
            // wasted 015E polls when a clone ignores an unsupported PID silently.
            var fuelResult: PollResult? = null
            if (fuelPid != "") {
                fuelResult = pollPid(input, output, fuelPid ?: Obd2Pids.PID_FUEL_RATE)
                if (fuelPid == null) {
                    fuelProbeCycles++
                    when {
                        fuelResult.bytes != null -> fuelPid = Obd2Pids.PID_FUEL_RATE
                        fuelResult.answered || fuelProbeCycles >= FUEL_PROBE_MAX_CYCLES -> {
                            fuelResult = pollPid(input, output, Obd2Pids.PID_MAF)
                            fuelPid = when {
                                fuelResult.bytes != null -> Obd2Pids.PID_MAF
                                fuelResult.answered || fuelProbeCycles >= FUEL_PROBE_MAX_CYCLES -> ""
                                else -> null // MAF timed out; keep trying
                            }
                        }
                        // else: 015E just timed out — retry next cycle, don't give up
                    }
                }
            }
```

with (note `FUEL_PROBE_MAX_CYCLES` is already `PID_PROBE_MAX_CYCLES` after Task 1):

```kotlin
            // Fuel is polled before speed so speed stays the last poll before the
            // telemetry publish (see the comment on `parseSpeed`).
            val fuelCycle = probePidCycle(
                input, output, fuelProbe,
                primary = Obd2Pids.PID_FUEL_RATE, fallback = Obd2Pids.PID_MAF,
                maxCycles = PID_PROBE_MAX_CYCLES,
            )
            fuelProbe = fuelCycle.state
            val fuelResult = fuelCycle.result
```

- [ ] **Step 3: Update the fuel parse**

Replace (`:356-359`):

```kotlin
            val directLph = if (fuelPid == Obd2Pids.PID_FUEL_RATE)
                fuelResult?.bytes?.let { Obd2Pids.parseFuelRateLph(it) } else null
            val mafGps = if (fuelPid == Obd2Pids.PID_MAF)
                fuelResult?.bytes?.let { Obd2Pids.parseMafGramsPerSec(it) } else null
```

with:

```kotlin
            val fuelLatched = fuelProbe as? PidProbe.Latched
            val directLph = if (fuelLatched?.pid == Obd2Pids.PID_FUEL_RATE)
                fuelResult?.bytes?.let { Obd2Pids.parseFuelRateLph(it) } else null
            val mafGps = if (fuelLatched?.pid == Obd2Pids.PID_MAF)
                fuelResult?.bytes?.let { Obd2Pids.parseMafGramsPerSec(it) } else null
```

- [ ] **Step 4: Run the tests**

Run: `./gradlew :app:testDebugUnitTest --tests "com.jellemax.detour.obd2.Obd2ConnectionTest"`
Expected: PASS. (`pollLoop` has no direct test; this confirms compile + no regression in the covered stream logic.)

- [ ] **Step 5: Build**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL — no unresolved `fuelPid` / `fuelProbeCycles` references left.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/jellemax/detour/obd2/Obd2Connection.kt
git commit -m "$(cat <<'EOF'
refactor(obd2): route the fuel probe through probePidCycle (#103)

Behaviour-identical: the fuel PollResult feeds only the direct/MAF parse,
not the empty-poll watchdog, and the probe branches map one-to-one onto
the previous when-ladder.

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01AA6YEKTr59Gb2ZZQdwkhoo
EOF
)"
```

---

## Task 3: Wire the throttle probe through `probePidCycle` (carries the behaviour delta)

**Files:**
- Modify: `app/src/main/java/com/jellemax/detour/obd2/Obd2Connection.kt` — `pollLoop`, the `throttlePid` declaration (`:283-289`), the probe block (`:310-320`), the `throttleClosed` derivation (`:361-364`).
- Test: `app/src/test/java/com/jellemax/detour/obd2/Obd2ConnectionTest.kt` — one added case.

**Interfaces:**
- Consumes: `probePidCycle`, `PidProbe` from Task 1.
- Produces: nothing new.

**Intended behaviour deltas (call these out in the commit body):**
1. The throttle slot **gains the `PID_PROBE_MAX_CYCLES` budget.** Before, a primary (0145) that only ever timed out kept the slot `null` forever with no give-up; now it forces 0111 then `Unsupported` after 5 cycles. A transient can no longer strand the probe.
2. A slot where **both** throttle PIDs are unsupported becomes `PidProbe.Unsupported` and polls nothing, instead of latching to 0145 and re-polling it (a harmless NO DATA) every cycle — one fewer request per cycle on the 1 Hz loop.
3. Consequence of (2): a vehicle supporting neither throttle PID no longer has its empty-poll watchdog fed by that harmless 0145 poll. If speed **and** RPM also stop answering, the watchdog now correctly trips into backoff instead of sitting on a live "Connected" with no data. (Reaching this needs speed+RPM to time out for 5 straight cycles — a connection that is already dead.)

All three are strict hardening and are the point of #103.

- [ ] **Step 1: Write the added failing test**

Add to the `probePidCycle` group in `Obd2ConnectionTest.kt`:

```kotlin
@Test
fun probeDoesNotLatchThrottleUnsupportedOnATransientTimeout() {
    // Early cycle, primary (0145) just times out: the slot must keep probing,
    // not conclude the pedal PID is unsupported.
    val cycle = Obd2Connection.probePidCycle(
        ByteArrayInputStream(ByteArray(0)), ByteArrayOutputStream(),
        Obd2Connection.PidProbe.Probing(cycles = 0),
        primary = Obd2Pids.PID_THROTTLE_REL, fallback = Obd2Pids.PID_THROTTLE,
        maxCycles = Obd2Connection.PID_PROBE_MAX_CYCLES,
    )
    assertEquals(Obd2Connection.PidProbe.Probing(cycles = 1), cycle.state)
}
```

- [ ] **Step 2: Run it to verify it passes already**

Run: `./gradlew :app:testDebugUnitTest --tests "com.jellemax.detour.obd2.Obd2ConnectionTest"`
Expected: PASS — `probePidCycle` from Task 1 already has this behaviour. This test pins it against the throttle wiring about to be added. (This task's "failing" state is the wiring, not a red test; the added test is a regression pin.)

- [ ] **Step 3: Replace the `throttlePid` declaration**

In `pollLoop`, replace (`:283-289`):

```kotlin
    // Relative throttle (pedal) is preferred, but not every vehicle reports
    // it. null = undecided: try 0145, and on a clean unsupported answer fall
    // back to 0111. Once either probe answers (with data or a sticky NO DATA)
    // throttlePid is fixed for the rest of this connection — a vehicle that
    // supports neither won't start supporting one mid-drive, and re-probing
    // both every cycle is a permanent extra request on the 1 Hz loop.
    var throttlePid: String? = null
```

with:

```kotlin
    // Relative throttle (pedal, 0145) is preferred; fall back to absolute
    // throttle (plate, 0111). Latched once per connection — see fuelProbe.
    var throttleProbe: PidProbe = PidProbe.Probing()
```

- [ ] **Step 4: Replace the throttle probe block**

Replace (`:310-320`):

```kotlin
            var throttleResult = pollPid(input, output, throttlePid ?: Obd2Pids.PID_THROTTLE_REL)
            if (throttlePid == null) {
                if (throttleResult.bytes != null) {
                    throttlePid = Obd2Pids.PID_THROTTLE_REL
                } else if (throttleResult.answered) {
                    throttleResult = pollPid(input, output, Obd2Pids.PID_THROTTLE)
                    throttlePid =
                        if (throttleResult.bytes != null) Obd2Pids.PID_THROTTLE
                        else Obd2Pids.PID_THROTTLE_REL // both unsupported; stop probing
                }
            }
```

with:

```kotlin
            val throttleCycle = probePidCycle(
                input, output, throttleProbe,
                primary = Obd2Pids.PID_THROTTLE_REL, fallback = Obd2Pids.PID_THROTTLE,
                maxCycles = PID_PROBE_MAX_CYCLES,
            )
            throttleProbe = throttleCycle.state
            val throttleResult = throttleCycle.result ?: PollResult(bytes = null, answered = false)
```

- [ ] **Step 5: Update the `throttleClosed` derivation**

Replace (`:361-364`):

```kotlin
            // DFCO needs a *pedal* signal: the absolute-throttle PID (0111) idles
            // at 15-20% even fully closed, so pass null (skip the cut) unless the
            // reading came from relative throttle (0145).
            val throttleClosed = if (throttlePid == Obd2Pids.PID_THROTTLE_REL && throttle != null)
                throttle < DFCO_THROTTLE_PCT else null
```

with:

```kotlin
            // DFCO needs a *pedal* signal: the absolute-throttle PID (0111) idles
            // at 15-20% even fully closed, so pass null (skip the cut) unless the
            // reading came from relative throttle (0145).
            val throttleClosed = if ((throttleProbe as? PidProbe.Latched)?.pid == Obd2Pids.PID_THROTTLE_REL &&
                throttle != null
            ) throttle < DFCO_THROTTLE_PCT else null
```

- [ ] **Step 6: Run the tests**

Run: `./gradlew :app:testDebugUnitTest --tests "com.jellemax.detour.obd2.Obd2ConnectionTest"`
Expected: PASS.

- [ ] **Step 7: Full build + suites**

Run: `./gradlew :app:testDebugUnitTest :shared:testDebugUnitTest :app:assembleDebug :app:assembleRelease`
Expected: all BUILD SUCCESSFUL / tests green. Confirms no `throttlePid` reference left and R8 is clean.

- [ ] **Step 8: Diff read**

`git diff` the two commits so far: confirm no `LaunchedEffect`/effect key lists touched (none in this file), no reformatting outside the changed blocks, `pollLoop` still reads top-to-bottom throttle → rpm → fuel → speed.

- [ ] **Step 9: Commit**

```bash
git add app/src/main/java/com/jellemax/detour/obd2/Obd2Connection.kt \
        app/src/test/java/com/jellemax/detour/obd2/Obd2ConnectionTest.kt
git commit -m "$(cat <<'EOF'
refactor(obd2): route the throttle probe through probePidCycle (#103)

The throttle slot now shares the fuel slot's robustness — a
PID_PROBE_MAX_CYCLES budget so a transient timeout can't strand the
probe, and a both-unsupported slot that stops re-polling 0145 harmlessly
every cycle. Downstream (throttle -> throttleClosed -> DFCO) is
unchanged. Deltas are strict hardening, called out per #103.

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01AA6YEKTr59Gb2ZZQdwkhoo
EOF
)"
```

---

## Task 4: `cappedFixDtSec` + fold the two `TripTrackingService` gap guards

**Files:**
- Modify: `app/src/main/java/com/jellemax/detour/tracking/TripTrackingService.kt`
  - add top-level `internal fun cappedFixDtSec(...)` next to `obdSpeedMpsFrom` (`:1785`) / `pickObd2Address` (`:1807`)
  - fuel integrator site (`:1419-1428`)
  - `secondsOverLimit` site (`:1495-1500`)
- Test: `app/src/test/java/com/jellemax/detour/tracking/CappedFixDtSecTest.kt` (new)

**Interfaces:**
- Consumes: nothing.
- Produces: `internal fun cappedFixDtSec(nowMs: Long, lastMs: Long): Double?` — returns `(nowMs - lastMs) / 1000.0` when `lastMs > 0L` and `nowMs - lastMs in 1L..15_000L`, else `null`.

**Equivalence (Stage 1 keeps the operands as-is — Stage 3 swaps the clock):**
- Fuel: `lastFuelSampleMs > 0L && dtMs in 1L..15_000L` ⇒ `cappedFixDtSec(location.time, lastFuelSampleMs) != null`. Same guard.
- `secondsOverLimit`: `overLimitDtMs in 1..15_000` with `lastLimitFixMs` starting at 0 ⇒ first fix has `overLimitDtMs ≈ location.time` (huge) so it's already excluded; `cappedFixDtSec`'s explicit `lastMs > 0L` makes that skip intentional instead of incidental. Same guard for every real fix.

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/com/jellemax/detour/tracking/CappedFixDtSecTest.kt`:

```kotlin
package com.jellemax.detour.tracking

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [cappedFixDtSec] is the shared "gap since the last fix, in seconds, or drop
 * it" rule the fuel integrator and secondsOverLimit both need: a gap outside
 * 1..15 s (a tunnel, a Doze window, a BT dropout) is discarded so the next
 * real fix's own Δt spans it, rather than saturating at 15 s of invented fuel
 * or over-limit time.
 */
class CappedFixDtSecTest {

    @Test fun unsetPreviousStampGivesNull() {
        assertNull(cappedFixDtSec(nowMs = 10_000L, lastMs = 0L))
    }

    @Test fun sameInstantGivesNull() {
        assertNull(cappedFixDtSec(nowMs = 10_000L, lastMs = 10_000L))
    }

    @Test fun aOneSecondGapGivesOneSecond() {
        assertEquals(1.0, cappedFixDtSec(nowMs = 11_000L, lastMs = 10_000L)!!, 1e-9)
    }

    @Test fun aGapAtTheFifteenSecondCeilingIsKept() {
        assertEquals(15.0, cappedFixDtSec(nowMs = 25_000L, lastMs = 10_000L)!!, 1e-9)
    }

    @Test fun aGapPastTheCeilingIsDropped() {
        assertNull(cappedFixDtSec(nowMs = 25_001L, lastMs = 10_000L))
    }

    @Test fun aNegativeGapFromAClockStepIsDropped() {
        assertNull(cappedFixDtSec(nowMs = 9_000L, lastMs = 10_000L))
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "com.jellemax.detour.tracking.CappedFixDtSecTest"`
Expected: compile failure — `cappedFixDtSec` unresolved.

- [ ] **Step 3: Add the helper**

In `TripTrackingService.kt`, next to the other top-level `internal fun`s (after `obdSpeedMpsFrom`, around `:1785`):

```kotlin
/** Seconds between [lastMs] and [nowMs], or null when [lastMs] is unset (0) or
 *  the gap is outside 1..15_000 ms — a tunnel, a Doze window, a BT dropout.
 *  Dropping the Δt (rather than clamping it) means the *next* real fix's own
 *  gap spans the lost interval, instead of this fix inventing a saturated 15 s
 *  of fuel burn or over-limit time. Shared by the fuel integrator and
 *  secondsOverLimit; the trace-distance gate keeps its own GPS-clock check. */
internal fun cappedFixDtSec(nowMs: Long, lastMs: Long): Double? =
    (nowMs - lastMs).takeIf { lastMs > 0L && it in 1L..15_000L }?.let { it / 1000.0 }
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "com.jellemax.detour.tracking.CappedFixDtSecTest"`
Expected: PASS.

- [ ] **Step 5: Fold the fuel integrator site**

Replace (`:1412-1430`, inside `if (obd.hasFuelRate) { ... }`):

```kotlin
            if (obd.hasFuelRate) {
                // Fuel is a rate, so it's integrated over time, not averaged like
                // RPM above: this fix's L/h held over the gap since the last fuel
                // sample. A gap outside 1..15s (a tunnel, a Doze window, a BT
                // dropout) is dropped, not saturated — the same `in 1..15_000`
                // rule secondsOverLimit uses, so the next real fix's own Δt spans
                // the gap rather than 15s of fuel being invented.
                val fixMs = location.time
                val dtMs = fixMs - lastFuelSampleMs
                if (lastFuelSampleMs > 0L && dtMs in 1L..15_000L) {
                    fuelMlAccum += obd.fuelRateLph * (1000.0 / 3600.0) * (dtMs / 1000.0)
                    // Distance covered while a fuel reading was live — the L/100km
                    // denominator, so a mid-trip disconnect can't make a partial
                    // measurement look like a whole-trip figure.
                    fuelSampledMeters += (distance - stats.distanceMeters).coerceAtLeast(0.0)
                }
                lastFuelSampleMs = fixMs
                if (obd.fuelEstimated) fuelWasEstimated = true
            }
```

with:

```kotlin
            if (obd.hasFuelRate) {
                // Fuel is a rate, so it's integrated over time, not averaged like
                // RPM above: this fix's L/h held over the gap since the last fuel
                // sample, dropped (not saturated) when that gap is outside 1..15s.
                val fixMs = location.time
                cappedFixDtSec(fixMs, lastFuelSampleMs)?.let { dtSec ->
                    fuelMlAccum += obd.fuelRateLph * (1000.0 / 3600.0) * dtSec
                    // Distance covered while a fuel reading was live — the L/100km
                    // denominator, so a mid-trip disconnect can't make a partial
                    // measurement look like a whole-trip figure.
                    fuelSampledMeters += (distance - stats.distanceMeters).coerceAtLeast(0.0)
                }
                lastFuelSampleMs = fixMs
                if (obd.fuelEstimated) fuelWasEstimated = true
            }
```

- [ ] **Step 6: Fold the `secondsOverLimit` site**

Replace (`:1493-1502`):

```kotlin
        var currentlyOverLimitNow: Boolean? = null
        if (speedIsReal) {
            val overLimitDtMs = location.time - lastLimitFixMs
            val over = limitKmh != null && effectiveSpeedMps * 3.6 > limitKmh * OVER_LIMIT_MARGIN
            if (overLimitDtMs in 1..15_000 && over) {
                secondsOverLimit += overLimitDtMs / 1000.0
            }
            lastLimitFixMs = location.time
            currentlyOverLimitNow = over
        }
```

with:

```kotlin
        var currentlyOverLimitNow: Boolean? = null
        if (speedIsReal) {
            val over = limitKmh != null && effectiveSpeedMps * 3.6 > limitKmh * OVER_LIMIT_MARGIN
            if (over) cappedFixDtSec(location.time, lastLimitFixMs)?.let { secondsOverLimit += it }
            lastLimitFixMs = location.time
            currentlyOverLimitNow = over
        }
```

- [ ] **Step 7: Full suites + build**

Run: `./gradlew :app:testDebugUnitTest :shared:testDebugUnitTest :app:assembleDebug :app:assembleRelease :app:lintDebug`
Expected: all green. Grep-check: `grep -c '15_000' app/src/main/java/com/jellemax/detour/tracking/TripTrackingService.kt` ⇒ **1** (only the trace-distance guard `:~1332` keeps a `1..15_000` literal; the fuel and over-limit sites, and the old fuel comment that spelled out `in 1..15_000`, are all gone).

- [ ] **Step 8: Commit**

```bash
git add app/src/main/java/com/jellemax/detour/tracking/TripTrackingService.kt \
        app/src/test/java/com/jellemax/detour/tracking/CappedFixDtSecTest.kt
git commit -m "$(cat <<'EOF'
refactor(trip): cappedFixDtSec() — one fix-gap guard for fuel + overspeed (#103)

Folds the two identical `dt in 1..15_000 ms or drop it` blocks (fuel
integrator, secondsOverLimit) into one tested helper. Operands unchanged
— still the GPS fix clock; Stage 3 (#98) swaps in the OBD telemetry
clock. The trace-distance gate keeps its own check.

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01AA6YEKTr59Gb2ZZQdwkhoo
EOF
)"
```

---

## Task 5: Stage bookkeeping + PR

**Files:**
- Modify: `docs/superpowers/specs/2026-09-02-obd2-fuel-accuracy-design.md` — Stage 1 `State` line.

- [ ] **Step 1: Update the Stage 1 Status**

In the design doc, change the Stage 1 `**State**` line from:

```
**State** | not started. Preconditions below verified against
`fix/obd2-connection-lifecycle` @ `e256257` on 2026-09-02.
```

to:

```
**State** | **done** <date>. `probePidCycle` + `PidProbe` replace both probe
ladders; `cappedFixDtSec` folds the fuel + secondsOverLimit gap guards.
Commits <first>..<last>. Plan: `docs/superpowers/plans/2026-09-02-obd2-probe-helper.md`.
Behaviour deltas (throttle probe budget + no idle 0145 re-poll) shipped as
called out. Live-adapter path unverified this session — no dongle.
```

- [ ] **Step 2: Run Stage 2's preconditions and record them**

```sh
P=shared/src/commonMain/kotlin/com/jellemax/detour/drive/Obd2Pids.kt
S=shared/src/commonMain/kotlin/com/jellemax/detour/data/Settings.kt
M=app/src/main/java/com/jellemax/detour/obd2/Obd2Connection.kt
grep -c 'probePidCycle' $M            # expect >= 3 now (def + 2 call sites)
grep -c 'STOICH_AFR_PETROL' $P        # expect 2
grep -c 'enum class FuelType' $P $S   # expect 0
```

Append the results to Stage 2's `**State**` line in the design doc (e.g. "Stage 2 preconditions checked <date> after Stage 1 landed: `probePidCycle` count 3, all green").

- [ ] **Step 3: Commit the bookkeeping**

```bash
git add docs/superpowers/specs/2026-09-02-obd2-fuel-accuracy-design.md
git commit -m "$(cat <<'EOF'
docs: Stage 1 of the OBD2 fuel chain landed (#103)

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01AA6YEKTr59Gb2ZZQdwkhoo
EOF
)"
```

- [ ] **Step 4: Push and open the PR**

```bash
git push -u origin refactor/obd2-probe-helper
gh pr create --base fix/obd2-connection-lifecycle \
  --title "refactor(obd2): one probe-and-latch primitive + one fix-gap guard (#103)" \
  --body "$(cat <<'EOF'
**Depends on #114** — base is `fix/obd2-connection-lifecycle`, not `main`. Do
not merge until #114 lands (GitHub will retarget this to `main` then; rebase
`--onto main` and it's ready).

Stage 1 of the OBD2 fuel-accuracy chain
(`docs/superpowers/specs/2026-09-02-obd2-fuel-accuracy-design.md`). Pure
refactor, no version bump.

## What changed

- `probePidCycle()` + `PidProbe` sealed state replace the two copy-pasted
  probe-and-latch ladders in `Obd2Connection.pollLoop` (throttle 0145→0111,
  fuel 015E→0110). `FUEL_PROBE_MAX_CYCLES` → `PID_PROBE_MAX_CYCLES`.
- `cappedFixDtSec()` folds `TripTrackingService`'s two identical
  `dt in 1..15_000 ms or drop it` guards (fuel integrator, `secondsOverLimit`)
  into one tested helper. Operands unchanged — Stage 3 (#98) swaps the clock.

## Behaviour deltas (intended, per #103)

The throttle probe inherits the fuel probe's robustness:
1. gains the `PID_PROBE_MAX_CYCLES` budget — a transient 0145 timeout can no
   longer strand the probe with no give-up;
2. a both-unsupported throttle slot stops re-polling 0145 (a harmless NO DATA)
   every cycle;
3. consequence of (2): if speed and RPM also stop answering, the empty-poll
   watchdog now trips into backoff instead of sitting on a data-less
   "Connected". Reaching that needs a 5-cycle speed+RPM outage — an already
   dead link.

The fuel probe wiring is zero-delta (its `PollResult` feeds only the
direct/MAF parse, not the watchdog).

## Testing

- `probePidCycle` and `cappedFixDtSec` unit-covered (`Obd2ConnectionTest`,
  `CappedFixDtSecTest`).
- `./gradlew :app:testDebugUnitTest :shared:testDebugUnitTest :app:assembleDebug :app:assembleRelease :app:lintDebug` green.
- **Unverified-live** — no physical adapter this session; `pollLoop` has no
  device-free test (pre-existing).

🤖 Generated with [Claude Code](https://claude.com/claude-code)
EOF
)"
```

---

## Self-Review

**Spec coverage (Stage 1 section):**
- "New `probeLatchedPid`... collapsing both probe blocks" → Tasks 1–3. Named `probePidCycle` (it does one cycle, not a whole latch); the `giveUp` sentinel from the issue's suggested signature is replaced by `PidProbe.Unsupported`. Noted here as a deliberate deviation.
- "rename to `PID_PROBE_MAX_CYCLES`" → Task 1 Step 3.
- "throttle probe **gains** the fuel probe's robustness ... called out in the PR body" → Task 3 deltas + PR body.
- "`cappedFixDtSec` ... Fold the fuel and secondsOverLimit sites. Leave the trace-distance gate" → Task 4, Global Constraints.
- "Out of scope: PID 0144 / fuel type / calibration / Δt clock change / `ObdTelemetry` field" → none touched; Task 4 keeps operands as `location.time` / `lastFuelSampleMs`.
- Done criteria greps → Task 4 Step 7, Task 5 Step 2.
- "Version: no bump" → Global Constraints.
- Stop-point recorded → Task 5 Step 1.

**Placeholder scan:** none — every code step has the literal before/after text. `<date>` / `<first>..<last>` in Task 5 are runtime values, not code placeholders.

**Type consistency:** `PidProbe` / `PidProbe.Probing(cycles)` / `PidProbe.Latched(pid)` / `PidProbe.Unsupported` / `ProbeCycle(state, result)` / `probePidCycle(input, output, state, primary, fallback, maxCycles)` / `PID_PROBE_MAX_CYCLES` / `cappedFixDtSec(nowMs, lastMs)` — used identically in Tasks 1→4 and the tests. `fuelProbe` / `throttleProbe` are the two `pollLoop` locals. `(x as? PidProbe.Latched)?.pid` smart-cast pattern used identically in Tasks 2 and 3 (both operate on a `var`, hence `as?` not a plain `is` check).
