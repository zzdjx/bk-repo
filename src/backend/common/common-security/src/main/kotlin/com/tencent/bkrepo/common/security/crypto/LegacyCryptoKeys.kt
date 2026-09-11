package com.tencent.bkrepo.common.security.crypto

/**
 * 升级前硬编码在 [CryptoProperties] 里的默认私钥，已随开源仓库公开，用它加密等同于没有加密。
 *
 * 只允许出现在 RsaUtils.decryptWithSource 的解密兜底分支，用于读取升级前写入的存量密文。
 * 任何加密路径引用它都是安全漏洞，不要放进 [CryptoProperties]，那样会被 Spring 绑定成活跃密钥。
 *
 * 存量密文由 LegacyCryptoRewriteJob 重加密完成后，本文件连同兜底分支一并删除。
 */
@Deprecated("仅用于解密升级前写入的存量数据，禁止用于加密")
object LegacyCryptoKeys {
    /**
     * 旧的 security.crypto.privateKeyStr，1024 位 PKCS#8。公钥不必保留，解密用不上。
     *
     * 其余四把旧默认密钥（2048PKCS8/2048PKCS1/aesKey/aesIv）不留兜底，它们保护的都不是取不回来的
     * 用户数据：OAuth token 重新授权即可，制品传输 crypt key 下次请求现取公钥就自愈，
     * proxy secretKey 只有 ProxyServiceImpl.create 会生成、没有重置接口，需要删除代理节点后重建。
     * 而 privateKeyStr 加密的是用户填进仓库的第三方密码，换钥后取不回来，只能兜底。
     * 升级时想避免这些动作，就在 chart 里把旧值显式填回去，见 values.yaml security.crypto。
     */
    const val RSA_1024_PRIVATE_KEY = """
        MIICdwIBADANBgkqhkiG9w0BAQEFAASCAmEwggJdAgEAAoGBAMaoDhrj+Da2tGpa
        wrE8et6vHBjprVj0UiCEza7JVymYTo9gd/pxNJRnbf6NehUL1WP8D6f5e2XZEDNf
        qXOqyEjPqOKtWIYI6ZLQeQIuAXgyGE5aP3/KVHFnxk+IuzcJtvqTAthfeuVXGel9
        ATP8hlEyDuCJe7/orBjIVYFk3p+PAgMBAAECgYAGYwLJFIk3YRpdzPszbYlZvXF+
        z4x2LqyxRPPD6c82lCH6dBSHZbpWBxk/NNc29AFxTHpIYTn5ZUgjDrFI+bWkqxvg
        qWS/oyfB6rxajIQjTeorsGvt/oumxQA7hvUE2XXLi218RXCURWgz/FZnvNhGhPYU
        OJWHoPeNlVx3V5mG8QJBAOzP9iSPcw1YJkv6uAgY4MRv1GqPu3NcMif+DQVPOZCN
        Pq7ynSg15Zl3HMpl6jAZNJ/AUXRby3tLhO8WiWr6C8cCQQDWwKbhy4AZ5SDigFIP
        tk/655Uzprm2JaZSvGkBeOSB9EYCUC1ApKeImrufPZpSj3Ood/fbMyA6cl8Bswl2
        z335AkBTNa+ToSQYKEUspWhM0BEKdRD6cI65NkgZbVc96lybwkWoS2+VVXrbtdLT
        +4OSawjmqTj13dtd82c+a3jVsg65AkEAn1kiO0caDZzj8s2OlpQL8rwmDMZ45Lw5
        FwkwzWPcAsWzsQG3IlFK8uUFtRoryXkiM+6Y3nCoSFYXQxaLPjqmWQJBAI2tn28X
        AHFcSd0UnS8L6exJuMdjCw4huI5FOeZ0arf5NrWDFoKU30Crw2ozmRBcDvtjDVH9
        sn8oLC2ObCFItlM=
    """
}
