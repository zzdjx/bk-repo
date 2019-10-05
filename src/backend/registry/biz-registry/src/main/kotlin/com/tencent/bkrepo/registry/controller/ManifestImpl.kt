package com.tencent.bkrepo.registry.controller

import com.fasterxml.jackson.databind.ObjectMapper
import com.tencent.bkrepo.registry.api.Manifest
// import com.tencent.bkrepo.registry.manifest2.ManifestDeserializer
// import com.tencent.bkrepo.registry.manifest2.ManifestHandler
// import com.tencent.bkrepo.registry.util.Digest
import okhttp3.MediaType
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.web.bind.annotation.RestController

/**
 * 元数据服务接口实现类
 *
 * @author: owenlxu
 * @date: 2019-10-03
 */

// ManifestImpl validates and impl the manifest interface
@RestController
class ManifestImpl @Autowired constructor(val objectMapper: ObjectMapper) : Manifest {

    override fun putManifest(
        name: String,
        reference: String,
        contentTypeHeader: String,
        body: String
    ): String {
//        var manHandle = ManifestHandler("", "")
        var mediaType = MediaType.parse(contentTypeHeader).toString()

        return "Hello, $name, $reference ,$mediaType，$body"
    }
}
