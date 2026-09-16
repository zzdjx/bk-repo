package com.tencent.bkrepo.huggingface.artifact

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("HuggingFace 构件路径")
class HuggingfaceArtifactInfoTest {

    @Test
    fun `tree path_in_repo is resolved under revision root`() {
        val artifactInfo = HuggingfaceArtifactInfo(
            projectId = "p",
            repoName = "r",
            organization = "org",
            name = "model",
            revision = "abc123",
            type = "model",
            artifactUri = "/checkpoints/1",
        )

        assertEquals("/checkpoints/1", artifactInfo.getArtifactName())
        assertEquals("/org/model/resolve/abc123/checkpoints/1", artifactInfo.getArtifactFullPath())
    }

    @Test
    fun `tree root lists the revision directory`() {
        val artifactInfo = HuggingfaceArtifactInfo(
            projectId = "p",
            repoName = "r",
            organization = "org",
            name = "model",
            revision = "abc123",
            type = "model",
            artifactUri = "/",
        )

        assertEquals("/", artifactInfo.getArtifactName())
        assertEquals("/org/model/resolve/abc123/", artifactInfo.getArtifactFullPath())
    }
}
