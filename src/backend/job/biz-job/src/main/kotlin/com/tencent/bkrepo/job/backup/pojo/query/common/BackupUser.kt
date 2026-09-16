package com.tencent.bkrepo.job.backup.pojo.query.common

import com.tencent.bkrepo.auth.pojo.token.Token
import com.tencent.bkrepo.job.backup.pojo.query.enums.BackupUserSource
import java.time.LocalDateTime

data class BackupUser(
    var id: String?,
    val userId: String,
    val name: String,
    var pwd: String,
    val admin: Boolean = false,
    val locked: Boolean = false,
    var tokens: List<Token> = emptyList(),
    val roles: List<String> = emptyList(),
    val asstUsers: List<String> = emptyList(),
    val group: Boolean = false,
    val email: String? = null,
    val phone: String? = null,
    var accounts: List<String>? = emptyList(),
    val source: BackupUserSource = BackupUserSource.REPO,
    val createdDate: LocalDateTime? = LocalDateTime.now(),
    val lastModifiedDate: LocalDateTime? = LocalDateTime.now()
) {
    override fun toString(): String {
        return "BackupUser(id=$id, userId=$userId, name=$name, admin=$admin, " +
            "locked=$locked, roles=$roles, asstUsers=$asstUsers, group=$group, " +
            "email=$email, phone=$phone, accounts=$accounts, source=$source, " +
            "createdDate=$createdDate, lastModifiedDate=$lastModifiedDate)"
    }
}
