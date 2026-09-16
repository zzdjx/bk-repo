package com.tencent.bkrepo.analyst.utils

interface VersionRange {
    /**
     * 判断[version]是否在当前版本范围内
     */
    fun contains(version: String): Boolean

    fun contains(versionNumber: VersionNumber): Boolean = contains(versionNumber.version)

    class UnsupportedVersionRangeException(range: String): RuntimeException("Unsupported version range[$range]")
}
