package com.tencent.bkrepo.rpm.job

import com.tencent.bkrepo.common.artifact.manager.StorageManager
import com.tencent.bkrepo.common.metadata.service.node.NodeSearchService
import com.tencent.bkrepo.common.metadata.service.node.NodeService
import com.tencent.bkrepo.common.metadata.service.repo.RepositoryService
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import org.mockito.Mockito.mock
import org.xml.sax.SAXException
import java.io.File

@DisplayName("RPM 索引 XML 校验")
class JobServiceTest {

    private val jobService = JobService(
        mock(NodeService::class.java),
        mock(NodeSearchService::class.java),
        mock(RepositoryService::class.java),
        mock(StorageManager::class.java)
    )

    @TempDir
    private lateinit var tempDir: File

    private lateinit var xmlFile: File

    @BeforeEach
    fun setUp() {
        xmlFile = File(tempDir, "index.xml")
    }

    @Test
    fun `normal primary index without doctype should pass`() {
        xmlFile.writeText(PRIMARY_XML)
        assertDoesNotThrow { jobService.checkValid(xmlFile) }
    }

    @Test
    fun `malformed xml should fail`() {
        xmlFile.writeText("<metadata>")
        assertThrows<SAXException> { jobService.checkValid(xmlFile) }
    }

    @Test
    fun `doctype should be rejected`() {
        xmlFile.writeText(
            "<?xml version=\"1.0\" encoding=\"UTF-8\" ?>\n" +
                "<!DOCTYPE metadata>\n" +
                "<metadata xmlns=\"http://linux.duke.edu/metadata/common\" " +
                "xmlns:rpm=\"http://linux.duke.edu/metadata/rpm\" packages=\"0\">\n" +
                "</metadata>"
        )
        val exception = assertThrows<SAXException> { jobService.checkValid(xmlFile) }
        assertTrue(exception.message.orEmpty().contains("DOCTYPE"))
    }

    companion object {
        private const val PRIMARY_XML =
            "<?xml version=\"1.0\" encoding=\"UTF-8\" ?>\n" +
                "<metadata xmlns=\"http://linux.duke.edu/metadata/common\" " +
                "xmlns:rpm=\"http://linux.duke.edu/metadata/rpm\" packages=\"0\">\n" +
                "</metadata>"
    }
}
