package app.pwhs.blockads

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SkipRuleMatcherTest {

    @Test
    fun `skip terms match common skip labels`() {
        assertTrue(app.pwhs.blockads.service.SkipRuleMatcher.isSkipText("跳过"))
        assertTrue(app.pwhs.blockads.service.SkipRuleMatcher.isSkipText("跳过 3"))
        assertTrue(app.pwhs.blockads.service.SkipRuleMatcher.isSkipText("跳過廣告"))
        assertTrue(app.pwhs.blockads.service.SkipRuleMatcher.isSkipText("Skip Ad"))
        assertTrue(app.pwhs.blockads.service.SkipRuleMatcher.isSkipText("X skip"))
    }

    @Test
    fun `long labels containing skip terms are rejected`() {
        // GKD global group: text.length<10 — a long label is prose
        assertFalse(
            app.pwhs.blockads.service.SkipRuleMatcher
                .isSkipText("跳过广告并继续观看完整视频")
        )
    }

    @Test
    fun `countdown ids match but download does not`() {
        val m = app.pwhs.blockads.service.SkipRuleMatcher
        assertTrue(m.isCountdownId("count_down"))
        assertTrue(m.isCountdownId("CountdownView"))
        assertTrue(m.isCountdownId("tv_countdown_skip"))
        assertFalse(m.isCountdownId("download_btn"))
        assertFalse(m.isCountdownId("skip"))
    }

        @Test
    fun `non skip labels do not match`() {
        assertFalse(app.pwhs.blockads.service.SkipRuleMatcher.isSkipText(null))
        assertFalse(app.pwhs.blockads.service.SkipRuleMatcher.isSkipText(""))
        assertFalse(app.pwhs.blockads.service.SkipRuleMatcher.isSkipText("  "))
        assertFalse(app.pwhs.blockads.service.SkipRuleMatcher.isSkipText("详情"))
        assertFalse(app.pwhs.blockads.service.SkipRuleMatcher.isSkipText("立即下载"))
    }
}
