package com.tencent.bkrepo.analyst.utils

import java.util.regex.Pattern

/**
 * Spring 早期版本：`x.x.x..RELEASE` / `M1` / `RC1` / `BUILD-SNAPSHOT` 等。
 *
 * SNAPSHOT = BUILD-SNAPSHOT < M* < RC* < RELEASE = GA = FINAL = 无 qualifier
 */
class SpringVersion(version: String) {
    private val versionCore: IntArray
    private val qualifier: Qualifier
    private val qualifierNumber: Int

    init {
        var trimVersion = version.trim()
        if (trimVersion.startsWith("v", ignoreCase = true)) {
            trimVersion = trimVersion.substring(1)
        }

        val coreMatcher = CORE_PATTERN.matcher(trimVersion)
        if (!coreMatcher.lookingAt()) {
            throw VersionNumber.UnsupportedVersionException(version)
        }
        versionCore = coreMatcher.group().split('.').map { it.toInt() }.toIntArray()

        val parsed = parseQualifier(trimVersion.substring(coreMatcher.end()), version)
        qualifier = parsed.first
        qualifierNumber = parsed.second
    }

    fun compareTo(other: SpringVersion): Int {
        val size = maxOf(versionCore.size, other.versionCore.size)
        for (i in 0 until size) {
            val coreNumber = versionCore.getOrElse(i) { 0 }
            val otherCoreNumber = other.versionCore.getOrElse(i) { 0 }
            if (coreNumber != otherCoreNumber) {
                return coreNumber - otherCoreNumber
            }
        }
        val qualifierCmp = qualifier.ordinal - other.qualifier.ordinal
        if (qualifierCmp != 0) {
            return qualifierCmp
        }
        return qualifierNumber - other.qualifierNumber
    }

    private enum class Qualifier {
        SNAPSHOT, MILESTONE, RC, RELEASE
    }

    companion object {
        private val CORE_PATTERN = Pattern.compile("^(?:0|[1-9]\\d*)(?:\\.(?:0|[1-9]\\d*))*")
        private val QUALIFIER_HINT = Pattern.compile(
            "(?i)[.\\-](?:BUILD-SNAPSHOT|SNAPSHOT|RELEASE|GA|FINAL|(?:CR|RC|M)\\d+)$"
        )
        private val NUMBERED_QUALIFIER = Pattern.compile("^(CR|RC|M)(\\d+)$", Pattern.CASE_INSENSITIVE)

        fun looksLike(version: String): Boolean {
            var trimVersion = version.trim()
            if (trimVersion.startsWith("v", ignoreCase = true)) {
                trimVersion = trimVersion.substring(1)
            }
            return QUALIFIER_HINT.matcher(trimVersion).find()
        }

        private fun parseQualifier(rest: String, original: String): Pair<Qualifier, Int> {
            if (rest.isEmpty()) {
                return Qualifier.RELEASE to 0
            }
            if (rest[0] != '.' && rest[0] != '-') {
                throw VersionNumber.UnsupportedVersionException(original)
            }
            val token = rest.substring(1)
            when (token.uppercase()) {
                "RELEASE", "GA", "FINAL" -> return Qualifier.RELEASE to 0
                "SNAPSHOT", "BUILD-SNAPSHOT" -> return Qualifier.SNAPSHOT to 0
            }
            val numbered = NUMBERED_QUALIFIER.matcher(token)
            if (!numbered.matches()) {
                throw VersionNumber.UnsupportedVersionException(original)
            }
            val number = numbered.group(2).toInt()
            return if (numbered.group(1).equals("M", ignoreCase = true)) {
                Qualifier.MILESTONE to number
            } else {
                Qualifier.RC to number
            }
        }
    }
}

/**
 * 任一侧命中 Spring qualifier 则两侧都走 [SpringVersion]，否则走 [VersionNumber]。
 */
object VersionCompare {
    fun compare(a: String, b: String): Int {
        return if (SpringVersion.looksLike(a) || SpringVersion.looksLike(b)) {
            SpringVersion(a).compareTo(SpringVersion(b))
        } else {
            VersionNumber(a).compareTo(VersionNumber(b))
        }
    }

    fun validate(version: String) {
        if (SpringVersion.looksLike(version)) {
            SpringVersion(version)
        } else {
            VersionNumber(version)
        }
    }
}
