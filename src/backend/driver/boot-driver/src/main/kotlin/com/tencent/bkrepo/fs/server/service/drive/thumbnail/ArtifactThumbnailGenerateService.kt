package com.tencent.bkrepo.fs.server.service.drive.thumbnail

import com.google.common.util.concurrent.ThreadFactoryBuilder
import com.tencent.bkrepo.fs.server.config.properties.drive.DriveThumbnailProperties
import com.tencent.bkrepo.fs.server.utils.ArtifactThumbnailTypes
import jakarta.annotation.PreDestroy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * 生成器门面：类型跳过、按类型信号量、硬超时。
 * [generate] 拿不到许可立即 Failed；[generateWaiting] 等待直到剩余预算。
 * 超时用独立线程 [java.util.concurrent.Future.get]，并中止已登记的文档 / grabber / 页面；
 * 调用方不必等 PDFBox / POI / FFmpeg / Playwright 自己跑完。
 * 不写 sidecar、不读节点元数据。
 */
@Service
class ArtifactThumbnailGenerateService(
    private val generator: ArtifactThumbnailGenerator,
    private val properties: DriveThumbnailProperties,
) {
    private val semaphores = ConcurrentHashMap<String, Semaphore>()
    private val generateExecutor = Executors.newCachedThreadPool(
        ThreadFactoryBuilder()
            .setNameFormat("drive-thumb-generate-%d")
            .setDaemon(true)
            .build(),
    )

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
            generateWithHardTimeout(input, type, output)
        } catch (e: Exception) {
            logger.warn("Thumbnail generate failed type[$type] input[$input]: [${e.message}]", e)
            ThumbnailGenerateResult.Failed(e.message ?: "generate failed")
        } finally {
            semaphore?.release()
        }
    }

    /**
     * 在独立线程跑阻塞生成。超时立即 Failed，并 abort 工作线程上已登记的资源。
     */
    private suspend fun generateWithHardTimeout(
        input: Path,
        type: String,
        output: Path,
    ): ThumbnailGenerateResult {
        val abort = ThumbnailAbort()
        val future = generateExecutor.submit(
            Callable {
                abort.run { generator.generate(input, type, output) }
            },
        )
        return withContext(Dispatchers.IO) {
            try {
                future.get(properties.timeout.toNanos().coerceAtLeast(0), TimeUnit.NANOSECONDS)
            } catch (_: TimeoutException) {
                abort.abort()
                future.cancel(true)
                ThumbnailGenerateResult.Failed("timeout")
            } catch (_: InterruptedException) {
                abort.abort()
                future.cancel(true)
                Thread.currentThread().interrupt()
                ThumbnailGenerateResult.Failed("timeout")
            } catch (e: ExecutionException) {
                throw e.cause ?: e
            }
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

    @PreDestroy
    fun shutdownGenerateExecutor() {
        generateExecutor.shutdownNow()
    }

    companion object {
        private val logger = LoggerFactory.getLogger(ArtifactThumbnailGenerateService::class.java)
    }
}
