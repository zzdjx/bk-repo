import {
    escapeHtml,
    isLineStart,
    transformMarkdownOutsideCode,
    tryReadFence,
    tryReadInlineCode
} from './markdownJsxPreviewCore.js'
import { GITHUB_EMOJI_SHORTCODES } from './markdownEmoji.js'

const ALERT_TYPES = {
    note: '注意',
    tip: '提示',
    important: '重要',
    warning: '警告',
    caution: '小心'
}

function skipCode (source, index) {
    return tryReadFence(source, index) || tryReadInlineCode(source, index)
}

function mapPlainText (text, transform) {
    return String(text).replace(/(<[^>]+>)|([^<]+)/g, (match, tag, plain) => {
        return tag || transform(plain)
    })
}

function stripQuotes (value) {
    const text = String(value == null ? '' : value).trim()
    if ((text.startsWith('"') && text.endsWith('"')) || (text.startsWith("'") && text.endsWith("'"))) {
        return text.slice(1, -1)
    }
    return text
}

function normalizeYamlNewlines (text) {
    return String(text || '').replace(/\r\n/g, '\n').replace(/\r/g, '\n')
}

export function parseSimpleYaml (text) {
    const result = {}
    let listKey = null
    normalizeYamlNewlines(text).split('\n').forEach((line) => {
        if (/^\s*$/.test(line) || /^\s*#/.test(line)) {
            return
        }
        const listItem = line.match(/^\s+-\s+(.*)$/)
        if (listItem && listKey) {
            if (!Array.isArray(result[listKey])) {
                result[listKey] = result[listKey] === undefined || result[listKey] === ''
                    ? []
                    : [result[listKey]]
            }
            result[listKey].push(stripQuotes(listItem[1]))
            return
        }
        const kv = line.match(/^([A-Za-z0-9_-]+):\s*(.*)$/)
        if (!kv) {
            listKey = null
            return
        }
        const key = kv[1]
        const value = kv[2].trim()
        if (value === '') {
            result[key] = []
            listKey = key
            return
        }
        result[key] = stripQuotes(value)
        listKey = null
    })
    return result
}

function renderFrontMatterHtml (fields, rawYaml) {
    const keys = Object.keys(fields)
    const title = '<div class="markdown-frontmatter-title">文档属性</div>'
    if (!keys.length) {
        const leftover = String(rawYaml || '').trim()
        if (!leftover) {
            return ''
        }
        return `<div class="markdown-frontmatter">${title}<pre>${escapeHtml(leftover)}</pre></div>\n`
    }
    const rows = keys.map((key) => {
        const value = fields[key]
        const display = Array.isArray(value) ? value.join(', ') : String(value)
        return `<tr><th>${escapeHtml(key)}</th><td>${escapeHtml(display)}</td></tr>`
    }).join('')
    return `<div class="markdown-frontmatter">${title}<table>${rows}</table></div>\n`
}

const FRONT_MATTER_BLOCK = /^(?:[ \t]*\r?\n)*[ \t]*---[ \t]*\r?\n([\s\S]*?)\r?\n[ \t]*(?:---|\.\.\.)[ \t]*(?:\r?\n|$)/

export function extractMarkdownFrontMatter (markdown) {
    if (!markdown) {
        return { markdown: markdown || '', html: '', fields: null }
    }
    const source = String(markdown).replace(/^\uFEFF/, '')
    const match = source.match(FRONT_MATTER_BLOCK)
    if (!match) {
        return { markdown: source, html: '', fields: null }
    }
    const fields = parseSimpleYaml(match[1])
    return {
        markdown: source.slice(match[0].length),
        html: renderFrontMatterHtml(fields, match[1]),
        fields
    }
}

