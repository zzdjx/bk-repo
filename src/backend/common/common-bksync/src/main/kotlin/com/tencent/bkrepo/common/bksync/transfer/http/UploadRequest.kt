package com.tencent.bkrepo.common.bksync.transfer.http

import java.io.File
import java.net.URLEncoder

/**
 * 上传请求
 * */
class UploadRequest(
    val file: File,
    val domain: String,
    val projectId: String,
    val repoName: String,
    val newFilePath: String,
    val oldFilePath: String,
    val token: String
) {
    // 增量上传url
    val deltaUrl = "http://$domain/generic/temporary/patch/delta/" +
        "$projectId/$repoName/${urlEncode(newFilePath)}?token=$token"
    // 签名url
    val signUrl = "http://$domain/generic/temporary/sign/delta/" +
        "$projectId/$repoName${urlEncode(oldFilePath)}?token=$token"
    // 新文件签名url
    val newFileSignUrl = "http://$domain/generic/temporary/sign/delta/" +
        "$projectId/$repoName/${urlEncode(newFilePath)}?token=$token"
    // 测速上报url
    val speedReportUrl = "http://$domain/generic/delta/speed"
    // 普通上传url
    val genericUrl: String = "http://$domain/generic/temporary/upload/" +
        "$projectId/$repoName/${urlEncode(newFilePath)}?token=$token"
    // 指标上报url
    val metricsUrl = "http://$domain/generic/delta/metrics"

    val headers = mutableMapOf<String, String>()

    fun addHeaders(header: String, value: String) {
        headers[header] = value
    }

    private fun urlEncode(str: String?): String {
        return URLEncoder.encode(str, Charsets.UTF_8.toString()).replace("+", "%20")
    }
}
