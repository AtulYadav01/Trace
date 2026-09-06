package dev.trace.android.internal

import kotlinx.serialization.json.JsonObject
import trace.core.SessionRecorder
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Feeds events into trace-core's [SessionRecorder] from a single background
 * thread, so the host app's main thread never blocks on disk and event order is
 * preserved.
 *
 * ### Shutdown determinism
 * Every event that [submit] accepts (returns `true`) is written to the file
 * before the recorder is closed. [shutdownAndClose] enqueues the optional final
 * event and the recorder `close()` as the **last** tasks on the same FIFO queue,
 * then waits for the queue to drain. There is no window in which an accepted
 * event runs after `close()` and is silently lost:
 *
 * - Events submitted before [shutdownAndClose] are already queued ahead of the
 *   close task and are written.
 * - Events submitted after [shutdownAndClose] begins are rejected by [submit]
 *   (returns `false`), either because `accepting` is already `false` or because
 *   the executor rejects the task after `shutdown()`.
 * - A task that still slips onto the queue after the close task hits the closed
 *   recorder, which throws `IllegalStateException`; that is counted as
 *   `rejected`, never a silent loss.
 *
 * ### Crash path
 * [crashCloseDirect] is called from the crashing thread. It never touches the
 * executor's `awaitTermination` (no deadlock) and writes the exception event and
 * `close()` directly. [SessionRecorder] is itself thread-safe, so a straggler
 * task on the writer thread cannot corrupt the file or the `seq` sequence: it
 * either acquires an earlier `seq` (still contiguous) or hits the closed recorder
 * and is dropped.
 */
internal class TraceWriter(private val recorder: SessionRecorder) {

    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "trace-writer").apply { isDaemon = true }
    }

    private val accepting = AtomicBoolean(true)

    private val acceptedCount = AtomicLong(0)
    private val writtenCount = AtomicLong(0)
    private val rejectedCount = AtomicLong(0)

    /**
     * Hands an event to the writer thread.
     *
     * @return `true` if the event was accepted onto the queue (it will be
     *   written before the recorder closes), `false` if it was rejected because
     *   shutdown has begun.
     */
    fun submit(event: TraceEvent): Boolean {
        if (!accepting.get()) {
            rejectedCount.incrementAndGet()
            return false
        }
        return try {
            executor.execute { write(event.type, event.payload) }
            acceptedCount.incrementAndGet()
            true
        } catch (e: RejectedExecutionException) {
            rejectedCount.incrementAndGet()
            false
        }
    }

    private fun write(type: String, payload: JsonObject) {
        try {
            recorder.record(type, payload)
            writtenCount.incrementAndGet()
        } catch (e: IllegalStateException) {
            // Recorder already closed (raced shutdown). Explicit rejection, not silent loss.
            rejectedCount.incrementAndGet()
        } catch (t: Throwable) {
            rejectedCount.incrementAndGet()
            AndroidTraceLog.w("trace record failed for '$type'", t)
        }
    }

    /**
     * Deterministic shutdown: stop accepting, enqueue [finalEvent] (if any) and
     * the recorder close as the last tasks, then block up to [timeoutMs] for the
     * queue to drain.
     */
    fun shutdownAndClose(finalEvent: TraceEvent?, timeoutMs: Long) {
        if (!accepting.compareAndSet(true, false)) return
        try {
            if (finalEvent != null) {
                executor.execute { write(finalEvent.type, finalEvent.payload) }
            }
            executor.execute { closeRecorderQuietly() }
        } catch (e: RejectedExecutionException) {
            // Executor already shutting down; fall through to the safety net below.
        }
        executor.shutdown()
        try {
            if (!executor.awaitTermination(timeoutMs, TimeUnit.MILLISECONDS)) {
                AndroidTraceLog.w("trace writer did not drain within ${timeoutMs}ms; forcing")
                executor.shutdownNow()
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            executor.shutdownNow()
        }
        // Safety net: the recorder MUST end up closed even if the close task was
        // dropped. close() is idempotent in trace-core.
        closeRecorderQuietly()
    }

    /**
     * Crash-path close. Does not block on the executor. Cancels pending tasks,
     * then writes the exception event and closes the recorder directly.
     */
    fun crashCloseDirect(exceptionEvent: TraceEvent) {
        accepting.set(false)
        try {
            executor.shutdownNow()
        } catch (t: Throwable) {
            // ignore
        }
        try {
            recorder.record(exceptionEvent.type, exceptionEvent.payload)
            writtenCount.incrementAndGet()
        } catch (t: Throwable) {
            AndroidTraceLog.w("crash-path record failed", t)
        }
        closeRecorderQuietly()
    }

    private fun closeRecorderQuietly() {
        try {
            recorder.close()
        } catch (t: Throwable) {
            AndroidTraceLog.w("recorder close failed", t)
        }
    }

    fun stats(): Stats = Stats(acceptedCount.get(), writtenCount.get(), rejectedCount.get())

    internal data class Stats(val accepted: Long, val written: Long, val rejected: Long)
}
