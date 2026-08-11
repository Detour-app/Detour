# Debug intents

Hooks that exist only in a debug build, for exercising behaviour that is
otherwise expensive to reach — a notification you would have to drive for, a
screen you can only arrive at from somewhere else.

Everything here lives in `app/src/debug/`, which AGP merges into debug builds
only. None of it exists in a release APK, and that is worth keeping true: these
receivers are `exported`, which is what lets `adb shell am broadcast` reach them
and equally means any app on the device could. Confirm after changing anything
in that source set:

```
aapt dump xmltree app/build/outputs/apk/release/app-release-unsigned.apk AndroidManifest.xml \
  | grep -i debug
```

Release should declare only `BootReceiver`,
`CarAppNotificationBroadcastReceiver` and `ProfileInstallReceiver`.

The debug build's applicationId carries a `.debug` suffix
(`app/build.gradle.kts`), so it installs alongside a release-signed app rather
than replacing it. Every component name below therefore starts
`io.github.maxke24.detour.debug`.

## Trip ended — `DebugTripEndedReceiver`

Raises the real "Trip ended — saved to history." notification, the one
auto-detection posts when it ends a trip you did not end yourself. Reaching it
honestly means driving past `MIN_AUTO_TRIP_METERS` and then standing still long
enough for auto-detection to give up, which is a slow loop for behaviour that is
one `PendingIntent`.

It calls `TripEndedNotification.show`, the same entry point `endTrip()` uses —
not a copy of the builder. A debug trigger that rebuilt its own notification
would prove nothing about the shipped one, which is why the builder lives in
`notif/` rather than inside `TripTrackingService`.

```
# newest trip in history
adb shell am broadcast \
  -n io.github.maxke24.detour.debug/com.jellemax.detour.debug.DebugTripEndedReceiver

# a specific trip, by its start time
adb shell am broadcast \
  -n io.github.maxke24.detour.debug/com.jellemax.detour.debug.DebugTripEndedReceiver \
  --el start_ms 1786449800000

# a trip that does not exist — tapping should land on History
adb shell am broadcast \
  -n io.github.maxke24.detour.debug/com.jellemax.detour.debug.DebugTripEndedReceiver \
  --el start_ms 1
```

`start_ms` is a trip's `startTimeMs`, which is how trips are identified
throughout — `Trip` has no id field, and `TripStore.updateMode` and
`TripStore.delete` key on the same value. With no extra, the receiver uses the
newest trip in history; with no trips at all it uses `System.currentTimeMillis()`,
an id no trip can have, so the missing-trip path is exercised rather than
nothing happening.

It logs what it picked:

```
adb logcat -s DebugTripEnded
```

### Testing the stale-extras case

Broadcasting twice with different ids and tapping only after the second is the
`FLAG_UPDATE_CURRENT` test. A `PendingIntent` reused under the same request code
keeps its *original* extras, so without that flag the second trip's notification
opens the first trip. Neither a compile nor a single manual trip can see this.

```
adb shell am broadcast -n …/…DebugTripEndedReceiver --el start_ms <trip A>
adb shell am broadcast -n …/…DebugTripEndedReceiver --el start_ms <trip B>
# now tap — it must open trip B
```

## Opening a trip without a notification

`MainActivity` reads `open_trip_start_ms` from any intent that carries it, so
the navigation half can be driven directly. This is a production extra, not a
debug one — the notification's `PendingIntent` sets exactly this — but it is
useful on its own for testing `AppRoot`'s handling without going near the shade:

```
adb shell am start -n io.github.maxke24.detour.debug/com.jellemax.detour.MainActivity \
  --el open_trip_start_ms 1786449800000
```

A trip that exists opens its detail screen; one that does not lands on History.
`MainActivity` is `singleTop`, so this arrives through `onNewIntent` when the app
is already open and through `onCreate` when it is not — both paths are worth
trying, they are different code.

## Seeding trip history

Neither hook is much use with an empty history, and recording real trips to test
a notification is the loop we are trying to avoid. `run-as` works on a debug
build, so history can be written directly:

```
cat > /tmp/trips.json <<'JSON'
[{"startTimeMs":1786449800000,"endTimeMs":1786452500000,"distanceMeters":34200.0,
  "topSpeedMps":33.3,"maxLeanAngleDeg":32.0,"maxGForce":0.9,
  "destinationLat":null,"destinationLon":null,"mode":"MOTO"}]
JSON

adb shell am force-stop io.github.maxke24.detour.debug
adb shell "run-as io.github.maxke24.detour.debug sh -c 'cat > files/trips.json'" < /tmp/trips.json
```

The shape is whatever `TripStore.encode` writes; `mode` is one of `WALK`, `BIKE`,
`MOTO`, `CAR`. Seeded trips have no GPS trace, so a detail screen's map is empty
— that is the seed, not a bug.

Two things to know before doing this on a signed-in build: `endTrip()` calls
`SyncClient.syncQuietly()`, and a sync pushes local trips to your server, so
synthetic trips can escape onto it. Check `shared_prefs/` for an account first,
or seed only on a build that has never signed in. To clear them again:

```
adb shell run-as io.github.maxke24.detour.debug rm files/trips.json
```

## Related

For behaviour that genuinely needs movement — auto-detection starting a trip,
fog of war, badges — `tools/mocklocation` feeds the device a route rather than
faking the outcome.
