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

package com.tencent.bkrepo.common.security.crypto

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * 加解密密钥。代码里不保留默认值，未配置的密钥在被用到时才报错，见 [CryptoPropertiesInitializer]。
 * helm 部署由 chart 渲染时随机生成，二进制部署用 scripts/gen-crypto-keys.sh 生成后填入。
 * 公钥可留空，启动时从私钥推导。
 */
@ConfigurationProperties("security.crypto")
data class CryptoProperties(
    var rsaAlgorithm: String = "RSA/ECB/PKCS1Padding",
    /**
     * RSA 私钥，单行 Base64。登录密码加解密，多实例须同一套。
     * 不限位数，存量集群多是 1024，chart 与 gen-crypto-keys.sh 现在统一生成 2048。
     */
    var privateKeyStr: String = "",
    /**
     * RSA 公钥，单行 Base64。
     */
    var publicKeyStr: String = "",
    /**
     * RSA PKCS#8 私钥，单行 Base64。OAuth/OIDC JWT 签名走 RS256，
     * jjwt 按 RFC 7518 要求密钥不低于 2048 位，否则签名时抛 WeakKeyException。
     */
    var privateKeyStr2048PKCS8: String = "",
    /**
     * RSA PKCS#8 公钥，单行 Base64。
     */
    var publicKeyStr2048PKCS8: String = "",
    /**
     * RSA 2048 私钥，单行 PKCS#8 Base64（字段名历史遗留，不是 PKCS#1）。
     * 制品传输 crypt key（artifact.crypt.enabled）。
     */
    var privateKeyStr2048PKCS1: String = "",
    /**
     * RSA 2048 公钥，单行 X.509 Base64。
     */
    var publicKeyStr2048PKCS1: String = "",
    /**
     * AES 密钥，16/24/32 字节。代理密钥加解密。
     */
    var aesKey: String = "",
    /**
     * AES IV，16 字节。
     */
    var aesIv: String = ""
)
