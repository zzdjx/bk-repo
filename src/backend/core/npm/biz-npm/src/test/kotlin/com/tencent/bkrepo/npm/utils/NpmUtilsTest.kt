package com.tencent.bkrepo.npm.utils

import com.tencent.bkrepo.common.api.exception.ErrorCodeException
import com.tencent.bkrepo.npm.artifact.NpmArtifactInfo
import com.tencent.bkrepo.npm.exception.NpmBadRequestException
import com.tencent.bkrepo.npm.model.metadata.NpmPackageMetaData
import com.tencent.bkrepo.npm.model.metadata.NpmVersionMetadata
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class NpmUtilsTest {

    private fun buildMetaData(name: String?, version: String, attachment: String): NpmPackageMetaData {
        val versionMetadata = NpmVersionMetadata().apply {
            this.name = name
            this.version = version
        }
        return NpmPackageMetaData().apply {
            this.name = name
            versions.map[version] = versionMetadata
            attachments = NpmPackageMetaData.Attachments().apply {
                add(attachment, NpmPackageMetaData.Attachment())
            }
        }
    }

    @Test
    fun testCheckPackageMetaData() {
        NpmUtils.checkPackageMetaData(buildMetaData("demo", "1.0.0", "demo-1.0.0.tgz"))
        // scope包的tarball文件名自带scope前缀，不能当成单层文件名校验
        NpmUtils.checkPackageMetaData(buildMetaData("@test/demo", "1.0.0", "@test/demo-1.0.0.tgz"))

        // 包名、版本号、tarball文件名中的路径分隔符与..会被拼进fullPath，必须拒绝
        assertThrows<ErrorCodeException> {
            NpmUtils.checkPackageMetaData(buildMetaData("@test/..", "1.0.0", "demo-1.0.0.tgz"))
        }
        assertThrows<NpmBadRequestException> {
            NpmUtils.checkPackageMetaData(buildMetaData("../../evil", "1.0.0", "demo-1.0.0.tgz"))
        }
        assertThrows<ErrorCodeException> {
            NpmUtils.checkPackageMetaData(buildMetaData("demo", "../../evil", "demo-1.0.0.tgz"))
        }
        assertThrows<NpmBadRequestException> {
            NpmUtils.checkPackageMetaData(buildMetaData("demo", "1.0.0", "../evil.tgz"))
        }
        assertThrows<NpmBadRequestException> {
            NpmUtils.checkPackageMetaData(buildMetaData("@test/demo", "1.0.0", "@test/../evil.tgz"))
        }
        assertThrows<ErrorCodeException> {
            NpmUtils.checkPackageMetaData(buildMetaData("@test/demo", "1.0.0", "@test/.."))
        }
        assertThrows<NpmBadRequestException> { NpmUtils.checkPackageMetaData(buildMetaData(null, "1.0.0", "a.tgz")) }

        // 版本元数据里的包名必须与顶层包名一致，否则会写到其他包的路径下
        val mismatch = buildMetaData("demo", "1.0.0", "demo-1.0.0.tgz")
        mismatch.versions.map.values.first().name = "victim"
        assertThrows<NpmBadRequestException> { NpmUtils.checkPackageMetaData(mismatch) }
    }

    @Test
    fun testBuildPackageTgzTarball() {
        val oldTarball =
            "http://bkrepo.example.com/npm/blueking/npm-local/@test/bkrepo-test/-/@test/bkrepo-test-1.0.0.tgz"
        val domain = "http://bkrepo.example.com/npm"
        val tarballPrefix = "http://bkrepo.example.com/npm"
        val name = "@test/bkrepo-test"
        val artifactInfo = NpmArtifactInfo(projectId = "blueking", repoName = "npm-local", artifactUri = "/")

        // not return repo id
        var tarball = NpmUtils.buildPackageTgzTarball(
            oldTarball, domain, tarballPrefix, false, name, artifactInfo
        )
        assertEquals(
            "$tarballPrefix/@test/bkrepo-test/-/@test/bkrepo-test-1.0.0.tgz",
            tarball
        )

        // return repo id
        tarball = NpmUtils.buildPackageTgzTarball(
            oldTarball, domain, tarballPrefix, true, name, artifactInfo
        )
        assertEquals(
            "$tarballPrefix/blueking/npm-local/@test/bkrepo-test/-/@test/bkrepo-test-1.0.0.tgz",
            tarball
        )

        // tarball prefix is empty
        tarball = NpmUtils.buildPackageTgzTarball(
            oldTarball, domain, "", false, name, artifactInfo
        )
        assertEquals(
            "$domain/blueking/npm-local/@test/bkrepo-test/-/@test/bkrepo-test-1.0.0.tgz",
            tarball
        )
    }
}
