# State ownership

*Part of the [Detour Kotlin guidelines](README.md). Section numbers are stable across files — a reference like §8.4 always means §8.4, wherever it lives.*

## 4. State management

There is no `ViewModel`. State lives in exactly two places, and mixing them up
is the single most common defect in this codebase.

### 4.1 Shared state — a store `object`

```kotlin
object FriendsStore {
    private val _state = MutableStateFlow(FriendsState())
    val state: StateFlow<FriendsState> = _state.asStateFlow()

    suspend fun refreshOwn(me: RiderRef) { … }   // suspend: no Dispatchers here
}
```

Rules:

- **Never expose `MutableStateFlow`.** Public surface is `StateFlow`, via
  `asStateFlow()`.
- **Update with `_state.update { it.copy(…) }`.** Never mutate in place, never
  `_state.value = _state.value.copy(…)` from more than one coroutine.
- **The store is the single writer.** Screens and services read and call
  suspend actions.
- **No coroutine of its own.** Every action is `suspend`; the caller supplies
  the scope (`scope.launch` in Compose, `Task { }` in Swift). This is forced —
  `commonMain` has no `Dispatchers`.
- A store action that mutates re-reads the server's view rather than patching
  the local copy, so two crossing requests cannot leave client and server
  disagreeing.
- Loading is tri-state where it matters: `null` ≠ empty. `FriendsState.lists`
  is null until the first load lands, because "no friends yet" and "server has
  not answered" must not render the same.

### 4.2 UI state — Compose `remember`

Ephemeral, screen-local, dies with the screen: sheet open/closed, text field
contents, which chip is selected. `remember { mutableStateOf(...) }` is
correct here and needs no store.

**The line:** if a second surface would need the same value to behave the
same, it is not UI state. Anything a rider would notice being wrong on iOS
belongs in a store or a `drive/` machine.

### 4.3 The failure mode this repo actually has

A stateful machine written inside a `@Composable` — accumulating with
`remember`, stepping in a `LaunchedEffect` — is invisible to iOS and to the
car screen, and untestable without an emulator. `docs/refactor/mapscreen/`
exists because of this. The audit finding is blunt: **statefulness, not domain
relevance, is what currently decides whether a feature ships on iOS.**

When you write a state machine inside a composable, that is the decision you
are making.

## 13. The screen-model layer (target state)

**Status: designed, not implemented.** The executable spec is
`docs/refactor/screenmodel/specs/stage-1-screen-model-seam.md`. Until stage 1
lands, §4 describes the code as it is and this section describes where it is
going. Do not write new presenters against §13 before the seam exists.

### 13.1 The problem this fixes

`presentation/` has eight `*Presenter` classes in **two incompatible shapes**:

| Shape | Count | Files |
| --- | --- | --- |
| Owns `_state`, publishes rows | 5 | `Badges`, `Coverage`, `Places`, `Routes`, `You` |
| Publishes nothing; the screen maps on the render path | 3 | `Friends`, `CirclesList`, `CircleDetail` |

The three anemic ones are anemic on purpose, and the reason is good:
`FriendsPresenter`'s KDoc argues that *a cached snapshot goes stale the instant a
mutation lands underneath it*. That is correct — and it is an argument against
**caching**, not against owning state.

### 13.2 The shape

A `ScreenModel` **derives** rather than caches, which dissolves the objection:
every store emission re-runs the mapper.

```kotlin
// commonMain/presentation/ScreenModel.kt
abstract class ScreenModel(protected val scope: CoroutineScope) {
    open fun close() { scope.cancel() }
}
```

```kotlin
// commonMain/presentation/FriendsScreenModel.kt
class FriendsScreenModel(
    scope: CoroutineScope,
    private val repo: FriendsRepository = FriendsStore,
) : ScreenModel(scope) {

    val state: StateFlow<FriendsBoardState> = repo.state
        .map { friendsBoardStateFrom(it.lists, it.leaderboard, it.own) }
        .stateIn(scope, SharingStarted.WhileSubscribed(5_000), FriendsBoardState())

    fun refresh() = scope.launch { repo.reloadIfStale(); repo.refreshOwn(me()) }
}
```

The scope is **passed in** — no `Dispatchers` reference enters `commonMain`, so
the §2.1 constraint holds. Each surface supplies a scope with its own lifetime:

| Surface | Owner | Cancels on |
| --- | --- | --- |
| Phone | a thin `androidx` `ViewModel` in `app/` holding the model | `onCleared()` |
| Android Auto | the `Screen`'s lifecycle | `onDestroy` |
| iOS | an `ObservableObject` holding it | `deinit` → `close()` |

The androidx shell stays in `app/`; `:shared` gains no androidx dependency.

```kotlin
// app/ui/ScreenModelHost.kt
class ScreenModelHolder<T : ScreenModel>(factory: (CoroutineScope) -> T) : ViewModel() {
    val model: T = factory(viewModelScope)
}
```

### 13.3 The repository half — change less than you think

The `object` stores already **are** the repositories. Do not put all 64 behind
interfaces. Extract a narrow interface only where a screen model needs a fake:

```kotlin
interface FriendsRepository {
    val state: StateFlow<FriendsState>
    suspend fun reloadIfStale()
    suspend fun refreshOwn(me: RiderRef)
}
object FriendsStore : FriendsRepository { /* unchanged */ }
```

One real implementation plus one test fake clears the §5.2 bar — `RelaySocket`
is the precedent, and its third implementation is a test fake. Inject with a
default argument, not a container (§12.1).

### 13.4 What it costs

- **The `FlowWatcher` tax.** Every new `StateFlow<XUiState>` element type needs
  an `iosMain` `Watcher` subclass (§5.3). Budget 8–14 new classes.
- **`WhileSubscribed(5_000)` changes refresh timing.** This is a behaviour
  change, not a pure refactor — the account-switch path must be re-tested.
- **Two shapes coexist during the migration.** Stage 1 converts one presenter as
  the proof; the rest follow.
