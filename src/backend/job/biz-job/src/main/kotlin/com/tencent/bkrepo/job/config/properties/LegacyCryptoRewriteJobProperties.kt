package com.tencent.bkrepo.job.config.properties

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Component

/**
 * 存量密文重加密。默认关闭，升级后由运维通过 PUT /api/job/update/LegacyCryptoRewriteJob 打开跑一次，
 * 跑到一轮没有改动 job 会自行 disable，重启后读回这里的 false，不会复活。
 * cron 不能省，JobUtils.parseBatchJob 在 cron/fixedDelay/fixedRate 全空时会直接报错。
 */
@Component
@ConfigurationProperties("job.legacy-crypto-rewrite")
class LegacyCryptoRewriteJobProperties : MongodbJobProperties(enabled = false) {
    override var cron: String = "0 0 3 * * ?"
}
