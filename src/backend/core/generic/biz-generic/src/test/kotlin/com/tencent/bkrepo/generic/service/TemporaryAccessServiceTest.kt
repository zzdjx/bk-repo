package com.tencent.bkrepo.generic.service

import com.tencent.bkrepo.auth.api.ServiceTemporaryTokenClient
import com.tencent.bkrepo.auth.pojo.token.TemporaryTokenInfo
import com.tencent.bkrepo.auth.pojo.token.TokenType
import com.tencent.bkrepo.common.api.exception.ErrorCodeException
import com.tencent.bkrepo.common.artifact.api.ArtifactFile
import com.tencent.bkrepo.common.artifact.constant.REPO_KEY
import com.tencent.bkrepo.common.metadata.permission.PermissionManager
import com.tencent.bkrepo.common.metadata.service.repo.RepositoryService
import com.tencent.bkrepo.common.storage.core.StorageService
import com.tencent.bkrepo.generic.UT_PROJECT_ID
import com.tencent.bkrepo.generic.UT_REPO_NAME
import com.tencent.bkrepo.generic.UT_USER
import com.tencent.bkrepo.generic.artifact.GenericArtifactInfo
import com.tencent.bkrepo.generic.config.GenericProperties
import com.tencent.bkrepo.repository.pojo.repo.RepositoryDetail
import com.tencent.devops.plugin.api.PluginManager
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.web.context.request.RequestContextHolder
import org.springframework.web.context.request.ServletRequestAttributes
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter
import java.net.URLEncoder

class TemporaryAccessServiceTest {

    private val temporaryTokenClient: ServiceTemporaryTokenClient = mock()
    private val repositoryService: RepositoryService = mock()
    private val pluginManager: PluginManager = mock()
    private val deltaSyncService: DeltaSyncService = mock()
    private val permissionManager: PermissionManager = mock()
    private val storageService: StorageService = mock()
    private val artifactFile: ArtifactFile = mock()
    private val request: HttpServletRequest = mock()
    private val repoDetail: RepositoryDetail = mock()

    private val service = TemporaryAccessService(
        temporaryTokenClient,
        repositoryService,
        GenericProperties(),
        pluginManager,
        deltaSyncService,
        permissionManager,
        storageService,
    )

    @BeforeEach
    fun setUp() {
        whenever(repositoryService.getRepoDetail(UT_PROJECT_ID, UT_REPO_NAME)).thenReturn(repoDetail)
        whenever(deltaSyncService.patch(any(), any())).thenReturn(SseEmitter())
        RequestContextHolder.setRequestAttributes(
            ServletRequestAttributes(request, mock<HttpServletResponse>())
        )
    }

    @AfterEach
    fun tearDown() {
        RequestContextHolder.resetRequestAttributes()
    }

    @Test
    fun `patch should reject path outside grant`() {
        val token = tokenInfo(fullPath = "/allowed/app.apk")
        val dest = GenericArtifactInfo(UT_PROJECT_ID, UT_REPO_NAME, "/allowed/app.apk")

        assertThrows<ErrorCodeException> {
            service.patch(dest, "/other/file", artifactFile, token)
        }
        verify(deltaSyncService, never()).patch(any(), any())
    }

    @Test
    fun `patch should reject neighboring prefix path`() {
        val token = tokenInfo(fullPath = "/data/report")
        val dest = GenericArtifactInfo(UT_PROJECT_ID, UT_REPO_NAME, "/data/report/a.pdf")

        assertThrows<ErrorCodeException> {
            service.patch(dest, "/data/report-extra/file.txt", artifactFile, token)
        }
        verify(deltaSyncService, never()).patch(any(), any())
    }

    @Test
    fun `patch should reject relative parent path`() {
        val token = tokenInfo(fullPath = "/allowed/dir")
        val dest = GenericArtifactInfo(UT_PROJECT_ID, UT_REPO_NAME, "/allowed/dir/app.apk")

        assertThrows<ErrorCodeException> {
            service.patch(dest, "/allowed/dir/../../other/file", artifactFile, token)
        }
        verify(deltaSyncService, never()).patch(any(), any())
    }

