*Part of the [Detour Kotlin guidelines](README.md). Section numbers are stable across files — a reference like §8.4 always means §8.4, wherever it lives.*

## 10. Tests

`shared/src/commonTest/` is the best-protected test location in the repo: it
runs on every PR via `build.yml`, and again on JVM **and** Kotlin/Native via
`ios.yml` when `shared/**` changed. `iosApp/` has no test target at all, which
is a further argument for putting a rule in `commonMain` rather than in Swift.

House style, matching what is there:

- Plain `kotlin.test`. No mocking library, no coroutine test dispatcher.
- One class per subject, KDoc saying which contract it covers.
- A `private fun` fixture builder, `.copy(…)` per test. Not a `@Before` field.
- Test names are full sentences in camelCase stating the property:
  `fewerThanTwoStopsIsRejected`.
- **Time and randomness are arguments, never ambient**:
  `RouteGpx.parseGpx(gpx, nowMs = 999L)`.
- Doubles compared with `absoluteTolerance`, never bare `assertEquals`.
- Regression tests carry the observed symptom in the assertion message.
