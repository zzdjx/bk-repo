package com.tencent.bkrepo.common.metadata.util

import com.tencent.bkrepo.common.api.exception.ErrorCodeException
import com.tencent.bkrepo.common.artifact.pojo.configuration.composite.CompositeConfiguration
import com.tencent.bkrepo.common.artifact.pojo.configuration.composite.ProxyChannelSetting
import com.tencent.bkrepo.common.artifact.pojo.configuration.composite.ProxyConfiguration
import com.tencent.bkrepo.common.artifact.pojo.configuration.remote.RemoteConfiguration
import com.tencent.bkrepo.common.metadata.util.RepositoryServiceHelper.Companion.PASSWORD_MASK
import com.tencent.bkrepo.common.metadata.util.RepositoryServiceHelper.Companion.maskConfigurationPwd
import com.tencent.bkrepo.common.metadata.util.RepositoryServiceHelper.Companion.restoreMaskedPasswords
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RepositoryServiceHelperTest {

    @Test
    fun maskRemoteAndCompositePassword() {
        val remote = RemoteConfiguration()
        remote.credentials.password = "cipher-text"
        maskConfigurationPwd(remote)
        assertEquals(PASSWORD_MASK, remote.credentials.password)

        val channel = ProxyChannelSetting(
            public = false,
            name = "private",
            url = "http://example.com",
            password = "cipher-text",
        )
        val composite = CompositeConfiguration(proxy = ProxyConfiguration(channelList = listOf(channel)))
        maskConfigurationPwd(composite)
        assertEquals(PASSWORD_MASK, composite.proxy.channelList[0].password)
    }

    @Test
    fun restoreMaskedPasswordKeepsOldValue() {
        val oldRemote = RemoteConfiguration()
        oldRemote.credentials.password = "old-secret"
        val newRemote = RemoteConfiguration()
        newRemote.credentials.password = PASSWORD_MASK
        restoreMaskedPasswords(newRemote, oldRemote)
        assertEquals("old-secret", newRemote.credentials.password)

        val oldChannel = ProxyChannelSetting(
            public = false,
            name = "private",
            url = "http://example.com",
            password = "old-secret",
        )
        val newChannel = ProxyChannelSetting(
            public = false,
            name = "private",
            url = "http://example.com",
            password = PASSWORD_MASK,
        )
        restoreMaskedPasswords(
            CompositeConfiguration(proxy = ProxyConfiguration(channelList = listOf(newChannel))),
            CompositeConfiguration(proxy = ProxyConfiguration(channelList = listOf(oldChannel))),
        )
        assertEquals("old-secret", newChannel.password)
    }

    @Test
    fun keepBlankPasswordWhenNoOldChannel() {
        val newChannel = ProxyChannelSetting(
            public = false,
            name = "new",
            url = "http://example.com",
            password = "",
        )
        restoreMaskedPasswords(
            CompositeConfiguration(proxy = ProxyConfiguration(channelList = listOf(newChannel))),
            CompositeConfiguration(),
        )
        assertTrue(newChannel.password.isNullOrBlank())

        val oldChannel = ProxyChannelSetting(
            public = false,
            name = "private",
            url = "http://example.com",
            password = "old-secret",
        )
        val blankChannel = ProxyChannelSetting(
            public = false,
            name = "private",
            url = "http://example.com",
            password = "",
        )
        restoreMaskedPasswords(
            CompositeConfiguration(proxy = ProxyConfiguration(channelList = listOf(blankChannel))),
            CompositeConfiguration(proxy = ProxyConfiguration(channelList = listOf(oldChannel))),
        )
        assertTrue(blankChannel.password.isNullOrBlank())
    }

    @Test
    fun restorePasswordWhenChannelRenamedSameUrl() {
        val oldChannel = ProxyChannelSetting(
            public = false,
            name = "old-name",
            url = "http://example.com",
            password = "old-secret",
        )
        val newChannel = ProxyChannelSetting(
            public = false,
            name = "new-name",
            url = "http://example.com",
            password = PASSWORD_MASK,
        )
        restoreMaskedPasswords(
            CompositeConfiguration(proxy = ProxyConfiguration(channelList = listOf(newChannel))),
            CompositeConfiguration(proxy = ProxyConfiguration(channelList = listOf(oldChannel))),
        )
        assertEquals("old-secret", newChannel.password)
    }

    @Test
    fun rejectWhenNameAndUrlDiffer() {
        val oldChannel = ProxyChannelSetting(
            public = false,
            name = "old-name",
            url = "http://old.example.com",
            password = "old-secret",
        )
        val newChannel = ProxyChannelSetting(
            public = false,
            name = "new-name",
            url = "http://new.example.com",
            password = PASSWORD_MASK,
        )
        assertThrows(ErrorCodeException::class.java) {
            restoreMaskedPasswords(
                CompositeConfiguration(proxy = ProxyConfiguration(channelList = listOf(newChannel))),
                CompositeConfiguration(proxy = ProxyConfiguration(channelList = listOf(oldChannel))),
            )
        }
    }

    @Test
    fun rejectWhenSameNameUrlChanged() {
        val oldChannel = ProxyChannelSetting(
            public = false,
            name = "private",
            url = "http://old.example.com",
            password = "old-secret",
        )
        val newChannel = ProxyChannelSetting(
            public = false,
            name = "private",
            url = "http://new.example.com",
            password = PASSWORD_MASK,
        )
        assertThrows(ErrorCodeException::class.java) {
            restoreMaskedPasswords(
                CompositeConfiguration(proxy = ProxyConfiguration(channelList = listOf(newChannel))),
                CompositeConfiguration(proxy = ProxyConfiguration(channelList = listOf(oldChannel))),
            )
        }
    }

    @Test
    fun restorePasswordWhenUrlDiffersOnlyByFormat() {
        val oldChannel = ProxyChannelSetting(
            public = false,
            name = "private",
            url = "http://example.com/",
            password = "old-secret",
        )
        val newChannel = ProxyChannelSetting(
            public = false,
            name = "private",
            url = "example.com",
            password = PASSWORD_MASK,
        )
        restoreMaskedPasswords(
            CompositeConfiguration(proxy = ProxyConfiguration(channelList = listOf(newChannel))),
            CompositeConfiguration(proxy = ProxyConfiguration(channelList = listOf(oldChannel))),
        )
        assertEquals("old-secret", newChannel.password)
    }

    @Test
    fun rejectRemotePasswordWhenUrlChanged() {
        val oldRemote = RemoteConfiguration(url = "http://old.example.com")
        oldRemote.credentials.password = "old-secret"
        val newRemote = RemoteConfiguration(url = "http://new.example.com")
        newRemote.credentials.password = PASSWORD_MASK
        assertThrows(ErrorCodeException::class.java) {
            restoreMaskedPasswords(newRemote, oldRemote)
        }
    }
}
