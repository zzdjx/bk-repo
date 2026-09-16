package com.tencent.bkrepo.huggingface.exception

import com.tencent.bkrepo.common.api.util.readJsonString
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.http.MediaType
import org.springframework.mock.web.MockHttpServletResponse

@DisplayName("HuggingFace 错误响应体")
class HfErrorResponseTest {

    @Test
    fun `writes json error body and huggingface error headers`() {
        val response = MockHttpServletResponse()

        HfErrorResponse.write(
            response = response,
            status = 404,
            errorCode = "RevisionNotFound",
            errorMessage = "Revision[main] not found",
        )

        assertEquals(404, response.status)
        assertEquals("RevisionNotFound", response.getHeader("X-Error-Code"))
        assertEquals("Revision[main] not found", response.getHeader("X-Error-Message"))
        assertTrue(response.contentType?.startsWith(MediaType.APPLICATION_JSON_VALUE) == true)
        val body: Map<String, String> = response.contentAsString.readJsonString()
        assertEquals("Revision[main] not found", body["error"])
    }
}
