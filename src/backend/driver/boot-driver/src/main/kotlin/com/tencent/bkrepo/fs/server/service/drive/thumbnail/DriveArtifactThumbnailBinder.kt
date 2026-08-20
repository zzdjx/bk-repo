package com.tencent.bkrepo.fs.server.service.drive.thumbnail

import com.tencent.bkrepo.common.metadata.model.TMetadata
import com.tencent.bkrepo.fs.server.config.properties.drive.DriveThumbnailProperties
import com.tencent.bkrepo.fs.server.storage.CoArtifactFile
import com.tencent.bkrepo.fs.server.utils.ArtifactThumbnailTypes
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.io.path.exists

/**
 * 作品缩略图：在源文件被 cache store move 之前渲染 JPEG，入库后再写 sidecar。
 * 失败不影响原文件上传结果。
 */
@Component
class DriveArtifactThumbnailBinder(
    private val generateService: ArtifactThumbnailGenerateService,
    private val properties: DriveThumbnailProperties,
) {

    /**
     * 在源文件被 cache store `move` 走之前渲染 JPEG。成功返回临时文件，调用方负责删除。
     */
    suspend fun render(
        fullPath: String,
        metadata: List<TMetadata>?,
        source: CoArtifactFile,
    ): Path? {
        if (!canRender(fullPath, metadata)) {
            return null
        }
        val artifactType = ArtifactThumbnailTypes.fromMetadata(metadata, fullPath)
        val resolved = resolveSource(fullPath, source) ?: return null
        val output = Files.createTempFile(THUMB_PREFIX, ArtifactThumbnailTypes.SIDECAR_SUFFIX)
        var keepOutput = false
        try {
            return when (val result = generateService.generate(resolved.path, artifactType, output)) {
                ThumbnailGenerateResult.Success -> output.also { keepOutput = true }
                is ThumbnailGenerateResult.Skipped -> {
                    logger.info("Skip thumbnail for [$fullPath]: ${result.reason}")
                    null
                }
                is ThumbnailGenerateResult.Failed -> {
                    logger.warn("Thumbnail generate failed for [$fullPath]: ${result.reason}")
                    null
                }
            }
        } catch (e: Exception) {
            logger.warn("Thumbnail generate failed for [$fullPath]: ${e.message}")
            return null
        } finally {
            if (!keepOutput) {
                Files.deleteIfExists(output)
            }
            if (resolved.temporary) {
                Files.deleteIfExists(resolved.path)
            }
        }
    }

    private fun canRender(fullPath: String, metadata: List<TMetadata>?): Boolean {
        if (!properties.enabled) {
            return false
        }
        val fileName = fullPath.substringAfterLast('/')
        if (ArtifactThumbnailTypes.isSidecarFileName(fileName)) {
            return false
        }
        return ArtifactThumbnailTypes.metadataValue(
            metadata,
            ArtifactThumbnailTypes.METADATA_ARTIFACT_NAME,
        ) != null
    }

    private fun resolveSource(fullPath: String, source: CoArtifactFile): ResolvedSource? {
        val existing = runCatching { source.getFile()?.toPath() }.getOrNull()
        if (existing != null && existing.exists()) {
            return ResolvedSource(existing, temporary = false)
        }
        return copySourceToTemp(fullPath, source)
    }

    private fun copySourceToTemp(fullPath: String, source: CoArtifactFile): ResolvedSource? {
        var temp: Path? = null
        try {
            val created = Files.createTempFile(THUMB_PREFIX, sourceSuffix(fullPath))
            temp = created
            source.getInputStream().use { input ->
                Files.copy(input, created, StandardCopyOption.REPLACE_EXISTING)
            }
            if (!created.exists() || Files.size(created) <= 0) {
                logger.warn("Skip thumbnail for [$fullPath]: source stream is empty")
                return null
            }
            temp = null
            return ResolvedSource(created, temporary = true)
        } catch (e: Exception) {
            logger.warn("Skip thumbnail for [$fullPath]: source file is not available: ${e.message}")
            return null
        } finally {
            temp?.let { Files.deleteIfExists(it) }
        }
    }

    private fun sourceSuffix(fullPath: String): String {
        val name = fullPath.substringAfterLast('/')
        // 隐藏文件（首字符为 `.`）不算带扩展名
        val ext = if (name.lastIndexOf('.') > 0) name.substringAfterLast('.') else ""
        val usable = ext.isNotEmpty() &&
            ext.length <= MAX_SOURCE_EXT_LENGTH &&
            ext.all { it.isLetterOrDigit() }
        return if (usable) ".${ext.lowercase()}" else SOURCE_SUFFIX
    }

    private data class ResolvedSource(
        val path: Path,
        val temporary: Boolean,
    )

    companion object {
        private const val THUMB_PREFIX = "drive-thumb-"
        private const val SOURCE_SUFFIX = ".src"
        private const val MAX_SOURCE_EXT_LENGTH = 7
        private val logger = LoggerFactory.getLogger(DriveArtifactThumbnailBinder::class.java)
    }
}
