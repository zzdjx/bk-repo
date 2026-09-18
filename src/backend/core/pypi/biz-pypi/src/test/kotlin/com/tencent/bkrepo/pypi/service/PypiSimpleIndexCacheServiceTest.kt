/*
 * Tencent is pleased to support the open source community by making BK-CI 蓝鲸持续集成平台 available.
 *
 * Copyright (C) 2024 Tencent.  All rights reserved.
 *
 * BK-CI 蓝鲸持续集成平台 is licensed under the MIT license.
 *
 * A copy of the MIT License is included in this file.
 *
 * Terms of the MIT License:
 * ---------------------------------------------------
 * Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated
 * documentation files (the "Software"), to deal in the Software without restriction, including without limitation the
 * rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the Software, and to
 * permit persons to whom the Software is furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all copies or substantial portions of
 * the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT
 * LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN
 * NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY,
 * WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE
 * SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */

package com.tencent.bkrepo.pypi.service

import com.tencent.bkrepo.common.artifact.manager.StorageManager
import com.tencent.bkrepo.common.artifact.stream.ArtifactInputStream
import com.tencent.bkrepo.common.artifact.stream.Range
import com.tencent.bkrepo.common.lock.service.LockOperation
import com.tencent.bkrepo.common.metadata.service.node.NodeService
import com.tencent.bkrepo.pypi.artifact.PypiProperties
import com.tencent.bkrepo.pypi.exception.PypiSimpleNotFoundException
import com.tencent.bkrepo.pypi.util.PypiSimpleIndexUtils
import com.tencent.bkrepo.repository.pojo.node.NodeDetail
import com.tencent.bkrepo.repository.pojo.node.NodeInfo
import com.tencent.bkrepo.repository.pojo.node.service.NodeCreateRequest
import com.tencent.bkrepo.repository.pojo.node.service.NodeDeleteRequest
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.LocalDateTime

@DisplayName("PyPI simple 单包索引文件缓存")
class PypiSimpleIndexCacheServiceTest {

    private val nodeService: NodeService = mockk(relaxed = true)
    private val storageManager: StorageManager = mockk(relaxed = true)
    private val lockOperation: LockOperation = mockk(relaxed = true)
    private val pypiProperties = PypiProperties().apply {
        enableSimpleIndexCache = true
        simpleIndexCacheTtl = Duration.ofMinutes(1)
    }
    private lateinit var service: PypiSimpleIndexCacheService

    @BeforeEach
    fun setUp() {
        clearMocks(nodeService, storageManager, lockOperation)
        pypiProperties.enableSimpleIndexCache = true
        pypiProperties.simpleIndexCacheTtl = Duration.ofMinutes(1)
        service = PypiSimpleIndexCacheService(nodeService, storageManager, lockOperation, pypiProperties)
    }

    @Test
    @DisplayName("命中缓存时返回 HTML，不写存储")
    fun loadReturnsCachedHtml() {
        val html = "<html>cached</html>"
        val fullPath = PypiSimpleIndexUtils.packageCacheFullPath("My_Package")
        val node = nodeDetail(fullPath)
        every { nodeService.getNodeDetail(any(), any()) } returns node
        every {
            storageManager.loadFullArtifactInputStream(node, null)
        } returns ArtifactInputStream(html.byteInputStream(), Range.full(html.length.toLong()))

        val result = service.load(PROJECT, REPO, "My_Package", null)

        assertEquals(html, result!!.html)
        assertFalse(result.expired)
        verify(exactly = 0) { storageManager.storeArtifactFile(any(), any(), any()) }
    }

    @Test
    @DisplayName("未命中缓存返回 null")
    fun loadReturnsNullWhenMissing() {
        every { nodeService.getNodeDetail(any(), any()) } returns null

        assertNull(service.load(PROJECT, REPO, "demo", null))
        verify(exactly = 0) { storageManager.loadFullArtifactInputStream(any(), any()) }
    }

    @Test
    @DisplayName("缓存超过 TTL 时仍返回旧 HTML")
    fun loadReturnsHtmlWhenExpired() {
        val html = "<html>stale</html>"
        val fullPath = PypiSimpleIndexUtils.packageCacheFullPath("demo")
        val node = nodeDetail(fullPath, lastModifiedDate = LocalDateTime.now().minusMinutes(2))
        every { nodeService.getNodeDetail(any(), any()) } returns node
        every {
            storageManager.loadFullArtifactInputStream(node, null)
        } returns ArtifactInputStream(html.byteInputStream(), Range.full(html.length.toLong()))

        val result = service.load(PROJECT, REPO, "demo", null)

        assertEquals(html, result!!.html)
        assertTrue(result.expired)
    }

