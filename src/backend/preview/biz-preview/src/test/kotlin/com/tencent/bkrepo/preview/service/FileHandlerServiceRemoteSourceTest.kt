package com.tencent.bkrepo.preview.service

import com.tencent.bkrepo.common.artifact.api.ArtifactInfo
import com.tencent.bkrepo.preview.config.configuration.PreviewConfig
import com.tencent.bkrepo.preview.constant.PreviewMessageCode
import com.tencent.bkrepo.preview.exception.PreviewInvalidException
import io.mockk.every
import io.mockk.mockk
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

@DisplayName("远程预览不走仓库节点")
class FileHandlerServiceRemoteSourceTest {

    @TempDir
    lateinit var workspace: Path

    private val service by lazy {
        val config = mockk<PreviewConfig>()
        every { config.fileDir } returns workspace.toAbsolutePath().toString()
        FileHandlerService(config, mockk<CommonResourceService>(relaxed = true), mockk())
    }

    @Test
    fun `repo coordinates in extraParam do not select bkrepo storage`() {
        val extraParam = """
            {"projectId":"p1","repoName":"generic-local","artifactUri":"/talk/deck.pptx",
            "url":"https://example.com/deck.pptx","fileName":"deck.pptx"}
        """.trimIndent()
        val attribute = service.getFileAttribute(extraParam)
        assertEquals(1, attribute.storageType)
        assertNull(attribute.projectId)
        assertNull(attribute.repoName)
        assertNull(attribute.artifactUri)
        assertEquals("https://example.com/deck.pptx", attribute.url)
    }

    @Test
    fun `repo coordinates without url are rejected`() {
        val extraParam = """
            {"projectId":"p1","repoName":"generic-local",
            "artifactUri":"/talk/deck.pptx","fileName":"deck.pptx"}
        """.trimIndent()
        val exception = assertThrows(PreviewInvalidException::class.java) {
            service.getFileAttribute(extraParam)
        }
        assertEquals(PreviewMessageCode.PREVIEW_PARAMETER_INVALID, exception.messageCode)
    }

    @Test
    fun `path based preview still uses bkrepo storage`() {
        val artifactInfo = ArtifactInfo("p1", "generic-local", "/talk/deck.pptx")
        val attribute = service.getFileAttribute(
            artifactInfo,
            """{"url":"https://example.com/deck.pptx","fileName":"deck.pptx"}"""
        )
        assertEquals(0, attribute.storageType)
        assertEquals("p1", attribute.projectId)
        assertEquals("generic-local", attribute.repoName)
        assertEquals("/talk/deck.pptx", attribute.artifactUri)
    }

    @Test
    fun `remote preview cannot stream zip parts from repo`() {
        val attribute = service.getFileAttribute(
            """{"url":"https://example.com/deck.pptx","fileName":"deck.pptx"}"""
        )
        val exception = assertThrows(PreviewInvalidException::class.java) {
            service.applyEmbeddedPartQuery(attribute, "ppt/media/media1.mp4")
        }
        assertEquals(PreviewMessageCode.PREVIEW_PARAMETER_INVALID, exception.messageCode)
    }
}