function tryReadAlert (source, index) {
    if (!isLineStart(source, index)) {
        return null
    }
    const head = source.slice(index).match(/^> \[!(NOTE|TIP|IMPORTANT|WARNING|CAUTION)\][ \t]*([^\n]*)(?:\n|$)/i)
    if (!head) {
        return null
    }
    let i = index + head[0].length
    const lines = []
    while (i < source.length) {
        if (source[i] !== '>') {
            break
        }
        const newline = source.indexOf('\n', i)
        const line = newline === -1 ? source.slice(i) : source.slice(i, newline)
        lines.push(line.replace(/^> ?/, ''))
        i = newline === -1 ? source.length : newline + 1
    }
    return {
        type: head[1].toLowerCase(),
        customTitle: head[2].trim(),
        body: lines.join('\n').trim(),
        end: i
    }
}

export function rewriteMarkdownAlerts (markdown, renderBody) {
    if (!markdown) {
        return markdown
    }
    const source = String(markdown)
    let i = 0
    let out = ''
    while (i < source.length) {
        const skipped = skipCode(source, i)
        if (skipped) {
            out += source.slice(i, skipped.end)
            i = skipped.end
            continue
        }
        const alert = tryReadAlert(source, i)
        if (alert) {
            const title = alert.customTitle || ALERT_TYPES[alert.type] || alert.type
            const bodyHtml = alert.body && typeof renderBody === 'function' ? renderBody(alert.body) : ''
            out += `<div class="markdown-alert markdown-alert-${alert.type}"><p class="markdown-alert-title">${escapeHtml(title)}</p>${bodyHtml}</div>\n`
            i = alert.end
            continue
        }
        out += source[i]
        i += 1
    }
    return out
}

function tryReadDefinitionItem (source, index) {
    if (!isLineStart(source, index)) {
        return null
    }
    const first = source[index]
    if (first === '#' || first === '>' || first === '|' || first === '`' || first === ':') {
        return null
    }
    const newline = source.indexOf('\n', index)
    if (newline === -1) {
        return null
    }
    const term = source.slice(index, newline)
    if (!term.trim() || /^\s/.test(term) || /^[-*+] /.test(term) || /^\d+[.)] /.test(term)) {
        return null
    }
    let i = newline + 1
    const defs = []
    while (i < source.length) {
        const lineEnd = source.indexOf('\n', i)
        const line = lineEnd === -1 ? source.slice(i) : source.slice(i, lineEnd)
        const def = line.match(/^[ \t]*:[ \t]+(.*)$/)
        if (!def) {
            break
        }
        defs.push(def[1])
        i = lineEnd === -1 ? source.length : lineEnd + 1
    }
    if (!defs.length) {
        return null
    }
    return { term: term.trim(), defs, end: i }
}

export function rewriteMarkdownDefinitionLists (markdown, renderInline) {
    if (!markdown) {
        return markdown
    }
    const source = String(markdown)
    const render = typeof renderInline === 'function' ? renderInline : (value) => escapeHtml(value)
    let i = 0
    let out = ''
    while (i < source.length) {
        const skipped = skipCode(source, i)
        if (skipped) {
            out += source.slice(i, skipped.end)
            i = skipped.end
            continue
        }
        if (source[i] === '\n') {
            let look = i + 1
            while (source[look] === '\n') {
                look += 1
            }
            const first = tryReadDefinitionItem(source, look)
            if (first) {
                const items = [first]
                let cursor = first.end
                while (cursor < source.length) {
                    let nextIndex = cursor
                    if (source[nextIndex] === '\n') {
                        nextIndex += 1
                    }
                    const next = tryReadDefinitionItem(source, nextIndex)
                    if (!next) {
                        break
                    }
                    items.push(next)
                    cursor = next.end
                }
                const body = items.map((item) => {
                    const dts = `<dt>${render(item.term)}</dt>`
                    const dds = item.defs.map((def) => `<dd>${render(def)}</dd>`).join('')
                    return dts + dds
                }).join('')
                out += `\n<dl class="markdown-deflist">${body}</dl>\n`
                i = cursor
                continue
            }
        }
        const item = tryReadDefinitionItem(source, i)
        if (item) {
            const items = [item]
            let cursor = item.end
            while (cursor < source.length) {
                let nextIndex = cursor
                if (source[nextIndex] === '\n') {
                    nextIndex += 1
                }
                const next = tryReadDefinitionItem(source, nextIndex)
                if (!next) {
                    break
                }
                items.push(next)
                cursor = next.end
            }
            const body = items.map((entry) => {
                const dts = `<dt>${render(entry.term)}</dt>`
                const dds = entry.defs.map((def) => `<dd>${render(def)}</dd>`).join('')
                return dts + dds
            }).join('')
            out += `<dl class="markdown-deflist">${body}</dl>\n`
            i = cursor
            continue
        }
        out += source[i]
        i += 1
    }
    return out
}