    @Test
    @DisplayName("TTL 小于等于 0 时不过期")
    fun loadIgnoresTtlWhenDisabled() {
        pypiProperties.simpleIndexCacheTtl = Duration.ZERO
        val html = "<html>cached</html>"
        val fullPath = PypiSimpleIndexUtils.packageCacheFullPath("demo")
        val node = nodeDetail(fullPath, lastModifiedDate = LocalDateTime.now().minusDays(30))
        every { nodeService.getNodeDetail(any(), any()) } returns node
        every {
            storageManager.loadFullArtifactInputStream(node, null)
        } returns ArtifactInputStream(html.byteInputStream(), Range.full(html.length.toLong()))

        val result = service.load(PROJECT, REPO, "demo", null)
        assertEquals(html, result!!.html)
        assertFalse(result.expired)
    }

    @Test
    @DisplayName("获锁后按原始包名写入缓存")
    fun tryStoreWritesWhenLockAcquired() {
        val lock = Any()
        val fullPath = PypiSimpleIndexUtils.packageCacheFullPath("My.Package")
        every { lockOperation.getLock(any()) } returns lock
        every { lockOperation.acquireLock(any(), lock) } returns true
        every { lockOperation.close(any(), lock) } returns Unit
        every { nodeService.getNodeDetail(any(), any()) } returns null
        every { storageManager.storeArtifactFile(any(), any(), any()) } returns nodeDetail(fullPath)

        val stored = service.tryStore(PROJECT, REPO, "My.Package", "<html>new</html>", "user", null)

        assertTrue(stored)
        verify {
            storageManager.storeArtifactFile(
                match<NodeCreateRequest> {
                    it.fullPath == fullPath && it.overwrite && !it.folder
                },
                any(),
                null
            )
        }
        verify { lockOperation.close(any(), lock) }
    }

    @Test
    @DisplayName("tryStore 发现缓存节点已存在则不覆盖")
    fun tryStoreSkipsWriteWhenCacheNodeExists() {
        val fullPath = PypiSimpleIndexUtils.packageCacheFullPath("demo")
        val lock = Any()
        every { lockOperation.getLock(any()) } returns lock
        every { lockOperation.acquireLock(any(), lock) } returns true
        every { lockOperation.close(any(), lock) } returns Unit
        every { nodeService.getNodeDetail(any(), any()) } returns nodeDetail(fullPath)

        val stored = service.tryStore(PROJECT, REPO, "demo", "<html>stale</html>", "user", null)

        assertFalse(stored)
        verify(exactly = 0) { storageManager.storeArtifactFile(any(), any(), any()) }
    }

    @Test
    @DisplayName("未获锁时不写缓存")
    fun tryStoreSkipsWriteWhenLockNotAcquired() {
        val lock = Any()
        every { lockOperation.getLock(any()) } returns lock
        every { lockOperation.acquireLock(any(), lock) } returns false

        val stored = service.tryStore(PROJECT, REPO, "demo", "<html>x</html>", "user", null)

        assertFalse(stored)
        verify(exactly = 0) { storageManager.storeArtifactFile(any(), any(), any()) }
        verify(exactly = 0) { lockOperation.close(any(), any()) }
    }

    @Test
    @DisplayName("锁异常时不写缓存")
    fun tryStoreSkipsWriteWhenLockFails() {
        every { lockOperation.getLock(any()) } throws RuntimeException("redis down")

        val stored = service.tryStore(PROJECT, REPO, "demo", "<html>x</html>", "user", null)

        assertFalse(stored)
        verify(exactly = 0) { storageManager.storeArtifactFile(any(), any(), any()) }
    }

