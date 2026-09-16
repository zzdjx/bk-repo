package com.tencent.bkrepo.s3.config

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "s3.auth")
class S3AuthProperties {
    /**
     * x-amz-date 与服务器时间允许的最大偏差（秒）
     */
    var maxClockSkewSeconds: Long = 900L
}
