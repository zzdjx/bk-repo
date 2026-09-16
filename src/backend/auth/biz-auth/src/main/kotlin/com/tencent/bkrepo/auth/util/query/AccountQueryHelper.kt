package com.tencent.bkrepo.auth.util.query

import com.tencent.bkrepo.auth.model.TAccount
import com.tencent.bkrepo.auth.pojo.enums.CredentialStatus
import com.tencent.bkrepo.auth.pojo.oauth.AuthorizationGrantType
import com.tencent.bkrepo.auth.pojo.token.CredentialSet
import org.springframework.data.mongodb.core.query.Criteria
import org.springframework.data.mongodb.core.query.Query
import org.springframework.data.mongodb.core.query.isEqualTo

object AccountQueryHelper {

    fun checkCredential(accessKey: String, secretKey: String, authorizationGrantType: AuthorizationGrantType?): Query {
        val credentialCriteria = mutableListOf(
            Criteria.where(CredentialSet::accessKey.name).isEqualTo(accessKey),
            Criteria.where(CredentialSet::secretKey.name).isEqualTo(secretKey),
            Criteria.where(CredentialSet::status.name).isEqualTo(CredentialStatus.ENABLE)
        )
        authorizationGrantType?.let { type ->
            credentialCriteria.add(
                Criteria().orOperator(
                    Criteria.where(CredentialSet::authorizationGrantType.name).isEqualTo(null),
                    Criteria.where(CredentialSet::authorizationGrantType.name).isEqualTo(type)
                )
            )
        }
        return Query.query(
            Criteria.where(TAccount::credentials.name).elemMatch(
                Criteria().andOperator(credentialCriteria)
            )
        )
    }

    fun checkAppAccessKey(appId: String, accessKey: String): Query {
        return Query.query(
            Criteria.where(TAccount::appId.name).`is`(appId)
                .and("credentials.accessKey").`is`(accessKey)
        )
    }
}