    @Test
    @DisplayName("tryRefresh 获锁且过期时重建并写入")
    fun tryRefreshStoresWhenLockAcquiredAndExpired() {
        val stale = "<html>stale</html>"
        val html = "<html>new</html>"
        val fullPath = PypiSimpleIndexUtils.packageCacheFullPath("demo")
        val lock = Any()
        val expiredNode = nodeDetail(fullPath, lastModifiedDate = LocalDateTime.now().minusMinutes(2))
        every { lockOperation.getLock(any()) } returns lock
        every { lockOperation.acquireLock(any(), lock) } returns true
        every { lockOperation.close(any(), lock) } returns Unit
        every { nodeService.getNodeDetail(any(), any()) } returns expiredNode
        every {
            storageManager.loadFullArtifactInputStream(expiredNode, null)
        } returns ArtifactInputStream(stale.byteInputStream(), Range.full(stale.length.toLong()))
        every { storageManager.storeArtifactFile(any(), any(), any()) } returns nodeDetail(fullPath)

        val result = service.tryRefresh(PROJECT, REPO, "demo", "user", null) { html }

        assertEquals(html, result)
        verify {
            storageManager.storeArtifactFile(
                match<NodeCreateRequest> { it.fullPath == fullPath && it.overwrite },
                any(),
                null
            )
        }
    }

    @Test
    @DisplayName("tryRefresh 未获锁时不重建不写缓存")
    fun tryRefreshReturnsNullWhenLockNotAcquired() {
        val lock = Any()
        every { lockOperation.getLock(any()) } returns lock
        every { lockOperation.acquireLock(any(), lock) } returns false
        var rebuilt = 0

        val result = service.tryRefresh(PROJECT, REPO, "demo", "user", null) {
            rebuilt++
            "<html>new</html>"
        }

        assertNull(result)
        assertEquals(0, rebuilt)
        verify(exactly = 0) { storageManager.storeArtifactFile(any(), any(), any()) }
    }

    @Test
    @DisplayName("tryRefresh 获锁后发现未过期则直接返回，不重建")
    fun tryRefreshReturnsCurrentWhenNotExpiredAfterLock() {
        val html = "<html>fresh</html>"
        val fullPath = PypiSimpleIndexUtils.packageCacheFullPath("demo")
        val lock = Any()
        val node = nodeDetail(fullPath)
        every { lockOperation.getLock(any()) } returns lock
        every { lockOperation.acquireLock(any(), lock) } returns true
        every { lockOperation.close(any(), lock) } returns Unit
        every { nodeService.getNodeDetail(any(), any()) } returns node
        every {
            storageManager.loadFullArtifactInputStream(node, null)
        } returns ArtifactInputStream(html.byteInputStream(), Range.full(html.length.toLong()))
        var rebuilt = 0

        val result = service.tryRefresh(PROJECT, REPO, "demo", "user", null) {
            rebuilt++
            "<html>new</html>"
        }

        assertEquals(html, result)
        assertEquals(0, rebuilt)
        verify(exactly = 0) { storageManager.storeArtifactFile(any(), any(), any()) }
    }

    @Test
    @DisplayName("tryRefresh 重建期间 lastModified 变化则不覆盖写")
    fun tryRefreshSkipsStoreWhenLastModifiedChanged() {
        val stale = "<html>stale</html>"
        val uploaded = "<html>uploaded</html>"
        val fullPath = PypiSimpleIndexUtils.packageCacheFullPath("demo")
        val lock = Any()
        val expiredNode = nodeDetail(fullPath, lastModifiedDate = LocalDateTime.now().minusMinutes(2))
        val uploadedNode = nodeDetail(fullPath, lastModifiedDate = LocalDateTime.now())
        var lookups = 0
        every { lockOperation.getLock(any()) } returns lock
        every { lockOperation.acquireLock(any(), lock) } returns true
        every { lockOperation.close(any(), lock) } returns Unit
        every { nodeService.getNodeDetail(any(), any()) } answers {
            lookups++
            if (lookups == 1) expiredNode else uploadedNode
        }
        every {
            storageManager.loadFullArtifactInputStream(expiredNode, null)
        } returns ArtifactInputStream(stale.byteInputStream(), Range.full(stale.length.toLong()))
        every {
            storageManager.loadFullArtifactInputStream(uploadedNode, null)
        } returns ArtifactInputStream(uploaded.byteInputStream(), Range.full(uploaded.length.toLong()))

        val result = service.tryRefresh(PROJECT, REPO, "demo", "user", null) { "<html>rebuilt</html>" }

        assertEquals(uploaded, result)
        verify(exactly = 0) { storageManager.storeArtifactFile(any(), any(), any()) }
    }

