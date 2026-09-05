package app.pwhs.blockads

import app.pwhs.blockads.data.entities.FilterList
import app.pwhs.blockads.data.repository.FilterRulesMapper
import org.junit.Assert.assertEquals
import org.junit.Test

class FilterRulesMapperTest {

    @Test
    fun adultAndGamblingIdsRemapToTheirOwnCategories() {
        assertEquals(
            FilterList.CATEGORY_ADULT,
            FilterRulesMapper.normalizeCategory("stevenblack_porn", "ads")
        )
        assertEquals(
            FilterList.CATEGORY_GAMBLING,
            FilterRulesMapper.normalizeCategory("stevenblack_gambling", "ads")
        )
    }

    @Test
    fun securityCategoryPassesThrough() {
        assertEquals(
            FilterList.CATEGORY_SECURITY,
            FilterRulesMapper.normalizeCategory("some_security_list", "security")
        )
    }

    @Test
    fun unknownIdsFallBackToRemoteCategory() {
        assertEquals(
            FilterList.CATEGORY_AD,
            FilterRulesMapper.normalizeCategory("easylist", "ads")
        )
    }

    @Test
    fun cnRulesRepoMapsToCnRegion() {
        assertEquals(
            FilterList.REGION_CN,
            FilterRulesMapper.inferRegion(
                "whatever",
                "https://raw.githubusercontent.com/liangrk/blockads-cn-rules/main/dist/cn-ads.txt"
            )
        )
    }

    @Test
    fun knownInternationalListsMapToGlobal() {
        assertEquals(
            FilterList.REGION_GLOBAL,
            FilterRulesMapper.inferRegion(
                "stevenblack",
                "https://raw.githubusercontent.com/StevenBlack/hosts/master/hosts"
            )
        )
    }
}
