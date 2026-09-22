import DOMPurify from 'dompurify'
import { marked } from 'marked'
import { Base64 } from 'js-base64'
import { appendPreviewTokenToUrl } from '@repository/utils/previewOfficeFile'
import { isCode, isHtmlFile, isJsx, isMarkdown } from '@repository/utils/file'
import {
    buildJsxSandboxSrcdoc as buildJsxSandboxSrcdocCore,
    isMathFenceLanguage,
    isMermaidFenceLanguage,
    normalizeCodeText as normalizeCodeTextCore,
    normalizeMarkdownText as normalizeMarkdownTextCore,
    normalizePreviewPath,
    resolveMonacoLanguage,
    resolveRelativePath,
    rewriteMarkdownImageUrls,
    rewriteMarkdownMath,
    wrapMathSource,
    wrapMermaidSource,
    extractMarkdownAbbreviations,
    extractMarkdownFootnotes,
    applyMarkdownAbbreviations,
    appendMarkdownFootnotes,
    isPlantumlFenceLanguage,
    wrapPlantumlSource
} from '@repository/utils/markdownJsxPreviewCore'
import {
    extractMarkdownFrontMatter,
    fillMarkdownToc,
    renderMarkdownHeading,
    rewriteMarkdownAlerts,
    rewriteMarkdownDefinitionLists,
    rewriteMarkdownDetails,
    rewriteMarkdownEmoji,
    rewriteMarkdownHighlight,
    rewriteMarkdownImageSize,
    rewriteMarkdownParagraphAttrs,
    rewriteMarkdownSubSup,
    rewriteMarkdownTocPlaceholder,
    rewriteMarkdownWikilinks
} from '@repository/utils/markdownSyntaxExtras'

export {
    normalizePreviewPath,
    parsePreviewContext,
    resolvePreviewViewMode,
    resolveRelativePath,
    rewriteMarkdownImageUrls
} from '@repository/utils/markdownJsxPreviewCore'

export { prepareJsxSource } from '@repository/utils/markdownJsxPreviewCore'

export { renderMermaidCharts } from '@repository/utils/markdownMermaidPreview'

export { renderMathFormulas } from '@repository/utils/markdownMathPreview'

export { renderPlantumlDiagrams } from '@repository/utils/markdownPlantumlPreview'

export { buildHtmlSandboxSrcdoc } from '@repository/utils/htmlFilePreview'

export function buildGenericAssetUrl ({ projectId, repoName, resolvedPath }) {
    if (!projectId || !repoName || !resolvedPath) {
        return null
    }
    const path = normalizePreviewPath(resolvedPath)
    const base = `${location.origin}${window.BK_SUBPATH}web/generic/${projectId}/${repoName}${path}?download=true`
    return appendPreviewTokenToUrl(base)
}

export function createAssetResolver (context) {
    return (relativePath) => {
        const resolved = resolveRelativePath(context.filePath, relativePath)
        if (!resolved || /^(https?:|data:|mailto:)/i.test(resolved)) {
            return resolved
        }
        return buildGenericAssetUrl({
            projectId: context.projectId,
            repoName: context.repoName,
            resolvedPath: resolved
        })
    }
}

export function normalizeMarkdownText (text) {
    return normalizeMarkdownTextCore(text, (value) => Base64.decode(value))
}

export function normalizeCodeText (text) {
    return normalizeCodeTextCore(text, (value) => Base64.decode(value))
}

function createMarkdownRenderer () {
    const renderer = new marked.Renderer()
    const originalCode = renderer.code.bind(renderer)
    renderer.code = function (code, infostring, escaped) {
        if (isMermaidFenceLanguage(infostring)) {
            return wrapMermaidSource(code)
        }
        if (isMathFenceLanguage(infostring)) {
            return wrapMathSource(code, true)
        }
        if (isPlantumlFenceLanguage(infostring)) {
            return wrapPlantumlSource(code)
        }
        return originalCode(code, infostring, escaped)
    }
    renderer.heading = function (text, level, raw, slugger) {
        return renderMarkdownHeading(text, level, raw, slugger)
    }
    renderer.listitem = function (text, task) {
        if (task) {
            return `<li class="task-list-item">${text}</li>\n`
        }
        return `<li>${text}</li>\n`
    }
    renderer.list = function (body, ordered, start) {
        const type = ordered ? 'ol' : 'ul'
        const taskClass = /class="task-list-item"/.test(body) ? ' class="contains-task-list"' : ''
        const startAttr = (ordered && start !== 1) ? (` start="${start}"`) : ''
        return `<${type}${taskClass}${startAttr}>\n${body}</${type}>\n`
    }
    return renderer
}

