/*
 * Tencent is pleased to support the open source community by making BK-CI 蓝鲸持续集成平台 available.
 *
 * Copyright (C) 2020 Tencent.  All rights reserved.
 *
 * BK-CI 蓝鲸持续集成平台 is licensed under the MIT license.
 *
 * A copy of the MIT License is included in this file.
 *
 *
 * Terms of the MIT License:
 * ---------------------------------------------------
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package com.tencent.bkrepo.preview.utils

import com.tencent.bkrepo.common.api.exception.SystemErrorException
import com.tencent.bkrepo.common.api.message.CommonMessageCode
import com.tencent.bkrepo.preview.config.configuration.PreviewConfig
import com.tencent.bkrepo.preview.exception.PreviewInvalidException
import com.tencent.bkrepo.preview.pojo.DownloadResult
import com.tencent.bkrepo.preview.pojo.FileAttribute
import okhttp3.ResponseBody
import org.springframework.stereotype.Component
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.net.URL
import java.util.UUID

/**
 * 文件下载工具
 */
@Component
class DownloadUtils(
    private val httpUtils: HttpUtils,
    private val ssrfGuard: SsrfGuard
) {
    companion object {
        private val logger = org.slf4j.LoggerFactory.getLogger(DownloadUtils::class.java)
        private const val URL_PARAM_FTP_USERNAME = "ftp.username"
        private const val URL_PARAM_FTP_PASSWORD = "ftp.password"
        private const val URL_PARAM_FTP_CONTROL_ENCODING = "ftp.control.encoding"
    }

    /**
     * 下载文件
     * @param fileAttribute 文件属性
     * @param config 配置信息
     * @return 下载结果
     */
    @Throws(Exception::class)
    fun downLoad(fileAttribute: FileAttribute, config: PreviewConfig): DownloadResult {
        val result = DownloadResult()
        val urlStr = processUrl(fileAttribute)
        if (urlStr.isNullOrBlank()) {
            result.apply {
                code = DownloadResult.CODE_FAIL
                msg = "Unrecognized URL"
            }
            return result
        }
        // HTTP 与 FTP 共用 SSRF / 远程预览总开关。控制连接在此校验；
        // FTP PASV 数据连接在 FtpUtils 打开 socket 前再次校验目标地址。
        ssrfGuard.validate(urlStr)

        val fileName = fileAttribute.fileName
        val realPath = getRelFilePath(fileName, fileAttribute.suffix!!, config.fileDir)
        val resolvedName = File(realPath).name

        if (!isValidFile(resolvedName, urlStr, config, result)) return result

        try {
            val url = WebUtils.normalizedURL(urlStr)
            if (FileUtils.isHttpUrl(url)) {
                downloadHttpFile(url, realPath, result)
            } else if (FileUtils.isFtpUrl(url)) {
                downloadFtpFile(fileAttribute, realPath, config, result)
            } else {
                result.apply {
                    code = DownloadResult.CODE_FAIL
                    msg = "Unrecognized URL: $urlStr"
                }
            }

            return calculateFileMd5AndSize(realPath, resolvedName, result)
        } catch (e: IOException) {
            logger.error("File download failed，url：$urlStr")
            result.apply {
                code = DownloadResult.CODE_FAIL
                msg = when (e) {
                    is FileNotFoundException -> "The file doesn't exist"
                    else -> e.message ?: "File download failed"
                }
            }
            return result
        }
    }

    private fun downloadHttpFile(url: URL, realPath: String, result: DownloadResult) {
        try {
            // SSRF 防护已下沉到 HttpUtils：初始 URL 及每次重定向前都会走一次 SSRF 校验
            val response = httpUtils.downloadHttpFile(url)
            saveFile(response.body, realPath)
            result.apply {
                code = DownloadResult.CODE_SUCCESS
                msg = "Download succeeded."
            }
        } catch (e: Exception) {
            logger.error("Download failed: $e")
            result.apply {
                code = DownloadResult.CODE_FAIL
                msg = "The download failed: $e"
            }
        }
    }

    private fun saveFile(body: ResponseBody?, realPath: String) {
        val file = File(realPath)
        if (!file.parentFile.exists() && !file.parentFile.mkdirs()) {
            logger.error("Failed to create directory [$realPath], please check the directory permissions!")
            throw SystemErrorException(CommonMessageCode.SYSTEM_ERROR, "Failed to create directory")
        }
        body?.byteStream()?.use { inputStream ->
            file.outputStream().use { outputStream ->
                inputStream.copyTo(outputStream)
            }
        }
    }

    private fun downloadFtpFile(fileAttribute: FileAttribute,
                                realPath: String,
                                config: PreviewConfig,
                                result: DownloadResult) {
        try {
            val ftpUsername = WebUtils.getUrlParameterReg(fileAttribute.url!!, URL_PARAM_FTP_USERNAME)
            val ftpPassword = WebUtils.getUrlParameterReg(fileAttribute.url!!, URL_PARAM_FTP_PASSWORD)
            val ftpControlEncoding = WebUtils.getUrlParameterReg(fileAttribute.url!!, URL_PARAM_FTP_CONTROL_ENCODING)
            FtpUtils.download(
                fileAttribute.url,
                realPath,
                ftpUsername.takeUnless { it.isNullOrEmpty() } ?: config.ftpUsername,
                ftpPassword.takeUnless { it.isNullOrEmpty() } ?: config.ftpPassword,
                ftpControlEncoding.takeUnless { it.isNullOrEmpty() } ?: config.ftpControlEncoding,
                ssrfGuard
            )
        } catch (e: PreviewInvalidException) {
            throw e
        } catch (e: Exception) {
            logger.error("FTP download failed: $e")
            result.apply {
                code = DownloadResult.CODE_FAIL
                msg = "FTP download failed: $e"
            }
        }
    }

    private fun calculateFileMd5AndSize(realPath: String, fileName: String, result: DownloadResult): DownloadResult {
        FileUtils.getFileMd5AndSize(realPath)?.let { (md5, size) ->
            result.apply {
                code = DownloadResult.CODE_SUCCESS
                filePath = realPath
                this.md5 = md5
                this.size = size
                msg = fileName
            }
        } ?: run {
            result.apply {
                code = DownloadResult.CODE_FAIL
                msg = "Failed to calculate MD5 or size for file: $fileName"
            }
        }
        return result
    }

    private fun isValidFile(
        fileName: String,
        urlStr: String,
        config: PreviewConfig,
        result: DownloadResult
    ): Boolean {
        if (FileUtils.isIllegalFileName(fileName)) {
            result.apply {
                code = DownloadResult.CODE_FAIL
                msg = "Download failed, The file name is invalid,$fileName"
            }
            return false
        }
        if (!FileUtils.isAllowedUpload(fileName, config.prohibitSuffix)) {
            result.apply {
                code = DownloadResult.CODE_FAIL
                msg = "Download Failed, Unsupported Type, $urlStr"
            }
            return false
        }
        return true
    }

    private fun processUrl(fileAttribute: FileAttribute): String? {
        return try {
            fileAttribute.url?.replace("+", "%20")?.replace(" ", "%20")
        } catch (e: Exception) {
            logger.error("processUrl exceptions", e)
            null
        }
    }


    /**
     * 文件存放的真实路径
     * @param fileName 文件名
     * @param suffix 文件后缀
     * @param fileDir 根路径
     * @return 文件路径
     */
    fun getRelFilePath(fileName: String?, suffix: String, fileDir: String): String {
        var updatedFileName = fileName ?: UUID.randomUUID().toString() + "." + suffix
        FileUtils.requireSafeFileName(updatedFileName)
        val dotIndex = updatedFileName.lastIndexOf('.')
        if (dotIndex >= 0) {
            updatedFileName = updatedFileName.replace(
                updatedFileName.substring(dotIndex + 1),
                suffix
            )
        }
        FileUtils.requireSafeFileName(updatedFileName)

        val uniqueDir = UUID.randomUUID().toString()
        val realPath = FileUtils.resolveUnderDirectory(fileDir, "download", uniqueDir, updatedFileName)
        val dirFile = File(fileDir, "download")

        if (!dirFile.exists() && !dirFile.mkdirs()) {
            logger.error("Failed to create directory,${dirFile.path}")
            throw SystemErrorException(CommonMessageCode.SYSTEM_ERROR, "Failed to create directory")
        }
        return realPath
    }
}