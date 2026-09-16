package com.tencent.bkrepo.common.ratelimiter.metrics

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RateLimiterMetricsTest {

    @Test
    fun `does not tag full uri and cardinality stays bounded`() {
        val registry = SimpleMeterRegistry()
        val metrics = RateLimiterMetrics(registry)
        val before = registry.meters.size
        repeat(200) { i ->
            val uri = "/generic/proj/repo/secret-path-$i?token=leak"
            metrics.collectMetrics(uri, result = i % 2 == 0, e = null, dimension = "URL")
            metrics.recordLimitRejectDetail("URL", uri, 1)
            metrics.recordPassedPermits("URL", uri, 1)
        }
        val tagValues = registry.meters.flatMap { meter -> meter.id.tags.map { it.value } }
        assertTrue(tagValues.none { it.contains("secret-path") || it.contains("token=") })
        assertTrue(registry.meters.size - before < 30)
    }
}
