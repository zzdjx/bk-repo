package com.tencent.bkrepo.huggingface.util

import com.tencent.bkrepo.huggingface.constants.REPO_TYPE_DATASET
import com.tencent.bkrepo.huggingface.constants.REPO_TYPE_MODEL
import com.tencent.bkrepo.huggingface.exception.OperationNotSupportException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("HuggingFace Hub URL")
class HfHubUrlsTest {

    @Test
    fun `model path omits singular type prefix for huggingface_hub parse_hf_uri`() {
        val path = HfHubUrls.repoUrlPath(REPO_TYPE_MODEL, "org/model")

        assertEquals("org/model", path)
        assertFalse(path.startsWith("model/"))
    }

    @Test
    fun `dataset path uses plural datasets prefix`() {
        assertEquals("datasets/org/data", HfHubUrls.repoUrlPath(REPO_TYPE_DATASET, "org/data"))
    }

    @Test
    fun `model commit url can be split on commit for huggingface_hub CommitInfo`() {
        val url = HfHubUrls.commitUrl(REPO_TYPE_MODEL, "org/model", "abc123")

        assertEquals("org/model/commit/abc123", url)
        assertEquals("org/model", url.split("/commit/")[0])
        assertFalse(url.startsWith("model/"))
    }

    @Test
    fun `dataset commit url keeps plural prefix after split`() {
        val url = HfHubUrls.commitUrl(REPO_TYPE_DATASET, "org/data", "def456")

        assertEquals("datasets/org/data/commit/def456", url)
        assertEquals("datasets/org/data", url.split("/commit/")[0])
    }

    @Test
    fun `space and unknown types are rejected`() {
        assertThrows(OperationNotSupportException::class.java) {
            HfHubUrls.repoUrlPath("space", "org/app")
        }
        assertThrows(OperationNotSupportException::class.java) {
            HfHubUrls.commitUrl("space", "org/app", "abc123")
        }
        assertThrows(OperationNotSupportException::class.java) {
            HfHubUrls.repoUrlPath("invalid", "org/x")
        }
    }
}
