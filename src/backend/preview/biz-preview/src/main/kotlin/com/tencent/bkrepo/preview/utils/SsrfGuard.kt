/*
 * Tencent is pleased to support the open source community by making BK-CI 蓝鲸持续集成平台 available.
 *
 * Copyright (C) 2020 Tencent.  All rights reserved.
 *
 * BK-CI 蓝鲸持续集成平台 is licensed under the MIT license.
 *
 * A copy of the MIT License is included in this file.
 *
 *
 * Terms of the MIT License:
 * ---------------------------------------------------
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package com.tencent.bkrepo.preview.utils

import com.tencent.bkrepo.common.api.util.checkurl.PrivateAddressMatcher
import com.tencent.bkrepo.preview.config.configuration.PreviewConfig
import com.tencent.bkrepo.preview.constant.PreviewMessageCode
import com.tencent.bkrepo.preview.exception.PreviewInvalidException
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.net.InetAddress
import java.net.URI
import java.net.URL
import java.net.UnknownHostException

/**
 * 远程预览 SSRF 防护工具
 *
 * 在对外部 URL 发起 HTTP/FTP 请求前，对 URL 进行安全校验：
 * - 协议白名单（默认 https、ftp）
 * - 端口白名单（默认 80/443/21）
 * - 域名/IP 白名单（可选，空表示不限制）
 * - 内网 / 环回 / 链路本地 / 多播 / 云元数据 / 保留网段黑名单
 *
 * 同时返回已解析的 IP 列表，调用方在实际发起请求时应传入这些 IP 以抵御 DNS Rebinding。
 * FTP PASV 数据连接请使用 [validateResolvedHost]：只校验地址黑名单，不套用控制连接端口白名单。
 */
@Component
class SsrfGuard(private val config: PreviewConfig) {

    companion object {
        private val logger = LoggerFactory.getLogger(SsrfGuard::class.java)
    }

    /**
     * 校验远程 URL。校验失败会抛出 [PreviewInvalidException]。
     * 校验成功时返回该 URL，供调用方继续使用。
     */
    fun validate(urlStr: String): URL {
        if (!config.isRemotePreviewEnabled) {
            logger.warn("Remote preview is disabled by configuration, reject url: {}", urlStr)
            throw PreviewInvalidException(PreviewMessageCode.PREVIEW_PARAMETER_INVALID, "url")
        }

        val url = try {
            URI(urlStr).toURL()
        } catch (e: Exception) {
            logger.warn("Invalid remote preview url: {}, err: {}", urlStr, e.message)
            throw PreviewInvalidException(PreviewMessageCode.PREVIEW_PARAMETER_INVALID, "url")
        }

        // 1. 协议白名单
        val scheme = url.protocol?.lowercase().orEmpty()
        val allowedSchemes = config.remoteAllowedSchemes
            .split(",").map { it.trim().lowercase() }.filter { it.isNotEmpty() }.toSet()
        if (scheme !in allowedSchemes) {
            logger.warn("Remote preview scheme [{}] not in whitelist {}", scheme, allowedSchemes)
            throw PreviewInvalidException(PreviewMessageCode.PREVIEW_PARAMETER_INVALID, "url")
        }

        // 2. 端口白名单
        val effectivePort = if (url.port == -1) url.defaultPort else url.port
        val allowedPorts = config.remoteAllowedPorts
            .split(",").mapNotNull { it.trim().toIntOrNull() }.toSet()
        if (effectivePort <= 0 || effectivePort !in allowedPorts) {
            logger.warn("Remote preview port [{}] not in whitelist {}", effectivePort, allowedPorts)
            throw PreviewInvalidException(PreviewMessageCode.PREVIEW_PARAMETER_INVALID, "url")
        }

        val host = url.host
        if (host.isNullOrBlank()) {
            logger.warn("Remote preview url has no host: {}", urlStr)
            throw PreviewInvalidException(PreviewMessageCode.PREVIEW_PARAMETER_INVALID, "url")
        }

        // 3. 域名白名单（可选）
        val allowedHosts = config.remoteAllowedHosts
            .split(",").map { it.trim().lowercase() }.filter { it.isNotEmpty() }
        if (allowedHosts.isNotEmpty() && !hostMatches(host.lowercase(), allowedHosts)) {
            logger.warn("Remote preview host [{}] not in whitelist {}", host, allowedHosts)
            throw PreviewInvalidException(PreviewMessageCode.PREVIEW_PARAMETER_INVALID, "url")
        }

        // 4. 内网 / 保留地址黑名单（基于 DNS 解析后的所有 IP）
        validateResolvedHost(host)
        return url
    }

    /**
     * 校验主机名或字面 IP 解析后的地址是否落在禁止网段。
     * 供 FTP PASV/EPSV 数据连接复用：只做地址黑名单，不做协议与端口白名单。
     * PASV 数据端口为服务端分配的临时端口，不能套用控制连接的端口白名单。
     */
    fun validateResolvedHost(host: String) {
        if (host.isBlank()) {
            logger.warn("Remote preview host is blank")
            throw PreviewInvalidException(PreviewMessageCode.PREVIEW_PARAMETER_INVALID, "url")
        }
        val addresses = try {
            InetAddress.getAllByName(host)
        } catch (e: UnknownHostException) {
            logger.warn("Remote preview host [{}] cannot be resolved", host)
            throw PreviewInvalidException(PreviewMessageCode.PREVIEW_PARAMETER_INVALID, "url")
        }
        addresses.forEach { addr ->
            if (isForbiddenAddress(addr)) {
                logger.warn("Remote preview host [{}] resolved to forbidden address [{}]", host, addr.hostAddress)
                throw PreviewInvalidException(PreviewMessageCode.PREVIEW_PARAMETER_INVALID, "url")
            }
        }
    }

    /**
     * 按白名单匹配域名：
     * - 条目以 "." 开头：后缀匹配（如 `.example.com` 匹配 `a.example.com` 与 `example.com`）
     * - 否则：精确匹配
     */
    private fun hostMatches(host: String, allowed: List<String>): Boolean {
        return allowed.any { pattern ->
            if (pattern.startsWith(".")) {
                val suffix = pattern.substring(1)
                host == suffix || host.endsWith(pattern)
            } else {
                host == pattern
            }
        }
    }

    /**
     * 判断 IP 是否属于禁止访问的保留/内网/云元数据等地址。
     */
    private fun isForbiddenAddress(addr: InetAddress): Boolean {
        return PrivateAddressMatcher.isForbidden(addr, blockInternal = config.isBlockInternalAddress)
    }
}
