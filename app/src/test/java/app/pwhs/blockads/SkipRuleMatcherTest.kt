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
    fun `non skip labels do not match`() {
        assertFalse(app.pwhs.blockads.service.SkipRuleMatcher.isSkipText(null))
        assertFalse(app.pwhs.blockads.service.SkipRuleMatcher.isSkipText(""))
        assertFalse(app.pwhs.blockads.service.SkipRuleMatcher.isSkipText("  "))
        assertFalse(app.pwhs.blockads.service.SkipRuleMatcher.isSkipText("详情"))
        assertFalse(app.pwhs.blockads.service.SkipRuleMatcher.isSkipText("立即下载"))
    }
}
