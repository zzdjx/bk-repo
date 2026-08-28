/*
 * Tencent is pleased to support the open source community by making BK-CI 蓝鲸持续集成平台 available.
 *
 * Copyright (C) 2026 Tencent.  All rights reserved.
 *
 * BK-CI 蓝鲸持续集成平台 is licensed under the MIT license.
 */

package com.tencent.bkrepo.agent.service.impl

import com.tencent.bkrepo.agent.memory.MemoryFilesystemAccess
import com.tencent.bkrepo.agent.pojo.AgentMemoryFileInfo
import com.tencent.bkrepo.agent.service.AgentMemoryService
import com.tencent.bkrepo.common.api.exception.NotFoundException
import com.tencent.bkrepo.common.api.message.CommonMessageCode
import com.tencent.bkrepo.common.api.util.Preconditions
import io.agentscope.core.agent.RuntimeContext
import org.springframework.stereotype.Service

@Service
class AgentMemoryServiceImpl(
    private val memoryFilesystemAccess: MemoryFilesystemAccess,
) : AgentMemoryService {

    override fun listFiles(userId: String): List<AgentMemoryFileInfo> {
        assertAvailable()
        return memoryFilesystemAccess.listFiles(runtimeContextFor(userId)).map {
            AgentMemoryFileInfo(it.path, it.modifiedAt)
        }
    }

    override fun readFile(userId: String, path: String): String {
        assertAvailable()
        assertValidPath(path)
        return memoryFilesystemAccess.readFile(runtimeContextFor(userId), path)
            ?: throw NotFoundException(CommonMessageCode.RESOURCE_NOT_FOUND, "Memory file[$path]")
    }

    override fun deleteFile(userId: String, path: String): Boolean {
        assertAvailable()
        assertValidPath(path)
        return memoryFilesystemAccess.deleteFile(runtimeContextFor(userId), path)
    }

    override fun clearAll(userId: String) {
        assertAvailable()
        memoryFilesystemAccess.deleteAll(runtimeContextFor(userId))
    }

    private fun assertAvailable() {
        if (!memoryFilesystemAccess.isAvailable()) {
            throw NotFoundException(CommonMessageCode.RESOURCE_NOT_FOUND, "long-term memory")
        }
    }

    private fun assertValidPath(path: String) {
        Preconditions.checkNotBlank(path, "path")
        val normalized = path.trim().removePrefix("/")
        val valid = normalized == MemoryFilesystemAccess.MEMORY_MD ||
            normalized.startsWith("${MemoryFilesystemAccess.MEMORY_DIR}/")
        Preconditions.checkArgument(valid, "path")
    }

    private fun runtimeContextFor(userId: String): RuntimeContext = RuntimeContext.builder().userId(userId).build()
}
