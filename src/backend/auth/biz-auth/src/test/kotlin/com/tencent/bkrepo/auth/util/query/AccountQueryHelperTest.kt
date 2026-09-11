package com.tencent.bkrepo.auth.util.query

import com.tencent.bkrepo.auth.model.TAccount
import com.tencent.bkrepo.auth.pojo.enums.CredentialStatus
import com.tencent.bkrepo.auth.pojo.oauth.AuthorizationGrantType
import com.tencent.bkrepo.auth.pojo.token.CredentialSet
import org.bson.Document
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("账号凭证查询条件")
class AccountQueryHelperTest {

    @Test
    fun `checkCredential binds ak sk grant type and status on the same array element`() {
        val query = AccountQueryHelper.checkCredential(
            ACCESS_KEY,
            SECRET_KEY,
            AuthorizationGrantType.PLATFORM
        )

        val queryObject = query.queryObject
        assertFalse(queryObject.containsKey("credentials.accessKey"))
        assertFalse(queryObject.containsKey("credentials.secretKey"))
        assertFalse(queryObject.containsKey("credentials.authorizationGrantType"))
        assertFalse(queryObject.containsKey("credentials.status"))

        val elemMatch = requireElemMatch(queryObject)
        val fields = equalityFields(elemMatch)
        assertEquals(ACCESS_KEY, fields[CredentialSet::accessKey.name])
        assertEquals(SECRET_KEY, fields[CredentialSet::secretKey.name])
        assertStatusEnable(fields[CredentialSet::status.name])
        assertGrantTypeAllows(elemMatch, AuthorizationGrantType.PLATFORM)
    }

    @Test
    fun `checkCredential still requires enable status when grant type is omitted`() {
        val query = AccountQueryHelper.checkCredential(ACCESS_KEY, SECRET_KEY, null)

        val elemMatch = requireElemMatch(query.queryObject)
        val fields = equalityFields(elemMatch)
        assertEquals(ACCESS_KEY, fields[CredentialSet::accessKey.name])
        assertEquals(SECRET_KEY, fields[CredentialSet::secretKey.name])
        assertStatusEnable(fields[CredentialSet::status.name])
    }

    private fun requireElemMatch(queryObject: Document): Document {
        val credentials = queryObject[TAccount::credentials.name]
        assertTrue(credentials is Document, "credentials should be queried with \$elemMatch: $queryObject")
        val elemMatch = (credentials as Document)["\$elemMatch"]
        assertNotNull(elemMatch, "missing \$elemMatch: $queryObject")
        return elemMatch as Document
    }

    private fun conditions(elemMatch: Document): List<Document> {
        val and = elemMatch["\$and"]
        return if (and is List<*>) {
            and.map { it as Document }
        } else {
            listOf(elemMatch)
        }
    }

    private fun equalityFields(elemMatch: Document): Map<String, Any?> {
        return conditions(elemMatch)
            .filter { !it.containsKey("\$or") && !it.containsKey("\$and") }
            .flatMap { it.entries }
            .associate { it.key to it.value }
    }

    private fun assertStatusEnable(value: Any?) {
        val status = when (value) {
            is CredentialStatus -> value
            is String -> CredentialStatus.valueOf(value)
            else -> null
        }
        assertEquals(CredentialStatus.ENABLE, status, "status should be ENABLE, got: $value")
    }

    private fun assertGrantTypeAllows(elemMatch: Document, type: AuthorizationGrantType) {
        val orDocs = conditions(elemMatch)
            .mapNotNull { it["\$or"] as? List<*> }
            .flatten()
            .map { it as Document }
        assertTrue(orDocs.isNotEmpty(), "grant type should be constrained inside \$elemMatch: $elemMatch")
        val field = CredentialSet::authorizationGrantType.name
        assertTrue(
            orDocs.any { it.containsKey(field) && it[field] == null },
            "legacy credentials without grant type should still match: $orDocs"
        )
        assertTrue(
            orDocs.any { it[field] == type || it[field]?.toString() == type.name },
            "grant type $type should be allowed, got: $orDocs"
        )
    }

    companion object {
        private const val ACCESS_KEY = "oauth-ak"
        private const val SECRET_KEY = "oauth-sk"
    }
}
