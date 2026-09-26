package app.pwhs.blockads.service

import android.view.accessibility.AccessibilityNodeInfo
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/**
 * Self-learning sampler for unlabeled splash ads.
 *
 * When a DNS-pre-activated scan finds ad-like nodes but no skip button,
 * capture a compact JSON snapshot (activity, ad-feature nodes, clickable
 * candidates, blocked domains) to filesDir/ad_learn/ for later rule
 * synthesis. Dedup: one sample per package+activity per hour; the store
 * is capped and oldest-first evicted. Pure collection - no auto taps on
 * learned patterns (mis-tap risk); rules are confirmed offline first.
 */
object LearningSampler {

    private const val DIR = "ad_learn"
    private const val MAX_SAMPLES = 200
    private const val MAX_NODES = 15

    /** Node id fragments that indicate ad inventory. */
    private val AD_ID_HINTS = listOf(
        "ad_", "_ad", "ads", "splash", "banner", "interstitial",
        "advert", "csj", "gdt", "pangle"
    )

    /** Visible labels that indicate ad inventory. */
    private val AD_TEXT_HINTS = listOf("广告", "推广", "推廣")

    /** Returns true when [node] looks like ad inventory. */
    fun looksLikeAd(node: AccessibilityNodeInfo): Boolean {
        val id = node.viewIdResourceName?.substringAfterLast('/')?.lowercase()
        if (id != null && AD_ID_HINTS.any { id.contains(it) }) return true
        val t = node.text?.toString() ?: ""
        if (AD_TEXT_HINTS.any { t.contains(it) }) return true
        val d = node.contentDescription?.toString() ?: ""
        return AD_TEXT_HINTS.any { d.contains(it) }
    }

    /**
     * Capture a learning sample. Called on the service worker thread when
     * a pre-activated scan missed the skip button.
     */
    fun sample(
        filesDir: File,
        pkg: String,
        activity: String?,
        root: AccessibilityNodeInfo
    ) {
        try {
            val dir = File(filesDir, DIR).apply { mkdirs() }
            val hour = java.text.SimpleDateFormat(
                "yyyyMMddHH", java.util.Locale.US
            ).format(java.util.Date())
            val actShort = (activity ?: "unknown").substringAfterLast('.').take(60)
            val dedup = File(dir, "${pkg}_${actShort}_$hour.json")
            if (dedup.exists()) return

            val adNodes = mutableListOf<JSONObject>()
            val clickables = mutableListOf<JSONObject>()
            walk(root, adNodes, clickables)
            if (adNodes.isEmpty()) return  // nothing ad-like: not worth a sample

            val obj = JSONObject()
            obj.put("package", pkg)
            obj.put("activity", activity ?: "")
            obj.put("time", System.currentTimeMillis())
            obj.put(
                "blockedDomains",
                JSONArray(AdSkipManager.recentBlockedDomains(pkg))
            )
            obj.put("adNodes", JSONArray(adNodes))
            obj.put("clickables", JSONArray(clickables))

            dedup.writeText(obj.toString())
            evictOld(dir)
        } catch (_: Exception) {
            // Sampling must never break the skip path.
        }
    }

    private fun walk(
        root: AccessibilityNodeInfo,
        adNodes: MutableList<JSONObject>,
        clickables: MutableList<JSONObject>
    ) {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var visited = 0
        while (queue.isNotEmpty() && visited < 300) {
            val n = queue.removeFirst()
            visited++
            if (clickables.size < MAX_NODES && n.isClickable && n.isVisibleToUser) {
                clickables.add(nodeJson(n))
            }
            if (adNodes.size < MAX_NODES && looksLikeAd(n)) {
                adNodes.add(nodeJson(n))
            }
            for (i in 0 until n.childCount) {
                n.getChild(i)?.let { queue.add(it) }
            }
        }
    }

    private fun nodeJson(n: AccessibilityNodeInfo): JSONObject {
        val o = JSONObject()
        o.put("text", n.text?.toString()?.take(80) ?: "")
        o.put("desc", n.contentDescription?.toString()?.take(80) ?: "")
        o.put("id", n.viewIdResourceName ?: "")
        o.put("cls", n.className?.toString() ?: "")
        o.put("clickable", n.isClickable)
        return o
    }

    private fun evictOld(dir: File) {
        val files = dir.listFiles() ?: return
        if (files.size <= MAX_SAMPLES) return
        files.sortedBy { it.lastModified() }
            .take(files.size - MAX_SAMPLES)
            .forEach { it.delete() }
    }
}
