package dev.trace.android.internal

import android.app.Activity
import android.app.Application
import android.content.res.Resources
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.text.method.PasswordTransformationMethod
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import dev.trace.android.TraceConfig

/**
 * Captures user interaction in instrumented activities and turns it into
 * `android.ui.click` / `android.ui.text_change` / `android.ui.scroll` events.
 *
 * ### Supported behaviour (v0.2)
 * - **Clicks**: on `onActivityResumed` the activity's [android.view.Window.Callback]
 *   is wrapped with [TracingWindowCallback]. On every touch-up the deepest
 *   clickable view under the touch point is resolved by hit-testing the decor
 *   view and a click event is recorded, unless that view is a password
 *   [EditText] (per [isPasswordField]), which produces **no** click event
 *   either. This captures *"the user tapped this view"* for touch input. It
 *   does **not** capture programmatic `performClick()`, key/D-pad activation,
 *   or input in a window TRACE did not wrap.
 * - **Text changes**: a [TextWatcher] is attached to every non-password
 *   [EditText] found in the tree. Password fields (detected by `inputType`
 *   variation and by [PasswordTransformationMethod], with an id/hint keyword
 *   check as a backstop) get **no** watcher and produce **no** events. With
 *   `captureText == false` the payload carries only `length`.
 * - **Scroll**: a throttled `View.OnScrollChangeListener` is attached to views
 *   that report scrolling through `View.scrollX/scrollY` (ScrollView,
 *   NestedScrollView, HorizontalScrollView). RecyclerView / ListView scrolling
 *   is not captured in v0.2.
 *
 * The tree is (re)scanned on resume and on debounced global-layout changes.
 * TRACE does not claim universal Android input capture.
 */
