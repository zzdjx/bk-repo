package com.tencent.bkrepo.huggingface.util

import com.tencent.bkrepo.common.api.constant.HttpHeaders
import com.tencent.bkrepo.huggingface.constants.COMMIT_ID_HEADER
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("HuggingFace 重定向拦截器")
class RedirectInterceptorTest {

    @Test
    fun `keeps authorization and commit header on same-host redirect`() {
        val captured = mutableListOf<Request>()
        val client = client { request ->
            captured += request
            if (request.url.encodedPath == "/src") {
                redirect(request, "https://huggingface.co/dst")
            } else {
                ok(request)
            }
        }

        val response = client.newCall(authorizedRequest("https://huggingface.co/src")).execute()

        assertEquals(200, response.code)
        assertEquals("commit-1", response.header(COMMIT_ID_HEADER))
        assertEquals("Bearer secret", captured[1].header(HttpHeaders.AUTHORIZATION))
        response.close()
    }

    @Test
    fun `strips authorization on cross-host cdn redirect`() {
        val captured = mutableListOf<Request>()
        val client = client { request ->
            captured += request
            if (request.url.host == "huggingface.co") {
                redirect(request, "https://cdn-lfs.huggingface.co/blob")
            } else {
                ok(request)
            }
        }

        val response = client.newCall(authorizedRequest("https://huggingface.co/src")).execute()

        assertEquals(200, response.code)
        assertEquals("commit-1", response.header(COMMIT_ID_HEADER))
        assertEquals("Bearer secret", captured[0].header(HttpHeaders.AUTHORIZATION))
        assertNull(captured[1].header(HttpHeaders.AUTHORIZATION))
        response.close()
    }

    private fun client(handler: (Request) -> Response): OkHttpClient {
        return OkHttpClient.Builder()
            .addInterceptor(RedirectInterceptor())
            .addInterceptor { chain -> handler(chain.request()) }
            .followRedirects(false)
            .build()
    }

    private fun authorizedRequest(url: String): Request {
        return Request.Builder()
            .url(url)
            .header(HttpHeaders.AUTHORIZATION, "Bearer secret")
            .build()
    }

    private fun redirect(request: Request, location: String): Response {
        return Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(302)
            .message("Found")
            .header(HttpHeaders.LOCATION, location)
            .header(COMMIT_ID_HEADER, "commit-1")
            .body(ByteArray(0).toResponseBody("text/plain".toMediaType()))
            .build()
    }

    private fun ok(request: Request): Response {
        return Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(200)
            .message("OK")
            .body("ok".toResponseBody("text/plain".toMediaType()))
            .build()
    }
}
