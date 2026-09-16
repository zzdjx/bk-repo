package com.tencent.bkrepo.huggingface.artifact

import com.tencent.bkrepo.huggingface.pojo.RepoFile
import com.tencent.bkrepo.huggingface.pojo.RepoFolder
import com.tencent.bkrepo.repository.pojo.node.NodeInfo
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("HuggingFace tree 节点转换")
class HfTreeConverterTest {

    @Test
    fun `converts files and folders to repo-relative paths with type and oid`() {
        val nodes = listOf(
            node(folder = false, fullPath = "/org/name/resolve/abc/README.md", size = 123, sha256 = "blob-1"),
            node(folder = true, fullPath = "/org/name/resolve/abc/folder", size = 0, sha256 = "tree-1"),
            node(folder = false, fullPath = "/org/name/resolve/abc/folder/weights.bin", size = 456, sha256 = "blob-2"),
        )

        val entries = HfTreeConverter.convert(nodes, "/org/name/resolve/abc")

        assertEquals(3, entries.size)
        val readme = entries[0] as RepoFile
        assertEquals("file", readme.type)
        assertEquals("README.md", readme.path)
        assertEquals(123, readme.size)
        assertEquals("blob-1", readme.oid)
        val folder = entries[1] as RepoFolder
        assertEquals("directory", folder.type)
        assertEquals("folder", folder.path)
        assertEquals(0, folder.size)
        assertEquals("tree-1", folder.oid)
        val weights = entries[2] as RepoFile
        assertEquals("folder/weights.bin", weights.path)
    }

    @Test
    fun `skips the listed directory itself and nodes outside the revision root`() {
        val nodes = listOf(
            node(folder = true, fullPath = "/org/name/resolve/abc", size = 0, sha256 = "root"),
            node(folder = false, fullPath = "/other/resolve/abc/x.txt", size = 1, sha256 = "outside"),
            node(folder = false, fullPath = "/org/name/resolve/abc/in.txt", size = 2, sha256 = "inside"),
        )

        val entries = HfTreeConverter.convert(nodes, "/org/name/resolve/abc")

        assertEquals(1, entries.size)
        assertTrue(entries[0] is RepoFile)
        assertEquals("in.txt", (entries[0] as RepoFile).path)
    }

    @Test
    fun `falls back to node id when folder has no sha256`() {
        val nodes = listOf(
            node(folder = true, fullPath = "/org/name/resolve/abc/empty", size = 0, sha256 = null, id = "node-1"),
        )

        val entries = HfTreeConverter.convert(nodes, "/org/name/resolve/abc")

        assertEquals("node-1", (entries.single() as RepoFolder).oid)
    }

    @Test
    fun `converts a file path_in_repo to a single file entry`() {
        val entries = HfTreeConverter.convertFile(
            fullPath = "/org/name/resolve/abc/README.md",
            size = 123,
            oid = "blob-1",
            revisionRoot = "/org/name/resolve/abc",
        )

        assertEquals(1, entries.size)
        assertEquals("README.md", (entries.single() as RepoFile).path)
        assertEquals("blob-1", (entries.single() as RepoFile).oid)
        assertEquals(123, (entries.single() as RepoFile).size)
    }

    private fun node(
        folder: Boolean,
        fullPath: String,
        size: Long,
        sha256: String?,
        id: String? = null,
    ): NodeInfo {
        val name = fullPath.substringAfterLast('/')
        return NodeInfo(
            id = id,
            createdBy = "system",
            createdDate = "2026-01-01T00:00:00",
            lastModifiedBy = "system",
            lastModifiedDate = "2026-01-01T00:00:00",
            folder = folder,
            path = fullPath.substringBeforeLast('/') + "/",
            name = name,
            fullPath = fullPath,
            size = size,
            projectId = "p",
            repoName = "r",
            sha256 = sha256,
        )
    }
}
