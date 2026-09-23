import { previewNavigationGuardSource } from './previewExternalNavigation.js'

const ABSOLUTE_URL_PATTERN = /^(https?:|data:|mailto:)/i

export function normalizePreviewPath (filePath) {
    if (!filePath) {
        return ''
    }
    const normalized = filePath.replace(/\\/g, '/')
    return normalized.startsWith('/') ? normalized : `/${normalized}`
}

export function resolveRelativePath (baseFilePath, relativePath) {
    if (!relativePath || ABSOLUTE_URL_PATTERN.test(relativePath)) {
        return relativePath
    }
    const baseDir = normalizePreviewPath(baseFilePath).replace(/\/[^/]*$/, '') || '/'
    const segments = `${baseDir}/${relativePath}`.split('/')
    const resolved = []
    for (const segment of segments) {
        if (!segment || segment === '.') {
            continue
        }
        if (segment === '..') {
            if (resolved.length === 0) {
                return null
            }
            resolved.pop()
            continue
        }
        resolved.push(segment)
    }
    return `/${resolved.join('/')}`
}

export function parsePreviewContext ({ projectId, repoName, filePath, extraParam }) {
    const context = {
        projectId: projectId || '',
        repoName: repoName || '',
        filePath: normalizePreviewPath(filePath)
    }
    if (!extraParam || extraParam === '0') {
        return context
    }
    try {
        const decoded = typeof extraParam === 'string' && extraParam.includes('%')
            ? decodeURIComponent(extraParam)
            : extraParam
        const payload = JSON.parse(decoded)
        if (payload.projectId) {
            context.projectId = payload.projectId
        }
        if (payload.repoName) {
            context.repoName = payload.repoName
        }
        if (payload.artifactUri) {
            context.filePath = normalizePreviewPath(payload.artifactUri)
        } else if (payload.fullPath) {
            context.filePath = normalizePreviewPath(payload.fullPath)
        } else if (payload.url) {
            const match = payload.url.match(/\/generic\/([^/]+)\/([^/]+)(\/.*)?$/)
            if (match) {
                context.projectId = context.projectId || match[1]
                context.repoName = context.repoName || match[2]
                context.filePath = normalizePreviewPath(match[3] || '')
            }
        }
    } catch (e) {
        // ignore malformed extraParam
    }
    return context
}

export function normalizeMarkdownText (text, decodeBase64) {
    if (!text) {
        return ''
    }
    const trimmed = text.trim()
    if (!trimmed || trimmed.includes('\n#') || trimmed.startsWith('#')) {
        return text
    }
    if (/^[A-Za-z0-9+/=\s]+$/.test(trimmed) && typeof decodeBase64 === 'function') {
        try {
            const decoded = decodeBase64(trimmed)
            if (decoded && (decoded.includes('#') || decoded.includes('```') || decoded.includes('*'))) {
                return decoded
            }
        } catch (e) {
            // keep raw text for backward compatibility
        }
    }
    return text
}

/**
 * Decode preview-service CODE payload (always Base64). Falls back to raw text on failure.
 */
export function normalizeCodeText (text, decodeBase64) {
    if (!text) {
        return ''
    }
    if (typeof decodeBase64 !== 'function') {
        return text
    }
    try {
        const decoded = decodeBase64(text.trim())
        return decoded == null ? text : decoded
    } catch (e) {
        return text
    }
}

const MONACO_LANGUAGE_BY_SUFFIX = {
    java: 'java',
    py: 'python',
    python: 'python',
    go: 'go',
    js: 'javascript',
    html: 'html',
    css: 'css',
    sh: 'shell',
    yaml: 'yaml',
    yml: 'yaml',
    json: 'json',
    sql: 'sql',
    cpp: 'cpp',
    h: 'c',
    c: 'c',
    cs: 'csharp',
    rb: 'ruby',
    php: 'php',
    lua: 'lua',
    aspx: 'plaintext',
    jsp: 'plaintext',
    ftl: 'plaintext',
    xml: 'xml',
    ini: 'ini',
    bat: 'bat',
    txt: 'plaintext',
    log: 'plaintext',
    properties: 'plaintext',
    toml: 'plaintext'
}

