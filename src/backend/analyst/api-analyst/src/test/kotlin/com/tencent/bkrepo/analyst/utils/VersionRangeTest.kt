package com.tencent.bkrepo.analyst.utils

import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test

class VersionRangeTest {
    @Test
    fun testContains() {
        val gt = DefaultVersionRange.build("   >  1.0.0  ")
        val gte = DefaultVersionRange.build("   >=  1.0.0  ")
        val eq = DefaultVersionRange.build("   ==  1.0.0  ")
        val lte = DefaultVersionRange.build("   <=  1.0.0  ")
        val lt = DefaultVersionRange.build("   <  1.0.0  ")

        Assertions.assertFalse(gt.contains(VersionNumber("1.0.0")))
        Assertions.assertTrue(gt.contains(VersionNumber("1.2.0")))

        Assertions.assertTrue(gte.contains(VersionNumber("1.0.0")))

        Assertions.assertTrue(eq.contains(VersionNumber("1.0.0")))

        Assertions.assertFalse(lte.contains(VersionNumber("1.2.0")))
        Assertions.assertTrue(lte.contains(VersionNumber("1.0.0")))
        Assertions.assertTrue(lte.contains(VersionNumber("0.1.0")))

        Assertions.assertTrue(lt.contains(VersionNumber("0.5.1-alpha.1+001")))
        Assertions.assertFalse(lt.contains(VersionNumber("1.5.1-alpha.1+001")))

        Assertions.assertThrows(VersionRange.UnsupportedVersionRangeException::class.java) {
            DefaultVersionRange.build(">")
        }
        Assertions.assertThrows(VersionRange.UnsupportedVersionRangeException::class.java) {
            DefaultVersionRange.build("gt 1.2.3")

        }
        Assertions.assertThrows(VersionNumber.UnsupportedVersionException::class.java) {
            DefaultVersionRange.build(">=")
        }
        Assertions.assertThrows(VersionNumber.UnsupportedVersionException::class.java) {
            DefaultVersionRange.build("> = 1.2.3")
        }
    }

    @Test
    fun testCompositeVersionRange() {
        val compositeRange = CompositeVersionRange.build("<2.3.1;>=2.7.1,<4.2.3")

        Assertions.assertFalse(compositeRange.contains(VersionNumber("5.2.3")))
        Assertions.assertFalse(compositeRange.contains(VersionNumber("4.2.3")))
        Assertions.assertFalse(compositeRange.contains(VersionNumber("2.3.1")))
        Assertions.assertTrue(compositeRange.contains(VersionNumber("1.7.1")))
        Assertions.assertTrue(compositeRange.contains(VersionNumber("2.7.1")))
        Assertions.assertTrue(compositeRange.contains(VersionNumber("3.8.1")))

        Assertions.assertThrows(VersionRange.UnsupportedVersionRangeException::class.java) {
            CompositeVersionRange.build(",")
        }
        Assertions.assertThrows(VersionRange.UnsupportedVersionRangeException::class.java) {
            CompositeVersionRange.build(";")
        }
    }

    @Test
    fun testSpringQualifierRange() {
        val gteRelease = DefaultVersionRange.build(">=2.2.13.RELEASE")
        Assertions.assertTrue(gteRelease.contains("2.2.13"))
        Assertions.assertTrue(gteRelease.contains("2.2.13.RELEASE"))
        Assertions.assertTrue(gteRelease.contains("2.2.14"))
        Assertions.assertFalse(gteRelease.contains("2.2.13.RC1"))
        Assertions.assertFalse(gteRelease.contains("2.2.13.SNAPSHOT"))

        val gtePlain = DefaultVersionRange.build(">=2.2.13")
        Assertions.assertTrue(gtePlain.contains("2.2.13.RELEASE"))
        Assertions.assertFalse(gtePlain.contains("2.2.13.M1"))

        val compositeRange = CompositeVersionRange.build(">=2.2.13.M1,<2.2.13.RELEASE")
        Assertions.assertTrue(compositeRange.contains("2.2.13.M1"))
        Assertions.assertTrue(compositeRange.contains("2.2.13.RC1"))
        Assertions.assertFalse(compositeRange.contains("2.2.13.SNAPSHOT"))
        Assertions.assertFalse(compositeRange.contains("2.2.13.RELEASE"))
        Assertions.assertFalse(compositeRange.contains("2.2.13"))
    }
}
