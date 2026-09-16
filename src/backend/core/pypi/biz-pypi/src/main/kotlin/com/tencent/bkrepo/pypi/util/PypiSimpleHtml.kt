package com.tencent.bkrepo.pypi.util

import com.tencent.bkrepo.pypi.constants.INDENT
import com.tencent.bkrepo.pypi.constants.LINE_BREAK
import com.tencent.bkrepo.pypi.constants.NON_ALPHANUMERIC_SEQ_REGEX
import com.tencent.bkrepo.pypi.constants.REQUIRES_PYTHON_ATTR
import com.tencent.bkrepo.pypi.constants.SIMPLE_PAGE_CONTENT

object PypiSimpleHtml {

    private val nonAlphanumericSeqRegex = Regex(NON_ALPHANUMERIC_SEQ_REGEX)

    fun fileNodeLink(fullPath: String, name: String, sha256: String?, requiresPython: String?): String {
        val href = HtmlUtils.escape("../../packages$fullPath#sha256=$sha256")
        val requiresPythonAttr = requiresPython
            ?.let { " $REQUIRES_PYTHON_ATTR=\"${HtmlUtils.escape(it)}\"" } ?: ""
        return "$INDENT<a href=\"$href\"$requiresPythonAttr rel=\"internal\">${HtmlUtils.escape(name)}</a>$LINE_BREAK"
    }

    fun packageListItem(name: String): String {
        val href = HtmlUtils.escape("${name.replace(nonAlphanumericSeqRegex, "-").lowercase()}/")
        return "$INDENT<a href=\"$href\" rel=\"internal\">${HtmlUtils.escape(name)}</a>$LINE_BREAK"
    }

    fun page(title: String, listContent: String): String {
        val safeTitle = HtmlUtils.escape(title)
        return SIMPLE_PAGE_CONTENT.format(safeTitle, safeTitle, listContent)
    }
}
