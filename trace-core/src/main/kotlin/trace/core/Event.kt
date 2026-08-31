package trace.core

import kotlinx.serialization.json.JsonObject

/**
 * Public representation of an event for consumers of the read API.
 * This is the type returned by [SessionReader.events].
 */
data class Event(
    /**
     * Sequence number, strictly increasing within the session (0-indexed).
     */
    val seq: Long,

    /**
     * Nanoseconds since session start. Monotonically non-decreasing.
     */
    val timestampNanos: Long,

    /**
     * Event type identifier. Recommended convention: {vendor}.{category}.{name}
     * Example: "android.lifecycle.onCreate"
     */
    val type: String,

    /**
     * Arbitrary JSON payload associated with the event.
     */
    val payload: JsonObject
)
