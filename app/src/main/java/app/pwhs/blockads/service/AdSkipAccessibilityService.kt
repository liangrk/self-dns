package app.pwhs.blockads.service

import android.accessibilityservice.AccessibilityService
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import timber.log.Timber

/**
 * Accessibility service that auto-taps splash-ad skip buttons.
 *
 * Millisecond response design (vs. event-only blockers like GKD):
 * - DNS pre-activation (AdSkipManager.isActive) knows an app is about to
 *   show an ad BEFORE the ad UI renders. While pre-activated, the service
 *   polls actively at a 32ms period; the ad window is tapped on the first
 *   poll after it appears (~32ms worst case), without waiting for the
 *   system event pipeline. Accessibility events remain a parallel
 *   trigger - whichever hits first wins.
 * - The front app is taken from event.packageName directly: zero extra IPC.
 * - Matching uses findAccessibilityNodeInfosByText (system-level search,
 *   1-2 IPCs) instead of a per-node tree walk.
 * - Tapping uses performAction(ACTION_CLICK): no gesture preparation.
 *
 * Unlabeled ads are captured by [LearningSampler] for rule synthesis.
 */
class AdSkipAccessibilityService : AccessibilityService() {

    companion object {
        /** Poll period while a package is DNS-pre-activated. */
        private const val POLL_PERIOD_MS = 32L

        @Volatile
        var instance: AdSkipAccessibilityService? = null
            private set

        /** Whether the service is connected AND the feature is enabled. */
        val isReady: Boolean
            get() = instance != null && AdSkipManager.enabled
    }

    // Single worker: scans run off the main thread, events only enqueue.
    private val worker = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    // Coalescing state for high-frequency CONTENT_CHANGED events.
    private val pendingPkg = java.util.concurrent.atomic.AtomicReference<String?>(null)

    /** One active poll loop at a time; guarded by this flag. */
    private val polling = AtomicBoolean(false)

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        AdSkipManager.init(applicationContext)
        Timber.i("AdSkip service connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        if (!AdSkipManager.enabled) return
        val pkg = event.packageName?.toString() ?: return
        if (pkg == packageName) return

        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                AdSkipManager.onNewWindow(pkg)
                scheduleScan(pkg, event.className?.toString(), immediate = true)
            }
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                // Full-speed scans only for DNS pre-activated apps.
                if (AdSkipManager.isActive(pkg)) {
                    scheduleScan(pkg, event.className?.toString(), immediate = false)
                }
            }
        }
    }

    private fun scheduleScan(
        pkg: String,
        activity: String?,
        immediate: Boolean
    ) {
        if (AdSkipManager.isActive(pkg)) {
            // Pre-activated: start the poll loop if not already running.
            // The loop re-checks isActive each round and stops when the
            // window expires, so it costs nothing when idle.
            if (polling.compareAndSet(false, true)) {
                pollLoop(pkg)
            }
            return
        }
        if (!immediate) {
            // Non-activated apps: at most one pending throttled scan.
            if (!pendingPkg.compareAndSet(null, pkg)) return
            worker.execute {
                pendingPkg.set(null)
                scanAndTap(pkg, activity)
            }
        } else {
            worker.execute { scanAndTap(pkg, activity) }
        }
    }

    /**
     * Active poll loop for a pre-activated package: scan every
     * [POLL_PERIOD_MS] until it taps, the activation window expires, or
     * the feature is disabled. This is what makes the response feel
     * instant: the ad view is tapped on the first poll after it renders.
     */
    private fun pollLoop(pkg: String) {
        worker.execute {
            try {
                val hit = scanAndTap(pkg, null)
                if (hit || !AdSkipManager.isActive(pkg) || !AdSkipManager.enabled) {
                    polling.set(false)
                    return@execute
                }
                mainHandler.postDelayed({ pollLoop(pkg) }, POLL_PERIOD_MS)
            } catch (e: Exception) {
                polling.set(false)
                Timber.e("AdSkip poll failed: %s", e.message ?: "")
            }
        }
    }

    /** Returns true when a skip tap was performed. */
    private fun scanAndTap(pkg: String, activity: String?): Boolean {
        try {
            if (!AdSkipManager.enabled) return false
            val root = rootInActiveWindow ?: return false
            val rootPkg = root.packageName?.toString()
            // Only act on windows of the app that raised the event.
            if (rootPkg != null && rootPkg != pkg) return false

            val seen = HashSet<AccessibilityNodeInfo>()
            for (term in SkipRuleMatcher.SKIP_TERMS) {
                val candidates = root.findAccessibilityNodeInfosByText(term) ?: continue
                for (cand in candidates) {
                    if (!seen.add(cand)) continue
                    val target = SkipRuleMatcher.findTapTarget(cand)
                    if (target != null && AdSkipManager.canTap(pkg)) {
                        val ok = target.performAction(
                            AccessibilityNodeInfo.ACTION_CLICK
                        )
                        if (ok) {
                            AdSkipManager.onTap(pkg)
                            Timber.i("Ad skip tapped for %s", pkg)
                        }
                        return true
                    }
                }
            }
            // No skip button found: if this window looks like an ad,
            // capture a sample so the rule set can learn it.
            if (AdSkipManager.isActive(pkg)) {
                LearningSampler.sample(
                    getFilesDir(), pkg, activity, root
                )
            }
            return false
        } catch (e: Exception) {
            Timber.e("AdSkip scan failed: %s", e.message ?: "")
            return false
        }
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        if (instance === this) instance = null
        worker.shutdownNow()
        super.onDestroy()
    }
}
