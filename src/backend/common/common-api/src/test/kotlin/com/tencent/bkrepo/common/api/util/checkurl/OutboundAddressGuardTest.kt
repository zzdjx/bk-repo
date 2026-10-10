package com.tencent.bkrepo.common.api.util.checkurl

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.net.InetAddress
import java.net.UnknownHostException

class OutboundAddressGuardTest {

    @Test
    fun `resolve rejects private lookup result`() {
        val loopback = InetAddress.getByName("127.0.0.1")
        assertThrows(UnknownHostException::class.java) {
            OutboundAddressGuard.resolve("repo.example.com") { listOf(loopback) }
        }
    }

    @Test
    fun `resolve keeps the checked addresses`() {
        val publicAddress = InetAddress.getByName("8.8.8.8")
        val resolved = OutboundAddressGuard.resolve("repo.example.com") { listOf(publicAddress) }
        assertEquals(listOf(publicAddress), resolved)
    }
}
