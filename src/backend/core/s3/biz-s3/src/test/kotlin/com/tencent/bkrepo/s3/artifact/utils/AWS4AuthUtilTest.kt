package com.tencent.bkrepo.s3.artifact.utils

import com.tencent.bkrepo.s3.constant.UNSIGNED_PAYLOAD
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

@DisplayName("AWS4 时间窗与 payload hash 校验")
class AWS4AuthUtilTest {

    private val formatter = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC)
    private val now = Instant.parse("2024-01-01T00:00:00Z")
    private val skewSeconds = 900L
    private val sha256 = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"

    @Test
    fun `request date within skew is fresh`() {
        val date = formatter.format(now.minusSeconds(900))
        assertTrue(AWS4AuthUtil.isRequestDateFresh(date, skewSeconds, now))
    }

    @Test
    fun `request date beyond skew is stale`() {
        val date = formatter.format(now.minusSeconds(901))
        assertFalse(AWS4AuthUtil.isRequestDateFresh(date, skewSeconds, now))
    }

    @Test
    fun `future request date beyond skew is stale`() {
        val date = formatter.format(now.plusSeconds(901))
        assertFalse(AWS4AuthUtil.isRequestDateFresh(date, skewSeconds, now))
    }

    @Test
    fun `invalid or empty request date is stale`() {
        assertFalse(AWS4AuthUtil.isRequestDateFresh("", skewSeconds, now))
        assertFalse(AWS4AuthUtil.isRequestDateFresh("not-a-date", skewSeconds, now))
    }

    @Test
    fun `payload hash is compared to actual sha256`() {
        assertTrue(AWS4AuthUtil.payloadHashMatches(sha256, sha256))
        assertTrue(AWS4AuthUtil.payloadHashMatches(sha256.uppercase(), sha256))
        assertFalse(
            AWS4AuthUtil.payloadHashMatches(
                "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                sha256
            )
        )
        assertFalse(AWS4AuthUtil.payloadHashMatches(null, sha256))
        assertFalse(AWS4AuthUtil.payloadHashMatches("", sha256))
        assertFalse(AWS4AuthUtil.payloadHashMatches("not-a-hash", sha256))
        assertFalse(AWS4AuthUtil.payloadHashMatches("STREAMING-AWS4-HMAC-SHA256-PAYLOAD", sha256))
    }

    @Test
    fun `unsigned payload skips body hash`() {
        assertTrue(AWS4AuthUtil.payloadHashMatches(UNSIGNED_PAYLOAD, sha256))
    }

    @Test
    fun `credential date matching x-amz-date day is consistent`() {
        assertTrue(AWS4AuthUtil.isCredentialDateConsistent(authorization("20240101"), "20240101T000000Z"))
        assertTrue(AWS4AuthUtil.isCredentialDateConsistent(authorization("20240101"), "20240101T235959Z"))
    }

    @Test
    fun `credential date mismatching x-amz-date day is inconsistent`() {
        assertFalse(AWS4AuthUtil.isCredentialDateConsistent(authorization("20240101"), "20240102T000000Z"))
        assertFalse(AWS4AuthUtil.isCredentialDateConsistent(authorization("20240101"), ""))
        assertFalse(AWS4AuthUtil.isCredentialDateConsistent(authorization("20240101"), "2024010"))
    }

    private fun authorization(credentialDate: String): String {
        return "AWS4-HMAC-SHA256 Credential=admin/$credentialDate/us-east-1/s3/aws4_request, " +
            "SignedHeaders=host;x-amz-date, Signature=abc"
    }
}
