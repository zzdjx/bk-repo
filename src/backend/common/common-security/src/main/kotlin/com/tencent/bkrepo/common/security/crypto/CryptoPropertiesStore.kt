package com.tencent.bkrepo.common.security.crypto

interface CryptoPropertiesStore {
    /**
     * 有记录则返回；没有返回 null。
     * 查询失败、半对密钥或 fingerprint 对不上时抛错，禁止当成无记录。
     */
    fun load(): CryptoProperties?

    /** 无记录时原子写入 [candidate]；并发抢写时返回已有记录 */
    fun insertIfAbsent(candidate: CryptoProperties): CryptoProperties

    /**
     * 只补 DB 为空的密钥类型。CAS 失败返回 null，调用方重读即可。
     */
    fun patch(expectedFingerprint: String, merged: CryptoProperties): CryptoProperties?
}
