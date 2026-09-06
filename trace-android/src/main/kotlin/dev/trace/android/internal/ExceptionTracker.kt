package dev.trace.android.internal

/**
 * Observes uncaught exceptions in the application process. On a crash it records
 * one `android.exception` event and closes the session, then hands the throwable
 * to whatever uncaught-exception handler was installed before TRACE.
 *
 * TRACE never changes Android crash semantics:
 * - it does not consume the exception,
 * - it does not replace the process-death behaviour,
 * - it always delegates to the previous handler.
 *
 * The crash write goes straight to the recorder from the crashing thread
 * ([AndroidTraceSession.crashStop] -> [TraceWriter.crashCloseDirect]); it never
 * calls `awaitTermination`, so the crashing thread cannot deadlock on the writer
 * thread. trace-core's `SessionRecorder` is internally synchronized, so a
 * straggler queued event cannot corrupt the file or break `seq` continuity.
 */
internal class ExceptionTracker(
    private val session: AndroidTraceSession,
) : Thread.UncaughtExceptionHandler {

    private var previous: Thread.UncaughtExceptionHandler? = null
    private var installed = false

    fun install() {
        if (installed) return
        previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler(this)
        installed = true
    }

    fun uninstall() {
        if (!installed) return
        // Only restore if nobody replaced us in the meantime.
        if (Thread.getDefaultUncaughtExceptionHandler() === this) {
            Thread.setDefaultUncaughtExceptionHandler(previous)
        }
        installed = false
    }

    override fun uncaughtException(thread: Thread, throwable: Throwable) {
        try {
            session.crashStop(EventFactory.exception(throwable, thread.name, fatal = true))
        } catch (t: Throwable) {
            AndroidTraceLog.w("failed to record crash", t)
        }
        // Always hand the crash on. If there is no previous handler (should not
        // happen on Android, where RuntimeInit always installs one) there is
        // nothing more we can safely do from inside an uncaught handler.
        previous?.uncaughtException(thread, throwable)
    }

    /** Test hook: the handler that was in place when [install] ran. */
    internal fun previousHandler(): Thread.UncaughtExceptionHandler? = previous
}
