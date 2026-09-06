package dev.trace.android.internal

import dev.trace.android.TraceConfig
import trace.core.SessionRecorder
import java.io.File
import java.util.concurrent.atomic.AtomicReference

/**
 * One TRACE recording session: owns the trace-core [SessionRecorder] (via
 * [TraceWriter]) and the session state machine.
 *
 * State transitions:
 * ```
 * NOT_STARTED --start()--> RUNNING --stop()--> STOPPING --(drain, app.stop, close)--> STOPPED
 *                                  \--crashStop()------------------------------------> STOPPED
 * ```
 * (`NOT_STARTED` is modelled by the absence of a session in `TraceAndroid`; an
 * `AndroidTraceSession` instance is always at least `RUNNING`.)
 */
internal class AndroidTraceSession private constructor(
    val file: File,
    val config: TraceConfig,
    private val writer: TraceWriter,
) {

    private val state = AtomicReference(SessionState.RUNNING)

    val isRunning: Boolean get() = state.get() == SessionState.RUNNING

    val currentState: SessionState get() = state.get()

    /** Records an event if the session is RUNNING; otherwise silently ignores it. */
    fun record(event: TraceEvent) {
        if (state.get() != SessionState.RUNNING) return
        writer.submit(event)
    }

    /**
     * RUNNING -> STOPPING -> drain accepted events -> write [stopEvent] -> close
     * recorder -> STOPPED. Blocks up to [STOP_DRAIN_TIMEOUT_MS]. Idempotent.
     */
    fun stop(stopEvent: TraceEvent?) {
        if (!state.compareAndSet(SessionState.RUNNING, SessionState.STOPPING)) return
        writer.shutdownAndClose(stopEvent, STOP_DRAIN_TIMEOUT_MS)
        state.set(SessionState.STOPPED)
    }

    /**
     * Crash path. Moves straight to STOPPED (so any further [record] is ignored),
     * then writes [exceptionEvent] and closes the recorder without blocking on
     * the writer thread. Safe to call from any thread.
     */
    fun crashStop(exceptionEvent: TraceEvent) {
        val previous = state.getAndSet(SessionState.STOPPED)
        if (previous == SessionState.STOPPED) return
        writer.crashCloseDirect(exceptionEvent)
    }

    fun writerStats(): TraceWriter.Stats = writer.stats()

    companion object {
        const val STOP_DRAIN_TIMEOUT_MS = 2_000L

        fun start(directory: File, config: TraceConfig): AndroidTraceSession {
            directory.mkdirs()
            val recorder = SessionRecorder.create(directory.toPath())
            val file = File(directory, "${recorder.sessionId}.trace.jsonl")
            return AndroidTraceSession(file, config, TraceWriter(recorder))
        }
    }
}

internal enum class SessionState { NOT_STARTED, RUNNING, STOPPING, STOPPED }
