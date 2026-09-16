package com.tencent.bkrepo.analyst.pojo.response.filter

import com.tencent.bkrepo.common.analysis.pojo.scanner.Level
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test

class MergedFilterRuleTest {
    @Test
    fun testShouldIgnore() {
        val mergedFilterRule = MergedFilterRule()
        mergedFilterRule.includeRule.add(
            FilterRule(
                name = "",
                description = "",
                projectId = "",
                riskyPackageVersions = mapOf("com.tencent.bkrepo:bkrepo" to "<2.1.0;>=2.5.1,<=3.0.0")
            )
        )

        mergedFilterRule.includeRule.add(
            FilterRule(
                name = "",
                description = "",
                projectId = "",
                riskyPackageKeys = setOf("com.tencent.bkrepo:analyst")
            )
        )

        Assertions.assertFalse(
            mergedFilterRule.shouldIgnore(
                vulId = "",
                riskyPackageKey = "com.tencent.bkrepo:bkrepo",
                riskyPackageVersions = setOf("2.0.0", "3.0.0")
            )
        )

        Assertions.assertFalse(
            mergedFilterRule.shouldIgnore(
                vulId = "",
                riskyPackageKey = "com.tencent.bkrepo:analyst"
            )
        )

        Assertions.assertTrue(
            mergedFilterRule.shouldIgnore(
                vulId = "",
                riskyPackageKey = "com.tencent.bkrepo:anything"
            )
        )
    }

    @Test
    fun testMinSeverity() {
        val mergeFilterRule = MergedFilterRule(minSeverityLevel = Level.HIGH.level)
        Assertions.assertTrue(mergeFilterRule.shouldIgnore("CVE-2011-11211", severity = Level.MEDIUM.level))
        Assertions.assertFalse(mergeFilterRule.shouldIgnore("CVE-2011-11211", severity = Level.HIGH.level))
    }

    @Test
    fun testIgnoreRiskyPackageVersions() {
        val mergeFilterRule = MergedFilterRule()
        mergeFilterRule.ignoreRule.add(
            FilterRule(
                name = "",
                description = "",
                projectId = "",
                riskyPackageVersions = mapOf("com.tencent.bkrepo:bkrepo" to ">3.0.0")
            )
        )
        Assertions.assertFalse(
            mergeFilterRule.shouldIgnore(
                vulId = "BKREPO-2011-11211",
                riskyPackageKey = "com.tencent.bkrepo:bkrepo",
                riskyPackageVersions = setOf("1.3.0")
            )
        )
        Assertions.assertTrue(
            mergeFilterRule.shouldIgnore(
                vulId = "BKREPO-2011-11211",
                riskyPackageKey = "com.tencent.bkrepo:bkrepo",
                riskyPackageVersions = setOf("3.3.0")
            )
        )
    }

    @Test
    fun testIgnoreSpringQualifierVersions() {
        val mergeFilterRule = MergedFilterRule()
        mergeFilterRule.ignoreRule.add(
            FilterRule(
                name = "",
                description = "",
                projectId = "",
                riskyPackageVersions = mapOf("org.springframework:spring-core" to ">=5.1.5.RELEASE")
            )
        )
        Assertions.assertTrue(
            mergeFilterRule.shouldIgnore(
                vulId = "CVE-2019-11211",
                riskyPackageKey = "org.springframework:spring-core",
                riskyPackageVersions = setOf("5.1.5")
            )
        )
        Assertions.assertFalse(
            mergeFilterRule.shouldIgnore(
                vulId = "CVE-2019-11211",
                riskyPackageKey = "org.springframework:spring-core",
                riskyPackageVersions = setOf("5.1.5.RC1")
            )
        )
    }

    @Test
    fun testIncludeRiskyPackageVersions() {
        val mergeFilterRule = MergedFilterRule()
        mergeFilterRule.includeRule.add(
            FilterRule(
                name = "",
                description = "",
                projectId = "",
                riskyPackageVersions = mapOf("com.tencent.bkrepo:bkrepo" to "<3.0.0")
            )
        )
        Assertions.assertFalse(
            mergeFilterRule.shouldIgnore(
                vulId = "BKREPO-2011-11211",
                riskyPackageKey = "com.tencent.bkrepo:bkrepo",
                riskyPackageVersions = setOf("1.3.0")
            )
        )
        Assertions.assertTrue(
            mergeFilterRule.shouldIgnore(
                vulId = "BKREPO-2011-11211",
                riskyPackageKey = "com.tencent.bkrepo:bkrepo",
                riskyPackageVersions = setOf("3.3.0")
            )
        )
    }

