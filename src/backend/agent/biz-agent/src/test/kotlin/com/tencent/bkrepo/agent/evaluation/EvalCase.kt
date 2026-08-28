/*
 * Tencent is pleased to support the open source community by making BK-CI 蓝鲸持续集成平台 available.
 *
 * Copyright (C) 2026 Tencent.  All rights reserved.
 *
 * BK-CI 蓝鲸持续集成平台 is licensed under the MIT license.
 *
 * A copy of the MIT License is included in this file.
 *
 *
 * Terms of the MIT License:
 * ---------------------------------------------------
 * Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated
 * documentation files (the "Software"), to deal in the Software without restriction, including without limitation the
 * rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the Software, and to
 * permit persons to whom the Software is furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all copies or substantial portions of
 * the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT
 * LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN
 * NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY,
 * WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE
 * SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */

package com.tencent.bkrepo.agent.evaluation

import com.fasterxml.jackson.databind.ObjectMapper

/**
 * 离线评估用例：给定用户第一句话，断言协调者的"首次决策"——要么发起某个工具调用，要么直接给出文本回复。
 *
 * ## 为什么只覆盖"首次决策"，不覆盖多轮工具结果之后的后续推理
 *
 * 本地客户端工具（[com.tencent.bkrepo.agent.tool.local.ExternalLocalTool]）真正执行前必定挂起
 * （`callAsync` 恒抛 `ToolSuspendException`，交给客户端本地执行），因此一次
 * `HarnessAgent.streamEvents(...)` 调用天然只能推进到"模型决定调用某个工具"或"模型直接给出文本回复"
 * 为止——观察不到"客户端把工具结果传回来之后模型会怎么做"。要覆盖后续多轮决策，需要像
 * `FlattenedWriteToolSuspensionEndToEndTest` 那样驱动完整的 AG-UI resume 协议、手工构造客户端回传的
 * 工具结果，属于明显更重的机制，留给后续增量按需补充（例如"list_download_tasks 返回空列表后，模型是否
 * 如实说明找不到任务而不是编造 taskId"这类反幻觉场景，就需要这条能力）。
 *
 * 即便只有"首次决策"这一层，仍然能覆盖工具选择正确性、破坏性操作是否触发 ASK 确认、是否会在没有
 * taskId 时凭空编造、是否会越界回答与下载客户端无关的问题这几类真正有价值的信号。
 */
data class EvalCase(
    val id: String,
    val description: String,
    val userMessage: String,
    val expectations: List<EvalExpectation>,
)

/** 一次评估调用的可观察结果：实际发起的工具调用、触发 ASK 的工具调用、最终文本回复。 */
data class EvalTranscript(
    val calledTools: List<CalledTool>,
    val askedToolCallIds: Set<String>,
    val finalText: String,
) {
    data class CalledTool(val toolCallId: String, val toolName: String, val argsDigest: String?)

    fun wasAsked(toolCallId: String): Boolean = toolCallId in askedToolCallIds
}

sealed interface EvalExpectation {

    /** 返回 null 表示通过；否则返回描述失败原因的文本，用于断言失败信息里展示。 */
    fun check(transcript: EvalTranscript): String?

    /** 必须调用指定工具；[argContains] 里每个键值对都必须能在实际调用参数 JSON 里按字符串值匹配上。 */
    data class MustCallTool(
        val toolName: String,
        val argContains: Map<String, String> = emptyMap(),
    ) : EvalExpectation {
        override fun check(transcript: EvalTranscript): String? {
            val matches = transcript.calledTools.filter { it.toolName == toolName }
            if (matches.isEmpty()) {
                return "期望调用工具 '$toolName'，但实际未调用该工具"
            }
            if (argContains.isEmpty()) return null
            val argMatched = matches.any { call -> argContainsAll(call.argsDigest, argContains) }
            if (!argMatched) {
                return "期望调用工具 '$toolName' 且参数满足 $argContains，" +
                    "但实际调用参数为 ${matches.map { it.argsDigest }}"
            }
            return null
        }
    }

    /** 不能调用指定工具（越权/破坏性操作误触发防护、防止跳过诊断直接下重手）。 */
    data class MustNotCallTool(val toolName: String) : EvalExpectation {
        override fun check(transcript: EvalTranscript): String? {
            val matched = transcript.calledTools.filter { it.toolName == toolName }
            if (matched.isNotEmpty()) {
                return "期望不调用工具 '$toolName'，但实际调用了：${matched.map { it.argsDigest }}"
            }
            return null
        }
    }

    /** 本轮不应调用任何工具（用于拒绝越界请求、纯文本澄清场景）。 */
    data object MustNotCallAnyTool : EvalExpectation {
        override fun check(transcript: EvalTranscript): String? {
            if (transcript.calledTools.isNotEmpty()) {
                return "期望本轮不调用任何工具，但实际调用了：${transcript.calledTools.map { it.toolName }}"
            }
            return null
        }
    }

    /** 指定工具的调用必须触发 ASK 二次确认（而不是被静默放行）。 */
    data class MustAskConfirmation(val toolName: String) : EvalExpectation {
        override fun check(transcript: EvalTranscript): String? {
            val matched = transcript.calledTools.filter { it.toolName == toolName }
            if (matched.isEmpty()) {
                return "期望工具 '$toolName' 触发 ASK 确认，但该工具本轮根本未被调用"
            }
            if (matched.none { transcript.wasAsked(it.toolCallId) }) {
                return "期望工具 '$toolName' 触发 ASK 确认，但实际未观察到 ASKING（可能被直接放行执行）"
            }
            return null
        }
    }

    /** 最终文本回复必须匹配给定正则（用于校验拒绝/澄清措辞，或校验字段确有提及）。 */
    data class ResponseMustMatch(val pattern: Regex) : EvalExpectation {
        override fun check(transcript: EvalTranscript): String? {
            if (!pattern.containsMatchIn(transcript.finalText)) {
                return "期望最终回复匹配正则 /${pattern.pattern}/，实际回复：${transcript.finalText}"
            }
            return null
        }
    }

    /** 最终文本回复不能匹配给定正则（反幻觉：例如不应输出看起来像真实 taskId 的编造内容）。 */
    data class ResponseMustNotMatch(val pattern: Regex) : EvalExpectation {
        override fun check(transcript: EvalTranscript): String? {
            if (pattern.containsMatchIn(transcript.finalText)) {
                return "期望最终回复不匹配正则 /${pattern.pattern}/，实际回复：${transcript.finalText}"
            }
            return null
        }
    }

    companion object {
        private val objectMapper = ObjectMapper()

        private fun argContainsAll(argsDigest: String?, expected: Map<String, String>): Boolean {
            if (argsDigest.isNullOrBlank()) return false
            val node = runCatching { objectMapper.readTree(argsDigest) }.getOrNull() ?: return false
            return expected.all { (key, value) -> node.get(key)?.asText() == value }
        }
    }
}
