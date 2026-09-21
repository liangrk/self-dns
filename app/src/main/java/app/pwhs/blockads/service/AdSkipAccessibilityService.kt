package app.pwhs.blockads.service

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.util.concurrent.Executors
import timber.log.Timber

/**
 * Accessibility service that auto-taps splash-ad skip buttons.
 *
 * Latency design (vs. event-only blockers like GKD):
 * - The front app is taken from event.packageName directly: zero extra IPC.
 * - DNS pre-activation (AdSkipManager.isActive) lets the service scan at
 *   full speed the moment an ad activity opens, while non-active apps only
 *   get one throttled scan per new window.
 * - Matching uses findAccessibilityNodeInfosByText (system-level search,
 *   1-2 IPCs) instead of a per-node tree walk.
 * - Tapping uses performAction(ACTION_CLICK): no gesture preparation.
 */
class AdSkipAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile
        var instance: AdSkipAccessibilityService? = null
            private set

        /** Whether the service is connected AND the feature is enabled. */
        val isReady: Boolean
            get() = instance != null && AdSkipManager.enabled
    }

    // Single worker: scans run off the main thread, events only enqueue.
    private val worker = Executors.newSingleThreadExecutor()

    // Coalescing state for high-frequency CONTENT_CHANGED events.
    private val pendingPkg = java.util.concurrent.atomic.AtomicReference<String?>(
        null
    )

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
                scheduleScan(pkg, immediate = true)
            }
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                // Full-speed scans only for DNS pre-activated apps.
                if (AdSkipManager.isActive(pkg)) {
                    scheduleScan(pkg, immediate = false)
                }
            }
        }
    }

    private fun scheduleScan(pkg: String, immediate: Boolean) {
        if (immediate) {
            worker.execute { scanAndTap(pkg) }
        } else {
            // Throttle CONTENT_CHANGED scans to at most one per 80ms.
            if (!pendingPkg.compareAndSet(null, pkg)) return
            worker.execute {
                pendingPkg.set(null)
                scanAndTap(pkg)
            }
        }
    }

    private fun scanAndTap(pkg: String) {
        try {
            if (!AdSkipManager.enabled) return
            val root = rootInActiveWindow ?: return
            val rootPkg = root.packageName?.toString()
            // Only act on windows of the app that raised the event.
            if (rootPkg != null && rootPkg != pkg) return

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
                        return
                    }
                }
            }
        } catch (e: Exception) {
            Timber.e(e, "AdSkip scan failed")
        }
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        if (instance === this) instance = null
        worker.shutdownNow()
        super.onDestroy()
    }
}
