/*
 * Tencent is pleased to support the open source community by making BK-CI 蓝鲸持续集成平台 available.
 *
 * Copyright (C) 2019 Tencent.  All rights reserved.
 *
 * BK-CI 蓝鲸持续集成平台 is licensed under the MIT license.
 *
 * A copy of the MIT License is included in this file.
 *
 * Terms of the MIT License:
 * ---------------------------------------------------
 * Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated
 * documentation files (the "Software"), to deal in the Software without restriction, including without limitation the
 * rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the Software, and to
 * permit persons to whom the Software is furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all copies or substantial portions of
 * the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT
 * LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN
 * NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY,
 * WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE
 * SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */

package com.tencent.bkrepo.webhook.executor

import com.tencent.bkrepo.common.api.util.checkurl.PrivateAddressMatcher
import com.tencent.bkrepo.webhook.config.WebHookProperties
import okhttp3.Dns
import java.net.InetAddress
import java.net.UnknownHostException

/**
 * WebHook回调请求专用DNS解析器。
 *
 * [com.tencent.bkrepo.common.api.util.checkurl.SecUrlValidator]只在创建/更新WebHook时对URL中的字面量IP做
 * 一次性校验，无法感知域名后续被解析（或被DNS Rebinding）到内网/回环地址的情况。这里在真正建立TCP连接前，
 * 对本次DNS解析出的每个地址执行[PrivateAddressMatcher.isForbidden]校验，且直接复用该结果供后续连接使用，
 * 避免"先查后连"两次解析之间的TOCTOU窗口。
 */
class WebHookCallbackDns(private val webHookProperties: WebHookProperties) : Dns {

    override fun lookup(hostname: String): List<InetAddress> {
        val addresses = Dns.SYSTEM.lookup(hostname)
        if (!webHookProperties.callbackUrl.blockPrivateIp) {
            return addresses
        }
        if (addresses.any { PrivateAddressMatcher.isForbidden(it) }) {
            throw UnknownHostException(
                "Host [$hostname] resolved to a private/loopback/link-local IP, request is forbidden."
            )
        }
        return addresses
    }
}
