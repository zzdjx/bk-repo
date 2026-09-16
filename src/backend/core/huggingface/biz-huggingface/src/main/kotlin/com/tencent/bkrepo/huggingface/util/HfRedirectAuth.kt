package com.tencent.bkrepo.huggingface.util

/**
 * 重定向时是否转发 Authorization：只比较当前请求与 Location 的 host。
 * 不使用官方 Hub 白名单，避免用户配置的 mirror token 被带到 huggingface.co。
 */
internal object HfRedirectAuth {

    /**
     * 仅同 host 转发 Authorization；跨主机（CDN、其它 mirror、官方 Hub）均不转发。
     */
    fun shouldForwardAuthorization(fromHost: String?, toHost: String?): Boolean {
        val target = toHost?.lowercase().orEmpty()
        val source = fromHost?.lowercase().orEmpty()
        return target.isNotEmpty() && target == source
    }
}
