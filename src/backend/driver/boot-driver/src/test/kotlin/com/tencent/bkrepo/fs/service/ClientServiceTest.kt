package com.tencent.bkrepo.fs.service

import com.tencent.bkrepo.fs.UT_PROJECT_ID
import com.tencent.bkrepo.fs.UT_REPO_NAME
import com.tencent.bkrepo.fs.UT_USER
import com.tencent.bkrepo.fs.server.context.ReactiveRequestContextHolder
import com.tencent.bkrepo.fs.server.model.TClient
import com.tencent.bkrepo.fs.server.model.TDailyClient
import com.tencent.bkrepo.fs.server.repository.ClientRepository
import com.tencent.bkrepo.fs.server.repository.DailyClientRepository
import com.tencent.bkrepo.fs.server.request.ClientCreateRequest
import com.tencent.bkrepo.fs.server.service.ClientService
import com.tencent.bkrepo.fs.server.utils.ReactiveSecurityUtils
import io.mockk.coEvery
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.slot
import io.mockk.unmockkObject
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.data.mongodb.core.query.Query
import java.time.LocalDateTime

@DisplayName("客户端创建身份匹配测试")
class ClientServiceTest {

    private val clientRepository = mockk<ClientRepository>()
    private val dailyClientRepository = mockk<DailyClientRepository>()
    private val service = ClientService(clientRepository, dailyClientRepository)

    @BeforeEach
    fun setUp() {
        mockkObject(ReactiveSecurityUtils)
        mockkObject(ReactiveRequestContextHolder)
        coEvery { ReactiveSecurityUtils.getUser() } returns UT_USER
        coEvery { ReactiveRequestContextHolder.getClientAddress() } returns CLIENT_IP
        coEvery { dailyClientRepository.save(any()) } answers { firstArg<TDailyClient>() }
        coEvery { clientRepository.save(any()) } answers {
            val client = firstArg<TClient>()
            if (client.id == null) client.copy(id = NEW_CLIENT_ID) else client
        }
    }

    @AfterEach
    fun tearDown() {
        unmockkObject(ReactiveSecurityUtils)
        unmockkObject(ReactiveRequestContextHolder)
    }

    @Test
    fun `createClient lookup includes userId`() = runBlocking {
        val querySlot = slot<Query>()
        coEvery { clientRepository.find(capture(querySlot)) } returns emptyList()

        service.createClient(createRequest())

        assertEquals(UT_USER, querySlot.captured.queryObject["userId"])
        assertEquals(UT_PROJECT_ID, querySlot.captured.queryObject["projectId"])
        assertEquals(UT_REPO_NAME, querySlot.captured.queryObject["repoName"])
        assertEquals(MOUNT_POINT, querySlot.captured.queryObject["mountPoint"])
        assertEquals(CLIENT_IP, querySlot.captured.queryObject["ip"])
    }

    @Test
    fun `createClient with forceNew inserts instead of updating existing client`() = runBlocking {
        val created = service.createClient(createRequest(forceNew = true))

        assertEquals(NEW_CLIENT_ID, created.id)
        assertNotEquals(EXISTING_CLIENT_ID, created.id)
    }

    @Test
    fun `createClient without forceNew updates existing client`() = runBlocking {
        val existing = existingClient(
            id = EXISTING_CLIENT_ID,
            version = "1.0.0",
            online = true,
            heartbeatTime = LocalDateTime.now()
        )
        coEvery { clientRepository.find(any()) } returns listOf(existing)

        val created = service.createClient(createRequest(forceNew = false, version = "2.0.0"))

        assertEquals(EXISTING_CLIENT_ID, created.id)
        assertEquals("2.0.0", created.version)
    }

    @Test
    fun `createClient without forceNew prefers online client over newer offline client`() = runBlocking {
        val newerOffline = existingClient(
            id = "offline-id",
            online = false,
            heartbeatTime = LocalDateTime.now()
        )
        val olderOnline = existingClient(
            id = "online-id",
            online = true,
            heartbeatTime = LocalDateTime.now().minusHours(1)
        )
        coEvery { clientRepository.find(any()) } returns listOf(newerOffline, olderOnline)

        val created = service.createClient(createRequest(forceNew = false))

        assertEquals("online-id", created.id)
    }

    @Test
    fun `createClient without forceNew prefers latest heartbeat among online clients`() = runBlocking {
        val olderOnline = existingClient(
            id = "older-online",
            online = true,
            heartbeatTime = LocalDateTime.now().minusHours(2)
        )
        val newerOnline = existingClient(
            id = "newer-online",
            online = true,
            heartbeatTime = LocalDateTime.now().minusMinutes(5)
        )
        coEvery { clientRepository.find(any()) } returns listOf(olderOnline, newerOnline)

        val created = service.createClient(createRequest(forceNew = false))

        assertEquals("newer-online", created.id)
    }

    private fun createRequest(forceNew: Boolean = false, version: String = "2.0.0"): ClientCreateRequest {
        return ClientCreateRequest(
            projectId = UT_PROJECT_ID,
            repoName = UT_REPO_NAME,
            mountPoint = MOUNT_POINT,
            version = version,
            os = "linux",
            arch = "amd64",
            ip = CLIENT_IP,
            forceNew = forceNew
        )
    }

    private fun existingClient(
        id: String,
        version: String = "1.0.0",
        online: Boolean,
        heartbeatTime: LocalDateTime
    ): TClient {
        return TClient(
            id = id,
            projectId = UT_PROJECT_ID,
            repoName = UT_REPO_NAME,
            mountPoint = MOUNT_POINT,
            userId = UT_USER,
            ip = CLIENT_IP,
            version = version,
            os = "linux",
            arch = "amd64",
            online = online,
            heartbeatTime = heartbeatTime
        )
    }

    companion object {
        private const val MOUNT_POINT = "/mnt/repo"
        private const val CLIENT_IP = "127.0.0.1"
        private const val EXISTING_CLIENT_ID = "existing-id"
        private const val NEW_CLIENT_ID = "new-id"
    }
}
