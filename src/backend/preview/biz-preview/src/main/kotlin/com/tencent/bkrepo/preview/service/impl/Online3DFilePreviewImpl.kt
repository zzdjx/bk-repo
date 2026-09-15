package com.tencent.bkrepo.preview.service.impl

import com.tencent.bkrepo.preview.config.configuration.PreviewConfig
import com.tencent.bkrepo.preview.constant.PreviewMessageCode
import com.tencent.bkrepo.preview.exception.PreviewSystemException
import com.tencent.bkrepo.preview.pojo.FileAttribute
import com.tencent.bkrepo.preview.service.FilePreview
import com.tencent.bkrepo.preview.service.FileTransferService
import com.tencent.bkrepo.preview.service.Online3dPreview
import org.springframework.stereotype.Service

/**
 * ONLINE3D：仅本地 `.glb` 原样 Range 流出，不转换、不落地整文件。
 */
@Service("online3DFilePreviewImpl")
class Online3DFilePreviewImpl(
    private val fileTransferService: FileTransferService,
    private val config: PreviewConfig
) : FilePreview {
    override fun filePreviewHandle(fileAttribute: FileAttribute) {
        val suffix = fileAttribute.suffix.orEmpty()
        if (fileAttribute.storageType != 0 || !Online3dPreview.isGlb(suffix)) {
            throw PreviewSystemException(
                PreviewMessageCode.PREVIEW_FILE_NOT_SUPPORT_ERROR,
                suffix
            )
        }
        val maxFileSizeMb = config.online3dMaxFileSize
        if (maxFileSizeMb > 0) {
            val size = fileTransferService.originalFileSize(fileAttribute)
            val maxBytes = Online3dPreview.megabytesToBytes(maxFileSizeMb)
            if (size > maxBytes) {
                throw PreviewSystemException(
                    PreviewMessageCode.PREVIEW_FILE_SIZE_LIMIT_ERROR,
                    "${maxFileSizeMb}M"
                )
            }
        }
        fileTransferService.sendOriginalFileAsResponse(fileAttribute)
    }
}
