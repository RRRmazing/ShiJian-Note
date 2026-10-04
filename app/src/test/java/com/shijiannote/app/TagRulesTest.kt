package com.shijiannote.app

import org.junit.Assert.assertEquals
import org.junit.Test

class TagRulesTest {
    @Test fun legacyWhitespaceAndVisualPrefixesProduceUniqueNames() {
        assertEquals(listOf("工作", "阅读", "旅行"), TagRules.names(" 工作  阅读\t#工作\n##旅行 # "))
    }

    @Test fun addingAnExistingNameDoesNotCreateDuplicateTags() {
        assertEquals("工作 阅读 旅行", TagRules.add("工作 阅读", "#阅读 旅行 旅行"))
    }

    @Test fun removalHandlesDuplicateLegacyNamesWithoutRemovingOtherTags() {
        assertEquals("阅读", TagRules.remove("工作 阅读 工作", "工作"))
    }

    @Test fun replacingATagWithAnExistingNameMergesDuplicatesInPlace() {
        assertEquals("阅读 旅行", TagRules.replace("工作 阅读 旅行", "工作", "#阅读"))
    }
}
