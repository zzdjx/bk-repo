package com.tencent.bkrepo.fs.server.service.drive.thumbnail

import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 缩略图生成超时中止：在工作线程上登记可关闭资源，超时后 close 并 interrupt。
 * PDFBox / POI / FFmpeg / Playwright 没有协程取消点，只能靠关文档、停 grabber、关页面。
 */
internal class ThumbnailAbort {

    private val cancelled = AtomicBoolean(false)
    private val resources = ConcurrentLinkedDeque<AutoCloseable>()

    @Volatile
    private var worker: Thread? = null

    fun isCancelled(): Boolean = cancelled.get()

    fun register(resource: AutoCloseable) {
        resources.addFirst(resource)
        if (cancelled.get()) {
            closeQuietly(resource)
        }
    }

    fun abort() {
        cancelled.set(true)
        resources.forEach { closeQuietly(it) }
        worker?.interrupt()
    }

    fun <T> run(block: () -> T): T {
        worker = Thread.currentThread()
        CURRENT.set(this)
        try {
            return block()
        } finally {
            CURRENT.remove()
            worker = null
        }
    }

    companion object {
        private val CURRENT = ThreadLocal<ThumbnailAbort>()

        fun isCancelled(): Boolean = CURRENT.get()?.isCancelled() == true

        fun register(resource: AutoCloseable) {
            CURRENT.get()?.register(resource)
        }

        private fun closeQuietly(resource: AutoCloseable) {
            runCatching { resource.close() }
        }
    }
}

internal inline fun <T : AutoCloseable, R> T.useAbortable(block: (T) -> R): R {
    ThumbnailAbort.register(this)
    return use(block)
}
