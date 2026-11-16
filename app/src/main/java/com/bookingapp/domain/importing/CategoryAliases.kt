package com.bookingapp.domain.importing

import com.bookingapp.domain.Category
import com.bookingapp.domain.jstrip
import java.util.Locale

/**
 * Port of the backend's `CategoryAliases`: old Chinese category names used in spreadsheets,
 * mapped to the current category names. A test checks that every target is seeded.
 */
object CategoryAliases {

    val ALIASES: Map<String, String> = mapOf(
        "衣-服装" to "CL - Apparel",
        "衣-清洁" to "CL - Laundry & Cleaning",
        "衣-理发" to "CL - Haircuts",
        "食-EO" to "FO - Eating Out",
        "食-食材" to "FO - Groceries",
        "住-房贷" to "HO - Mortgage",
        "住-保险" to "HO - Insurance",
        "住-房税" to "HO - Property Tax",
        "住-维修" to "HO - Repairs",
        "住-装修" to "HO - Renovation",
        "住-日杂" to "HO - Household Supplies",
        "住-通讯" to "HO - Phone & Internet",
        "住-绿植" to "HO - Plants",
        "住-礼品" to "HO - Gifts",
        "行-汽油" to "TR - Gas",
        "行-保险" to "TR - Insurance",
        "行-维修" to "TR - Repairs",
        "行-车贷" to "TR - Car Loan",
        "行-停车" to "TR - Parking",
        "行-公共交通" to "TR - Public Transit",
        "娃-玩具" to "KI - Toys",
        "娃-教育" to "KI - Education",
        "娱-运动" to "EN - Sports",
        "娱-旅游" to "EN - Travel",
        "娱-书" to "EN - Books",
        "娱-彩票" to "EN - Lottery",
        "健康-医疗" to "HE - Medical",
        "健康-药" to "HE - Medicine",
        "健康-保险" to "HE - Insurance",
    )

    /**
     * Resolves a category as written in a file: first the alias table, then an existing
     * category name ignoring case. Surrounding whitespace is ignored.
     */
    fun resolve(raw: String?, categories: Collection<Category>): Category? {
        if (raw == null) return null
        val text = raw.jstrip()
        val name = (ALIASES[text] ?: text).lowercase(Locale.ROOT)
        return categories.firstOrNull { it.name.lowercase(Locale.ROOT) == name }
    }
}
