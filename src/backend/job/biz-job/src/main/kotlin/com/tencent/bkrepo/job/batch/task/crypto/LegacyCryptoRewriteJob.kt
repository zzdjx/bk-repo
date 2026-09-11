package com.tencent.bkrepo.job.batch.task.crypto

import com.tencent.bkrepo.common.api.constant.StringPool
import com.tencent.bkrepo.common.api.util.readJsonString
import com.tencent.bkrepo.common.artifact.pojo.configuration.RepositoryConfiguration
import com.tencent.bkrepo.common.artifact.pojo.configuration.composite.CompositeConfiguration
import com.tencent.bkrepo.common.artifact.pojo.configuration.remote.RemoteConfiguration
import com.tencent.bkrepo.common.mongo.constant.ID
import com.tencent.bkrepo.common.security.util.KeySource
import com.tencent.bkrepo.common.security.util.RsaUtils
import com.tencent.bkrepo.common.service.log.LoggerHolder
import com.tencent.bkrepo.job.batch.base.JobContext
import com.tencent.bkrepo.job.batch.base.MongoDbBatchJob
import com.tencent.bkrepo.job.config.properties.LegacyCryptoRewriteJobProperties
import org.springframework.data.mongodb.core.query.Criteria
import org.springframework.data.mongodb.core.query.Query
import org.springframework.data.mongodb.core.query.Update
import org.springframework.data.mongodb.core.query.isEqualTo
import org.springframework.stereotype.Component
import java.time.Duration
import java.util.concurrent.atomic.AtomicLong
import kotlin.reflect.KClass

/**
 * 把升级前用默认密钥加密的存量密文换成当前密钥。
 *
 * 不依赖任何迁移标记：当前密钥能解开就说明已经换过，只有旧密钥才解得开的才回写。
 * 因此可以重复跑、可以多副本并发跑。一轮没有改动就自行 disable。
 * 存量清零后本类与 LegacyCryptoKeys 一并删除。
 */
