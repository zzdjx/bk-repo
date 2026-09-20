package com.tencent.bkrepo.pypi.artifact.repository

import com.tencent.bkrepo.common.artifact.pojo.RepositoryCategory
import com.tencent.bkrepo.common.artifact.pojo.RepositoryType
import com.tencent.bkrepo.common.artifact.pojo.configuration.local.LocalConfiguration
import com.tencent.bkrepo.common.artifact.repository.context.ArtifactQueryContext
import com.tencent.bkrepo.common.artifact.repository.context.ArtifactRemoveContext
import com.tencent.bkrepo.common.artifact.util.PackageKeys
import com.tencent.bkrepo.common.metadata.service.node.NodeService
import com.tencent.bkrepo.common.metadata.service.packages.PackageService
import com.tencent.bkrepo.common.metadata.service.packages.StageService
import com.tencent.bkrepo.common.security.util.SecurityUtils
import com.tencent.bkrepo.common.service.util.HttpContextHolder
import com.tencent.bkrepo.pypi.artifact.PypiArtifactInfo
import com.tencent.bkrepo.pypi.artifact.PypiProperties
import com.tencent.bkrepo.pypi.artifact.PypiSimpleArtifactInfo
import com.tencent.bkrepo.pypi.service.PypiSimpleIndexCache
import com.tencent.bkrepo.pypi.service.PypiSimpleIndexCacheService
import com.tencent.bkrepo.repository.pojo.metadata.MetadataModel
import com.tencent.bkrepo.repository.pojo.node.NodeInfo
import com.tencent.bkrepo.repository.pojo.node.NodeListOption
import com.tencent.bkrepo.repository.pojo.node.service.NodeDeleteRequest
import com.tencent.bkrepo.repository.pojo.repo.RepositoryDetail
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import io.mockk.verify
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("PyPI 仓库层 simple 缓存装配")
class PypiLocalRepositorySimpleIndexTest {

    private val stageService: StageService = mockk(relaxed = true)
    private val cacheService: PypiSimpleIndexCacheService = mockk(relaxed = true)
    private val nodeService: NodeService = mockk(relaxed = true)
    private val packageService: PackageService = mockk(relaxed = true)
    private val request: HttpServletRequest = mockk(relaxed = true)
    private val pypiProperties = PypiProperties().apply { enableSimpleIndexCache = true }
    private lateinit var repository: PypiLocalRepository

    @BeforeEach
    fun setUp() {
        clearMocks(stageService, cacheService, nodeService, packageService, request)
        pypiProperties.enableSimpleIndexCache = true
        pypiProperties.enableRegexQuery = true
        mockkObject(HttpContextHolder)
        mockkObject(SecurityUtils)
        every { HttpContextHolder.getRequest() } returns request
        every { HttpContextHolder.getResponse() } returns mockk<HttpServletResponse>(relaxed = true)
        every { HttpContextHolder.getClientAddress() } returns "127.0.0.1"
        every { SecurityUtils.getUserId() } returns "user"
        repository = PypiLocalRepository(stageService, pypiProperties, cacheService)
        repository.nodeService = nodeService
        repository.packageService = packageService
        every { cacheService.overwrite(any(), any(), any(), any(), any(), any()) } answers {
            @Suppress("UNCHECKED_CAST")
            (invocation.args[5] as () -> String?).invoke()
        }
    }

    @AfterEach
    fun tearDown() {
        unmockkObject(HttpContextHolder)
        unmockkObject(SecurityUtils)
    }

    @Test
    @DisplayName("未过期命中缓存，不 listing")
    fun queryReturnsFreshCacheWithoutListing() {
        val html = "<html>cached</html>"
        every { cacheService.load(PROJECT, REPO, "demo", any()) } returns cache(html, expired = false)

        val result = repository.query(queryContext())

        assertEquals(html, result)
        verify(exactly = 0) { cacheService.tryRefresh(any(), any(), any(), any(), any(), any()) }
        verify(exactly = 0) { nodeService.listNodeWithMetadataKeys(any(), any(), any()) }
    }

    @Test
    @DisplayName("过期且未抢锁时返回旧 HTML")
    fun queryReturnsStaleWhenTryRefreshMissesLock() {
        val stale = "<html>stale</html>"
        every { cacheService.load(PROJECT, REPO, "demo", any()) } returns cache(stale, expired = true)
        every { cacheService.tryRefresh(any(), any(), any(), any(), any(), any()) } returns null

        val result = repository.query(queryContext())

        assertEquals(stale, result)
        verify { cacheService.tryRefresh(any(), any(), any(), any(), any(), any()) }
        verify(exactly = 0) { nodeService.listNodeWithMetadataKeys(any(), any(), any()) }
    }

