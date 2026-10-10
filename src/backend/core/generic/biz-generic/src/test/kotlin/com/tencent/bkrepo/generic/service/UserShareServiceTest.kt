package com.tencent.bkrepo.generic.service

import com.tencent.bkrepo.common.api.exception.ErrorCodeException
import com.tencent.bkrepo.common.api.message.CommonMessageCode
import com.tencent.bkrepo.generic.dao.UserShareRecordDao
import com.tencent.bkrepo.generic.model.TUserShareRecord
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.LocalDateTime

@DisplayName("用户分享记录查询")
class UserShareServiceTest {

    private val userShareRecordDao: UserShareRecordDao = mockk()
    private lateinit var service: UserShareService

    @BeforeEach
    fun setUp() {
        every { userShareRecordDao.findById(SHARE_ID) } returns TUserShareRecord(
            id = SHARE_ID,
            projectId = PROJECT_ID,
            repoName = "custom",
            path = "/a.txt",
            createBy = "owner",
            createDate = LocalDateTime.now(),
            lastModifiedDate = LocalDateTime.now()
        )
        service = UserShareService(
            userShareRecordDao = userShareRecordDao,
            userShareApprovalService = mockk(),
            nodeService = mockk(),
            devXProperties = mockk(),
            permissionManager = mockk()
        )
    }

    @Test
    @DisplayName("分享记录属于请求项目时返回详情")
    fun `info returns record of requested project`() {
        val info = service.info(PROJECT_ID, SHARE_ID)

        assertEquals(PROJECT_ID, info.projectId)
        assertEquals("/s/$PROJECT_ID/$SHARE_ID", info.url)
    }

    @Test
    @DisplayName("分享记录不属于请求项目时按不存在处理")
    fun `info rejects record of another project`() {
        val exception = assertThrows<ErrorCodeException> {
            service.info("other-project", SHARE_ID)
        }

        assertEquals(CommonMessageCode.RESOURCE_NOT_FOUND, exception.messageCode)
    }

    companion object {
        private const val PROJECT_ID = "p1"
        private const val SHARE_ID = "share-1"
    }
}
