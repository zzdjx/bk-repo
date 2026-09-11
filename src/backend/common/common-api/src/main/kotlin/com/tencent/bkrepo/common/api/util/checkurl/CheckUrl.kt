package com.tencent.bkrepo.common.api.util.checkurl

import java.net.MalformedURLException
import java.net.URL

object CheckUrl {

    private const val DOMAIN_REGEX =
        "^(?=^.{3,255}$)[a-zA-Z0-9][-a-zA-Z0-9]{0,62}(\\.[a-zA-Z0-9][-a-zA-Z0-9]{0,62})+$"

    private val domainPattern = Regex(DOMAIN_REGEX)

    private val modeValues = setOf("equal", "subhost", "regex")

    private val modeMatchMethodMap = mapOf(
        "equal" to ValidateUrlFunction.IS_DOMAIN,
        "subhost" to ValidateUrlFunction.IS_SUB_DOMAIN,
        "regex" to ValidateUrlFunction.IS_SUB_DOMAIN_REGEX,
    )

    @Throws(IllegalArgumentException::class, MalformedURLException::class)
    fun checkUrl(url: String, config: UrlCheckConfig) {
        if (url.isBlank()) {
            throw IllegalArgumentException("Url is null.")
        }
        if (isConfigIllegal(config)) {
            throw IllegalArgumentException("Config is illegal.")
        }
        if (url.startsWith('/')) {
            return
        }
        val urlParsed = try {
            URL(url)
        } catch (_: MalformedURLException) {
            throw MalformedURLException("URL can not be parsed.")
        }
        if (!isHostnameValid(urlParsed.host)) {
            throw MalformedURLException("Hostname is not valid")
        }
        if (urlParsed.protocol !in config.schemes) {
            throw MalformedURLException("Scheme is not valid.")
        }
        if (config.blockPrivateIp && PrivateAddressMatcher.isForbiddenLiteral(urlParsed.host)) {
            throw MalformedURLException("Host is a private/loopback/link-local IP.")
        }
        if (config.rules.isEmpty()) return
        val validator = modeMatchMethodMap[config.mode]
            ?: throw IllegalArgumentException("Mode is not valid.")
        if (!validator.validateUrl(config.rules, urlParsed.host)) {
            throw MalformedURLException("URL does not meet the conditions.")
        }
    }

    private fun isConfigIllegal(config: UrlCheckConfig): Boolean {
        if (config.schemes.isEmpty()) return true
        if (config.rules.isEmpty()) return false
        return config.mode.isBlank() || config.mode !in modeValues
    }

    /**
     * 校验hostname格式：必须匹配域名格式，且不能是[PrivateAddressMatcher.isAmbiguousIpv4Literal]识别出的
     * 非规范IPv4字面量（如0177.0.0.1、192.168.000.001、0x7f.0.0.1）。这类写法点分格式上能匹配域名正则，
     * 但实际会被HTTP客户端解析成对应IP，若当成域名放行会绕过下方的[PrivateAddressMatcher.isForbiddenLiteral]
     * 内网/回环拦截。
     */
    private fun isHostnameValid(hostname: String): Boolean {
        if (!domainPattern.containsMatchIn(hostname)) return false
        return !PrivateAddressMatcher.isAmbiguousIpv4Literal(hostname)
    }
}
