package com.tencent.bkrepo.preview.utils

import okhttp3.Dns
import java.net.InetAddress

/**
 * 远程预览 HTTP 出站专用 DNS 解析器。
 *
 * [SsrfGuard.validate] 只在发起请求前校验一次，OkHttp 真正建连时会重新解析域名，两次解析之间存在
 * DNS Rebinding 窗口：攻击者控制的权威 DNS 只要在此期间把记录切到内网/回环地址即可绕过校验。
 * 这里在建立 TCP 连接前对本次解析出的地址做黑名单校验，并把同一批结果交给后续连接使用，
 * 使校验与连接共用一次解析。
 *
 * 该机制**只在直连时成立**：`RouteSelector` 对 HTTP 代理只解析代理自身地址、对 SOCKS 代理走
 * `InetSocketAddress.createUnresolved`，两种情况下目标域名都改由代理解析，不会经过这里。
 * 因此使用方必须固定直连（见 [HttpUtils] 的 `Proxy.NO_PROXY`）；若将来要支持代理，
 * 需在代理侧限制可访问的目标地址。
 */
class PreviewCallbackDns(private val ssrfGuard: SsrfGuard) : Dns {

    override fun lookup(hostname: String): List<InetAddress> {
        val addresses = Dns.SYSTEM.lookup(hostname)
        ssrfGuard.checkResolvedAddresses(hostname, addresses)
        return addresses
    }
}
