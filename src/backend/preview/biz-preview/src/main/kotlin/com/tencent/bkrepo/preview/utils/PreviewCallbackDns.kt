package com.tencent.bkrepo.preview.utils

import okhttp3.Dns
import java.net.InetAddress

/**
 * 远程预览 HTTP 出站专用 DNS 解析器。
 *
 * [SsrfGuard.validate] 只在发起请求前校验一次，OkHttp 真正建连时会重新解析域名，两次解析之间存在
 * DNS Rebinding 窗口：攻击者控制的权威 DNS 只要在此期间把记录切到内网/回环地址即可绕过校验。
 * 这里在建立 TCP 连接前对本次解析出的地址做黑名单校验，并把同一批结果交给后续连接使用，
 * 使校验与连接共用一次解析。重定向跳转到新 host 时同样会走到这里。
 */
class PreviewCallbackDns(private val ssrfGuard: SsrfGuard) : Dns {

    override fun lookup(hostname: String): List<InetAddress> {
        val addresses = Dns.SYSTEM.lookup(hostname)
        ssrfGuard.checkResolvedAddresses(hostname, addresses)
        return addresses
    }
}