function extractFileSuffix (filePath) {
    if (!filePath) {
        return ''
    }
    const normalized = String(filePath).replace(/\\/g, '/')
    const baseName = normalized.includes('/') ? normalized.slice(normalized.lastIndexOf('/') + 1) : normalized
    const dotIndex = baseName.lastIndexOf('.')
    if (dotIndex < 0 || dotIndex === baseName.length - 1) {
        return baseName.toLowerCase()
    }
    return baseName.slice(dotIndex + 1).toLowerCase()
}

/**
 * Map a file path / suffix to a Monaco language id for CODE preview.
 */
export function resolveMonacoLanguage (filePath) {
    const suffix = extractFileSuffix(filePath)
    return MONACO_LANGUAGE_BY_SUFFIX[suffix] || 'plaintext'
}

/**
 * Resolve md/jsx display mode from request params.
 * Query `view` wins over extraParam.view; default is preview.
 */
export function resolvePreviewViewMode ({ query, extraParam } = {}) {
    const fromQuery = normalizeViewMode(query && query.view)
    if (fromQuery) {
        return fromQuery
    }
    const fromExtra = readViewModeFromExtraParam(extraParam)
    if (fromExtra) {
        return fromExtra
    }
    return 'preview'
}

function normalizeViewMode (value) {
    if (value === 'source' || value === 'preview') {
        return value
    }
    return null
}

function readViewModeFromExtraParam (extraParam) {
    if (!extraParam || extraParam === '0') {
        return null
    }
    try {
        const decoded = typeof extraParam === 'string' && extraParam.includes('%')
            ? decodeURIComponent(extraParam)
            : extraParam
        const payload = typeof decoded === 'string' ? JSON.parse(decoded) : decoded
        return normalizeViewMode(payload && payload.view)
    } catch (e) {
        return null
    }
}

const ESM_CDN_PREFIX = 'https://esm.sh/'

function toEsmCdnUrl (specifier) {
    if (/^(https?:|data:)/i.test(specifier)) {
        return specifier
    }
    if (specifier.startsWith('.') || specifier.startsWith('/')) {
        throw new Error(`Relative import is not supported in JSX preview: ${specifier}`)
    }
    return `${ESM_CDN_PREFIX}${specifier}`
}

