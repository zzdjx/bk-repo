package com.tencent.bkrepo.preview.service.share

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("分享 downloadToken 绑定路径")
class ArtifactShareDownloadTokenPathTest {

    @Test
    fun `shallow parent stays on the file path`() {
        assertEquals("/docs/hero.png", ArtifactShareDownloadTokenPath.resolve("/docs/hero.png", MIN_PARENT_DEPTH))
    }

    @Test
    fun `root file stays on the file path`() {
        assertEquals("/hero.png", ArtifactShareDownloadTokenPath.resolve("/hero.png", MIN_PARENT_DEPTH))
    }

    @Test
    fun `deep artifact version directory binds parent`() {
        assertEquals(
            "/artifact/agent-ins-9226e33e7d8730d4/default/PSD拆图层场景PPT规划/v1/",
            ArtifactShareDownloadTokenPath.resolve(ARTIFACT_FILE, MIN_PARENT_DEPTH),
        )
    }

    @Test
    fun `parent at threshold stays on the file path`() {
        assertEquals(ARTIFACT_FILE, ArtifactShareDownloadTokenPath.resolve(ARTIFACT_FILE, 5))
    }

    @Test
    fun `zero min depth binds any non-root parent`() {
        assertEquals("/docs/", ArtifactShareDownloadTokenPath.resolve("/docs/hero.png", 0))
    }

    companion object {
        private const val MIN_PARENT_DEPTH = 4
        private const val ARTIFACT_FILE =
            "/artifact/agent-ins-9226e33e7d8730d4/default/PSD拆图层场景PPT规划/v1/.PSD拆图层场景PPT规划_v1.md"
    }
}
