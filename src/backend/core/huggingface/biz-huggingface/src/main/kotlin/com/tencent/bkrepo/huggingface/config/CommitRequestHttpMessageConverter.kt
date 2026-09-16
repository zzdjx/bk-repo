/*
 * Tencent is pleased to support the open source community by making BK-CI 蓝鲸持续集成平台 available.
 *
 * Copyright (C) 2025 Tencent.  All rights reserved.
 *
 * BK-CI 蓝鲸持续集成平台 is licensed under the MIT license.
 *
 * A copy of the MIT License is included in this file.
 *
 *
 * Terms of the MIT License:
 * ---------------------------------------------------
 * Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated
 * documentation files (the "Software"), to deal in the Software without restriction, including without limitation the
 * rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the Software, and to
 * permit persons to whom the Software is furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all copies or substantial portions of
 * the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT
 * LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN
 * NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY,
 * WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE
 * SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */

package com.tencent.bkrepo.huggingface.config

import com.fasterxml.jackson.databind.ObjectMapper
import com.tencent.bkrepo.huggingface.pojo.user.UserCommitRequest
import org.springframework.http.HttpInputMessage
import org.springframework.http.HttpOutputMessage
import org.springframework.http.MediaType
import org.springframework.http.converter.AbstractHttpMessageConverter
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter

class CommitRequestHttpMessageConverter(
    private val objectMapper: ObjectMapper,
) : AbstractHttpMessageConverter<List<UserCommitRequest>>() {

    init {
        this.supportedMediaTypes = mutableListOf(NDJSON)
    }

    override fun supports(clazz: Class<*>): Boolean {
        return MutableList::class.java.isAssignableFrom(clazz)
    }

    /**
     * 仅在明确指定 ndjson 时参与写出。父类对 null 或通配 media type 会返回 true，
     * 导致 tree 等返回 List 的 JSON 接口被本转换器抢走并发生 ClassCastException。
     */
    override fun canWrite(clazz: Class<*>, mediaType: MediaType?): Boolean {
        if (!supports(clazz) || mediaType == null || MediaType.ALL.equalsTypeAndSubtype(mediaType)) {
            return false
        }
        return NDJSON.isCompatibleWith(mediaType)
    }

    override fun canRead(clazz: Class<*>, mediaType: MediaType?): Boolean {
        return supports(clazz) && (mediaType == null || NDJSON.includes(mediaType))
    }

    override fun readInternal(
        clazz: Class<out List<UserCommitRequest>>,
        inputMessage: HttpInputMessage
    ): List<UserCommitRequest> {
        val result: MutableList<UserCommitRequest> = mutableListOf()
        BufferedReader(InputStreamReader(inputMessage.body)).use { reader ->
            var line: String?
            while ((reader.readLine().also { line = it }) != null) {
                result.add(objectMapper.readValue(line, UserCommitRequest::class.java))
            }
        }
        return result
    }

    override fun writeInternal(
        objects: List<UserCommitRequest>,
        outputMessage: HttpOutputMessage
    ) {
        OutputStreamWriter(outputMessage.body).use { writer ->
            for (o in objects) {
                writer.write(objectMapper.writeValueAsString(o))
                writer.write("\n")
            }
        }
    }

    companion object {
        val NDJSON = MediaType("application", "x-ndjson")
    }
}
