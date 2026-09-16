package com.tencent.bkrepo.common.notify.api

import com.tencent.bkrepo.common.api.util.SecretMask
import com.tencent.bkrepo.common.notify.api.bkci.BkciChannelCredential
import com.tencent.bkrepo.common.notify.api.weworkbot.WeworkBotChannelCredential

object NotifyChannelSecrets {
    fun mask(credential: NotifyChannelCredential): NotifyChannelCredential = when (credential) {
        is WeworkBotChannelCredential -> credential.copy(key = SecretMask.mask(credential.key).orEmpty())
        is BkciChannelCredential -> credential.copy(appSecret = SecretMask.mask(credential.appSecret).orEmpty())
        else -> credential
    }

    fun keepExisting(
        incoming: NotifyChannelCredential,
        existing: NotifyChannelCredential
    ): NotifyChannelCredential = when {
        incoming is WeworkBotChannelCredential && existing is WeworkBotChannelCredential ->
            incoming.copy(key = SecretMask.keep(incoming.key, existing.key).orEmpty())
        incoming is BkciChannelCredential && existing is BkciChannelCredential ->
            incoming.copy(appSecret = SecretMask.keep(incoming.appSecret, existing.appSecret).orEmpty())
        else -> incoming
    }
}
