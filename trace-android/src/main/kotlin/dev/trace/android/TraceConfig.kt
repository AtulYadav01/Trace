package dev.trace.android

import java.io.File

/**
 * Configuration for a TRACE Android recording session.
 *
 * All flags default to a privacy-safe, low-overhead configuration. See
 * `trace-android/README.md` for the exact list of what each flag captures.
 *
 * @property outputDirectory Directory the `.trace.jsonl` file is written to.
 *   When `null`, `<app files dir>/trace` is used. Created if missing.
 * @property captureLifecycle Record `android.app.*` and `android.activity.*`
 *   plus `android.screen.enter`. Default `true`.
 * @property captureInteractions Record `android.ui.click` / `android.ui.text_change`
 *   / `android.ui.scroll` from instrumented activities. Default `true`.
 * @property captureText When `false` (default) no user-entered or label text is
 *   ever put in a payload: text-change events carry only a `length`, click events
 *   carry only view id and class. When `true`, `text` / `contentDescription`
 *   fields are added; password fields are still never captured.
 * @property captureExceptions Install an uncaught-exception observer that records
 *   `android.exception` and closes the session before the process dies. TRACE
 *   never replaces the app's own crash handling. Default `true`.
 */
data class TraceConfig(
    val outputDirectory: File? = null,
    val captureLifecycle: Boolean = true,
    val captureInteractions: Boolean = true,
    val captureText: Boolean = false,
    val captureExceptions: Boolean = true,
)
