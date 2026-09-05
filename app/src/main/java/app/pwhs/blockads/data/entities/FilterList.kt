package app.pwhs.blockads.data.entities

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "filter_lists")
data class FilterList(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val name: String,
    val url: String,
    val description: String = "",
    val isEnabled: Boolean = true,
    val isBuiltIn: Boolean = false,
    val domainCount: Int = 0,
    val lastUpdated: Long = 0,
    val category: String = CATEGORY_AD,
    val bloomUrl: String = "",
    val trieUrl: String = "",
    val cssUrl: String = "",
    val scriptletsUrl: String = "",
    val ruleCount: Int = 0,
    val originalUrl: String = "",
    val region: String = REGION_GLOBAL
) {
    companion object {
        const val CATEGORY_AD = "AD"
        const val CATEGORY_SECURITY = "SECURITY"
        const val CATEGORY_ADULT = "ADULT"
        const val CATEGORY_GAMBLING = "GAMBLING"

        const val REGION_GLOBAL = "GLOBAL"
        const val REGION_CN = "CN"
    }
}