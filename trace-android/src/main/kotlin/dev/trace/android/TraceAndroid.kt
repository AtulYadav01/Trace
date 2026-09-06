package dev.trace.android

import android.app.Application
import dev.trace.android.internal.AndroidTraceSession
import dev.trace.android.internal.EventFactory
import java.io.File

/**
 * Entry point for recording a TRACE session from an Android application.
 *
 * ```
 * class MyApp : Application() {
 *     override fun onCreate() {
 *         super.onCreate()
 *         TraceAndroid.start(this)          // privacy-safe defaults
 *     }
 * }
 * ```
 *
 * The resulting `.trace.jsonl` file (path in [currentSessionFile]) is written by
 * trace-core and can be read back with `trace.core.SessionReader`.
 *
 * All methods are safe to call from any thread. Calls that do not apply
 * (e.g. [stop] before [start], [recordScreen] while not running) are no-ops.
 */
object TraceAndroid {

    private val lock = Any()

    @Volatile
    private var session: AndroidTraceSession? = null

    /** `true` while a session is actively recording. */
    val isRunning: Boolean
        get() = session?.isRunning == true

    /**
     * The `.trace.jsonl` file for the current or most recent session, or `null`
     * if [start] has never been called. Remains readable after [stop].
     */
    val currentSessionFile: File?
        get() = session?.file

    /**
     * Starts a recording session and writes `android.app.start`.
     *
     * @return `true` if a session was started, `false` if one is already running
     *   (in which case nothing changes).
     */
    fun start(application: Application, config: TraceConfig = TraceConfig()): Boolean {
        synchronized(lock) {
            val existing = session
            if (existing != null && existing.isRunning) return false

            val directory = config.outputDirectory ?: File(application.filesDir, "trace")
            val newSession = AndroidTraceSession.start(directory, config)
            session = newSession

            newSession.record(EventFactory.appStart(application))
            // Trackers (lifecycle / interaction / exception) are installed here
            // in later commits.
            return true
        }
    }

    /**
     * Stops the current session: drains everything already recorded, writes
     * `android.app.stop`, then closes the file. Idempotent; no-op if never
     * started or already stopped.
     */
    fun stop() {
        synchronized(lock) {
            val current = session ?: return
            // Trackers are uninstalled here in later commits.
            current.stop(EventFactory.appStop())
        }
    }

    /**
     * Records an `android.screen.enter` for a screen name that the app defines
     * itself (useful for Fragments, Compose destinations, or logical screens that
     * are not 1:1 with an Activity). No-op when not running.
     */
    fun recordScreen(name: String) {
        session?.takeIf { it.isRunning }?.record(EventFactory.screenEnter(name, activity = null))
    }

    /**
     * Records an `android.ui.click` for a widget the automatic instrumentation
     * cannot see (custom views, Compose). No-op when not running.
     */
    fun recordClick(viewId: String, extra: Map<String, String> = emptyMap()) {
        session?.takeIf { it.isRunning }
            ?.record(EventFactory.click(activity = null, viewId = viewId, viewClass = "explicit", extra = extra))
    }

    /**
     * Test-only: forget the current/last session so the process-wide singleton
     * starts from a clean slate. Not part of the public API.
     */
    internal fun resetForTesting() {
        synchronized(lock) {
            session?.takeIf { it.isRunning }?.stop(null)
            session = null
        }
    }
}