    @Test
    @DisplayName("过期抢到锁后返回重建结果")
    fun queryReturnsRefreshedHtmlWhenLockAcquired() {
        every { cacheService.load(PROJECT, REPO, "demo", any()) } returns cache("<html>stale</html>", expired = true)
        every { cacheService.tryRefresh(any(), any(), any(), any(), any(), any()) } returns "<html>new</html>"

        val result = repository.query(queryContext())

        assertEquals("<html>new</html>", result)
        verify { cacheService.tryRefresh(any(), any(), any(), any(), any(), any()) }
    }

    @Test
    @DisplayName("miss 时 listing skip-count 并 tryStore")
    fun queryMissListsWithoutCountThenTriesStore() {
        every { cacheService.load(PROJECT, REPO, "demo", any()) } returns null
        every {
            nodeService.listNodeWithMetadataKeys(
                match { it.getArtifactFullPath() == "/demo" },
                match<NodeListOption> { !it.includeTotalRecords && it.deep && !it.includeFolder },
                any(),
            )
        } returns listOf(wheelNode())
        every { cacheService.tryStore(any(), any(), any(), any(), any(), any()) } returns true

        val result = repository.query(queryContext()) as String

        assertTrue(result.contains("demo-1.0-py3-none-any.whl"))
        verify { cacheService.tryStore(PROJECT, REPO, "demo", any(), any(), any()) }
    }

    @Test
    @DisplayName("删版本后覆盖写，listing 不走 regex")
    fun removeVersionOverwritesWithoutRegex() {
        every { request.getParameter("packageKey") } returns PackageKeys.ofPypi("demo")
        every { request.getParameter("version") } returns "1.0"
        every { request.getParameter("contentPath") } returns "/demo/1.0/demo-1.0-py3-none-any.whl"
        every { nodeService.deleteNode(any<NodeDeleteRequest>()) } returns mockk(relaxed = true)
        every {
            nodeService.listNodeWithMetadataKeys(any(), any(), any())
        } returns listOf(wheelNode())

        repository.remove(removeContext())

        verify { cacheService.overwrite(PROJECT, REPO, "demo", any(), any(), any()) }
        verify(exactly = 0) { cacheService.invalidate(any(), any(), any()) }
    }

    @Test
    @DisplayName("删空包 NotFound 时删除缓存")
    fun removeEmptyPackageInvalidatesCache() {
        every { request.getParameter("packageKey") } returns PackageKeys.ofPypi("demo")
        every { request.getParameter("version") } returns "1.0"
        every { request.getParameter("contentPath") } returns "/demo/1.0/demo-1.0-py3-none-any.whl"
        every { nodeService.deleteNode(any<NodeDeleteRequest>()) } returns mockk(relaxed = true)
        every { nodeService.listNodeWithMetadataKeys(any(), any(), any()) } returns emptyList()

        repository.remove(removeContext())

        verify { cacheService.invalidate(PROJECT, REPO, "demo") }
    }

    private fun queryContext(): ArtifactQueryContext {
        return ArtifactQueryContext(repoDetail(), PypiSimpleArtifactInfo(PROJECT, REPO, "demo"))
    }

    private fun removeContext(): ArtifactRemoveContext {
        return ArtifactRemoveContext(repoDetail(), PypiArtifactInfo(PROJECT, REPO, "/demo"))
    }

    private fun cache(html: String, expired: Boolean) = PypiSimpleIndexCache(html, expired, "t")

    private fun wheelNode(): NodeInfo {
        return NodeInfo(
            createdBy = "user",
            createdDate = "t",
            lastModifiedBy = "user",
            lastModifiedDate = "t",
            folder = false,
            path = "/demo/1.0/",
            name = "demo-1.0-py3-none-any.whl",
            fullPath = "/demo/1.0/demo-1.0-py3-none-any.whl",
            size = 10,
            sha256 = "a".repeat(64),
            projectId = PROJECT,
            repoName = REPO,
            metadata = mapOf("version" to "1.0"),
            nodeMetadata = listOf(MetadataModel(key = "version", value = "1.0")),
        )
    }

    private fun repoDetail() = RepositoryDetail(
        projectId = PROJECT,
        name = REPO,
        storageCredentials = null,
        type = RepositoryType.PYPI,
        category = RepositoryCategory.LOCAL,
        public = false,
        description = "",
        configuration = LocalConfiguration(),
        createdBy = "",
        createdDate = "",
        lastModifiedBy = "",
        lastModifiedDate = "",
        oldCredentialsKey = null,
        quota = null,
        used = null,
    )

    companion object {
        private const val PROJECT = "p-test"
        private const val REPO = "pypi-local"
    }
}
