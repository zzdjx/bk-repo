package com.tencent.bkrepo.fs.server.service.drive.thumbnail

import java.nio.file.Path

/**
 * 作品缩略图生成端口。默认由 JVM 内处理实现。
 * 调用方负责跳过规则、超时和按类型并发；实现在调用线程上同步处理「文件进、JPEG 出」。
 */
fun interface ArtifactThumbnailGenerator {

    fun generate(input: Path, artifactType: String, output: Path): ThumbnailGenerateResult
}

sealed class ThumbnailGenerateResult {
    data object Success : ThumbnailGenerateResult()
    data class Skipped(val reason: String) : ThumbnailGenerateResult()
    data class Failed(val reason: String) : ThumbnailGenerateResult()
}
