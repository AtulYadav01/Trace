# trace-android

`trace-android` 0.2.0 connects a running Android application to **TRACE Core**
and turns runtime activity into a structured, chronological `.trace.jsonl`
session that `trace.core.SessionReader` can read back.

It is an SDK-level foundation, not a logging library and not a transparent
whole-OS capture layer. It records a small, deliberate set of meaningful events
so a later tool can answer questions like *"which screen was the user on before
this crash, and what did they tap?"*

- Depends on `:trace-core` (frozen at 0.1.0). Never modifies it.
- `minSdk 26` (TRACE Core uses `java.nio.file`, native on Android from API 26).
- No AndroidX, no accessibility service, no root, no bytecode instrumentation,
  no reflection magic, no network, no database.

---

## Initialization

```kotlin
class MyApp : Application() {
    override fun onCreate() {
        super.onCreate()
        TraceAndroid.start(this)              // privacy-safe defaults
    }
}
```

Call `start()` from `Application.onCreate()` so the full activity lifecycle is
captured. Starting later still works but misses activities created before the call.

```kotlin
TraceAndroid.stop()                          // writes android.app.stop + closes the file
TraceAndroid.isRunning                        // Boolean
TraceAndroid.currentSessionFile              // java.io.File? (current or most recent)

TraceAndroid.recordScreen("Checkout")        // logical screen name (Fragments / Compose)
TraceAndroid.recordClick("custom_widget")    // widget the auto-instrumentation can't see
```

`start()` returns `false` if a session is already running. `stop()` before
`start()`, and a second `stop()`, are no-ops. `recordScreen` / `recordClick`
are no-ops when not running. All methods are safe to call from any thread.

---

## Configuration

```kotlin
TraceAndroid.start(
    application,
    TraceConfig(
        outputDirectory = File(getExternalFilesDir(null), "traces"), // default: <filesDir>/trace
        captureLifecycle = true,
        captureInteractions = true,
        captureText = false,   // privacy-safe default
        captureExceptions = true,
    ),
)
```

No DI framework, no config files. That is the whole configuration surface.

---

## What is captured

### Always (when the relevant flag is on)

| Data | Where |
|---|---|
| Event `type` + `seq` + `timestampNanos` | every event (`seq`/`timestamp` assigned by TRACE Core) |
| App package, versionName, versionCode | `android.app.start` |
| API level, `Build.MODEL`, `Build.MANUFACTURER` (coarse, not identifiers) | `android.app.start` |
| `traceAndroidVersion`, `taxonomyVersion` | `android.app.start` |
| Activity class simple name + instance id | `android.activity.*` |
| Screen name (= activity name, or your `recordScreen` value) | `android.screen.enter` |
| Tapped view: resource id name + class | `android.ui.click` |
| Changed text field: resource id name + **length only** | `android.ui.text_change` |
| Scroll delta (`dx`, `dy`) + view id | `android.ui.scroll` |
| Exception type, message, thread, `fatal`, truncated stack trace | `android.exception` |

### Only when `captureText = true`

- `text` and `contentDescription` on `android.ui.click`
- `text` on `android.ui.text_change`

If you enable this, payloads may contain user-entered text and on-screen labels.

### Never captured

- **Password fields** - detected by `inputType` variation
  (`TYPE_TEXT_VARIATION_PASSWORD` / `WEB_PASSWORD` / `VISIBLE_PASSWORD`,
  `TYPE_NUMBER_VARIATION_PASSWORD`) and by `PasswordTransformationMethod`, with an
  id/hint keyword check as a backstop. Password fields get **no** watcher and
  produce **no** events, not even a length.
- Any typed text unless `captureText = true`.
- `contentDescription` / label text unless `captureText = true`.
- IMEI, Android ID, advertising ID, accounts, phone number, location, IP,
  request/response bodies, headers, Logcat.
- Screenshots, view snapshots, the accessibility tree.

---

## Trace output & retrieval

The file is `<outputDirectory>/{uuid}.trace.jsonl` (UTF-8, LF, one JSON object
per line - the TRACE Core v0.1 format, unchanged). Its path is
`TraceAndroid.currentSessionFile`.

For the demo (writes to the external files dir):

```bash
adb shell run-as dev.trace.demo ls files/traces
adb pull /sdcard/Android/data/dev.trace.demo/files/traces/<uuid>.trace.jsonl
```

Read it back:

```kotlin
SessionReader.open(file.toPath()).use { reader ->
    reader.events().forEach { e -> println("[${e.seq}] ${e.type} ${e.payload}") }
    println(if (reader.isComplete) "ended cleanly" else "truncated / process killed")
}
```

---

## Event taxonomy (`android.*`, `taxonomyVersion = 1`)

The `android.*` namespace and payload shapes are owned by `trace-android`. TRACE
Core is completely unaware of them. Event names are **not** frozen the way the
JSONL schema is; a change bumps `taxonomyVersion`, never the Core schema.