    @Test
    fun `patch should reject encoded path outside grant`() {
        val token = tokenInfo(fullPath = "/allowed/app.apk")
        val dest = GenericArtifactInfo(UT_PROJECT_ID, UT_REPO_NAME, "/allowed/app.apk")
        val encoded = URLEncoder.encode("/other/file", Charsets.UTF_8)

        assertThrows<ErrorCodeException> {
            service.patch(dest, encoded, artifactFile, token)
        }
        verify(deltaSyncService, never()).patch(any(), any())
    }

    @Test
    fun `patch should reject encoded parent path`() {
        val token = tokenInfo(fullPath = "/allowed")
        val dest = GenericArtifactInfo(UT_PROJECT_ID, UT_REPO_NAME, "/allowed/app.apk")

        assertThrows<ErrorCodeException> {
            service.patch(dest, "%2Fallowed%2F%2e%2e%2Fsecret", artifactFile, token)
        }
        verify(deltaSyncService, never()).patch(any(), any())
    }

    @Test
    fun `patch should pass through double-encoded dots as filename`() {
        val token = tokenInfo(fullPath = "/allowed")
        val dest = GenericArtifactInfo(UT_PROJECT_ID, UT_REPO_NAME, "/allowed/app.apk")
        val doubleEncoded = "%2Fallowed%2F%252e%252e%2Fsecret"

        service.patch(dest, doubleEncoded, artifactFile, token)

        verify(deltaSyncService).patch(eq(doubleEncoded), eq(artifactFile))
    }

    @Test
    fun `patch should decode plus as space like client URLEncoder`() {
        val token = tokenInfo(fullPath = "/allowed/foo bar.apk")
        val dest = GenericArtifactInfo(UT_PROJECT_ID, UT_REPO_NAME, "/allowed/foo bar.apk")
        val header = "/allowed/foo+bar.apk"

        service.patch(dest, header, artifactFile, token)

        verify(deltaSyncService).patch(eq(header), eq(artifactFile))
    }

    @Test
    fun `patch should reject plus header when grant is literal plus`() {
        val token = tokenInfo(fullPath = "/allowed/foo+bar.apk")
        val dest = GenericArtifactInfo(UT_PROJECT_ID, UT_REPO_NAME, "/allowed/foo+bar.apk")

        assertThrows<ErrorCodeException> {
            service.patch(dest, "/allowed/foo+bar.apk", artifactFile, token)
        }
        verify(deltaSyncService, never()).patch(any(), any())
    }

    @Test
    fun `patch should allow encoded plus filename`() {
        val token = tokenInfo(fullPath = "/allowed/foo+bar.apk")
        val dest = GenericArtifactInfo(UT_PROJECT_ID, UT_REPO_NAME, "/allowed/foo+bar.apk")
        val header = "/allowed/foo%2Bbar.apk"

        service.patch(dest, header, artifactFile, token)

        verify(deltaSyncService).patch(eq(header), eq(artifactFile))
    }

    @Test
    fun `patch should allow path under grant`() {
        val token = tokenInfo(fullPath = "/allowed")
        val dest = GenericArtifactInfo(UT_PROJECT_ID, UT_REPO_NAME, "/allowed/app.apk")

        service.patch(dest, "/allowed/old.apk", artifactFile, token)

        verify(deltaSyncService).patch(eq("/allowed/old.apk"), eq(artifactFile))
        verify(request).setAttribute(eq(REPO_KEY), eq(repoDetail))
    }

    @Test
    fun `patch should allow same path`() {
        val token = tokenInfo(fullPath = "/allowed/app.apk")
        val dest = GenericArtifactInfo(UT_PROJECT_ID, UT_REPO_NAME, "/allowed/app.apk")

        service.patch(dest, "/allowed/app.apk", artifactFile, token)

        verify(deltaSyncService).patch(eq("/allowed/app.apk"), eq(artifactFile))
    }

    private fun tokenInfo(fullPath: String) = TemporaryTokenInfo(
        projectId = UT_PROJECT_ID,
        repoName = UT_REPO_NAME,
        fullPath = fullPath,
        token = "token",
        authorizedUserList = emptySet(),
        authorizedIpList = emptySet(),
        expireDate = null,
        permits = null,
        type = TokenType.UPLOAD,
        createdBy = UT_USER,
    )
}
