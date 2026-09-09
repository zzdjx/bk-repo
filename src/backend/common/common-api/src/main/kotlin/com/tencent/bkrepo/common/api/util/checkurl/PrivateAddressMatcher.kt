package com.tencent.bkrepo.common.api.util.checkurl

import com.google.common.net.InetAddresses
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress

/**
 * 内网/回环/链路本地/云元数据/保留网段等地址匹配器，用于SSRF防护中判断一个IP是否禁止访问。
 * 只做地址匹配，不做DNS解析；域名解析后请对每个解析出的地址调用[isForbidden]校验。
 */
object PrivateAddressMatcher {

    /**
     * 始终禁止访问的IPv4网段，即使[blockInternal]为false也会命中。
     * 链路本地(169.254.0.0/16)、多播(224.0.0.0/4)已由JDK内置的[InetAddress.isLinkLocalAddress]、
     * [InetAddress.isMulticastAddress]覆盖，此处只保留JDK未覆盖的保留段(240.0.0.0/4)与0.0.0.0/8
     * （JDK的[InetAddress.isAnyLocalAddress]只识别0.0.0.0单个地址，不识别整个/8段）。
     */
    private val ALWAYS_BLOCKED_V4_RANGES = listOf(
        "240.0.0.0" to 4,
        "0.0.0.0" to 8,
    )

    /**
     * 当[blockInternal]为true时额外禁止的IPv4网段。
     * 回环(127.0.0.0/8)、RFC1918私网(10/8、172.16/12、192.168/16)已由JDK内置的
     * [InetAddress.isLoopbackAddress]、[InetAddress.isSiteLocalAddress]覆盖，此处只保留
     * JDK未覆盖、且真实网络环境中确实会被使用的Carrier-Grade NAT网段(100.64.0.0/10)。
     * 不再拦截192.0.0.0/24、192.0.2.0/24、198.18.0.0/15、198.51.100.0/24、203.0.113.0/24等
     * IANA保留的文档/基准测试专用网段——这些网段规范上不会分配给任何真实服务，拦截它们不会带来
     * 实际的SSRF防护收益。
     */
    private val INTERNAL_V4_RANGES = listOf(
        "100.64.0.0" to 10,
    )

    /**
     * 判断地址是否属于禁止访问的保留/内网/云元数据等地址。
     * @param blockInternal 是否额外拦截回环/内网等地址（[INTERNAL_V4_RANGES]），云元数据/多播/保留段始终拦截
     */
    fun isForbidden(addr: InetAddress, blockInternal: Boolean = true): Boolean {
        if (isReservedAddress(addr)) return true
        if (blockInternal && addr.isSiteLocalAddress) return true
        return when (addr) {
            is Inet4Address -> isForbiddenV4(addr, blockInternal)
            is Inet6Address -> isForbiddenV6(addr, blockInternal)
            else -> false
        }
    }

    /**
     * 判断字面量IP字符串是否禁止访问；如果host不是字面量IP（如域名），返回false，交由域名格式/白名单校验处理。
     * 不会对域名做DNS解析。
     */
    fun isForbiddenLiteral(host: String, blockInternal: Boolean = true): Boolean {
        if (!InetAddresses.isInetAddress(host)) return false
        return isForbidden(InetAddresses.forString(host), blockInternal)
    }

    /**
     * 判断是否为JDK内置识别的保留地址（回环、链路本地、多播、任意地址）
     */
    private fun isReservedAddress(addr: InetAddress): Boolean {
        val checks: List<(InetAddress) -> Boolean> = listOf(
            InetAddress::isLoopbackAddress,
            InetAddress::isLinkLocalAddress,
            InetAddress::isMulticastAddress,
            InetAddress::isAnyLocalAddress,
        )
        return checks.any { it(addr) }
    }

    private fun isForbiddenV4(addr: Inet4Address, blockInternal: Boolean): Boolean {
        if (ALWAYS_BLOCKED_V4_RANGES.any { (cidrAddr, prefix) -> inCidr(addr, cidrAddr, prefix) }) {
            return true
        }
        if (blockInternal && INTERNAL_V4_RANGES.any { (cidrAddr, prefix) -> inCidr(addr, cidrAddr, prefix) }) {
            return true
        }
        return false
    }

    private fun isForbiddenV6(addr: Inet6Address, blockInternal: Boolean): Boolean {
        if (!blockInternal) return false
        // ::1 已被 isLoopbackAddress 拦截；fe80::/10 已被 isLinkLocalAddress 拦截
        // 这里补充 ULA（fc00::/7） 与 IPv4-mapped v6（::ffff:0:0/96）
        val bytes = addr.address
        // fc00::/7 —— 首字节高 7 位为 1111 110
        if ((bytes[0].toInt() and 0xFE) == 0xFC) {
            return true
        }
        if (isV4MappedV6(bytes)) {
            val mappedV4 = InetAddress.getByAddress(bytes.copyOfRange(12, 16)) as Inet4Address
            // 走完整的isForbidden，而非isForbiddenV4，以便复用JDK内置的回环/私网判断
            // （127.0.0.0/8、10/8、172.16/12、192.168/16已从INTERNAL_V4_RANGES中移除，仅靠JDK内置方法覆盖）
            return isForbidden(mappedV4, blockInternal)
        }
        return false
    }

    /**
     * 判断一段 IPv6 字节是否为 IPv4-mapped 形式（::ffff:0:0/96）。
     * 前 80 位必须全 0，紧接着的 16 位必须全 1。
     */
    private fun isV4MappedV6(bytes: ByteArray): Boolean {
        val prefixAllZero = (0..9).all { bytes[it].toInt() == 0 }
        val markerAllOne = (bytes[10].toInt() and 0xFF) == 0xFF &&
            (bytes[11].toInt() and 0xFF) == 0xFF
        return prefixAllZero && markerAllOne
    }

    /**
     * 判断一个 IPv4 是否落在指定 CIDR 段内。
     */
    private fun inCidr(addr: Inet4Address, cidrAddr: String, prefix: Int): Boolean {
        val target = ipv4ToInt(addr.address)
        val base = ipv4ToInt(InetAddress.getByName(cidrAddr).address)
        if (prefix == 0) return true
        val mask = (-1 shl (32 - prefix))
        return (target and mask) == (base and mask)
    }

    private fun ipv4ToInt(bytes: ByteArray): Int {
        return ((bytes[0].toInt() and 0xFF) shl 24) or
            ((bytes[1].toInt() and 0xFF) shl 16) or
            ((bytes[2].toInt() and 0xFF) shl 8) or
            (bytes[3].toInt() and 0xFF)
    }
}
