package com.tencent.bkrepo.preview.service.impl

import com.tencent.bkrepo.preview.constant.PreviewMessageCode
import com.tencent.bkrepo.preview.exception.PreviewSystemException
import io.mockk.every
import io.mockk.mockk
import java.io.ByteArrayInputStream
import org.apache.commons.compress.archivers.ArchiveEntry
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("压缩包条目预览读取")
class CompressFilePreviewImplTest {

    @Test
    fun `readBoundedEntryBytes uses actual size instead of pre-allocating the cap`() {
        val payload = "small-entry".toByteArray()
        val cap = 50 * 1024 * 1024

        val bytes = CompressFilePreviewImpl.readBoundedEntryBytes(ByteArrayInputStream(payload), cap)

        assertEquals(payload.size, bytes.size)
        assertArrayEquals(payload, bytes)
    }

    @Test
    fun `readBoundedEntryBytes rejects content larger than cap`() {
        val payload = ByteArray(16) { 1 }
        val cap = 8

        val exception = assertThrows(PreviewSystemException::class.java) {
            CompressFilePreviewImpl.readBoundedEntryBytes(ByteArrayInputStream(payload), cap)
        }

        assertEquals(PreviewMessageCode.PREVIEW_FILE_SIZE_LIMIT_ERROR, exception.messageCode)
    }

    @Test
    fun `readBoundedEntryBytes accepts content equal to cap`() {
        val payload = ByteArray(8) { 2 }

        val bytes = CompressFilePreviewImpl.readBoundedEntryBytes(ByteArrayInputStream(payload), 8)

        assertArrayEquals(payload, bytes)
    }

    @Test
    fun `declared zip entry size over limit is rejected before copy`() {
        val entry = mockk<ArchiveEntry>()
        every { entry.size } returns 100L * 1024 * 1024

        val exception = assertThrows(PreviewSystemException::class.java) {
            CompressFilePreviewImpl.rejectIfDeclaredSizeTooLarge(entry, 50L * 1024 * 1024)
        }

        assertEquals(PreviewMessageCode.PREVIEW_FILE_SIZE_LIMIT_ERROR, exception.messageCode)
    }

    @Test
    fun `unknown declared size is not rejected by size header`() {
        val entry = mockk<ArchiveEntry>()
        every { entry.size } returns -1L

        CompressFilePreviewImpl.rejectIfDeclaredSizeTooLarge(entry, 50L * 1024 * 1024)
    }
}
