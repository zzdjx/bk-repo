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
import java.security.MessageDigest

/**
 * 加解密密钥。代码里不保留默认值，未配置的密钥在被用到时才报错，见 [CryptoPropertiesInitializer]。
 * 有 Mongo 时完整配置（含公钥）以 DB 为准；DB 无记录时沿用 helm / 本地配置并原子写入。
 * helm 由 chart 渲染时随机生成，二进制部署用 scripts/gen-crypto-keys.sh 生成后填入。
 * 公钥可留空，启动时从私钥推导后一并入库。
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
) {
    fun hasMaterial(): Boolean {
        return privateKeyStr.isNotEmpty() ||
            privateKeyStr2048PKCS8.isNotEmpty() ||
            privateKeyStr2048PKCS1.isNotEmpty() ||
            aesKey.isNotEmpty() ||
            aesIv.isNotEmpty()
    }

    /**
     * 入库校验指纹。对**已经归一化**的 9 个字段按固定顺序用 `|` 拼接后做 SHA-256，小写 hex。
     * 空字段也要占位（会出现 `||`）。手改库必须按这串重算整份，只改一把钥会启动失败。
     *
     * rsaAlgorithm|privateKeyStr|publicKeyStr|privateKeyStr2048PKCS8|publicKeyStr2048PKCS8|
     * privateKeyStr2048PKCS1|publicKeyStr2048PKCS1|aesKey|aesIv
     *
     * openssl 转私钥/公钥的步骤见 [TSecurityCryptoKeyring]。
     */
    fun fingerprint(): String {
        val payload = listOf(
            rsaAlgorithm,
            privateKeyStr,
            publicKeyStr,
            privateKeyStr2048PKCS8,
            publicKeyStr2048PKCS8,
            privateKeyStr2048PKCS1,
            publicKeyStr2048PKCS1,
            aesKey,
            aesIv
        ).joinToString("|")
        val digest = MessageDigest.getInstance("SHA-256").digest(payload.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }

    /**
     * DB 已有的值永远胜出。某类密钥在库里为空时用 [from] 补上：库里本来就没有，谈不上换钥。
     * 公私钥 / AES 成对补，避免只补一半。
     */
    fun fillMissingFrom(from: CryptoProperties): CryptoProperties {
        val merged = copy()
        if (merged.privateKeyStr.isEmpty()) {
            merged.privateKeyStr = from.privateKeyStr
            merged.publicKeyStr = from.publicKeyStr
        }
        if (merged.privateKeyStr2048PKCS8.isEmpty()) {
            merged.privateKeyStr2048PKCS8 = from.privateKeyStr2048PKCS8
            merged.publicKeyStr2048PKCS8 = from.publicKeyStr2048PKCS8
        }
        if (merged.privateKeyStr2048PKCS1.isEmpty()) {
            merged.privateKeyStr2048PKCS1 = from.privateKeyStr2048PKCS1
            merged.publicKeyStr2048PKCS1 = from.publicKeyStr2048PKCS1
        }
        if (merged.aesKey.isEmpty()) {
            merged.aesKey = from.aesKey
            merged.aesIv = from.aesIv
        }
        return merged
    }

    fun overwrite(from: CryptoProperties) {
        rsaAlgorithm = from.rsaAlgorithm
        privateKeyStr = from.privateKeyStr
        publicKeyStr = from.publicKeyStr
        privateKeyStr2048PKCS8 = from.privateKeyStr2048PKCS8
        publicKeyStr2048PKCS8 = from.publicKeyStr2048PKCS8
        privateKeyStr2048PKCS1 = from.privateKeyStr2048PKCS1
        publicKeyStr2048PKCS1 = from.publicKeyStr2048PKCS1
        aesKey = from.aesKey
        aesIv = from.aesIv
    }
}
