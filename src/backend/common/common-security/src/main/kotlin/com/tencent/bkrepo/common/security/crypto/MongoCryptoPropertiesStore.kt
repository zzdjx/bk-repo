package com.tencent.bkrepo.common.security.crypto

import com.mongodb.WriteConcern
import org.springframework.dao.DuplicateKeyException
import org.springframework.data.mongodb.core.FindAndModifyOptions
import org.springframework.data.mongodb.core.MongoTemplate
import org.springframework.data.mongodb.core.query.Criteria
import org.springframework.data.mongodb.core.query.Query
import org.springframework.data.mongodb.core.query.Update
import org.springframework.data.mongodb.core.query.isEqualTo
import java.time.LocalDateTime

class MongoCryptoPropertiesStore(
    mongoTemplate: MongoTemplate
) : CryptoPropertiesStore {

    private val template: MongoTemplate = MongoTemplate(
        mongoTemplate.mongoDatabaseFactory,
        mongoTemplate.converter
    ).apply { setWriteConcern(WriteConcern.MAJORITY) }

    override fun load(): CryptoProperties? {
        return findExisting()
    }

    override fun insertIfAbsent(candidate: CryptoProperties): CryptoProperties {
        return try {
            insert(candidate)
        } catch (ignored: DuplicateKeyException) {
            requireExisting("concurrent insert")
        } catch (exception: Exception) {
            findExisting() ?: throw IllegalStateException(
                "security.crypto keyring write failed; refusing to generate a new key",
                exception
            )
        }
    }

    private fun insert(candidate: CryptoProperties): CryptoProperties {
        val document = TSecurityCryptoKeyring.from(candidate, LocalDateTime.now())
        val update = Update().setOnInsert(ID, document.id).setOnInsert(CREATED_AT, document.createdAt)
        mutableFields(document).forEach { (name, value) -> update.setOnInsert(name, value) }
        val options = FindAndModifyOptions().upsert(true).returnNew(true)
        val saved = template.findAndModify(idQuery(), update, options, TSecurityCryptoKeyring::class.java)
        return toValidProperties(saved) ?: requireExisting("insert returned empty")
    }

    override fun patch(expectedFingerprint: String, merged: CryptoProperties): CryptoProperties? {
        val document = TSecurityCryptoKeyring.from(merged, LocalDateTime.now())
        val update = Update()
        mutableFields(document).forEach { (name, value) -> update.set(name, value) }
        val query = idQuery().addCriteria(Criteria.where(FINGERPRINT).isEqualTo(expectedFingerprint))
        val options = FindAndModifyOptions().returnNew(true)
        return toValidProperties(template.findAndModify(query, update, options, TSecurityCryptoKeyring::class.java))
    }

    private fun mutableFields(document: TSecurityCryptoKeyring): List<Pair<String, Any>> {
        return listOf(
            "schemaVersion" to document.schemaVersion,
            "rsaAlgorithm" to document.rsaAlgorithm,
            "privateKeyStr" to document.privateKeyStr,
            "publicKeyStr" to document.publicKeyStr,
            "privateKeyStr2048PKCS8" to document.privateKeyStr2048PKCS8,
            "publicKeyStr2048PKCS8" to document.publicKeyStr2048PKCS8,
            "privateKeyStr2048PKCS1" to document.privateKeyStr2048PKCS1,
            "publicKeyStr2048PKCS1" to document.publicKeyStr2048PKCS1,
            "aesKey" to document.aesKey,
            "aesIv" to document.aesIv,
            FINGERPRINT to document.fingerprint,
            "updatedAt" to document.updatedAt
        )
    }

    private fun findExisting(): CryptoProperties? {
        return try {
            template.findById(TSecurityCryptoKeyring.GLOBAL_ID, TSecurityCryptoKeyring::class.java)
                ?.let { toValidProperties(it) }
        } catch (exception: IllegalStateException) {
            throw exception
        } catch (exception: Exception) {
            throw IllegalStateException("security.crypto keyring query failed", exception)
        }
    }

    private fun requireExisting(reason: String): CryptoProperties {
        return findExisting() ?: throw IllegalStateException(
            "security.crypto keyring missing after $reason; refusing to generate a new key"
        )
    }

    private fun toValidProperties(document: TSecurityCryptoKeyring?): CryptoProperties? {
        if (document == null) {
            return null
        }
        check(document.id == TSecurityCryptoKeyring.GLOBAL_ID) {
            "security.crypto keyring is incomplete: unexpected id"
        }
        check(document.schemaVersion == TSecurityCryptoKeyring.SCHEMA_VERSION) {
            "security.crypto keyring is incomplete: unsupported schemaVersion"
        }
        check(document.rsaAlgorithm.isNotBlank()) {
            "security.crypto keyring is incomplete: rsaAlgorithm"
        }
        check(document.fingerprint.isNotBlank()) {
            "security.crypto keyring is incomplete: fingerprint"
        }
        val properties = document.toProperties()
        check(properties.privateKeyStr.isEmpty() == properties.publicKeyStr.isEmpty()) {
            "security.crypto keyring is incomplete: publicKeyStr"
        }
        check(properties.privateKeyStr2048PKCS8.isEmpty() == properties.publicKeyStr2048PKCS8.isEmpty()) {
            "security.crypto keyring is incomplete: publicKeyStr2048PKCS8"
        }
        check(properties.privateKeyStr2048PKCS1.isEmpty() == properties.publicKeyStr2048PKCS1.isEmpty()) {
            "security.crypto keyring is incomplete: publicKeyStr2048PKCS1"
        }
        check(properties.aesKey.isEmpty() == properties.aesIv.isEmpty()) {
            "security.crypto keyring is incomplete: aesKey/aesIv"
        }
        check(properties.fingerprint() == document.fingerprint) {
            "security.crypto keyring is incomplete: fingerprint mismatch"
        }
        return properties
    }

    private fun idQuery(): Query {
        return Query(Criteria.where(ID).isEqualTo(TSecurityCryptoKeyring.GLOBAL_ID))
    }

    companion object {
        private const val ID = "_id"
        private const val FINGERPRINT = "fingerprint"
        private const val CREATED_AT = "createdAt"
    }
}