@Component
class LegacyCryptoRewriteJob(
    properties: LegacyCryptoRewriteJobProperties
) : MongoDbBatchJob<LegacyCryptoRewriteJob.CryptoRow, LegacyCryptoRewriteJob.RewriteContext>(properties) {

    override fun createJobContext() = RewriteContext()

    override fun collectionNames() = listOf(PROXY_CHANNEL, CLUSTER_NODE, REPOSITORY)

    override fun entityClass(): KClass<CryptoRow> = CryptoRow::class

    override fun mapToEntity(row: Map<String, Any?>) = CryptoRow(row)

    override fun getLockAtMostFor(): Duration = Duration.ofHours(1)

    /**
     * 密文里没有版本标记，查不出是哪把密钥加的，这里只能粗筛出带密码的行，
     * 是不是旧密钥留给 [reEncryptOrNull] 现场试解
     */
    override fun buildQuery(): Query {
        return Query(
            Criteria().orOperator(
                Criteria.where(PASSWORD).nin(listOf(null, StringPool.EMPTY)),
                Criteria.where(CONFIGURATION).regex(CONFIG_PASSWORD)
            )
        )
    }

    override fun run(row: CryptoRow, collectionName: String, context: RewriteContext) {
        val rewritten = if (collectionName == REPOSITORY) {
            rewriteConfiguration(row)
        } else {
            rewritePassword(row, collectionName)
        }
        if (rewritten) {
            context.rewritten.incrementAndGet()
        }
    }

    override fun doStart0(jobContext: RewriteContext) {
        super.doStart0(jobContext)
        if (jobContext.rewritten.get() == 0L && jobContext.failed.get() == 0L) {
            logger.info("No legacy ciphertext left, disable job[${getJobName()}].")
            disable()
        }
    }

    private fun rewritePassword(row: CryptoRow, collectionName: String): Boolean {
        val cipher = row.password ?: return false
        val rewritten = reEncryptOrNull(cipher) ?: return false
        return update(collectionName, row.id, PASSWORD, cipher, rewritten)
    }

    private fun rewriteConfiguration(row: CryptoRow): Boolean {
        val raw = row.configuration ?: return false
        val updated = rewriteCiphers(raw)
        return updated != raw && update(REPOSITORY, row.id, CONFIGURATION, raw, updated)
    }

    /**
     * 按原值 CAS，避免与用户同时改密码时把刚改的新密码覆盖回旧值
     */
    private fun update(collection: String, id: Any?, field: String, old: String, new: String): Boolean {
        val query = Query(Criteria.where(ID).isEqualTo(id).and(field).isEqualTo(old))
        return mongoTemplate.updateFirst(query, Update().set(field, new), collection).modifiedCount > 0
    }

    class RewriteContext(val rewritten: AtomicLong = AtomicLong()) : JobContext() {
        override fun toString(): String = "Rewritten[$rewritten], ${super.toString()}"
    }

    data class CryptoRow(private val map: Map<String, Any?>) {
        val id: Any? get() = map[ID]
        val password: String? get() = map[PASSWORD] as? String
        val configuration: String? get() = map[CONFIGURATION] as? String
    }

    companion object {
        /**
         * 反序列化只用来定位密文，回写走原文替换，没有要换的原样返回。
         * JsonUtils 关掉了 FAIL_ON_UNKNOWN_PROPERTIES，整体 toJsonString 会把老版本写入、
         * 当前 POJO 已不认识的字段静默丢掉，而这个 job 会一次性扫过所有仓库，不能担这个风险
         */
        internal fun rewriteCiphers(raw: String): String {
            var updated = raw
            ciphers(raw.readJsonString()).forEach { cipher ->
                reEncryptOrNull(cipher)?.let { updated = updated.replace(quoted(cipher), quoted(it)) }
            }
            return updated
        }

        /**
         * 密文由 encryptBcd 产出，只含十六进制字符，不含需要转义的字符，可以带引号直接串替换
         */
        private fun quoted(cipher: String) = "\"$cipher\""

        private fun ciphers(config: RepositoryConfiguration): List<String> {
            return when (config) {
                is CompositeConfiguration -> config.proxy.channelList.mapNotNull { it.password }
                is RemoteConfiguration -> listOfNotNull(config.credentials.password)
                else -> emptyList()
            }.filter { it.isNotBlank() }.distinct()
        }

        /**
         * 只有旧密钥能解开时才返回新密文，其余一律返回 null 表示这行不该动。
         * 两把都解不开的行必须放过：当成明文再加密一次就变双层密文，原值取不回来了
         */
        internal fun reEncryptOrNull(cipher: String): String? {
            val (plain, source) = try {
                RsaUtils.decryptWithSource(cipher)
            } catch (ignored: Exception) {
                logger.warn("Skip ciphertext that no rsa key can decrypt.")
                return null
            }
            if (source != KeySource.LEGACY) {
                return null
            }
            if (!looksLikePassword(plain)) {
                logger.warn("Skip ciphertext decrypted into implausible plaintext.")
                return null
            }
            return RsaUtils.encrypt(plain)
        }

        /**
         * PKCS#1 v1.5 的 padding 校验有约 1/65536 的概率让错的密钥解出一串随机字节而不抛异常，
         * 这种「明文」一旦重加密回写，原密文就永久盖掉了。随机字节几乎必然带控制字符，
         * 或者不是合法 UTF-8 而被替换成 U+FFFD，用这个兜底拦一道
         */
        private fun looksLikePassword(plain: String): Boolean {
            return plain.isNotEmpty() && plain.none { it.isISOControl() || it == '\uFFFD' }
        }

        private val logger = LoggerHolder.jobLogger
        private const val PROXY_CHANNEL = "proxy_channel"
        private const val CLUSTER_NODE = "cluster_node"
        private const val REPOSITORY = "repository"
        private const val PASSWORD = "password"
        private const val CONFIGURATION = "configuration"

        // 只有 composite/remote 仓库的 configuration 里才带密码，先在服务端滤掉绝大多数仓库
        private const val CONFIG_PASSWORD = "\"password\"\\s*:\\s*\"[^\"]"
    }
}