| type | payload keys |
|---|---|
| `android.app.start` | `package`, `versionName`, `versionCode`, `osApiLevel`, `deviceModel`, `deviceManufacturer`, `traceAndroidVersion`, `taxonomyVersion` |
| `android.app.stop` | (none) |
| `android.activity.create` | `activity`, `instanceId`, `restoredState` |
| `android.activity.start` / `resume` / `pause` / `stop` / `destroy` | `activity`, `instanceId` |
| `android.screen.enter` | `screen`, `activity` |
| `android.ui.click` | `activity`, `viewId`, `viewClass` (+ `text`, `contentDescription` if `captureText`) |
| `android.ui.text_change` | `activity`, `viewId`, `length` (+ `text` if `captureText`) |
| `android.ui.scroll` | `activity`, `viewId`, `dx`, `dy` |
| `android.exception` | `exceptionType`, `message`, `thread`, `fatal`, `stackTrace` |

### Sample

```json
{"recordType":"session_start","schemaVersion":1,"id":"62cf0177-...","startedAtMillis":1788725048762,"source":"RECORDING","replayOf":null}
{"recordType":"event","seq":0,"timestampNanos":120950900,"type":"android.app.start","payload":{"package":"dev.trace.demo","versionName":"0.2.0","versionCode":1,"osApiLevel":33,"deviceModel":"robolectric","deviceManufacturer":"robolectric","traceAndroidVersion":"0.2.0","taxonomyVersion":1}}
{"recordType":"event","seq":1,"timestampNanos":464096600,"type":"android.activity.create","payload":{"activity":"HomeActivity","instanceId":"HomeActivity@4c22a728","restoredState":false}}
{"recordType":"event","seq":3,"timestampNanos":2129533800,"type":"android.activity.resume","payload":{"activity":"HomeActivity","instanceId":"HomeActivity@4c22a728"}}
{"recordType":"event","seq":4,"timestampNanos":2129870900,"type":"android.screen.enter","payload":{"screen":"HomeActivity","activity":"HomeActivity"}}
{"recordType":"event","seq":5,"timestampNanos":3029702700,"type":"android.ui.click","payload":{"activity":"HomeActivity","viewId":"button_a","viewClass":"android.widget.Button"}}
{"recordType":"event","seq":6,"timestampNanos":3059622500,"type":"android.ui.text_change","payload":{"activity":"HomeActivity","viewId":"name_field","length":3}}
{"recordType":"session_end","eventCount":22,"endedAtMillis":1788725051950}
```

---

## Behaviour & guarantees

- **Ordering.** All events go through a single background writer thread and are
  written to TRACE Core in submission order, so the file's physical order is the
  event order and `seq` is exactly `0..N-1`.
- **Shutdown.** `stop()` moves to `STOPPING`, stops accepting events, then
  enqueues `android.app.stop` and the file `close()` as the last tasks and waits
  (up to 2 s) for the queue to drain. Every event accepted before `stop()` is
  written before the file closes. Events submitted after `stop()` begins are
  rejected, never silently queued-then-lost.
- **Crash.** On an uncaught exception, `trace-android` records one
  `android.exception` and closes the session **from the crashing thread**,
  without waiting on the writer thread (no deadlock), then always hands the
  throwable to the handler that was installed before TRACE. It never consumes the
  exception or changes process-death behaviour. TRACE Core's synchronized
  recorder keeps the file valid and `seq`-contiguous regardless of any in-flight
  event.
- **Incomplete sessions.** If the process is killed (not an uncaught exception -
  e.g. the OS reclaims a backgrounded app), the file simply ends with no
  `session_end`; `SessionReader.isComplete` is then `false`. Android provides no
  reliable "app stopped/killed" callback, so `trace-android` does not fake one.

---

## Interaction capture: exactly what works

`trace-android` wraps each resumed activity's `Window.Callback` and, on touch-up,
hit-tests the decor view for the deepest clickable view under the touch point.

**Captured:** the user tapping a view with touch input, in an activity TRACE
instrumented, whose view is in the normal decor hierarchy.

**Not captured:** programmatic `performClick()`, key / D-pad / hardware-button
activation, gestures other than scroll, dialogs or windows TRACE did not wrap,
`RecyclerView` / `ListView` scrolling (only `ScrollView` /
`HorizontalScrollView` / `NestedScrollView` report scroll here), and anything in
a process where `TraceAndroid.start()` was not called.

For custom views or Jetpack Compose, call `TraceAndroid.recordClick(id)` /
`TraceAndroid.recordScreen(name)` yourself. Compose-native integration is a
future addition, not part of 0.2.

---

## Limitations (0.2)

- Verified on the JVM (Robolectric) and by Android lint; not yet exercised on a
  physical device / emulator.
- One writer thread does a file write + flush per event; fine at interaction
  frequency, not tuned for thousands of events per second.
- `isComplete` is authoritative only after `events()` has been fully consumed
  (a TRACE Core property).
- `deviceModel` / `deviceManufacturer` are coarse strings from `Build`, included
  for context; they are not device identifiers, but disable
  `captureExceptions` / review payloads if your policy forbids even that.

---

## Demo

`:trace-demo` is a minimal Views app (`dev.trace.demo`):

- `DemoApp` starts TRACE in `onCreate`.
- `HomeActivity`: Button A, Button B (clicks captured automatically), a name
  field, a password field (never captured), "Go to Details", "Finish TRACE
  session" (calls `stop()` and shows the file path), and a developer-only
  "Crash" button.
- `DetailsActivity`: an action button and Back.

```bash
./gradlew :trace-demo:installDebug     # to a connected device/emulator
# interact, press "Finish TRACE session" or "Crash", then pull the file (see above)
```
