# TRACE v0.2 architecture

v0.2 adds the first platform adapter, `trace-android`, plus a demo app. TRACE
Core 0.1.0 is frozen and unchanged.

```
Android application
   |  Application.ActivityLifecycleCallbacks
   |  Window.Callback (touch -> hit-test)
   |  TextWatcher / View.OnScrollChangeListener
   |  Thread.UncaughtExceptionHandler
   v
trace-android  0.2.0
   TraceAndroid (public object)  --start/stop-->  AndroidTraceSession
        |                                              |
        |   LifecycleTracker   InteractionTracker   ExceptionTracker
        |          \                 |                  /
        |           +---------> EventFactory (type + JsonObject payload)
        |                              |
        |                    single background "trace-writer" thread
        v                              |
   trace-core  0.1.0  (FROZEN)         |  SessionRecorder.record(type, payload)
        SessionRecorder  ------------->+
        v
   {uuid}.trace.jsonl   (UTF-8, LF, JSONL, schemaVersion 1)
        ^
        |  read back
   trace.core.SessionReader  ->  Sequence<Event>, isComplete
```

## Module dependency graph

```
trace-demo   (com.android.application, minSdk 26)
   +-- implementation project(":trace-android")

trace-android   (com.android.library, minSdk 26, version 0.2.0)
   +-- implementation project(":trace-core")        // no trace-core type in trace-android's public API

trace-core   (kotlin("jvm"), FROZEN 0.1.0)
   +-- api  org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3

Never:  trace-core -> android.* , trace-core -> trace-android , trace-core -> trace-demo
```

## Components

| Type | Visibility | Responsibility |
|---|---|---|
| `TraceAndroid` | public `object` | start/stop, `isRunning`, `currentSessionFile`, `recordScreen`, `recordClick`. Owns the tracker instances. Thread-safe via one lock. |
| `TraceConfig` | public `data class` | 5 flags. No behaviour. |
| `AndroidTraceSession` | internal | Session state machine over one `SessionRecorder` (via `TraceWriter`). |
| `TraceWriter` | internal | Single-thread executor feeding `SessionRecorder`; deterministic shutdown; non-blocking crash close. |
| `EventFactory` | internal | Pure builders for the `android.*` taxonomy (`TAXONOMY_VERSION = 1`). |
| `LifecycleTracker` | internal | `ActivityLifecycleCallbacks` -> `android.activity.*` + `android.screen.enter`. |
| `InteractionTracker` | internal | `Window.Callback` wrap + hit-test -> `android.ui.click`; `TextWatcher` -> `android.ui.text_change` (password-safe); throttled scroll listener -> `android.ui.scroll`. |
| `TracingWindowCallback` | internal | Pass-through `Window.Callback` decorator; reports touch-up only. |
| `ExceptionTracker` | internal | `UncaughtExceptionHandler` decorator -> `android.exception` + close, always delegates to the previous handler. |

## Session lifecycle

```
                start()                         stop()
NOT_STARTED  ----------->  RUNNING  ---------------------------->  STOPPING
(no session)              write app.start        (a) stop accepting events
                          install trackers       (b) enqueue app.stop
                                 |               (c) enqueue recorder.close()
                                 |               (d) executor.shutdown()
                                 |               (e) awaitTermination(2s)
                                 |                        |
                                 |                        v
                                 |                     STOPPED
                                 |
                                 |  uncaught exception (captureExceptions)
                                 v
                          crashStop():  state -> STOPPED
                            executor.shutdownNow()  (cancel pending, no wait)
                            recorder.record(android.exception)   } from the
                            recorder.close()                     } crashing thread
                            delegate to previous UncaughtExceptionHandler
```

- A second `start()` while `RUNNING` returns `false`. `stop()` before `start()`
  or when `STOPPED` is a no-op (state `compareAndSet`).
- After `STOPPED`, `record(...)` calls are dropped; `currentSessionFile` still
  points at the (now complete) file. `start()` again creates a fresh session.

## Threading model

- **One** `Executors.newSingleThreadExecutor` = the trace-writer thread. Every
  normal event is `execute`d on it in submission order.
- Lifecycle + interaction callbacks arrive on the **main thread** and enqueue.
- The uncaught-exception handler runs on the **crashing thread** and calls
  `record` / `close` directly - it never calls `awaitTermination`, so the
  crashing thread cannot deadlock on the writer thread.
- `SessionRecorder` (TRACE Core) is itself `synchronized`: no interleaved lines,
  `seq` assigned under its lock, so `seq` in the file is always `0..N-1` and
  timestamps are non-decreasing no matter how writer thread and crash thread
  interleave.
- `TraceAndroid` state: `AtomicReference<SessionState>`; `TraceWriter`
  acceptance: `AtomicBoolean` + the executor's own `RejectedExecutionException`.

## Shutdown / crash determinism

| Scenario | Guarantee |
|---|---|
| `stop()` with N events already queued | all N written, then `android.app.stop`, then `session_end`; `isComplete == true` |
| event submitted the instant `stop()` runs | rejected (`submit` returns `false`) or, if it slipped past, dropped when it hits the closed recorder - counted as `rejected`, never a silent loss |
| crash with N events queued | file is valid, `seq` contiguous, exactly one `android.exception`, `session_end` present |
| crash: does the crashing thread block? | no - `crashCloseDirect` never awaits the executor |
| crash: is the app's own crash behaviour changed? | no - the previous `UncaughtExceptionHandler` is always invoked with the original throwable |
| process killed (not an exception) | file ends without `session_end`; `SessionReader.isComplete == false` |

## Relationship to the frozen TRACE Core

`trace-android` only ever calls:

```
SessionRecorder.create(directory: Path, replayOf: String? = null)
recorder.record(type: String, payload: JsonObject)
recorder.close()
recorder.sessionId
```

It builds `JsonObject` payloads with `kotlinx.serialization` and never touches
the JSONL format, the schema version, `seq`, or timestamps - TRACE Core owns all
of those. If a future adapter needs Core to change, that is a Core schema-version
decision, made explicitly, not a silent edit.
