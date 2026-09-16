package com.tencent.bkrepo.preview.utils

import com.tencent.bkrepo.preview.constant.PreviewMessageCode
import com.tencent.bkrepo.preview.exception.PreviewInvalidException
import java.io.File
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

@DisplayName("预览文件名与落盘路径校验")
class FileUtilsPathTest {

    @TempDir
    lateinit var workspace: Path

    @ParameterizedTest
    @ValueSource(
        strings = [
            "../../../outside/pwned.sh",
            "..\\outside\\pwned.sh",
            "/tmp/pwned.sh",
            "foo/bar.txt",
            "foo\\bar.txt",
            "..",
            ".",
            "foo..bar.txt",
            " pwned.sh",
            "pwned.sh ",
            "C:pwned.sh",
            "%2e%2e%2fpwned.sh"
        ]
    )
    fun `illegal file names are rejected`(fileName: String) {
        assertTrue(FileUtils.isIllegalFileName(fileName))
        val exception = assertThrows(PreviewInvalidException::class.java) {
            FileUtils.requireSafeFileName(fileName)
        }
        assertEquals(PreviewMessageCode.PREVIEW_PARAMETER_INVALID, exception.messageCode)
    }

    @Test
    fun `plain file name is accepted`() {
        assertFalse(FileUtils.isIllegalFileName("readme.txt"))
        FileUtils.requireSafeFileName("readme.txt")
    }

    @Test
    fun `htmlEscape does not neutralize path traversal`() {
        val fileName = "../../../outside/pwned.sh"
        assertEquals(fileName, FileUtils.htmlEscape(fileName))
        assertTrue(FileUtils.isIllegalFileName(FileUtils.htmlEscape(fileName)))
    }

    @Test
    fun `resolveUnderDirectory keeps legal path inside root`() {
        val root = workspace.resolve("preview").toFile().apply { mkdirs() }

        val resolved = FileUtils.resolveUnderDirectory(root.absolutePath, "download", "uuid-1", "readme.txt")

        val resolvedPath = File(resolved).canonicalFile.toPath()
        assertTrue(resolvedPath.startsWith(root.canonicalFile.toPath()))
        assertTrue(resolvedPath.endsWith("readme.txt"))
    }

    @Test
    fun `resolveUnderDirectory rejects parent dir segment`() {
        val root = workspace.resolve("preview").toFile().apply { mkdirs() }

        assertThrows(PreviewInvalidException::class.java) {
            FileUtils.resolveUnderDirectory(root.absolutePath, "download", "..", "outside.txt")
        }
    }
}
