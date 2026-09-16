package com.tencent.bkrepo.auth.helper

import com.tencent.bkrepo.auth.dao.PermissionDao
import com.tencent.bkrepo.auth.dao.PersonalPathDao
import com.tencent.bkrepo.auth.dao.UserDao
import com.tencent.bkrepo.auth.dao.repository.RoleRepository
import com.tencent.bkrepo.auth.message.AuthMessageCode
import com.tencent.bkrepo.auth.model.TPermission
import com.tencent.bkrepo.auth.pojo.enums.ResourceType
import com.tencent.bkrepo.common.api.exception.ErrorCodeException
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.junit.jupiter.api.assertThrows
import java.time.LocalDateTime

@DisplayName("权限文档项目归属校验")
class PermissionHelperProjectScopeTest {

    private val userDao: UserDao = mockk(relaxed = true)
    private val roleRepository: RoleRepository = mockk(relaxed = true)
    private val permissionDao: PermissionDao = mockk(relaxed = true)
    private val personalPathDao: PersonalPathDao = mockk(relaxed = true)
    private val helper = PermissionHelper(userDao, roleRepository, permissionDao, personalPathDao)

    @Test
    fun `should pass when permission belongs to project`() {
        every { permissionDao.findFirstById(PERMISSION_ID) } returns permission(projectId = PROJECT_A)

        assertDoesNotThrow {
            helper.checkPermissionBelongToProject(PERMISSION_ID, PROJECT_A)
        }
    }

    @Test
    fun `should reject when permission belongs to another project`() {
        every { permissionDao.findFirstById(PERMISSION_ID) } returns permission(projectId = PROJECT_B)

        val exception = assertThrows<ErrorCodeException> {
            helper.checkPermissionBelongToProject(PERMISSION_ID, PROJECT_A)
        }
        assertEquals(AuthMessageCode.AUTH_PERMISSION_NOT_EXIST, exception.messageCode)
    }

    @Test
    fun `should reject when permission has no project`() {
        every { permissionDao.findFirstById(PERMISSION_ID) } returns permission(projectId = null)

        val exception = assertThrows<ErrorCodeException> {
            helper.checkPermissionBelongToProject(PERMISSION_ID, PROJECT_A)
        }
        assertEquals(AuthMessageCode.AUTH_PERMISSION_NOT_EXIST, exception.messageCode)
    }

    @Test
    fun `should reject when permission not exist`() {
        every { permissionDao.findFirstById(PERMISSION_ID) } returns null

        val exception = assertThrows<ErrorCodeException> {
            helper.checkPermissionBelongToProject(PERMISSION_ID, PROJECT_A)
        }
        assertEquals(AuthMessageCode.AUTH_PERMISSION_NOT_EXIST, exception.messageCode)
    }

    private fun permission(projectId: String?): TPermission {
        return TPermission(
            id = PERMISSION_ID,
            resourceType = ResourceType.REPO.name,
            projectId = projectId,
            permName = "ut-perm",
            createBy = "ut",
            createAt = LocalDateTime.now(),
            updatedBy = "ut",
            updateAt = LocalDateTime.now()
        )
    }

    companion object {
        private const val PERMISSION_ID = "perm-1"
        private const val PROJECT_A = "project-a"
        private const val PROJECT_B = "project-b"
    }
}
