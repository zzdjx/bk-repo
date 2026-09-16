package com.tencent.bkrepo.common.notify.api

import com.tencent.bkrepo.common.api.util.SecretMask
import com.tencent.bkrepo.common.notify.api.bkci.BkciChannelCredential
import com.tencent.bkrepo.common.notify.api.weworkbot.WeworkBotChannelCredential
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("通知渠道凭据掩码")
class NotifyChannelSecretsTest {

    @Test
    fun `mask wework key and keep on placeholder`() {
        val stored = WeworkBotChannelCredential(name = "bot", key = "11111111-2222-3333-4444-555555555555")
        val masked = NotifyChannelSecrets.mask(stored) as WeworkBotChannelCredential
        assertEquals(SecretMask.MASKED, masked.key)
        assertEquals("11111111-2222-3333-4444-555555555555", stored.key)
        assertNotSame(stored, masked)

        val incoming = WeworkBotChannelCredential(name = "bot", default = true, key = "*")
        val merged = NotifyChannelSecrets.keepExisting(incoming, stored) as WeworkBotChannelCredential
        assertEquals(stored.key, merged.key)
        assertEquals(true, merged.default)
        assertEquals("*", incoming.key)
    }

    @Test
    fun `rotate bkci appSecret`() {
        val stored = BkciChannelCredential(name = "bk", appCode = "code", appSecret = "old-secret")
        val incoming = BkciChannelCredential(name = "bk", appCode = "code", appSecret = "new-secret")
        val merged = NotifyChannelSecrets.keepExisting(incoming, stored) as BkciChannelCredential
        assertEquals("new-secret", merged.appSecret)
        assertEquals(SecretMask.MASKED, (NotifyChannelSecrets.mask(stored) as BkciChannelCredential).appSecret)
        assertEquals("old-secret", stored.appSecret)
    }
}
