package dev.trace.android.internal

import kotlinx.serialization.json.JsonObject

/**
 * A single TRACE event before it reaches trace-core: an event [type] string plus
 * its structured [payload]. trace-core assigns `seq` and `timestampNanos`; this
 * type never carries them.
 */
internal class TraceEvent(val type: String, val payload: JsonObject) {
    override fun toString(): String = "TraceEvent($type, $payload)"
}
