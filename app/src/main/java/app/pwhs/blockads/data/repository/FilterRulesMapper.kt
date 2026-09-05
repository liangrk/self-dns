package app.pwhs.blockads.data.repository

import app.pwhs.blockads.data.entities.FilterList

/**
 * Fixes upstream catalog metadata errors and infers the region dimension.
 * Pure functions - unit-testable without Android.
 */
object FilterRulesMapper {

    // Remote catalog mislabels adult/gambling lists as "ads"; fix by id.
    private val CATEGORY_BY_ID = mapOf(
        "stevenblack_porn" to FilterList.CATEGORY_ADULT,
        "stevenblack_gambling" to FilterList.CATEGORY_GAMBLING
    )

    fun normalizeCategory(id: String, remoteCategory: String): String {
        CATEGORY_BY_ID[id]?.let { return it }
        return if (remoteCategory.equals("security", ignoreCase = true)) {
            FilterList.CATEGORY_SECURITY
        } else {
            FilterList.CATEGORY_AD
        }
    }

    // URL markers that identify mainland-China rule sources.
    private val CN_MARKERS = listOf(
        "blockads-cn-rules",
        "anti-ad.net"
    )

    fun inferRegion(id: String, originalUrl: String): String {
        val url = originalUrl.lowercase()
        return if (CN_MARKERS.any { url.contains(it) }) {
            FilterList.REGION_CN
        } else {
            FilterList.REGION_GLOBAL
        }
    }

    /** Built-in entries not present in the remote catalog must survive catalog sync. */
    fun isLocalOnlyBuiltIn(originalUrl: String): Boolean =
        originalUrl.contains("blockads-cn-rules")
}
