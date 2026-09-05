package app.pwhs.blockads.utils

import java.util.Locale

/**
 * Region-aware "System Default" DNS resolution.
 *
 * Measured on a CN device (2026-09-05): the shipped overseas defaults
 * (9.9.9.9 / 94.140.14.14) produce 231ms average / 10s worst-case DNS
 * latency, which stalls app cold-starts. AliDNS answers in <30ms from
 * mainland networks, so zh locales get AliDNS; everything else keeps
 * Google DNS.
 */
object RegionDns {

    private const val ALI_PRIMARY = "223.5.5.5"
    private const val ALI_FALLBACK = "223.6.6.6"
    private const val GOOGLE_PRIMARY = "8.8.8.8"
    private const val GOOGLE_FALLBACK = "8.8.4.4"

    /** Mainland proxy: system language is Chinese. */
    fun isMainlandUser(): Boolean = Locale.getDefault().language == "zh"

    fun systemPrimary(): String = if (isMainlandUser()) ALI_PRIMARY else GOOGLE_PRIMARY

    fun systemFallback(): String = if (isMainlandUser()) ALI_FALLBACK else GOOGLE_FALLBACK
}
