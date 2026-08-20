package com.tencent.bkrepo.fs.server.service.drive.thumbnail

import com.tencent.bkrepo.fs.server.config.properties.drive.DriveThumbnailProperties
import com.tencent.bkrepo.fs.server.utils.ArtifactThumbnailTypes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.time.withTimeoutOrNull
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

/**
 * 生成器门面：类型跳过、按类型信号量、硬超时。
 * [generate] 拿不到许可立即 Failed；[generateWaiting] 等待直到剩余预算。
 * 不写 sidecar、不读节点元数据。
 */
@Service
class ArtifactThumbnailGenerateService(
    private val generator: ArtifactThumbnailGenerator,
    private val properties: DriveThumbnailProperties,
) {
    private val semaphores = ConcurrentHashMap<String, Semaphore>()

    suspend fun generate(input: Path, artifactType: String, output: Path): ThumbnailGenerateResult {
        return doGenerate(input, artifactType, output, waitForPermit = false, waitTimeout = properties.timeout)
    }

    /**
     * 与 [generate] 相同，但拿不到类型许可时等待。
     * [waitTimeout] 是剩余墙钟；等待后剩余时间不超过 [DriveThumbnailProperties.timeout] 则不再开生成。
     */
    suspend fun generateWaiting(
        input: Path,
        artifactType: String,
        output: Path,
        waitTimeout: Duration,
    ): ThumbnailGenerateResult {
        return doGenerate(input, artifactType, output, waitForPermit = true, waitTimeout = waitTimeout)
    }

    private suspend fun doGenerate(
        input: Path,
        artifactType: String,
        output: Path,
        waitForPermit: Boolean,
        waitTimeout: Duration,
    ): ThumbnailGenerateResult {
        val type = artifactType.trim().lowercase().ifEmpty { ArtifactThumbnailTypes.TYPE_OTHER }
        if (type in properties.skipTypes) {
            return ThumbnailGenerateResult.Skipped("skip type[$type]")
        }
        val semaphore = semaphoreFor(type)
        val waitStarted = System.nanoTime()
        val acquired = acquirePermit(semaphore, waitForPermit, waitTimeout)
        if (!acquired) {
            return ThumbnailGenerateResult.Failed("busy type[$type]")
        }
        return try {
            if (waitForPermit && leftoverBudget(waitStarted, waitTimeout) <= properties.timeout) {
                return ThumbnailGenerateResult.Failed("duration")
            }
            val result = withTimeoutOrNull(properties.timeout) {
                withContext(Dispatchers.IO) {
                    generator.generate(input, type, output)
                }
            }
            result ?: ThumbnailGenerateResult.Failed("timeout")
        } catch (e: Exception) {
            logger.warn("Thumbnail generate failed type[$type] input[$input]: [${e.message}]", e)
            ThumbnailGenerateResult.Failed(e.message ?: "generate failed")
        } finally {
            semaphore?.release()
        }
    }

    private suspend fun acquirePermit(semaphore: Semaphore?, waitForPermit: Boolean, waitTimeout: Duration): Boolean {
        if (semaphore == null) {
            return true
        }
        if (!waitForPermit) {
            return semaphore.tryAcquire()
        }
        val nanos = waitTimeout.toNanos().coerceAtLeast(0)
        return withContext(Dispatchers.IO) {
            semaphore.tryAcquire(nanos, TimeUnit.NANOSECONDS)
        }
    }

    private fun leftoverBudget(waitStarted: Long, waitTimeout: Duration): Duration {
        val elapsed = Duration.ofNanos((System.nanoTime() - waitStarted).coerceAtLeast(0))
        val left = waitTimeout.minus(elapsed)
        return if (left.isNegative) Duration.ZERO else left
    }

    private fun semaphoreFor(type: String): Semaphore? {
        val permits = properties.concurrency[type] ?: properties.defaultConcurrency
        if (permits <= 0) {
            return null
        }
        return semaphores.computeIfAbsent(type) { Semaphore(permits) }
    }

    companion object {
        private val logger = LoggerFactory.getLogger(ArtifactThumbnailGenerateService::class.java)
    }
}