    @Test
    @DisplayName("tryRefresh 重建期间缓存被删则不写回")
    fun tryRefreshSkipsStoreWhenNodeDeletedDuringRebuild() {
        val stale = "<html>stale</html>"
        val rebuilt = "<html>rebuilt</html>"
        val fullPath = PypiSimpleIndexUtils.packageCacheFullPath("demo")
        val lock = Any()
        val expiredNode = nodeDetail(fullPath, lastModifiedDate = LocalDateTime.now().minusMinutes(2))
        var lookups = 0
        every { lockOperation.getLock(any()) } returns lock
        every { lockOperation.acquireLock(any(), lock) } returns true
        every { lockOperation.close(any(), lock) } returns Unit
        every { nodeService.getNodeDetail(any(), any()) } answers {
            lookups++
            if (lookups == 1) expiredNode else null
        }
        every {
            storageManager.loadFullArtifactInputStream(expiredNode, null)
        } returns ArtifactInputStream(stale.byteInputStream(), Range.full(stale.length.toLong()))

        val result = service.tryRefresh(PROJECT, REPO, "demo", "user", null) { rebuilt }

        assertEquals(rebuilt, result)
        verify(exactly = 0) { storageManager.storeArtifactFile(any(), any(), any()) }
    }

    @Test
    @DisplayName("tryRefresh 包不存在时删除缓存并抛 NotFound")
    fun tryRefreshInvalidatesWhenRebuildNotFound() {
        val stale = "<html>stale</html>"
        val fullPath = PypiSimpleIndexUtils.packageCacheFullPath("demo")
        val lock = Any()
        val expiredNode = nodeDetail(fullPath, lastModifiedDate = LocalDateTime.now().minusMinutes(2))
        every { lockOperation.getLock(any()) } returns lock
        every { lockOperation.acquireLock(any(), lock) } returns true
        every { lockOperation.close(any(), lock) } returns Unit
        every { nodeService.getNodeDetail(any(), any()) } returns expiredNode
        every { nodeService.deleteNode(any()) } returns mockk(relaxed = true)
        every {
            storageManager.loadFullArtifactInputStream(expiredNode, null)
        } returns ArtifactInputStream(stale.byteInputStream(), Range.full(stale.length.toLong()))

        assertThrows(PypiSimpleNotFoundException::class.java) {
            service.tryRefresh(PROJECT, REPO, "demo", "user", null) {
                throw PypiSimpleNotFoundException("demo")
            }
        }
        verify {
            nodeService.deleteNode(match<NodeDeleteRequest> { it.fullPath == fullPath })
        }
        verify(exactly = 0) { storageManager.storeArtifactFile(any(), any(), any()) }
    }

    @Test
    @DisplayName("overwrite 不抢锁，listing 后直接写")
    fun overwriteWritesWithoutLock() {
        val fullPath = PypiSimpleIndexUtils.packageCacheFullPath("demo")
        every { storageManager.storeArtifactFile(any(), any(), any()) } returns nodeDetail(fullPath)

        service.overwrite(PROJECT, REPO, "demo", "user", null) { "<html>new</html>" }

        verify {
            storageManager.storeArtifactFile(
                match<NodeCreateRequest> { it.fullPath == fullPath && it.overwrite },
                any(),
                null
            )
        }
        verify(exactly = 0) { lockOperation.getLock(any()) }
        verify(exactly = 0) { lockOperation.acquireLock(any(), any()) }
    }

    @Test
    @DisplayName("invalidate 按原始包名删除缓存")
    fun invalidateDeletesCachePath() {
        val fullPath = PypiSimpleIndexUtils.packageCacheFullPath("My_Package")
        every { nodeService.deleteNode(any()) } returns mockk(relaxed = true)

        service.invalidate(PROJECT, REPO, "My_Package")

        verify {
            nodeService.deleteNode(
                match<NodeDeleteRequest> { it.fullPath == fullPath && it.projectId == PROJECT && it.repoName == REPO }
            )
        }
    }

    @Test
    @DisplayName("invalidate 失败不抛异常")
    fun invalidateSwallowsErrors() {
        every { nodeService.deleteNode(any()) } throws RuntimeException("storage error")

        service.invalidate(PROJECT, REPO, "demo")
    }

