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

    /** True when a label contains a skip term (case-insensitive). */
    fun isSkipText(text: String?): Boolean {
        if (text.isNullOrBlank()) return false
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
        return SKIP_ID_HINTS.any { lower.contains(it) }
    }

    private fun hasNegativeContext(node: AccessibilityNodeInfo): Boolean {
        val t = (node.text?.toString() ?: "") + (node.contentDescription?.toString() ?: "")
        return NEGATIVE_TERMS.any { t.contains(it, ignoreCase = true) }
    }

    private fun clickableSelfOrAncestor(
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
}