export function rewriteMarkdownHighlight (markdown) {
    return transformMarkdownOutsideCode(markdown, (text) => mapPlainText(text, (plain) => {
        return plain.replace(/==([^=\n]+)==/g, (match, body) => `<mark>${escapeHtml(body)}</mark>`)
    }))
}

export function rewriteMarkdownEmoji (markdown, shortcodes = GITHUB_EMOJI_SHORTCODES) {
    return transformMarkdownOutsideCode(markdown, (text) => mapPlainText(text, (plain) => {
        return plain.replace(/:([a-z0-9_+-]+):/gi, (match, name) => {
            const key = name === '+1' || name === '-1' ? name : name.toLowerCase()
            return Object.prototype.hasOwnProperty.call(shortcodes, key) ? shortcodes[key] : match
        })
    }))
}

function wikiSlug (value) {
    return String(value)
        .trim()
        .toLowerCase()
        .replace(/[^\u4e00-\u9fffA-Za-z0-9_-]+/g, '-')
        .replace(/^-+|-+$/g, '') || 'page'
}

export function rewriteMarkdownTocPlaceholder (markdown) {
    return transformMarkdownOutsideCode(markdown, (text) => {
        return text.replace(/\[\[toc\]\]/gi, '<div class="markdown-toc-placeholder"></div>')
    })
}

