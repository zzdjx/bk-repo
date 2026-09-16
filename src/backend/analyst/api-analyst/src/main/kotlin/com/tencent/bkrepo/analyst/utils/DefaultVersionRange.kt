package com.tencent.bkrepo.analyst.utils

/**
 * 版本区间
 */
class DefaultVersionRange(
    val version: String,
    val operator: Operator
) : VersionRange {

    /**
     * 判断[version]是否在当前版本范围内
     */
    override fun contains(version: String): Boolean {
        val cmp = VersionCompare.compare(version, this.version)
        return when (operator) {
            Operator.LT -> cmp < 0
            Operator.LTE -> cmp <= 0
            Operator.EQ -> cmp == 0
            Operator.GTE -> cmp >= 0
            Operator.GT -> cmp > 0
        }
    }

    enum class Operator(val op: String) {

        LT("<"), LTE("<="), EQ("=="), GT(">"), GTE(">=");

        companion object {
            val opSymbols = arrayOf('>', '<', '=')
            fun lookup(op: String): Operator {
                return values().first { it.op == op }
            }
        }
    }

    companion object {

        /**
         * 根据版本范围字符串创建[DefaultVersionRange]
         *
         * @param range 版本范围，由操作符加版本号组成，示例：>=1.0.0
         *
         * @return 版本范围
         */
        fun build(range: String): DefaultVersionRange {
            val trimRange = range.trim()
            if (trimRange.length < 2 || trimRange[0] !in Operator.opSymbols) {
                throw VersionRange.UnsupportedVersionRangeException(trimRange)
            }
            val op = StringBuilder(2)
            op.append(trimRange[0])
            if (trimRange[1] in Operator.opSymbols) {
                op.append(trimRange[1])
            }
            val opStr = op.toString()
            val version = trimRange.substring(opStr.length)
            VersionCompare.validate(version)
            return DefaultVersionRange(version, Operator.lookup(opStr))
        }
    }
}
