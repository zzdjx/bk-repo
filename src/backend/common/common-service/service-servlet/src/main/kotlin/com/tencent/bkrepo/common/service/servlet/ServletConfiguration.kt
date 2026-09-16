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

package com.tencent.bkrepo.common.service.servlet

import com.tencent.bkrepo.common.api.constant.MediaTypes
import com.tencent.bkrepo.common.api.message.CommonMessageCode
import com.tencent.bkrepo.common.api.util.toJsonString
import com.tencent.bkrepo.common.service.log.LoggerHolder
import com.tencent.bkrepo.common.service.util.LocaleMessageUtils
import com.tencent.bkrepo.common.service.util.ResponseBuilder
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.beans.factory.annotation.Configurable
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.web.servlet.filter.OrderedFormContentFilter
import org.springframework.context.annotation.Bean
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.web.filter.FormContentFilter
import org.springframework.web.filter.UrlHandlerFilter

@Configurable
class ServletConfiguration {

    /**
     * 替换默认 FormContentFilter：非法 % 转义在 Filter 层抛 IAE，
     * 6.2.5+ 会再包成 HttpMessageNotReadableException，Advice 接不住。
     * 只拦解析阶段，后续链路的异常原样抛出。
     * 条件与被替换的自动配置 bean 保持一致。
     */
    @Bean
    @ConditionalOnMissingBean(FormContentFilter::class)
    @ConditionalOnProperty(
        prefix = "spring.mvc.formcontent.filter",
        name = ["enabled"],
        matchIfMissing = true
    )
    fun formContentFilter(): OrderedFormContentFilter {
        return object : OrderedFormContentFilter() {
            override fun doFilterInternal(
                request: HttpServletRequest,
                response: HttpServletResponse,
                filterChain: FilterChain
            ) {
                var enteredChain = false
                try {
                    super.doFilterInternal(request, response) { req, res ->
                        enteredChain = true
                        filterChain.doFilter(req, res)
                    }
                } catch (exception: RuntimeException) {
                    val invalidForm = exception is IllegalArgumentException ||
                        exception is HttpMessageNotReadableException
                    if (enteredChain || !invalidForm) throw exception
                    LoggerHolder.logException(exception, exception.message, false)
                    writeInvalidFormContent(request, response)
                }
            }
        }
    }

    private fun writeInvalidFormContent(
        request: HttpServletRequest,
        response: HttpServletResponse
    ) {
        val messageCode = CommonMessageCode.REQUEST_CONTENT_INVALID
        val message = LocaleMessageUtils.getLocalizedMessage(
            messageCode = messageCode,
            locale = request.locale
        )
        response.status = HttpServletResponse.SC_BAD_REQUEST
        response.contentType = MediaTypes.APPLICATION_JSON
        response.writer.write(ResponseBuilder.fail(messageCode.getCode(), message).toJsonString())
    }

    /**
     * https://docs.openrewrite.org/recipes/java/spring/boot3/addroutetrailingslash
     * https://docs.spring.io/spring-framework/reference/web/webmvc/filters.html#filters.url-handler
     */
    @Bean
    @ConditionalOnProperty(name = ["request.trailing.slash.enable"], havingValue = "true", matchIfMissing = true)
    fun urlHandlerFilter(): UrlHandlerFilter {
        return UrlHandlerFilter
            // will wrap the request to "/admin/user/account/" and make it as "/admin/user/account"
            .trailingSlashHandler("/**").wrapRequest()
            .build();
    }
}
