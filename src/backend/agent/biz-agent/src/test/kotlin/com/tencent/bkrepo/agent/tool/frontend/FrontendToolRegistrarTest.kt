/*
 * Tencent is pleased to support the open source community by making BK-CI 蓝鲸持续集成平台 available.
 *
 * Copyright (C) 2026 Tencent.  All rights reserved.
 *
 * BK-CI 蓝鲸持续集成平台 is licensed under the MIT license.
 */

package com.tencent.bkrepo.agent.tool.frontend

import com.tencent.bkrepo.agent.config.properties.EffectiveAgentRuntimeProperties
import com.tencent.bkrepo.agent.tool.local.LocalToolDefinitions
import io.agentscope.core.tool.Toolkit
import io.agentscope.core.tool.ToolkitConfig
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class FrontendToolRegistrarTest {

    private val catalog = FrontendToolCatalog()

    @Test
    fun `默认注册全部客户端工具`() {
        val toolkit = register(readOnlyMode = false, frontendToolsEnabled = true)

        assertEquals(catalog.registeredToolNames, toolkit.toolNames)
    }

    @Test
    fun `只读模式下只注册只读工具写工具对模型不可见`() {
        val toolkit = register(readOnlyMode = true, frontendToolsEnabled = true)

        val (writeTools, readTools) = LocalToolDefinitions.allTools()
            .map { it.name }
            .partition { catalog.isWriteTool(it) }
        assertTrue(writeTools.isNotEmpty(), "fixture broken: no write tool in catalog")
        assertEquals(readTools.toSet(), toolkit.toolNames)
        writeTools.forEach { assertTrue(it !in toolkit.toolNames, "$it should not be registered in read-only mode") }
    }

    @Test
    fun `关闭客户端工具开关时一个都不注册`() {
        val toolkit = register(readOnlyMode = false, frontendToolsEnabled = false)

        assertTrue(toolkit.toolNames.isEmpty())
    }

    /**
     * 只读模式收缩的是 toolkit 注册，不收缩服务端 allowlist：allowlist 还要给子 Agent 的
     * allowedToolNames 校验与挂起调用恢复识别工具名用，详见 [FrontendToolRegistrar.registeredToolNames]。
     */
    @Test
    fun `只读模式不收缩服务端工具名allowlist`() {
        val toolkit = Toolkit(ToolkitConfig.builder().parallel(false).build())
        val properties = propertiesOf(readOnlyMode = true, frontendToolsEnabled = true)
        val registrar = FrontendToolRegistrar(toolkit, properties, catalog)

        assertEquals(catalog.registeredToolNames, registrar.registeredToolNames)
    }

    private fun register(readOnlyMode: Boolean, frontendToolsEnabled: Boolean): Toolkit {
        val toolkit = Toolkit(ToolkitConfig.builder().parallel(false).build())
        FrontendToolRegistrar(toolkit, propertiesOf(readOnlyMode, frontendToolsEnabled), catalog).register()
        return toolkit
    }

    private fun propertiesOf(readOnlyMode: Boolean, frontendToolsEnabled: Boolean): EffectiveAgentRuntimeProperties =
        EffectiveAgentRuntimeProperties.defaults()
            .copy(readOnlyMode = readOnlyMode, frontendToolsEnabled = frontendToolsEnabled)
}
