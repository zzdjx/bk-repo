package com.tencent.bkrepo.common.metadata.util.drive

import com.tencent.bkrepo.common.metadata.pojo.drive.DriveMetadataQueryRule
import com.tencent.bkrepo.common.query.enums.OperationType
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DriveNodeDaoHelperSearchCriteriaTest {

    @Test
    fun `EXISTS matches metadata key without value predicate`() {
        val json = DriveNodeDaoHelper.searchCriteria(
            projectId = "p1",
            repoName = "drive",
            metadata = listOf(
                DriveMetadataQueryRule(key = "IMATE_ARTIFACT_TYPE", operation = OperationType.EXISTS),
            ),
        ).criteriaObject.toJson()

        assertTrue(json.contains("\$elemMatch"))
        assertTrue(json.contains("IMATE_ARTIFACT_TYPE"))
        assertTrue(json.contains("imate_artifact_type"))
        assertFalse(json.contains("\"value\""))
        assertFalse(json.contains("\$ne"))
    }

    @Test
    fun `EXISTS on lowercase key does not add case or`() {
        val json = DriveNodeDaoHelper.searchCriteria(
            projectId = "p1",
            repoName = "drive",
            metadata = listOf(
                DriveMetadataQueryRule(key = "author", operation = OperationType.EXISTS),
            ),
        ).criteriaObject.toJson()

        assertTrue(json.contains("\$elemMatch"))
        assertTrue(json.contains("author"))
        assertFalse(json.contains("\$or"))
        assertFalse(json.contains("\"value\""))
    }

    @Test
    fun `EQ still matches metadata value`() {
        val json = DriveNodeDaoHelper.searchCriteria(
            projectId = "p1",
            repoName = "drive",
            metadata = listOf(
                DriveMetadataQueryRule(key = "IMATE_ARTIFACT_TYPE", value = "pdf", operation = OperationType.EQ),
            ),
        ).criteriaObject.toJson()

        assertTrue(json.contains("\$elemMatch"))
        assertTrue(json.contains("pdf"))
        assertTrue(json.contains("\"value\""))
    }
}
