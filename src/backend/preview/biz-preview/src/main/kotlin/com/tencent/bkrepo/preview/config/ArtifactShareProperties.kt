package com.tencent.bkrepo.preview.config

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * 作品分享配置。
 *
 * ```yaml
 * preview:
 *   artifact-share:
 *     domain:
 *     download-token-min-parent-depth: 4
 * ```
 */
@ConfigurationProperties("preview.artifact-share")
data class ArtifactShareProperties(
    var domain: String = "",
    /**
     * 父目录路径段数必须大于该值，downloadToken 才绑定父目录，否则绑定原文件。
     * 默认 4：典型作品路径 `/artifact/{agent}/default/{name}/v1/file` 的父目录有 5 段，会绑定 v1。
     */
    var downloadTokenMinParentDepth: Int = 4,
)
