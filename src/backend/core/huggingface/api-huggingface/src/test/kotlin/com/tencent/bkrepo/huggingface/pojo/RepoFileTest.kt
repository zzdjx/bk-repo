package com.tencent.bkrepo.huggingface.pojo

import com.fasterxml.jackson.module.kotlin.readValue
import com.tencent.bkrepo.common.api.util.JsonUtils
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("HuggingFace tree 条目 JSON 协议")
class RepoFileTest {

    @Test
    fun `file entry serializes type oid path and size for huggingface_hub`() {
        val json = JsonUtils.objectMapper.writeValueAsString(
            RepoFile(
                path = "README.md",
                size = 123,
                oid = "ae8c63daedbd4206d7d40126955d4e6ab1c80f8f",
                lfs = null,
                lastCommit = null,
                security = null,
            )
        )
        val node = JsonUtils.objectMapper.readTree(json)

        assertEquals("file", node["type"].asText())
        assertEquals("README.md", node["path"].asText())
        assertEquals(123, node["size"].asLong())
        assertEquals("ae8c63daedbd4206d7d40126955d4e6ab1c80f8f", node["oid"].asText())
        assertFalse(node.has("blobId"))
        assertFalse(node.has("lfs"))
        assertFalse(node.has("lastCommit"))
        assertFalse(node.has("security"))
    }

    @Test
    fun `directory entry serializes type oid path and size for huggingface_hub`() {
        val json = JsonUtils.objectMapper.writeValueAsString(
            RepoFolder(
                path = "folder",
                oid = "aa536c4ea18073388b5b0bc791057a7296a00398",
                lastCommit = null,
            )
        )
        val node = JsonUtils.objectMapper.readTree(json)

        assertEquals("directory", node["type"].asText())
        assertEquals("folder", node["path"].asText())
        assertEquals(0, node["size"].asLong())
        assertEquals("aa536c4ea18073388b5b0bc791057a7296a00398", node["oid"].asText())
        assertFalse(node.has("treeId"))
        assertFalse(node.has("lastCommit"))
    }

    @Test
    fun `tree array round-trips as a JSON list huggingface_hub paginate can consume`() {
        val entries = listOf(
            RepoFile(
                path = "README.md",
                size = 123,
                oid = "blob-1",
                lfs = null,
                lastCommit = null,
                security = null,
            ),
            RepoFolder(
                path = "folder",
                oid = "tree-1",
                lastCommit = null,
            ),
        )
        val json = JsonUtils.objectMapper.writeValueAsString(entries)
        val parsed: List<Map<String, Any?>> = JsonUtils.objectMapper.readValue(json)

        assertEquals("file", parsed[0]["type"])
        assertEquals("blob-1", parsed[0]["oid"])
        assertEquals("directory", parsed[1]["type"])
        assertEquals("tree-1", parsed[1]["oid"])
        assertNull(parsed[0]["blobId"])
    }
}
