package com.tencent.bkrepo.common.api.util.checkurl

data class UrlCheckProperties(
    var schemes: List<String> = listOf("http", "https"),
    /** 主机名白名单，见 [mode]；空则只校验协议和域名格式，与现网默认行为一致 */
    var rules: List<String> = emptyList(),
    var mode: String = "subhost",
    /**
     * 是否拦截字面量形式的回环/内网/链路本地IP，防止SSRF。
     * 默认关闭以保持现有行为兼容，需要防护的场景应显式开启。
     * 注意：仅对URL中直接写字面量IP的情况生效，不会对域名做DNS解析后校验。
     */
    var blockPrivateIp: Boolean = false,
)
