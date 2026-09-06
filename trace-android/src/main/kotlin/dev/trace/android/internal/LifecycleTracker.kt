package dev.trace.android.internal

import android.app.Activity
import android.app.Application
import android.os.Bundle

/**
 * Maps Android activity lifecycle callbacks to `android.activity.*` events and
 * emits `android.screen.enter` on resume.
 *
 * Uses only [Application.ActivityLifecycleCallbacks], the supported, non-invasive
 * API. All callbacks arrive on the main thread; each is handed straight to the
 * session (which enqueues it on the writer thread).
 *
 * ### What is intentionally NOT emitted here
 * `android.app.start` / `android.app.stop` are written by
 * [dev.trace.android.TraceAndroid] itself (on `start()` / `stop()`), not by
 * lifecycle. Android provides no reliable "application stopped/killed" callback
 * (`Application.onTerminate()` never fires on real devices), so if the process is
 * killed while backgrounded the session simply ends with no `session_end`, which
 * trace-core surfaces as `SessionReader.isComplete == false`. The activity
 * `pause` / `stop` events already record that the app left the foreground.
 *
 * ### Limitation
 * If `TraceAndroid.start()` is called after activities already exist, those
 * earlier `create` / `start` events are missed. Start from `Application.onCreate()`
 * for a complete lifecycle.
 */
internal class LifecycleTracker(
    private val session: AndroidTraceSession,
) : Application.ActivityLifecycleCallbacks {

    fun install(application: Application) {
        application.registerActivityLifecycleCallbacks(this)
    }

    fun uninstall(application: Application) {
        application.unregisterActivityLifecycleCallbacks(this)
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
        session.record(
            EventFactory.activity(
                ActivityPhase.CREATE,
                activityName(activity),
                instanceId(activity),
                restoredState = savedInstanceState != null,
            ),
        )
    }

    override fun onActivityStarted(activity: Activity) {
        session.record(EventFactory.activity(ActivityPhase.START, activityName(activity), instanceId(activity)))
    }

    override fun onActivityResumed(activity: Activity) {
        val name = activityName(activity)
        session.record(EventFactory.activity(ActivityPhase.RESUME, name, instanceId(activity)))
        session.record(EventFactory.screenEnter(name, activity = name))
    }

    override fun onActivityPaused(activity: Activity) {
        session.record(EventFactory.activity(ActivityPhase.PAUSE, activityName(activity), instanceId(activity)))
    }

    override fun onActivityStopped(activity: Activity) {
        session.record(EventFactory.activity(ActivityPhase.STOP, activityName(activity), instanceId(activity)))
    }

    override fun onActivityDestroyed(activity: Activity) {
        session.record(EventFactory.activity(ActivityPhase.DESTROY, activityName(activity), instanceId(activity)))
    }

    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {
        // Not part of the v0.2 taxonomy.
    }

    private fun activityName(activity: Activity): String =
        activity.javaClass.simpleName.ifEmpty { activity.javaClass.name }

    private fun instanceId(activity: Activity): String =
        "${activityName(activity)}@${Integer.toHexString(System.identityHashCode(activity))}"
}
