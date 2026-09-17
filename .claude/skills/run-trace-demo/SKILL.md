---
name: run-trace-demo
description: Build, install, launch, and drive the trace-demo Android app on a local emulator. Use when asked to run, start, build, test, or screenshot trace-demo/trace-android, or to verify a trace-android change against a real device/emulator (not just Robolectric/JVM tests).
---

trace-demo is an Android app (module `trace-demo`, package `dev.trace.demo`) that exercises the `trace-android` library. There is no web/CLI surface — it only runs on an Android emulator or device. Drive it via `.claude/skills/run-trace-demo/driver.sh` (Git Bash), which wraps `adb`/`emulator`/`gradlew.bat`.

All paths below are relative to the repo root (`trace/`).

## Prerequisites

This is a Windows machine, not a Linux container — no `apt-get` step. What's required, already present on this machine and assumed present on any machine this skill runs on:

- Android SDK at the path in `local.properties`'s `sdk.dir` (this machine: `C:\Users\ashuy\AppData\Local\Android\Sdk`), with `platform-tools` and `emulator` installed.
- At least one AVD already created. This machine has `Pixel_9` (checked via `emulator -list-avds`). If none exists, create one first (Android Studio's Device Manager, or `avdmanager`) — this skill does not create AVDs.
- JDK 17–21 as the **Gradle launcher** JVM (AGP 8.5.2 requires it; the machine's default `java` may be newer, e.g. JDK 24, which AGP rejects). The Gradle foojay resolver auto-provisions a JDK 17 into `~/.gradle/jdks/eclipse_adoptium-17-*` the first time any Android module builds. `driver.sh build` finds it automatically; a fresh machine needs one successful build first for the resolver to fetch it.
- Git Bash (the driver is a bash script; run it from Git Bash, not PowerShell/cmd).

## Build

```bash
./.claude/skills/run-trace-demo/driver.sh build
# -> ./gradlew.bat -Dorg.gradle.java.home=<jdk17> :trace-demo:assembleDebug
# APK lands at trace-demo/build/outputs/apk/debug/trace-demo-debug.apk
```

## Run (agent path)

Boot the emulator, install, launch, then drive it with `driver.sh`:

```bash
./.claude/skills/run-trace-demo/driver.sh boot     # starts/attaches AVD "Pixel_9", waits for full boot
./.claude/skills/run-trace-demo/driver.sh install  # adb install -r the debug APK
./.claude/skills/run-trace-demo/driver.sh launch   # am start dev.trace.demo/.HomeActivity
```

Then interact and inspect:

| command | what it does |
|---|---|
| `dump <local-path>` | `uiautomator dump`, pulled to `<local-path>` — inspect it to find `resource-id`s/text before tapping |
| `tap-id <resource-id-suffix>` | dumps the UI, finds the element whose `resource-id` ends with the given suffix, taps its center — resolution-independent, prefer this over raw coordinates |
| `type <text>` | `adb shell input text` |
| `swipe <x1> <y1> <x2> <y2> [ms]` | `adb shell input swipe` |
| `key <keycode>` | `adb shell input keyevent` (e.g. `key 4` = BACK) |
| `screenshot <local-path>` | `adb exec-out screencap -p`, saved to `<local-path>` — **look at it**, don't just check it's non-empty |
| `pull-last-session <local-path>` | pulls the newest `.trace.jsonl` from the app's external files dir; prints its filename |
| `stop` | kills the emulator |

Example flow (this is exactly what was run to verify this skill — see Gotchas for the one snag hit along the way):

```bash
./.claude/skills/run-trace-demo/driver.sh tap-id button_a
./.claude/skills/run-trace-demo/driver.sh tap-id name_field
./.claude/skills/run-trace-demo/driver.sh type "DriverTest"
./.claude/skills/run-trace-demo/driver.sh screenshot /tmp/screen.png   # then actually view the file
./.claude/skills/run-trace-demo/driver.sh tap-id go_to_details
./.claude/skills/run-trace-demo/driver.sh key 4                        # back to Home
./.claude/skills/run-trace-demo/driver.sh tap-id finish_session
./.claude/skills/run-trace-demo/driver.sh pull-last-session /tmp/session.trace.jsonl
```

`resource-id` suffixes currently on screen (from `HomeActivity`/`DetailsActivity`, `dev.trace.demo:id/...`): `button_a`, `button_b`, `name_field`, `password_field`, `go_to_details`, `finish_session`, `crash_button`, `details_action`, `back_button`.

### Verifying a pulled session file for real

A pulled `.trace.jsonl` is only actually verified once it's read back through `trace-core`'s real `SessionReader` (not just eyeballed). There's no reusable harness for this yet — the way it's been done twice so far is a **throwaway** JUnit test added to `trace-core/src/test/kotlin/trace/core/`, run once, then deleted:

```kotlin
package trace.core
import org.junit.jupiter.api.Test
import java.nio.file.Paths

class ZZZManualDeviceVerificationTest {
    @Test fun verifyDevicePulledSession() {
        val path = System.getenv("TRACE_VERIFY_FILE") ?: error("set TRACE_VERIFY_FILE env var")
        SessionReader.open(Paths.get(path)).use { reader ->
            var expectedSeq = 0L; var lastTs = -1L; var count = 0
            reader.events().forEach { e ->
                check(e.seq == expectedSeq) { "seq mismatch: expected $expectedSeq got ${e.seq}" }
                check(e.timestampNanos >= lastTs) { "timestamp decreased at seq ${e.seq}" }
                expectedSeq++; lastTs = e.timestampNanos; count++
            }
            println("eventsRead=$count isComplete=${reader.isComplete}")
        }
    }
}
```

```bash
export TRACE_VERIFY_FILE=/tmp/session.trace.jsonl
./gradlew.bat :trace-core:test --tests "trace.core.ZZZManualDeviceVerificationTest" --rerun --console=plain
cat trace-core/build/test-results/test/TEST-trace.core.ZZZManualDeviceVerificationTest.xml | grep -A5 system-out
```

Delete the test file afterward and confirm `git status` is clean — `trace-core`'s frozen contract (see `.claude/lessons.md`) must stay untouched by this. `--rerun` is needed because Gradle's test task doesn't track `TRACE_VERIFY_FILE` as an input, so a second run with a different file silently reuses the cached result without it.

## Run (human path)

Same `boot`/`install`/`launch` steps, then just use the emulator window that opens (it's not headless on this machine — `emulator.exe` opens a visible window). Stop with `driver.sh stop` or close the emulator window.

## Test

```bash
./gradlew.bat -Dorg.gradle.java.home=<jdk17> test
```

133 tests as of 2026-09-17 (trace-core 73, trace-android 58 Robolectric, trace-demo 2 Robolectric integration). None of these touch a real device — that's what this skill is for.

---

## Gotchas

- **Git Bash mangles `/sdcard/...` and `/storage/...` paths.** Git Bash auto-converts a leading `/xxx` argument into a Windows path before `adb.exe` (a native binary) ever sees it, so `adb pull /sdcard/foo` fails with `cannot create file/directory 'C:\...\sdcard\foo'`. Fix: double the leading slash (`//sdcard/foo`) only on the **remote** path — this is what `dump`/`pull-last-session` do internally. Do **not** set `MSYS_NO_PATHCONV=1` to work around it; that also stops your **local** destination path from being converted, so a Windows-style local path with drive-letter prefix breaks instead.
- **`adb devices` can report a device as `offline` transiently** right after `uiautomator dump`, then recover a few seconds later with no action needed. If a command fails with `device offline`, just retry once or two after a short pause rather than assuming the emulator crashed.
- **Gradle's `test` task doesn't see `TRACE_VERIFY_FILE` as an input.** Re-running `:trace-core:test` with the env var changed but the test source unchanged returns a cached UP-TO-DATE result with no output. Pass `--rerun` (or touch the test file) to force it.
- **`recordClick()` will fire even on a password field.** If you're driving the password field during interactive testing, that's expected today (see `.claude/tasks.md` for the open privacy-gap task) — not a driver bug.

## Troubleshooting

- **`gradlew.bat` fails to configure the Android modules on a fresh machine** ("Android Gradle plugin requires Java 17 to run"): the default `java` is too new. Run `./.claude/skills/run-trace-demo/driver.sh build` (it passes `-Dorg.gradle.java.home` at the JDK 17 the foojay resolver provisioned) rather than a bare `./gradlew.bat`.
- **`emulator -avd Pixel_9` doesn't exist**: `emulator -list-avds` lists what's actually installed on this machine. If it's empty, an AVD needs to be created first (out of scope for this driver).
