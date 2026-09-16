package com.tencent.bkrepo.analyst.utils

import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test

class SpringVersionTest {
    @Test
    fun testReleaseEqualsPlainVersion() {
        assertEq("2.2.13.RELEASE", "2.2.13")
        assertEq("2.2.13.GA", "2.2.13")
        assertEq("2.2.13.FINAL", "2.2.13")
        assertEq("2.2.13-RELEASE", "2.2.13.RELEASE")
        assertEq("v2.2.13.RELEASE", "2.2.13")
        assertEq("2.2.13.release", "2.2.13.Release")
    }

    @Test
    fun testQualifierOrder() {
        assertLt("2.2.13.BUILD-SNAPSHOT", "2.2.13.M1")
        assertLt("2.2.13.SNAPSHOT", "2.2.13.M1")
        assertLt("2.2.13.M1", "2.2.13.M2")
        assertLt("2.2.13.M2", "2.2.13.M10")
        assertLt("2.2.13.M10", "2.2.13.RC1")
        assertLt("2.2.13.RC1", "2.2.13.RC2")
        assertLt("2.2.13.RC2", "2.2.13.RELEASE")
        assertLt("2.2.13.RC1", "2.2.13")
        assertEq("2.2.13.BUILD-SNAPSHOT", "2.2.13.SNAPSHOT")
        assertEq("2.2.13.RC1", "2.2.13-RC1")
        assertEq("2.2.13.RC1", "2.2.13.rc1")
        assertEq("2.2.13.CR1", "2.2.13.RC1")
    }

    @Test
    fun testCoreThenQualifier() {
        assertLt("2.2.13.RELEASE", "2.2.14")
        assertLt("2.2.13.RELEASE", "2.3.0.M1")
        Assertions.assertTrue(VersionCompare.compare("2.2.13.RELEASE", "2.2.13.RELEASE") == 0)
    }

    @Test
    fun testSemverPathUnchanged() {
        Assertions.assertTrue(VersionCompare.compare("1.0.0-alpha", "1.0.0") < 0)
        Assertions.assertTrue(VersionCompare.compare("1.0.0-rc.1", "1.0.0") < 0)
        Assertions.assertFalse(SpringVersion.looksLike("1.0.0-rc.1"))
        Assertions.assertFalse(SpringVersion.looksLike("1.0.0-alpha"))
        Assertions.assertTrue(SpringVersion.looksLike("2.2.13.RELEASE"))
        Assertions.assertTrue(SpringVersion.looksLike("2.2.13-RC1"))
    }

    @Test
    fun testUnsupported() {
        Assertions.assertThrows(VersionNumber.UnsupportedVersionException::class.java) {
            SpringVersion("2.2.13.UNKNOWN")
        }
        Assertions.assertThrows(VersionNumber.UnsupportedVersionException::class.java) {
            VersionCompare.compare("2.2.13.RELEASE", "2.2.13-alpha.1")
        }
    }

    private fun assertEq(a: String, b: String) {
        Assertions.assertEquals(0, VersionCompare.compare(a, b), "$a == $b")
        Assertions.assertEquals(0, VersionCompare.compare(b, a), "$b == $a")
    }

    private fun assertLt(a: String, b: String) {
        Assertions.assertTrue(VersionCompare.compare(a, b) < 0, "$a < $b")
        Assertions.assertTrue(VersionCompare.compare(b, a) > 0, "$b > $a")
    }
}