    @Test
    fun testOverrideRiskyPackageVersions() {
        val mergeFilterRule = MergedFilterRule()
        mergeFilterRule.includeRule.add(
            FilterRule(
                name = "",
                description = "",
                projectId = "",
                riskyPackageVersions = mapOf("com.tencent.bkrepo:bkrepo" to "<3.0.0")
            )
        )
        Assertions.assertFalse(
            mergeFilterRule.shouldIgnore(
                vulId = "BKREPO-2011-11211",
                riskyPackageKey = "com.tencent.bkrepo:bkrepo",
                riskyPackageVersions = setOf("2.3.0")
            )
        )
        mergeFilterRule.includeRule.add(
            FilterRule(
                name = "",
                description = "",
                projectId = "",
                riskyPackageVersions = mapOf("com.tencent.bkrepo:bkrepo" to "<2.0.0")
            )
        )
        Assertions.assertTrue(
            mergeFilterRule.shouldIgnore(
                vulId = "BKREPO-2011-11211",
                riskyPackageKey = "com.tencent.bkrepo:bkrepo",
                riskyPackageVersions = setOf("2.3.0")
            )
        )
        Assertions.assertFalse(
            mergeFilterRule.shouldIgnore(
                vulId = "BKREPO-2011-11211",
                riskyPackageKey = "com.tencent.bkrepo:bkrepo",
                riskyPackageVersions = setOf("1.3.0")
            )
        )
    }

    @Test
    fun testIgnoreRiskyPackageKeys() {
        val mergeFilterRule = MergedFilterRule()
        mergeFilterRule.ignoreRule.add(
            FilterRule(
                name = "",
                description = "",
                projectId = "",
                riskyPackageKeys = setOf("com.tencent.bkrepo:bkrepo")
            )
        )
        Assertions.assertFalse(
            mergeFilterRule.shouldIgnore(
                vulId = "BKREPO-2011-11211",
                riskyPackageKey = "com.tencent.bkrepo:analyst",
            )
        )
        Assertions.assertTrue(
            mergeFilterRule.shouldIgnore(
                vulId = "BKREPO-2011-11211",
                riskyPackageKey = "com.tencent.bkrepo:bkrepo",
            )
        )
    }

    @Test
    fun testIncludeRiskyPackageKeys() {
        val mergeFilterRule = MergedFilterRule()
        mergeFilterRule.includeRule.add(
            FilterRule(
                name = "",
                description = "",
                projectId = "",
                riskyPackageKeys = setOf("com.tencent.bkrepo:bkrepo")
            )
        )
        Assertions.assertTrue(
            mergeFilterRule.shouldIgnore(
                vulId = "BKREPO-2011-11211",
                riskyPackageKey = "com.tencent.bkrepo:analyst",
            )
        )
        Assertions.assertFalse(
            mergeFilterRule.shouldIgnore(
                vulId = "BKREPO-2011-11211",
                riskyPackageKey = "com.tencent.bkrepo:bkrepo",
            )
        )
    }

    @Test
    fun testIgnoreVulId() {
        val mergeFilterRule = MergedFilterRule()
        mergeFilterRule.ignoreRule.add(
            FilterRule(name = "", description = "", projectId = "", vulIds = setOf("BKREPO-2022-11211"))
        )
        Assertions.assertFalse(mergeFilterRule.shouldIgnore(vulId = "BKREPO-2011-11211", cveId = "CVE-2011-11211"))
        Assertions.assertTrue(mergeFilterRule.shouldIgnore(vulId = "BKREPO-2022-11211", cveId = "CVE-2022-11211"))
        Assertions.assertFalse(mergeFilterRule.shouldIgnore(vulId = "BKREPO-2033-11211", cveId = "CVE-2033-11211"))
    }

    @Test
    fun testIgnoreCveIdWhenVulIdIsScannerId() {
        val mergeFilterRule = MergedFilterRule()
        mergeFilterRule.ignoreRule.add(
            FilterRule(name = "", description = "", projectId = "", vulIds = setOf("CVE-2022-22968"))
        )
        Assertions.assertTrue(mergeFilterRule.shouldIgnore(vulId = "pcmgr-342415", cveId = "CVE-2022-22968"))
        Assertions.assertFalse(mergeFilterRule.shouldIgnore(vulId = "pcmgr-342415", cveId = null))
        Assertions.assertFalse(mergeFilterRule.shouldIgnore(vulId = "pcmgr-342415", cveId = ""))
    }

    @Test
    fun testIncludeVulId() {
        val mergeFilterRule = MergedFilterRule()
        mergeFilterRule.includeRule.add(
            FilterRule(name = "", description = "", projectId = "", vulIds = setOf("CVE-2011-11211"))
        )
        Assertions.assertFalse(mergeFilterRule.shouldIgnore(vulId = "BKREPO-2011-11211", cveId = "CVE-2011-11211"))
        Assertions.assertTrue(mergeFilterRule.shouldIgnore(vulId = "BKREPO-2022-11211", cveId = "CVE-2022-11211"))
        Assertions.assertTrue(mergeFilterRule.shouldIgnore(vulId = "BKREPO-2033-11211", cveId = "CVE-2033-11211"))
    }

    @Test
    fun testIgnoreLicenseName() {
        val mergeFilterRule = MergedFilterRule()
        mergeFilterRule.ignoreRule.add(
            FilterRule(name = "", description = "", projectId = "", licenseNames = setOf("GPL-2.0"))
        )
        Assertions.assertTrue(mergeFilterRule.shouldIgnore("GPL-2.0"))
        Assertions.assertFalse(mergeFilterRule.shouldIgnore("Apache-2.0"))
    }
}
