package com.tencent.bkrepo.common.api.util.checkurl

import java.net.InetAddress
import java.net.UnknownHostException

/**
 * 出站建连前的地址校验。返回值即本次解析结果，调用方应直接用它建连，避免二次解析。
 */
object OutboundAddressGuard {

    fun resolve(
        host: String,
        lookup: (String) -> List<InetAddress> = { InetAddress.getAllByName(it).toList() },
    ): List<InetAddress> {
        val addresses = lookup(host)
        if (addresses.isEmpty() || addresses.any { PrivateAddressMatcher.isForbidden(it) }) {
            throw UnknownHostException("Host [$host] resolved to a forbidden address")
        }
        return addresses
    }
}
