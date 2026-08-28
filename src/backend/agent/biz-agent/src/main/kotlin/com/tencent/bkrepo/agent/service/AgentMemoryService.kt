/*
 * Tencent is pleased to support the open source community by making BK-CI 蓝鲸持续集成平台 available.
 *
 * Copyright (C) 2026 Tencent.  All rights reserved.
 *
 * BK-CI 蓝鲸持续集成平台 is licensed under the MIT license.
 */

package com.tencent.bkrepo.agent.service

import com.tencent.bkrepo.agent.pojo.AgentMemoryFileInfo

/**
 * 用户直连查看/删除长期记忆（不经过 LLM 对话）。底层通过
 * [com.tencent.bkrepo.agent.memory.MemoryFilesystemAccess] 与 `memory_save`/`memory_search`/
 * `memory_get`/`memory_delete` 读写同一份存储，按 `userId` 隔离（不区分 `projectId`）。
 *
 * 未启用长期记忆（无 Redis）时统一按资源不存在处理。
 */
interface AgentMemoryService {

    /** 列出用户已保存的全部记忆文件，按路径排序。 */
    fun listFiles(userId: String): List<AgentMemoryFileInfo>

    /** 读取单个记忆文件完整内容；文件不存在时抛出 [com.tencent.bkrepo.common.api.exception.NotFoundException]。 */
    fun readFile(userId: String, path: String): String

    /** 删除单个记忆文件，幂等（文件本就不存在也返回 `true`）。 */
    fun deleteFile(userId: String, path: String): Boolean

    /** 清空用户的全部长期记忆。 */
    fun clearAll(userId: String)
}
