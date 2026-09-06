package dev.trace.android.internal

import android.view.MotionEvent
import android.view.Window

/**
 * A [Window.Callback] decorator: forwards every call unchanged to [delegate] and
 * additionally reports touch-up events to [onTouchUp] so [InteractionTracker] can
 * resolve which view the user tapped.
 *
 * This never changes dispatch behaviour: the return value and side effects are
 * exactly those of [delegate].
 */
internal class TracingWindowCallback(
    private val delegate: Window.Callback,
    private val onTouchUp: (MotionEvent) -> Unit,
) : Window.Callback by delegate {

    /** Set on the wrapped window so we can detect and unwrap ourselves. */
    val wrapped: Window.Callback get() = delegate

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        val handled = delegate.dispatchTouchEvent(event)
        if (event.actionMasked == MotionEvent.ACTION_UP) {
            try {
                onTouchUp(event)
            } catch (t: Throwable) {
                AndroidTraceLog.w("touch observation failed", t)
            }
        }
        return handled
    }
}
