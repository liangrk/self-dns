package app.pwhs.blockads.service

import android.view.accessibility.AccessibilityNodeInfo

/**
 * Pure-logic matcher for splash-ad skip buttons, kept free of accessibility
 * state so it stays unit-testable.
 *
 * Strategy: system-level text search (findAccessibilityNodeInfosByText)
 * finds candidate nodes with 1-2 binder IPCs instead of walking the whole
 * tree node-by-node (which costs one IPC per node). This object only
 * classifies candidates and picks the tap target; the service feeds it
 * candidates from findAccessibilityNodeInfosByText searches.
 */
object SkipRuleMatcher {

    /** Terms searched via AccessibilityNodeInfo.findAccessibilityNodeInfosByText. */
    val SKIP_TERMS = listOf("跳过", "跳過", "Skip", "skip")

    /** Resource-id fragments that strengthen a candidate. */
    private val SKIP_ID_HINTS = listOf("skip", "count_down", "countdown", "jump")

    /** Exact resource-id suffix from the Pangolin (csj) splash SDK. */
    private const val CSJ_SKIP_ID_SUFFIX = "tt_splash_skip_btn"

    /**
     * Countdown ids: contain both "count" and "down" but not "download"
     * (aligned with the GKD subscription's global splash group).
     */
    fun isCountdownId(idFragment: String): Boolean {
        val lower = idFragment.lowercase()
        return lower.contains("count") && lower.contains("down") &&
            !lower.contains("download")
    }

    /** Terms inside a candidate text that mean "do NOT tap". */
    private val NEGATIVE_TERMS = listOf("loading", "详情", "更多")

    /**
     * Decide whether [node] is a splash-ad skip control and return the node
     * to tap: the node itself when clickable, otherwise the nearest
     * clickable ancestor (up to 4 levels up), else null.
     */
    fun findTapTarget(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (!node.isVisibleToUser) return null
        if (!textMatches(node) && !idMatches(node)) return null
        if (hasNegativeContext(node)) return null
        return clickableSelfOrAncestor(node)
    }

    /** Max label length for a skip match (GKD: text.length<10). */
    private const val MAX_SKIP_TEXT_LEN = 10

    /** True when a label contains a skip term (case-insensitive). */
    fun isSkipText(text: String?): Boolean {
        if (text.isNullOrBlank()) return false
        // A long label containing "skip" is prose, not a button.
        if (text.length >= MAX_SKIP_TEXT_LEN) return false
        return SKIP_TERMS.any { text.contains(it, ignoreCase = true) }
    }

    /** True when the node text or contentDescription contains a skip term. */
    fun textMatches(node: AccessibilityNodeInfo): Boolean {
        val t = node.text?.toString()?.trim() ?: ""
        if (isSkipText(t)) return true
        val d = node.contentDescription?.toString()?.trim() ?: ""
        if (d.isEmpty()) return false
        // Long descriptions are unlikely to be a skip button label.
        if (d.length > 20) return false
        return isSkipText(d)
    }

    /** True when the view id resource name looks like a skip control. */
    fun idMatches(node: AccessibilityNodeInfo): Boolean {
        val id = node.viewIdResourceName ?: return false
        val lower = id.substringAfterLast('/').lowercase()
        return lower.endsWith(CSJ_SKIP_ID_SUFFIX) ||
            SKIP_ID_HINTS.any { lower.contains(it) } ||
            isCountdownId(lower)
    }

    fun hasNegativeContext(node: AccessibilityNodeInfo): Boolean {
        val t = (node.text?.toString() ?: "") + (node.contentDescription?.toString() ?: "")
        return NEGATIVE_TERMS.any { t.contains(it, ignoreCase = true) }
    }

    fun clickableSelfOrAncestor(
        node: AccessibilityNodeInfo,
        maxUp: Int = 4
    ): AccessibilityNodeInfo? {
        var cur: AccessibilityNodeInfo? = node
        var up = 0
        while (cur != null && up <= maxUp) {
            if (cur.isClickable) return cur
            cur = cur.parent
            up++
        }
        return null
    }

    /**
     * App-specific match against the remote rule table (SkipRuleLoader):
     * any text/desc contains-pattern, vid contains-pattern, or id
     * endswith-pattern from the validated table. Same safety gates as the
     * global path: visible, no negative context, clickable ancestor.
     */
    fun findAppRuleTarget(
        node: AccessibilityNodeInfo,
        rules: SkipRuleLoader.AppRules
    ): AccessibilityNodeInfo? {
        if (!node.isVisibleToUser) return null
        val t = node.text?.toString()?.trim() ?: ""
        val d = node.contentDescription?.toString()?.trim() ?: ""
        val id = node.viewIdResourceName?.substringAfterLast('/') ?: ""
        val matched = rules.texts.any { t.contains(it, true) || d.contains(it, true) } ||
            rules.vids.any { id.contains(it, true) } ||
            rules.idSuffixes.any { id.endsWith(it) }
        if (!matched) return null
        if (hasNegativeContext(node)) return null
        return clickableSelfOrAncestor(node)
    }
}