export function rewriteMarkdownWikilinks (markdown) {
    return transformMarkdownOutsideCode(markdown, (text) => mapPlainText(text, (plain) => {
        return plain.replace(/\[\[([^\]|#\n]+)(?:\|([^\]\n]+))?\]\]/g, (match, target, label) => {
            if (/^toc$/i.test(String(target).trim()) && !label) {
                return match
            }
            const slug = wikiSlug(target)
            const textLabel = String(label || target).trim()
            return `<a class="markdown-wikilink" href="#${escapeHtml(slug)}">${escapeHtml(textLabel)}</a>`
        })
    }))
}

export function rewriteMarkdownSubSup (markdown) {
    return transformMarkdownOutsideCode(markdown, (text) => mapPlainText(text, (plain) => {
        let next = plain.replace(/(?<!\^)\^([^\s^]+)\^(?!\^)/g, (match, body) => `<sup>${escapeHtml(body)}</sup>`)
        next = next.replace(/(?<!~)~([^\s~]+)~(?!~)/g, (match, body) => `<sub>${escapeHtml(body)}</sub>`)
        return next
    }))
}

export function parseMarkdownBraceAttrs (spec) {
    const source = String(spec || '')
    const idMatch = source.match(/#([A-Za-z][\w:.-]*)/)
    const classes = []
    source.replace(/\.([A-Za-z][\w:.-]*)/g, (_, name) => {
        classes.push(name)
        return ''
    })
    return {
        id: idMatch ? idMatch[1] : '',
        className: classes.join(' ')
    }
}

export function stripMarkdownBraceAttrs (value) {
    return String(value == null ? '' : value).replace(/\s*\{[^}]*\}\s*$/, '')
}

export function rewriteMarkdownParagraphAttrs (markdown) {
    return transformMarkdownOutsideCode(markdown, (text) => {
        return text.replace(/^(?!#{1,6}\s)(.+?)\s*\{([^}\n]+)\}[ \t]*$/gm, (match, body, spec) => {
            if (/^[-*+] |\d+[.)] |```/.test(body)) {
                return match
            }
            const attrs = parseMarkdownBraceAttrs(spec)
            if (!attrs.id && !attrs.className) {
                return match
            }
            const id = attrs.id ? ` id="${escapeHtml(attrs.id)}"` : ''
            const cls = attrs.className ? ` class="${escapeHtml(attrs.className)}"` : ''
            return `<p${id}${cls}>${body}</p>`
        })
    })
}

export function rewriteMarkdownImageSize (markdown) {
    return transformMarkdownOutsideCode(markdown, (text) => {
        return text.replace(
            /!\[([^\]]*)\]\(\s*(<[^>\s]+>|[^\s)]+)\s*(?:"([^"]*)")?\s*=(\d+)x(\d*)\s*\)/g,
            (match, alt, href, title, width, height) => {
                const src = href.startsWith('<') && href.endsWith('>') ? href.slice(1, -1) : href
                const titleAttr = title ? ` title="${escapeHtml(title)}"` : ''
                const heightAttr = height ? ` height="${height}"` : ''
                return `<img src="${escapeHtml(src)}" alt="${escapeHtml(alt)}" width="${width}"${heightAttr}${titleAttr}>`
            }
        )
    })
}

export function rewriteMarkdownDetails (markdown, renderMarkdown, renderInline) {
    if (!markdown) {
        return markdown
    }
    const parseBlock = typeof renderMarkdown === 'function' ? renderMarkdown : ((value) => value)
    const parseInline = typeof renderInline === 'function' ? renderInline : ((value) => value)
    return String(markdown).replace(/<details(\s[^>]*)?>([\s\S]*?)<\/details>/gi, (match, attrs, inner) => {
        const summaryMatch = inner.match(/<summary(\s[^>]*)?>([\s\S]*?)<\/summary>/i)
        let summary = ''
        let body = inner
        if (summaryMatch) {
            summary = `<summary${summaryMatch[1] || ''}>${parseInline(summaryMatch[2].trim())}</summary>`
            body = inner.replace(summaryMatch[0], '')
        }
        return `\n\n<details${attrs || ''}>${summary}${parseBlock(body.trim())}</details>\n\n`
    })
}

function headingPlainText (innerHtml) {
    return String(innerHtml)
        .replace(/<a class="markdown-heading-anchor"[^>]*>[\s\S]*?<\/a>/g, '')
        .replace(/<[^>]+>/g, '')
        .replace(/\s+/g, ' ')
        .trim()
}

export function fillMarkdownToc (html) {
    if (!html || html.indexOf('markdown-toc-placeholder') < 0) {
        return html
    }
    const items = []
    const headingRe = /<h([1-6])([^>]*)>([\s\S]*?)<\/h\1>/gi
    let match
    while ((match = headingRe.exec(html))) {
        const idMatch = match[2].match(/\sid="([^"]+)"/i)
        const text = headingPlainText(match[3])
        if (!idMatch || !text) {
            continue
        }
        items.push({
            level: Number(match[1]),
            id: idMatch[1],
            text
        })
    }
    const toc = items.length
        ? `<nav class="markdown-toc"><ol>${items.map((item) => {
            return `<li class="markdown-toc-h${item.level}"><a href="#${escapeHtml(item.id)}">${escapeHtml(item.text)}</a></li>`
        }).join('')}</ol></nav>`
        : ''
    return html.replace(/<div class="markdown-toc-placeholder"><\/div>/g, toc)
}

export function renderMarkdownHeading (text, level, raw, slugger) {
    const specMatch = String(raw || '').match(/\s*\{([^}]+)\}\s*$/)
    const attrs = specMatch ? parseMarkdownBraceAttrs(specMatch[1]) : { id: '', className: '' }
    const inner = stripMarkdownBraceAttrs(text)
    const id = attrs.id || (slugger && typeof slugger.slug === 'function'
        ? slugger.slug(stripMarkdownBraceAttrs(raw))
        : '')
    const idAttr = id ? ` id="${escapeHtml(id)}"` : ''
    const classAttr = attrs.className ? ` class="${escapeHtml(attrs.className)}"` : ''
    const anchor = id
        ? `<a class="markdown-heading-anchor" href="#${escapeHtml(id)}" aria-hidden="true">#</a>`
        : ''
    return `<h${level}${idAttr}${classAttr}>${anchor}${inner}</h${level}>\n`
}