export function rewriteBareImports (source) {
    let result = source.replace(
        /\bfrom\s+(['"])([^'"]+)\1/g,
        (match, quote, specifier) => `from ${quote}${toEsmCdnUrl(specifier)}${quote}`
    )
    result = result.replace(
        /^\s*import\s+(['"])([^'"]+)\1\s*;?\s*$/gm,
        (match, quote, specifier) => `import ${quote}${toEsmCdnUrl(specifier)}${quote};`
    )
    result = result.replace(
        /\bimport\s*\(\s*(['"])([^'"]+)\1\s*\)/g,
        (match, quote, specifier) => `import(${quote}${toEsmCdnUrl(specifier)}${quote})`
    )
    return result
}

/**
 * Babel standalone `preset-react` defaults to classic runtime
 * (`React.createElement`). User JSX often omits `import React`
 * (new JSX transform style). Detect whether `React` is already bound.
 */
export function hasReactBinding (source) {
    if (!source) {
        return false
    }
    return /^\s*import\s+React(?:\s*,|\s+from\b)/m.test(source)
        || /^\s*import\s+\*\s+as\s+React\s+from\b/m.test(source)
}

export function ensureReactImport (source) {
    if (hasReactBinding(source)) {
        return source
    }
    return `import React from '${ESM_CDN_PREFIX}react';\n${source}`
}

export function prepareJsxSource (jsxSource) {
    if (!jsxSource || !jsxSource.trim()) {
        throw new Error('JSX file is empty')
    }
    if (/\brequire\s*\(/.test(jsxSource)) {
        throw new Error('require() is not supported in JSX preview; use ESM import instead')
    }
    // Inject React into preview host scope; do not require user files to import it.
    let source = ensureReactImport(rewriteBareImports(jsxSource))
    if (/export\s+default\s+/m.test(source)) {
        source = source.replace(/export\s+default\s+/m, 'const __PREVIEW_COMPONENT__ = ')
    }
    if (!source.includes('__PREVIEW_COMPONENT__')) {
        throw new Error('Expected `export default` React component')
    }
    source = `${source}

import { createRoot as __createPreviewRoot } from '${ESM_CDN_PREFIX}react-dom/client';
const __previewRoot = __createPreviewRoot(document.getElementById('root'));
__previewRoot.render(React.createElement(__PREVIEW_COMPONENT__));
`
    return source.replace(/<\/script/gi, '<\\/script')
}

export function isMermaidFenceLanguage (language) {
    const lang = String(language || '').trim().split(/\s+/)[0].toLowerCase()
    return lang === 'mermaid' || lang === 'mermaidchart' || lang === 'mermaid-chart'
}

export function escapeHtml (text) {
    return String(text)
        .replace(/&/g, '&amp;')
        .replace(/</g, '&lt;')
        .replace(/>/g, '&gt;')
        .replace(/"/g, '&quot;')
}

export function wrapMermaidSource (source) {
    const body = escapeHtml(source == null ? '' : source).replace(/\n$/, '')
    return `<div class="mermaid mermaidChart">${body}</div>\n`
}

export function isPlantumlFenceLanguage (language) {
    const lang = String(language || '').trim().split(/\s+/)[0].toLowerCase()
    return lang === 'plantuml' || lang === 'puml' || lang === 'uml'
}

export function wrapPlantumlSource (source) {
    const body = escapeHtml(source == null ? '' : source).replace(/^\s+|\s+$/g, '')
    return `<div class="plantuml">${body}</div>\n`
}

export function isMathFenceLanguage (language) {
    const lang = String(language || '').trim().split(/\s+/)[0].toLowerCase()
    return lang === 'math' || lang === 'latex' || lang === 'katex'
}

export function wrapMathSource (source, display) {
    const body = escapeHtml(source == null ? '' : source).replace(/^\s+|\s+$/g, '')
    if (display) {
        return `<div class="markdown-math markdown-math-display">${body}</div>\n`
    }
    return `<span class="markdown-math markdown-math-inline">${body}</span>`
}

export function isLineStart (source, index) {
    if (index <= 0) {
        return true
    }
    const prev = source.charCodeAt(index - 1)
    return prev === 10 || prev === 13
}

function countRepeat (source, index, char) {
    let count = 0
    while (source[index + count] === char) {
        count += 1
    }
    return count
}

export function skipFenceIndent (source, index) {
    let i = index
    let spaces = 0
    while (i < source.length && spaces < 3 && source[i] === ' ') {
        i += 1
        spaces += 1
    }
    return i
}

function isEscapedChar (source, index) {
    let slashes = 0
    let i = index - 1
    while (i >= 0 && source[i] === '\\') {
        slashes += 1
        i -= 1
    }
    return slashes % 2 === 1
}

export function tryReadFence (source, index) {
    if (!isLineStart(source, index)) {
        return null
    }
    const fenceStart = skipFenceIndent(source, index)
    const fenceChar = source[fenceStart]
    if (fenceChar !== '`' && fenceChar !== '~') {
        return null
    }
    const fenceLen = countRepeat(source, fenceStart, fenceChar)
    if (fenceLen < 3) {
        return null
    }
    const firstNewline = source.indexOf('\n', fenceStart + fenceLen)
    if (firstNewline === -1) {
        return { end: source.length }
    }
    let i = firstNewline + 1
    while (i < source.length) {
        const contentStart = skipFenceIndent(source, i)
        if (source[contentStart] === fenceChar && countRepeat(source, contentStart, fenceChar) >= fenceLen) {
            let end = contentStart + countRepeat(source, contentStart, fenceChar)
            const newline = source.indexOf('\n', end)
            return { end: newline === -1 ? source.length : newline + 1 }
        }
        const newline = source.indexOf('\n', i)
        if (newline === -1) {
            return { end: source.length }
        }
        i = newline + 1
    }
    return { end: source.length }
}

export function tryReadInlineCode (source, index) {
    if (source[index] !== '`') {
        return null
    }
    const ticks = countRepeat(source, index, '`')
    if (ticks === 0) {
        return null
    }
    let i = index + ticks
    while (i < source.length) {
        if (source[i] !== '`') {
            i += 1
            continue
        }
        const closeLen = countRepeat(source, i, '`')
        if (closeLen === ticks) {
            return { end: i + closeLen }
        }
        i += closeLen
    }
    return null
}

export function transformMarkdownOutsideCode (markdown, transformText) {
    if (!markdown || typeof transformText !== 'function') {
        return markdown
    }
    const source = String(markdown)
    let i = 0
    let out = ''
    let buf = ''
    const flush = () => {
        if (buf) {
            out += transformText(buf)
            buf = ''
        }
    }
    while (i < source.length) {
        const fence = tryReadFence(source, i)
        if (fence) {
            flush()
            out += source.slice(i, fence.end)
            i = fence.end
            continue
        }
        const inlineCode = tryReadInlineCode(source, i)
        if (inlineCode) {
            flush()
            out += source.slice(i, inlineCode.end)
            i = inlineCode.end
            continue
        }
        buf += source[i]
        i += 1
    }
    flush()
    return out
}

function findClosingInlineDollar (source, start) {
    let i = start
    while (i < source.length) {
        if (source[i] === '\n') {
            return -1
        }
        if (source[i] === '$' && source[i + 1] !== '$' && !isEscapedChar(source, i)) {
            const prev = source[i - 1]
            if (prev !== ' ' && prev !== '\t') {
                return i
            }
        }
        i += 1
    }
    return -1
}

function findClosingPair (source, start, closer) {
    let from = start
    while (from < source.length) {
        const end = source.indexOf(closer, from)
        if (end === -1) {
            return -1
        }
        if (!isEscapedChar(source, end)) {
            return end
        }
        from = end + closer.length
    }
    return -1
}

/**
 * Replace TeX delimiters with HTML placeholders so marked does not treat
 * `_` / `*` inside formulas as markdown emphasis.
 * Skips fenced and inline code. `$...$` is single-line; `$$...$$` may wrap.
 */
export function rewriteMarkdownMath (markdown) {
    if (!markdown) {
        return markdown
    }
    const source = String(markdown)
    let i = 0
    let out = ''
    const n = source.length
    while (i < n) {
        const fence = tryReadFence(source, i)
        if (fence) {
            out += source.slice(i, fence.end)
            i = fence.end
            continue
        }

        const inlineCode = tryReadInlineCode(source, i)
        if (inlineCode) {
            out += source.slice(i, inlineCode.end)
            i = inlineCode.end
            continue
        }

        if (source[i] === '\\' && !isEscapedChar(source, i)) {
            if (source[i + 1] === '[') {
                const end = findClosingPair(source, i + 2, '\\]')
                if (end !== -1) {
                    out += wrapMathSource(source.slice(i + 2, end), true)
                    i = end + 2
                    continue
                }
            }
            if (source[i + 1] === '(') {
                const end = findClosingPair(source, i + 2, '\\)')
                if (end !== -1) {
                    out += wrapMathSource(source.slice(i + 2, end), false)
                    i = end + 2
                    continue
                }
            }
        }

        if (source[i] === '$' && source[i + 1] === '$' && !isEscapedChar(source, i)) {
            const end = findClosingPair(source, i + 2, '$$')
            if (end !== -1) {
                out += wrapMathSource(source.slice(i + 2, end), true)
                i = end + 2
                continue
            }
        }

        if (source[i] === '$' && source[i + 1] !== '$' && !isEscapedChar(source, i)) {
            const next = source[i + 1]
            if (next && next !== ' ' && next !== '\t' && next !== '\n') {
                const end = findClosingInlineDollar(source, i + 1)
                if (end > i + 1) {
                    out += wrapMathSource(source.slice(i + 1, end), false)
                    i = end + 1
                    continue
                }
            }
        }

        out += source[i]
        i += 1
    }
    return out
}

function footnoteSlug (label, usedSlugs) {
    const base = String(label).trim().replace(/[^A-Za-z0-9_-]+/g, '-').replace(/^-+|-+$/g, '') || 'note'
    if (!usedSlugs.has(base)) {
        return base
    }
    let n = 2
    while (usedSlugs.has(`${base}-${n}`)) {
        n += 1
    }
    return `${base}-${n}`
}

function isIndentedContinuation (source, index) {
    return source[index] === '\t' || source.startsWith('    ', index)
}

function stripOneIndent (line) {
    if (line[0] === '\t') {
        return line.slice(1)
    }
    if (line.startsWith('    ')) {
        return line.slice(4)
    }
    return line
}

function tryReadFootnoteDefinition (source, index) {
    if (!isLineStart(source, index)) {
        return null
    }
    const start = skipFenceIndent(source, index)
    if (source[start] !== '[' || source[start + 1] !== '^') {
        return null
    }
    const close = source.indexOf(']', start + 2)
    if (close === -1 || source[close + 1] !== ':') {
        return null
    }
    const label = source.slice(start + 2, close).trim()
    if (!label || /\n/.test(source.slice(start + 2, close))) {
        return null
    }
    let i = close + 2
    while (source[i] === ' ' || source[i] === '\t') {
        i += 1
    }
    const firstNewline = source.indexOf('\n', i)
    let body = firstNewline === -1 ? source.slice(i) : source.slice(i, firstNewline)
    i = firstNewline === -1 ? source.length : firstNewline + 1
    while (i < source.length) {
        if (source[i] === '\n') {
            let j = i + 1
            while (j < source.length && source[j] === '\n') {
                j += 1
            }
            if (j < source.length && isIndentedContinuation(source, j)) {
                body += source.slice(i, j)
                i = j
                continue
            }
            break
        }
        if (!isIndentedContinuation(source, i)) {
            break
        }
        const newline = source.indexOf('\n', i)
        const line = newline === -1 ? source.slice(i) : source.slice(i, newline)
        body += `\n${stripOneIndent(line)}`
        i = newline === -1 ? source.length : newline + 1
    }
    return {
        label,
        body: body.replace(/^\s+|\s+$/g, ''),
        end: i
    }
}

function tryReadFootnoteRef (source, index) {
    if (isEscapedChar(source, index) || source[index] !== '[' || source[index + 1] !== '^') {
        return null
    }
    const close = source.indexOf(']', index + 2)
    if (close === -1 || source[close + 1] === ':') {
        return null
    }
    const rawLabel = source.slice(index + 2, close)
    const label = rawLabel.trim()
    if (!label || /\n/.test(rawLabel)) {
        return null
    }
    return { label, end: close + 1 }
}

function skipCodeOrCopy (source, index, out) {
    const fence = tryReadFence(source, index)
    if (fence) {
        out.value += source.slice(index, fence.end)
        return fence.end
    }
    const inlineCode = tryReadInlineCode(source, index)
    if (inlineCode) {
        out.value += source.slice(index, inlineCode.end)
        return inlineCode.end
    }
    return -1
}

/**
 * GitHub/PHP Markdown Extra footnotes: `[^label]` plus `[^label]: body`.
 * Definitions are stripped so marked does not treat them as link refs.
 */
export function extractMarkdownFootnotes (markdown) {
    if (!markdown) {
        return { markdown: markdown || '', footnotes: [] }
    }
    const source = String(markdown)
    const definitions = new Map()
    const withoutDefs = { value: '' }
    let i = 0
    while (i < source.length) {
        const skipped = skipCodeOrCopy(source, i, withoutDefs)
        if (skipped !== -1) {
            i = skipped
            continue
        }
        const def = tryReadFootnoteDefinition(source, i)
        if (def) {
            if (!definitions.has(def.label)) {
                definitions.set(def.label, def.body)
            }
            if (withoutDefs.value && !withoutDefs.value.endsWith('\n')) {
                withoutDefs.value += '\n'
            }
            i = def.end
            continue
        }
        withoutDefs.value += source[i]
        i += 1
    }

    const text = withoutDefs.value
    const used = []
    const usedByLabel = new Map()
    const usedSlugs = new Set()
    let out = ''
    i = 0
    while (i < text.length) {
        const fence = tryReadFence(text, i)
        if (fence) {
            out += text.slice(i, fence.end)
            i = fence.end
            continue
        }
        const inlineCode = tryReadInlineCode(text, i)
        if (inlineCode) {
            out += text.slice(i, inlineCode.end)
            i = inlineCode.end
            continue
        }
        const ref = tryReadFootnoteRef(text, i)
        if (ref && definitions.has(ref.label)) {
            let item = usedByLabel.get(ref.label)
            if (!item) {
                const slug = footnoteSlug(ref.label, usedSlugs)
                usedSlugs.add(slug)
                item = {
                    label: ref.label,
                    slug,
                    id: `fn-${slug}`,
                    body: definitions.get(ref.label),
                    number: used.length + 1,
                    refs: []
                }
                used.push(item)
                usedByLabel.set(ref.label, item)
            }
            const refId = `fnref-${item.slug}-${item.refs.length + 1}`
            item.refs.push(refId)
            out += `<sup class="markdown-footnote-ref"><a href="#${item.id}" id="${refId}">${item.number}</a></sup>`
            i = ref.end
            continue
        }
        out += text[i]
        i += 1
    }
    return { markdown: out, footnotes: used }
}

function mergeFootnoteBody (bodyHtml, backrefs) {
    const trimmed = bodyHtml == null ? '' : String(bodyHtml).trim()
    if (!trimmed) {
        return backrefs
    }
    if (/<\/p>\s*$/i.test(trimmed)) {
        return trimmed.replace(/<\/p>\s*$/i, ` ${backrefs}</p>`)
    }
    return `${trimmed} ${backrefs}`
}

export function appendMarkdownFootnotes (html, footnotes, renderBody) {
    if (!html) {
        html = ''
    }
    if (!footnotes || !footnotes.length) {
        return html
    }
    const items = footnotes.map((fn) => {
        const body = fn.body && typeof renderBody === 'function' ? renderBody(fn.body) : escapeHtml(fn.body || '')
        const backrefs = (fn.refs || []).map((refId, index) => {
            const extra = fn.refs.length > 1 ? `<sup>${index + 1}</sup>` : ''
            return `<a href="#${escapeHtml(refId)}" class="markdown-footnote-backref">↩${extra}</a>`
        }).join(' ')
        return `<li id="${escapeHtml(fn.id)}">${mergeFootnoteBody(body, backrefs)}</li>`
    }).join('')
    return `${html}<section class="markdown-footnotes"><hr><ol>${items}</ol></section>`
}

function tryReadAbbreviationDefinition (source, index) {
    if (!isLineStart(source, index)) {
        return null
    }
    const start = skipFenceIndent(source, index)
    if (source[start] !== '*' || source[start + 1] !== '[') {
        return null
    }
    const close = source.indexOf(']', start + 2)
    if (close === -1 || source[close + 1] !== ':') {
        return null
    }
    const term = source.slice(start + 2, close)
    if (!term || /\n/.test(term)) {
        return null
    }
    let i = close + 2
    while (source[i] === ' ' || source[i] === '\t') {
        i += 1
    }
    const newline = source.indexOf('\n', i)
    const title = (newline === -1 ? source.slice(i) : source.slice(i, newline)).trim()
    return {
        term,
        title,
        end: newline === -1 ? source.length : newline + 1
    }
}

function escapeRegExp (value) {
    return String(value).replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
}

function abbreviationPattern (abbreviations) {
    const sources = abbreviations.map(({ term }) => {
        const escaped = escapeRegExp(term)
        if (/^[A-Za-z0-9_]+$/.test(term)) {
            return `\\b${escaped}\\b`
        }
        return `(?<![A-Za-z0-9_])${escaped}(?![A-Za-z0-9_])`
    })
    return new RegExp(sources.join('|'), 'g')
}

function wrapAbbreviationsInText (text, abbreviations, pattern) {
    if (!text) {
        return text
    }
    return text.replace(pattern, (match) => {
        const item = abbreviations.find((abbr) => abbr.term === match)
        if (!item) {
            return match
        }
        return `<abbr class="markdown-abbr" title="${escapeHtml(item.title)}">${escapeHtml(match)}</abbr>`
    })
}

function tryReadHtmlTag (source, index) {
    if (source[index] !== '<') {
        return null
    }
    const next = source[index + 1]
    if (!next || !/[a-zA-Z/!?]/.test(next)) {
        return null
    }
    const end = source.indexOf('>', index + 1)
    if (end === -1 || end - index > 2000) {
        return null
    }
    return { end: end + 1, raw: source.slice(index, end + 1) }
}

function isAbbrSkipContainer (raw) {
    return /markdown-math|markdown-footnote-ref|markdown-abbr/i.test(raw)
}

/**
 * PHP Markdown Extra abbreviations: `*[HTML]: Hyper Text Markup Language`.
 */
export function extractMarkdownAbbreviations (markdown) {
    if (!markdown) {
        return { markdown: markdown || '', abbreviations: [] }
    }
    const source = String(markdown)
    const abbreviations = []
    const seen = new Set()
    const withoutDefs = { value: '' }
    let i = 0
    while (i < source.length) {
        const skipped = skipCodeOrCopy(source, i, withoutDefs)
        if (skipped !== -1) {
            i = skipped
            continue
        }
        const def = tryReadAbbreviationDefinition(source, i)
        if (def) {
            if (def.title && !seen.has(def.term)) {
                seen.add(def.term)
                abbreviations.push({ term: def.term, title: def.title })
            }
            if (withoutDefs.value && !withoutDefs.value.endsWith('\n')) {
                withoutDefs.value += '\n'
            }
            i = def.end
            continue
        }
        withoutDefs.value += source[i]
        i += 1
    }
    abbreviations.sort((a, b) => b.term.length - a.term.length)
    return { markdown: withoutDefs.value, abbreviations }
}

export function applyMarkdownAbbreviations (markdown, abbreviations) {
    if (!markdown || !abbreviations || !abbreviations.length) {
        return markdown
    }
    const sorted = abbreviations.slice().sort((a, b) => b.term.length - a.term.length)
    const pattern = abbreviationPattern(sorted)
    const source = String(markdown)
    let i = 0
    let out = ''
    let textBuf = ''
    let skipDepth = 0
    const flush = () => {
        if (textBuf) {
            out += wrapAbbreviationsInText(textBuf, sorted, pattern)
            textBuf = ''
        }
    }
    while (i < source.length) {
        if (!skipDepth) {
            const fence = tryReadFence(source, i)
            if (fence) {
                flush()
                out += source.slice(i, fence.end)
                i = fence.end
                continue
            }
            const inlineCode = tryReadInlineCode(source, i)
            if (inlineCode) {
                flush()
                out += source.slice(i, inlineCode.end)
                i = inlineCode.end
                continue
            }
        }
        const tag = tryReadHtmlTag(source, i)
        if (tag) {
            flush()
            out += source.slice(i, tag.end)
            if (isAbbrSkipContainer(tag.raw) && !/^<\//.test(tag.raw)) {
                skipDepth += 1
            } else if (skipDepth && /^<\/(?:span|sup|div)\b/i.test(tag.raw)) {
                skipDepth -= 1
            }
            i = tag.end
            continue
        }
        if (skipDepth) {
            out += source[i]
        } else {
            textBuf += source[i]
        }
        i += 1
    }
    flush()
    return out
}

export function rewriteMarkdownImageUrls (html, resolveAssetUrl) {
    if (!resolveAssetUrl || !html) {
        return html
    }
    return html.replace(/<img([^>]*?)src=(["'])([^"']+)\2([^>]*)>/gi, (match, before, quote, src, after) => {
        const resolved = resolveAssetUrl(src)
        if (!resolved) {
            return match
        }
        return `<img${before}src=${quote}${resolved}${quote}${after}>`
    })
}

export function buildJsxSandboxSrcdoc (jsxSource, libBase) {
    const preparedSource = prepareJsxSource(jsxSource)
    const navigationGuard = previewNavigationGuardSource()
    return `<!DOCTYPE html>
<html>
<head>
  <meta charset="utf-8">
  <meta http-equiv="Content-Security-Policy" content="default-src 'none'; script-src 'unsafe-inline' 'unsafe-eval' ${libBase} https: blob:; connect-src https:; style-src 'unsafe-inline' https:; img-src data: blob: https:; font-src data: https:;">
  <script src="${libBase}babel.min.js"><\/script>
  <style>
    body { margin: 0; padding: 0; font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif; }
    pre.error { color: #ea3636; white-space: pre-wrap; padding: 16px; }
  </style>
</head>
<body>
  <div id="root"></div>
  <script>
    function __showPreviewError(error) {
      var message = error && error.message ? error.message : String(error);
      document.body.innerHTML = '<pre class="error">' + message + '</pre>';
    }
    function __isSandboxCapabilityError(error) {
      var message = error && error.message ? error.message : String(error || '');
      return /sandboxed|allow-same-origin|serviceWorker|Failed to set the 'cookie'/i.test(message);
    }
    function __copyTextFallback(text) {
      var textarea = document.createElement('textarea');
      textarea.value = text == null ? '' : String(text);
      textarea.setAttribute('readonly', '');
      textarea.style.position = 'fixed';
      textarea.style.left = '-9999px';
      document.body.appendChild(textarea);
      textarea.select();
      var ok = false;
      try {
        ok = document.execCommand('copy');
      } finally {
        document.body.removeChild(textarea);
      }
      if (!ok) {
        return Promise.reject(new Error('Clipboard copy is not available in this preview sandbox'));
      }
      return Promise.resolve();
    }
    // Opaque-origin sandbox: cookie / serviceWorker throw SecurityError and would blank the preview.
    try {
      Object.defineProperty(document, 'cookie', {
        configurable: true,
        get: function () { return ''; },
        set: function () {}
      });
    } catch (e) { /* ignore */ }
    try {
      Object.defineProperty(navigator, 'serviceWorker', {
        configurable: true,
        get: function () { return undefined; }
      });
    } catch (e) { /* ignore */ }
    // Fragment links in sandboxed srcdoc are treated as cross-origin navigations and blank the frame.
    ${navigationGuard}
    if (navigator.clipboard && typeof navigator.clipboard.writeText === 'function') {
      var __nativeWriteText = navigator.clipboard.writeText.bind(navigator.clipboard);
      navigator.clipboard.writeText = function (text) {
        return __nativeWriteText(text).catch(function () {
          return __copyTextFallback(text);
        });
      };
    } else {
      navigator.clipboard = {
        writeText: __copyTextFallback
      };
    }
    window.addEventListener('error', function (event) {
      var error = event.error || event.message;
      if (__isSandboxCapabilityError(error)) {
        event.preventDefault();
        return;
      }
      __showPreviewError(error);
    });
    window.addEventListener('unhandledrejection', function (event) {
      if (__isSandboxCapabilityError(event.reason)) {
        event.preventDefault();
        return;
      }
      __showPreviewError(event.reason);
    });
  <\/script>
  <script type="text/babel" data-type="module" data-presets="react">
${preparedSource}
  <\/script>
</body>
</html>`
}