export function renderMarkdownToSafeHtml (markdown, { resolveAssetUrl, highlight } = {}) {
    marked.setOptions({
        gfm: true,
        breaks: true,
        renderer: createMarkdownRenderer(),
        highlight: (code, language) => {
            if (isMermaidFenceLanguage(language) || isMathFenceLanguage(language) || isPlantumlFenceLanguage(language) || !highlight) {
                return code
            }
            if (language && highlight.getLanguage(language)) {
                return highlight.highlight(code, { language }).value
            }
            return highlight.highlightAuto(code).value
        }
    })
    const frontMatter = extractMarkdownFrontMatter(markdown)
    const { markdown: withoutFootnoteDefs, footnotes } = extractMarkdownFootnotes(frontMatter.markdown)
    const { markdown: withoutAbbrDefs, abbreviations } = extractMarkdownAbbreviations(withoutFootnoteDefs)
    const renderFragment = (body) => {
        return marked.parse(applyMarkdownAbbreviations(rewriteMarkdownMath(body), abbreviations))
    }
    const renderInline = (text) => {
        return marked.parseInline(applyMarkdownAbbreviations(rewriteMarkdownMath(text), abbreviations))
    }
    let next = rewriteMarkdownAlerts(withoutAbbrDefs, renderFragment)
    next = rewriteMarkdownDefinitionLists(next, renderInline)
    next = rewriteMarkdownImageSize(next)
    next = rewriteMarkdownMath(next)
    next = applyMarkdownAbbreviations(next, abbreviations)
    next = rewriteMarkdownHighlight(next)
    next = rewriteMarkdownEmoji(next)
    next = rewriteMarkdownTocPlaceholder(next)
    next = rewriteMarkdownWikilinks(next)
    next = rewriteMarkdownSubSup(next)
    next = rewriteMarkdownParagraphAttrs(next, renderInline)
    next = rewriteMarkdownDetails(next, renderFragment, renderInline)
    const rawHtml = marked.parse(next)
    const htmlWithToc = fillMarkdownToc(rawHtml)
    const htmlWithFootnotes = appendMarkdownFootnotes(htmlWithToc, footnotes, renderFragment)
    const htmlWithAssets = rewriteMarkdownImageUrls(htmlWithFootnotes, resolveAssetUrl)
    return DOMPurify.sanitize(`${frontMatter.html}${htmlWithAssets}`, {
        ADD_TAGS: ['mark'],
        ADD_ATTR: ['open', 'width', 'height', 'align']
    })
}

export function getPreviewLibBaseUrl () {
    return `${location.origin}${window.BK_SUBPATH}ui/libs/`
}

export function buildJsxSandboxSrcdoc (jsxSource) {
    return buildJsxSandboxSrcdocCore(jsxSource, getPreviewLibBaseUrl())
}

export function getMonacoLanguage (filePath) {
    if (isMarkdown(filePath)) {
        return 'markdown'
    }
    if (isJsx(filePath)) {
        return 'javascriptreact'
    }
    if (isHtmlFile(filePath)) {
        return 'html'
    }
    if (isCode(filePath)) {
        return resolveMonacoLanguage(filePath)
    }
    return 'plaintext'
}

export function getPreviewFileKind (filePath) {
    if (isMarkdown(filePath)) {
        return 'markdown'
    }
    if (isJsx(filePath)) {
        return 'jsx'
    }
    if (isHtmlFile(filePath)) {
        return 'html'
    }
    if (isCode(filePath)) {
        return 'code'
    }
    return null
}
