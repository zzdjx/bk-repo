package com.tencent.bkrepo.common.metadata.util.drive

import com.tencent.bkrepo.common.metadata.pojo.drive.DriveMetadataQueryRule
import com.tencent.bkrepo.common.query.enums.OperationType
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DriveNodeSeriesCountHelperTest {

    @Test
    fun `EXISTS latest type requires field present and not null`() {
        val json = DriveNodeSeriesCountHelper.resolveLatestTypeRuleCriteria(
            DriveMetadataQueryRule(key = "IMATE_ARTIFACT_TYPE", operation = OperationType.EXISTS),
        ).criteriaObject.toJson()

        assertTrue(json.contains(DriveNodeSeriesCountHelper.LATEST_VERSION_TYPE_FIELD))
        assertTrue(json.contains("\$exists"))
        assertTrue(json.contains("\$ne"))
    }
}
