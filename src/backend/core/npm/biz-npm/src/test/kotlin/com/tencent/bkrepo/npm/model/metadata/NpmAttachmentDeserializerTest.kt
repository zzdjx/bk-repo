package com.tencent.bkrepo.npm.model.metadata

import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.core.StreamReadConstraints
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.tencent.bkrepo.common.api.util.JsonUtils
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64

@DisplayName("npm attachment 流式反序列化")
class NpmAttachmentDeserializerTest {

    @AfterEach
    fun cleanupTrackedFiles() {
        NpmAttachmentDeserializer.cleanupTrackedFiles()
    }

    @Test
    fun `attachments data longer than maxStringLength is streamed to disk`() {
        val payload = ByteArray(80) { it.toByte() }
        val base64 = Base64.getEncoder().encodeToString(payload)
        assertTrue(base64.length > MAX_STRING_LENGTH)

        val json = """
            {
              "name": "demo",
              "readme": "ok",
              "_attachments": {
                "demo-1.0.0.tgz": {
                  "content_type": "application/octet-stream",
                  "data": "$base64",
                  "length": ${payload.size}
                }
              }
            }
        """.trimIndent()
        NpmAttachmentDeserializer.withCleanup(
            parse = { constrainedMapper().readValue(json, NpmPackageMetaData::class.java) }
        ) { meta ->
            val attachment = meta.attachments!!.getMap()["demo-1.0.0.tgz"]!!
            val artifactFile = attachment.artifactFile
            requireNotNull(artifactFile)
            assertEquals("application/octet-stream", attachment.contentType)
            assertEquals(payload.size, attachment.length)
            assertEquals(payload.size.toLong(), artifactFile.getSize())
            assertArrayEquals(payload, artifactFile.getInputStream().use { it.readBytes() })
        }
    }

    @Test
    fun `readme longer than maxStringLength is still rejected`() {
        val json = """{"name":"demo","readme":"${"a".repeat(MAX_STRING_LENGTH + 1)}"}"""
        assertThrows<JsonProcessingException> {
            constrainedMapper().readValue(json, NpmPackageMetaData::class.java)
        }
    }

    @Test
    fun `package metadata without attachments can be parsed`() {
        val json = """{"name":"demo","readme":"hello"}"""
        val meta = JsonUtils.objectMapper.readValue(json, NpmPackageMetaData::class.java)
        assertEquals("demo", meta.name)
        assertEquals("hello", meta.readme)
        assertNull(meta.attachments)
    }

    @Test
    fun `attachment binary is not written back to package metadata json`() {
        val payload = "tgz".toByteArray()
        val base64 = Base64.getEncoder().encodeToString(payload)
        val json = """
            {
              "name": "demo",
              "_attachments": {
                "demo-1.0.0.tgz": {
                  "content_type": "application/octet-stream",
                  "data": "$base64",
                  "length": ${payload.size}
                }
              }
            }
        """.trimIndent()
        val meta = JsonUtils.objectMapper.readValue(json, NpmPackageMetaData::class.java)
        assertNull(meta.attachments!!.getMap().values.single().artifactFile)
        val serialized = JsonUtils.objectMapper.writeValueAsString(meta)
        assertFalse(serialized.contains(base64))
        assertFalse(serialized.contains("artifactFile"))
    }

    @Test
    fun `read path discards attachment data without creating temp files`() {
        val payload = ByteArray(80) { it.toByte() }
        val base64 = Base64.getEncoder().encodeToString(payload)
        assertTrue(base64.length > MAX_STRING_LENGTH)
        val json = """
            {
              "name": "demo",
              "_attachments": {
                "demo-1.0.0.tgz": {
                  "content_type": "application/octet-stream",
                  "data": "$base64",
                  "length": ${payload.size}
                }
              }
            }
        """.trimIndent()
        val tmp = Path.of(System.getProperty("java.io.tmpdir"))
        val before = countNpmAttachmentTempFiles(tmp)
        val meta = constrainedMapper().readValue(json, NpmPackageMetaData::class.java)
        val attachment = meta.attachments!!.getMap()["demo-1.0.0.tgz"]!!
        assertEquals("application/octet-stream", attachment.contentType)
        assertEquals(payload.size, attachment.length)
        assertNull(attachment.artifactFile)
        assertEquals(before, countNpmAttachmentTempFiles(tmp))
    }

    @Test
    fun `withCleanup deletes streamed files when subsequent logic fails`() {
        val payload = "tgz".toByteArray()
        val base64 = Base64.getEncoder().encodeToString(payload)
        val json = """
            {
              "name": "demo",
              "_attachments": {
                "demo-1.0.0.tgz": {
                  "content_type": "application/octet-stream",
                  "data": "$base64",
                  "length": ${payload.size}
                }
              }
            }
        """.trimIndent()
        var filePath: Path? = null
        assertThrows<IllegalStateException> {
            NpmAttachmentDeserializer.withCleanup(
                parse = { JsonUtils.objectMapper.readValue(json, NpmPackageMetaData::class.java) }
            ) { meta ->
                val artifactFile = meta.attachments?.getMap()?.values?.single()?.artifactFile
                requireNotNull(artifactFile)
                val path = artifactFile.flushToFile().toPath()
                filePath = path
                assertTrue(Files.exists(path))
                throw IllegalStateException("publish failed")
            }
        }
        val deletedPath = filePath
        requireNotNull(deletedPath)
        assertFalse(Files.exists(deletedPath))
    }

    private fun constrainedMapper() = jacksonObjectMapper().apply {
        disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
        factory.setStreamReadConstraints(
            StreamReadConstraints.builder().maxStringLength(MAX_STRING_LENGTH).build()
        )
    }

    private fun countNpmAttachmentTempFiles(tmp: Path): Long {
        if (!Files.isDirectory(tmp)) {
            return 0
        }
        return Files.list(tmp).use { paths ->
            paths.filter { it.fileName.toString().startsWith("npm-attachment-") }.count()
        }
    }

    companion object {
        private const val MAX_STRING_LENGTH = 50
    }
}