    @Test
    @DisplayName("load/store/invalidate 对同一原始包名使用相同路径")
    fun loadStoreInvalidateShareSameRawPackagePath() {
        val packageName = "My_Package"
        val fullPath = PypiSimpleIndexUtils.packageCacheFullPath(packageName)
        val lock = Any()
        every { nodeService.getNodeDetail(any(), any()) } returns null
        every { lockOperation.getLock(any()) } returns lock
        every { lockOperation.acquireLock(any(), lock) } returns true
        every { lockOperation.close(any(), lock) } returns Unit
        every { storageManager.storeArtifactFile(any(), any(), any()) } returns nodeDetail(fullPath)
        every { nodeService.deleteNode(any()) } returns mockk(relaxed = true)

        assertNull(service.load(PROJECT, REPO, packageName, null))
        assertTrue(service.tryStore(PROJECT, REPO, packageName, "<html>old</html>", "user", null))
        service.invalidate(PROJECT, REPO, packageName)

        verify {
            nodeService.getNodeDetail(match { it.getArtifactFullPath() == fullPath }, any())
            storageManager.storeArtifactFile(
                match<NodeCreateRequest> { it.fullPath == fullPath },
                any(),
                null
            )
            nodeService.deleteNode(match<NodeDeleteRequest> { it.fullPath == fullPath })
        }
    }

    @Test
    @DisplayName("invalidate 后旧 HTML 仍可被 tryStore 写回，过期后 load 仍返回内容")
    fun staleStoreAfterInvalidateExpiresByTtl() {
        val packageName = "demo"
        val html = "<html>stale</html>"
        val fullPath = PypiSimpleIndexUtils.packageCacheFullPath(packageName)
        val lock = Any()
        every { lockOperation.getLock(any()) } returns lock
        every { lockOperation.acquireLock(any(), lock) } returns true
        every { lockOperation.close(any(), lock) } returns Unit
        every { nodeService.getNodeDetail(any(), any()) } returns null
        every { storageManager.storeArtifactFile(any(), any(), any()) } returns nodeDetail(fullPath)
        every { nodeService.deleteNode(any()) } returns mockk(relaxed = true)

        service.invalidate(PROJECT, REPO, packageName)
        assertTrue(service.tryStore(PROJECT, REPO, packageName, html, "user", null))

        val expiredNode = nodeDetail(fullPath, lastModifiedDate = LocalDateTime.now().minusMinutes(2))
        every { nodeService.getNodeDetail(any(), any()) } returns expiredNode
        every {
            storageManager.loadFullArtifactInputStream(expiredNode, null)
        } returns ArtifactInputStream(html.byteInputStream(), Range.full(html.length.toLong()))

        val result = service.load(PROJECT, REPO, packageName, null)
        assertEquals(html, result!!.html)
        assertTrue(result.expired)
    }

    @Test
    @DisplayName("缓存路径使用原始包名")
    fun packageCacheFullPathUsesRawName() {
        assertEquals(
            "/.pypi-simple-index/packages/My_Package.html",
            PypiSimpleIndexUtils.packageCacheFullPath("My_Package")
        )
        assertEquals(
            "/.pypi-simple-index/packages/My.Package.html",
            PypiSimpleIndexUtils.packageCacheFullPath("My.Package")
        )
        assertTrue(PypiSimpleIndexUtils.isSimpleIndexCacheFolder(".pypi-simple-index"))
        assertFalse(PypiSimpleIndexUtils.isSimpleIndexCacheFolder("requests"))
    }

    private fun nodeDetail(
        fullPath: String,
        lastModifiedDate: LocalDateTime = LocalDateTime.now(),
    ): NodeDetail {
        val name = fullPath.substringAfterLast('/')
        val path = fullPath.substringBeforeLast('/') + "/"
        val info = NodeInfo(
            createdBy = "system",
            createdDate = lastModifiedDate.toString(),
            lastModifiedBy = "system",
            lastModifiedDate = lastModifiedDate.toString(),
            folder = false,
            path = path,
            name = name,
            fullPath = fullPath,
            size = 10,
            projectId = PROJECT,
            repoName = REPO,
            sha256 = "a".repeat(64),
        )
        return NodeDetail(info)
    }

    companion object {
        private const val PROJECT = "p-test"
        private const val REPO = "pypi-local"
    }
}
