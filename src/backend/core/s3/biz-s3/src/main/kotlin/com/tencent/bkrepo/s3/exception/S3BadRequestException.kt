package com.tencent.bkrepo.s3.exception

import com.tencent.bkrepo.common.api.constant.HttpStatus
import com.tencent.bkrepo.common.api.exception.ErrorCodeException
import com.tencent.bkrepo.common.api.message.MessageCode

/**
 * S3 客户端错误，HTTP 400
 */
open class S3BadRequestException(
    status: HttpStatus = HttpStatus.BAD_REQUEST,
    code: MessageCode,
    vararg params: Any
) : ErrorCodeException(status, code, params)