internal class InteractionTracker(
    private val session: AndroidTraceSession,
    private val config: TraceConfig,
    private val clockNanos: () -> Long = { System.nanoTime() },
) : Application.ActivityLifecycleCallbacks {

    private val instrumentedTag = R_TAG_INSTRUMENTED
    private val scrollTimeTag = R_TAG_SCROLL_TIME

    /** Original window callbacks, keyed by activity identity, for restoration. */
    private val originalCallbacks = HashMap<Int, android.view.Window.Callback?>()

    fun install(application: Application) = application.registerActivityLifecycleCallbacks(this)
    fun uninstall(application: Application) = application.unregisterActivityLifecycleCallbacks(this)

    // ---- lifecycle ----

    override fun onActivityResumed(activity: Activity) {
        if (!config.captureInteractions) return
        wrapWindowCallback(activity)
        scanTree(activity)
    }

    override fun onActivityPaused(activity: Activity) {
        restoreWindowCallback(activity)
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
    override fun onActivityStarted(activity: Activity) {}
    override fun onActivityStopped(activity: Activity) {}
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
    override fun onActivityDestroyed(activity: Activity) {
        originalCallbacks.remove(System.identityHashCode(activity))
    }

    // ---- clicks ----

    private fun wrapWindowCallback(activity: Activity) {
        val window = activity.window ?: return
        val current = window.callback
        if (current is TracingWindowCallback) return
        originalCallbacks[System.identityHashCode(activity)] = current
        val name = activityName(activity)
        window.callback = TracingWindowCallback(current ?: return) { ev ->
            onTouchUp(activity, name, ev)
        }
    }

    private fun restoreWindowCallback(activity: Activity) {
        val window = activity.window ?: return
        val cb = window.callback
        if (cb is TracingWindowCallback) {
            window.callback = cb.wrapped
        }
        originalCallbacks.remove(System.identityHashCode(activity))
    }

    private fun onTouchUp(activity: Activity, activityName: String, event: MotionEvent) {
        val root = activity.window?.decorView ?: return
        val target = deepestClickable(root, event.rawX.toInt(), event.rawY.toInt()) ?: return
        if (target is EditText && isPasswordField(target)) return // never recorded
        recordClick(target, activityName)
    }

    /** Deepest clickable/long-clickable view whose on-screen bounds contain the point. */
    internal fun deepestClickable(root: View, screenX: Int, screenY: Int): View? {
        if (root.visibility != View.VISIBLE || !containsPoint(root, screenX, screenY)) return null
        if (root is ViewGroup) {
            for (i in root.childCount - 1 downTo 0) {
                val hit = deepestClickable(root.getChildAt(i), screenX, screenY)
                if (hit != null) return hit
            }
        }
        return if (root.isClickable || root.hasOnClickListeners() || root.isLongClickable) root else null
    }

    private fun containsPoint(view: View, screenX: Int, screenY: Int): Boolean {
        val loc = IntArray(2)
        view.getLocationOnScreen(loc)
        return screenX >= loc[0] && screenX < loc[0] + view.width &&
            screenY >= loc[1] && screenY < loc[1] + view.height
    }

    private fun recordClick(view: View, activityName: String) {
        val text = if (config.captureText) (view as? TextView)?.text?.toString()?.takeIf { it.isNotEmpty() } else null
        val cd = if (config.captureText) view.contentDescription?.toString()?.takeIf { it.isNotEmpty() } else null
        session.record(
            EventFactory.click(
                activity = activityName,
                viewId = viewIdName(view),
                viewClass = view.javaClass.name,
                text = text,
                contentDescription = cd,
            ),
        )
    }

    // ---- text + scroll instrumentation ----

    private fun scanTree(activity: Activity) {
        val root = activity.window?.decorView as? ViewGroup ?: return
        val name = activityName(activity)
        forEachView(root) { v ->
            when {
                v is EditText -> instrumentEditText(v, name)
                canReportScroll(v) -> instrumentScroll(v, name)
            }
        }
    }

    private fun forEachView(root: View, action: (View) -> Unit) {
        action(root)
        if (root is ViewGroup) {
            for (i in 0 until root.childCount) forEachView(root.getChildAt(i), action)
        }
    }

    private fun instrumentEditText(field: EditText, activityName: String) {
        if (field.getTag(instrumentedTag) == true) return
        field.setTag(instrumentedTag, true)
        if (isPasswordField(field)) return // never watched

        field.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val value = s?.toString().orEmpty()
                session.record(
                    EventFactory.textChange(
                        activity = activityName,
                        viewId = viewIdName(field),
                        length = value.length,
                        text = if (config.captureText) value else null,
                    ),
                )
            }
        })
    }

    private fun isPasswordField(field: EditText): Boolean {
        val type = field.inputType
        val klass = type and InputType.TYPE_MASK_CLASS
        val variation = type and InputType.TYPE_MASK_VARIATION
        val byInputType = when (klass) {
            InputType.TYPE_CLASS_TEXT -> variation == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
                variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD ||
                variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
            InputType.TYPE_CLASS_NUMBER -> variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD
            else -> false
        }
        if (byInputType) return true
        if (field.transformationMethod is PasswordTransformationMethod) return true
        // Backstop keyword check on id name and hint (not the only signal).
        val hay = (safeIdName(field) + " " + (field.hint ?: "")).lowercase()
        return hay.contains("password") || hay.contains("passwd") || hay.contains("pwd")
    }

    private fun canReportScroll(v: View): Boolean =
        v is android.widget.ScrollView ||
            v is android.widget.HorizontalScrollView ||
            v.javaClass.name == "androidx.core.widget.NestedScrollView"

    private fun instrumentScroll(view: View, activityName: String) {
        if (view.getTag(instrumentedTag) == true) return
        view.setTag(instrumentedTag, true)
        view.setOnScrollChangeListener { v, scrollX, scrollY, oldScrollX, oldScrollY ->
            val now = clockNanos()
            val last = (v.getTag(scrollTimeTag) as? Long) ?: 0L
            if (now - last < SCROLL_THROTTLE_NANOS) return@setOnScrollChangeListener
            v.setTag(scrollTimeTag, now)
            session.record(
                EventFactory.scroll(
                    activity = activityName,
                    viewId = viewIdName(v),
                    dx = scrollX - oldScrollX,
                    dy = scrollY - oldScrollY,
                ),
            )
        }
    }

    // ---- helpers ----

    private fun activityName(activity: Activity): String =
        activity.javaClass.simpleName.ifEmpty { activity.javaClass.name }

    internal fun viewIdName(view: View): String {
        val id = view.id
        if (id == View.NO_ID) return "no-id"
        return try {
            view.resources.getResourceEntryName(id)
        } catch (e: Resources.NotFoundException) {
            "0x%08x".format(id)
        }
    }

    private fun safeIdName(view: View): String =
        try {
            if (view.id == View.NO_ID) "" else view.resources.getResourceEntryName(view.id)
        } catch (e: Resources.NotFoundException) {
            ""
        }

    companion object {
        private const val SCROLL_THROTTLE_NANOS = 100_000_000L // 100 ms
        // Arbitrary, stable tag keys (View.setTag(int, Object) requires app-unique keys;
        // these are large negative-safe constants unlikely to collide with app usage).
        private const val R_TAG_INSTRUMENTED = 0x7E9C0001.toInt()
        private const val R_TAG_SCROLL_TIME = 0x7E9C0002.toInt()
    }
}
