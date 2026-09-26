package app.pwhs.blockads.service

import android.content.Context
import app.pwhs.blockads.data.datastore.AppPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import timber.log.Timber

/**
 * Process-wide coordinator for the accessibility-based ad-skip feature.
 *
 * The Go tunnel reports every DNS query through [tunnel.Tunnel] callbacks.
 * A blocked query (the domain is already in an ads list) is a strong signal
 * that the querying app is about to show an ad, BEFORE the ad UI appears.
 * [onDnsSignal] opens a short pre-activation window per package; the
 * accessibility service scans at full speed only for pre-activated apps and
 * falls back to throttled scans otherwise. The signal path is entirely
 * in-memory so it is safe to call from the Go callback thread.
 */
object AdSkipManager {

    /** Pre-activation window opened by a blocked DNS query. */
    private const val SIGNAL_TTL_MS = 8_000L

    /** After a successful skip tap, suppress further taps for the app. */
    private const val TAP_COOLDOWN_MS = 60_000L

    /** Max taps per package per pre-activation window (anti-loop guard). */
    private const val MAX_TAPS_PER_WINDOW = 2

    /** Per-app ring buffer size for recently blocked domains. */
    private const val DOMAIN_BUFFER_SIZE = 24

    private val active = ConcurrentHashMap<String, Long>()
    private val tapCooldown = ConcurrentHashMap<String, Long>()
    private val windowTaps = ConcurrentHashMap<String, AtomicInteger>()
    private val recentDomains = ConcurrentHashMap<String, ArrayDeque<String>>()

    @Volatile
    var enabled: Boolean = false
        private set

    private val initialized = AtomicBoolean(false)

    /**
     * Mirror the persisted preference into memory. Safe to call repeatedly;
     * the first call wins and later changes go through [setEnabled].
     */
    fun init(context: Context) {
        if (!initialized.compareAndSet(false, true)) return
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val prefs = AppPreferences(context.applicationContext)
                prefs.adSkipEnabled.collect {
                    enabled = it
                    if (!it) clearState()
                }
            } catch (e: Exception) {
                Timber.e(e, "AdSkipManager init failed")
            }
        }
    }

    /** Called from the Go DNS log callback (any thread, must be fast). */
    fun onDnsSignal(packageName: String, domain: String = "") {
        if (!enabled) return
        if (!packageName.contains('.')) return
        active[packageName] = System.currentTimeMillis() + SIGNAL_TTL_MS
        if (domain.isNotEmpty()) {
            val q = recentDomains.getOrPut(packageName) { ArrayDeque() }
            synchronized(q) {
                if (q.size >= DOMAIN_BUFFER_SIZE) q.removeFirst()
                q.addLast(domain)
            }
        }
    }

    /**
     * Blocked domains seen recently for [packageName] (for the learning
     * sampler to correlate an unlabeled ad screen with its ad networks).
     */
    fun recentBlockedDomains(packageName: String): List<String> {
        val q = recentDomains[packageName] ?: return emptyList()
        return synchronized(q) { q.toList() }
    }

    /** True while [packageName] sits inside a pre-activation window. */
    fun isActive(packageName: String?): Boolean {
        if (!enabled || packageName == null) return false
        val expiry = active[packageName] ?: return false
        if (expiry < System.currentTimeMillis()) {
            active.remove(packageName)
            return false
        }
        return true
    }

    /** Returns true when a tap is allowed under the anti-loop guards. */
    fun canTap(packageName: String): Boolean {
        val cooldownUntil = tapCooldown[packageName] ?: 0L
        if (cooldownUntil > System.currentTimeMillis()) return false
        val window = windowTaps[packageName] ?: AtomicInteger(0).also {
            windowTaps[packageName] = it
        }
        return window.get() < MAX_TAPS_PER_WINDOW
    }

    /** Record a successful skip tap. */
    fun onTap(packageName: String) {
        windowTaps.getOrPut(packageName) { AtomicInteger(0) }.incrementAndGet()
        tapCooldown[packageName] = System.currentTimeMillis() + TAP_COOLDOWN_MS
    }

    /** Wipe runtime state (feature disabled / service disconnected). */
    fun clearState() {
        active.clear()
        windowTaps.clear()
        tapCooldown.clear()
        recentDomains.clear()
    }

    /**
     * Drop the per-window tap counters so a fresh ad in the same app can be
     * skipped again once a new DNS signal re-arms the feature. Called when a
     * new window (activity) opens.
     */
    fun onNewWindow(packageName: String) {
        windowTaps.remove(packageName)
    }
}
