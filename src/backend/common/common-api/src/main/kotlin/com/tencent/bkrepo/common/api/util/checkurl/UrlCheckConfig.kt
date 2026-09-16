package com.tencent.bkrepo.common.api.util.checkurl

data class UrlCheckConfig(
    var schemes: List<String> = listOf("http", "https"),
    var rules: List<String> = mutableListOf(),
    var mode: String = "subhost",
    /** 是否拦截字面量形式的回环/内网/链路本地IP，防止SSRF */
    var blockPrivateIp: Boolean = false,
)
