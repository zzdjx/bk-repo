import test from 'node:test'
import assert from 'node:assert/strict'
import {
    resolveRelativePath,
    parsePreviewContext,
    prepareJsxSource,
    rewriteBareImports,
    rewriteMarkdownImageUrls,
    normalizeMarkdownText,
    normalizeCodeText,
    resolveMonacoLanguage,
    resolvePreviewViewMode,
    buildJsxSandboxSrcdoc,
    isMathFenceLanguage,
    isMermaidFenceLanguage,
    rewriteMarkdownMath,
    wrapMathSource,
    wrapMermaidSource,
    extractMarkdownAbbreviations,
    extractMarkdownFootnotes,
    applyMarkdownAbbreviations,
    appendMarkdownFootnotes,
    isPlantumlFenceLanguage,
    wrapPlantumlSource
} from './markdownJsxPreviewCore.js'
import {
    collectMermaidNodes,
    getMermaidLibUrl,
    renderMermaidCharts
} from './markdownMermaidPreview.js'
import {
    collectMathNodes,
    getKatexLibUrl,
    renderMathFormulas
} from './markdownMathPreview.js'
import {
    extractMarkdownFrontMatter,
    fillMarkdownToc,
    parseMarkdownBraceAttrs,
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
} from './markdownSyntaxExtras.js'
import {
    buildPlantumlSandboxSrcdoc,
    collectPlantumlNodes,
    getPlantumlLibUrl,
    parsePlantumlSvgSize,
    renderPlantumlDiagrams
} from './markdownPlantumlPreview.js'

function createPlantumlHostNode (source) {
    let text = source
    const node = {
        innerHTML: 'placeholder',
        child: null,
        getAttribute (name) {
            return this[name] || null
        },
        setAttribute (name, value) {
            this[name] = value
        },
        appendChild (el) {
            this.child = el
        },
        ownerDocument: {
            createElement (tag) {
                const el = {
                    tagName: String(tag).toLowerCase(),
                    attrs: {},
                    style: {},
                    setAttribute (name, value) {
                        this.attrs[name] = String(value)
                    },
                    getAttribute (name) {
                        return Object.prototype.hasOwnProperty.call(this.attrs, name) ? this.attrs[name] : null
                    }
                }
                return el
            }
        }
    }
    Object.defineProperty(node, 'textContent', {
        get () {
            return text
        },
        set (value) {
            text = value
            node.innerHTML = ''
            node.child = null
        }
    })
    return node
}

let previewDocumentLock = Promise.resolve()

function installPreviewDocument (settleLoad) {
    let releaseLock
    const owned = new Promise((resolve) => {
        releaseLock = resolve
    })
    const previousOwner = previewDocumentLock
    previewDocumentLock = owned
    return previousOwner.then(() => {
        const previous = {
            document: globalThis.document,
            window: globalThis.window,
            location: globalThis.location
        }
        const win = { BK_SUBPATH: '/' }
        const counts = { script: 0, link: 0 }
        globalThis.location = { origin: 'https://example.test' }
        globalThis.window = win
        globalThis.document = {
            head: {
                appendChild (el) {
                    const kind = el.tagName === 'link' ? 'link' : 'script'
                    counts[kind] += 1
                    const attempt = counts[kind]
                    queueMicrotask(() => settleLoad(el, kind, attempt))
                }
            },
            createElement (tag) {
                return { tagName: String(tag).toLowerCase() }
            },
            getElementById () {
                return null
            }
        }
        return {
            window: win,
            counts,
            restore () {
                globalThis.document = previous.document
                globalThis.window = previous.window
                globalThis.location = previous.location
                releaseLock()
            }
        }
    })
}

test('resolveRelativePath resolves sibling and parent paths', () => {
    assert.equal(resolveRelativePath('/docs/guide/README.md', './img.png'), '/docs/guide/img.png')
    assert.equal(resolveRelativePath('/docs/guide/README.md', '../assets/logo.png'), '/docs/assets/logo.png')
    assert.equal(resolveRelativePath('/docs/guide/README.md', 'https://example.com/a.png'), 'https://example.com/a.png')
    assert.equal(resolveRelativePath('/a.md', '../../etc/passwd'), null)
})

test('parsePreviewContext reads artifact info from extraParam', () => {
    const extraParam = JSON.stringify({
        projectId: 'demo',
        repoName: 'generic-local',
        artifactUri: '/docs/readme.md'
    })
    assert.deepEqual(parsePreviewContext({
        extraParam
    }), {
        projectId: 'demo',
        repoName: 'generic-local',
        filePath: '/docs/readme.md'
    })
})

test('rewriteBareImports maps packages to esm.sh', () => {
    const source = rewriteBareImports(
        'import React, { useState } from "react"\nimport { Copy } from "lucide-react"\nimport "side-effect-pkg"'
    )
    assert.match(source, /from "https:\/\/esm\.sh\/react"/)
    assert.match(source, /from "https:\/\/esm\.sh\/lucide-react"/)
    assert.match(source, /import "https:\/\/esm\.sh\/side-effect-pkg"/)
})

test('rewriteBareImports rejects relative imports', () => {
    assert.throws(
        () => rewriteBareImports('import Foo from "./foo"'),
        /Relative import is not supported/
    )
})

test('prepareJsxSource keeps package imports and mounts default export', () => {
    const source = prepareJsxSource(
        'import React, { useState } from "react"\nimport { Copy } from "lucide-react"\nexport default function Hello () { return <div>Hi</div> }'
    )
    assert.match(source, /from "https:\/\/esm\.sh\/react"/)
    assert.match(source, /from "https:\/\/esm\.sh\/lucide-react"/)
    assert.match(source, /const __PREVIEW_COMPONENT__ = function Hello/)
    assert.match(source, /createRoot/)
    assert.match(source, /React\.createElement\(__PREVIEW_COMPONENT__\)/)
    assert.equal((source.match(/import\s+React\b/g) || []).length, 1)
})

test('prepareJsxSource injects React when user JSX omits the import', () => {
    const source = prepareJsxSource(
        'export default function DeltaForceHome () { return <div>Hi</div> }'
    )
    assert.match(source, /^import React from 'https:\/\/esm\.sh\/react';/m)
    assert.match(source, /const __PREVIEW_COMPONENT__ = function DeltaForceHome/)
    assert.match(source, /React\.createElement\(__PREVIEW_COMPONENT__\)/)
})

test('prepareJsxSource injects React when only named hooks are imported', () => {
    const source = prepareJsxSource(
        'import { useState } from "react"\nexport default function App () { const [n] = useState(0); return <div>{n}</div> }'
    )
    assert.match(source, /import React from 'https:\/\/esm\.sh\/react';/)
    assert.match(source, /from "https:\/\/esm\.sh\/react"/)
    assert.equal((source.match(/import\s+React\b/g) || []).length, 1)
})

test('prepareJsxSource rejects require', () => {
    assert.throws(
        () => prepareJsxSource('const x = require("react")\nexport default () => <div />'),
        /require\(\) is not supported/
    )
})

test('normalizeMarkdownText decodes legacy base64 payload', () => {
    const encoded = Buffer.from('# Title', 'utf8').toString('base64')
    assert.equal(normalizeMarkdownText(encoded, (value) => Buffer.from(value, 'base64').toString('utf8')), '# Title')
})

test('rewriteMarkdownImageUrls rewrites double and single quoted src', () => {
    const resolve = (src) => (src.startsWith('./') ? `/resolved/${src.slice(2)}` : src)
    assert.equal(
        rewriteMarkdownImageUrls('<img src="./a.png" alt="a">', resolve),
        '<img src="/resolved/a.png" alt="a">'
    )
    assert.equal(
        rewriteMarkdownImageUrls("<img alt='b' src='./b.png'>", resolve),
        "<img alt='b' src='/resolved/b.png'>"
    )
    assert.equal(
        rewriteMarkdownImageUrls('<img src="https://example.com/c.png">', resolve),
        '<img src="https://example.com/c.png">'
    )
})

test('normalizeCodeText always base64-decodes code payload', () => {
    const decode = (value) => Buffer.from(value, 'base64').toString('utf8')
    const encoded = Buffer.from('public class Main {}', 'utf8').toString('base64')
    assert.equal(normalizeCodeText(encoded, decode), 'public class Main {}')
})

test('normalizeCodeText falls back to raw text when decode fails', () => {
    assert.equal(
        normalizeCodeText('not-valid-base64!!!', () => {
            throw new Error('decode failed')
        }),
        'not-valid-base64!!!'
    )
})

test('resolveMonacoLanguage maps CODE suffixes to monaco language ids', () => {
    assert.equal(resolveMonacoLanguage('Main.java'), 'java')
    assert.equal(resolveMonacoLanguage('app.py'), 'python')
    assert.equal(resolveMonacoLanguage('script.python'), 'python')
    assert.equal(resolveMonacoLanguage('main.go'), 'go')
    assert.equal(resolveMonacoLanguage('index.js'), 'javascript')
    assert.equal(resolveMonacoLanguage('page.html'), 'html')
    assert.equal(resolveMonacoLanguage('styles.css'), 'css')
    assert.equal(resolveMonacoLanguage('run.sh'), 'shell')
    assert.equal(resolveMonacoLanguage('conf.yaml'), 'yaml')
    assert.equal(resolveMonacoLanguage('conf.yml'), 'yaml')
    assert.equal(resolveMonacoLanguage('data.json'), 'json')
    assert.equal(resolveMonacoLanguage('query.sql'), 'sql')
    assert.equal(resolveMonacoLanguage('lib.cpp'), 'cpp')
    assert.equal(resolveMonacoLanguage('lib.h'), 'c')
    assert.equal(resolveMonacoLanguage('lib.c'), 'c')
    assert.equal(resolveMonacoLanguage('App.cs'), 'csharp')
    assert.equal(resolveMonacoLanguage('app.rb'), 'ruby')
    assert.equal(resolveMonacoLanguage('index.php'), 'php')
    assert.equal(resolveMonacoLanguage('mod.lua'), 'lua')
    assert.equal(resolveMonacoLanguage('page.aspx'), 'plaintext')
    assert.equal(resolveMonacoLanguage('page.jsp'), 'plaintext')
    assert.equal(resolveMonacoLanguage('page.ftl'), 'plaintext')
    assert.equal(resolveMonacoLanguage('notes.txt'), 'plaintext')
    assert.equal(resolveMonacoLanguage('payload.xml'), 'xml')
    assert.equal(resolveMonacoLanguage('config.ini'), 'ini')
    assert.equal(resolveMonacoLanguage('run.bat'), 'bat')
})

test('resolvePreviewViewMode reads view from query and defaults to preview', () => {
    assert.equal(resolvePreviewViewMode({ query: { view: 'source' } }), 'source')
    assert.equal(resolvePreviewViewMode({ query: { view: 'preview' } }), 'preview')
    assert.equal(resolvePreviewViewMode({ query: {} }), 'preview')
    assert.equal(resolvePreviewViewMode({ query: { view: 'other' } }), 'preview')
    assert.equal(resolvePreviewViewMode({}), 'preview')
})

test('resolvePreviewViewMode reads view from extraParam payload', () => {
    assert.equal(
        resolvePreviewViewMode({ extraParam: JSON.stringify({ view: 'source' }) }),
        'source'
    )
    assert.equal(
        resolvePreviewViewMode({
            query: { view: 'preview' },
            extraParam: JSON.stringify({ view: 'source' })
        }),
        'preview'
    )
})

test('buildJsxSandboxSrcdoc shims fragment navigation and sandbox APIs', () => {
    const srcdoc = buildJsxSandboxSrcdoc(
        'export default function App () { return <a href="#modes">modes</a> }',
        '/ui/libs/'
    )
    assert.match(srcdoc, /a\[href\^="#"]/)
    assert.match(srcdoc, /scrollIntoView/)
    assert.match(srcdoc, /defineProperty\(document, 'cookie'/)
    assert.match(srcdoc, /defineProperty\(navigator, 'serviceWorker'/)
    assert.match(srcdoc, /__isSandboxCapabilityError/)
})

test('isMermaidFenceLanguage accepts mermaid and mermaidChart', () => {
    assert.equal(isMermaidFenceLanguage('mermaid'), true)
    assert.equal(isMermaidFenceLanguage('mermaidChart'), true)
    assert.equal(isMermaidFenceLanguage('mermaid-chart'), true)
    assert.equal(isMermaidFenceLanguage('MERMAIDCHART'), true)
    assert.equal(isMermaidFenceLanguage('mermaid title="flow"'), true)
    assert.equal(isMermaidFenceLanguage('javascript'), false)
    assert.equal(isMermaidFenceLanguage(''), false)
})

test('wrapMermaidSource escapes HTML and keeps mermaidChart class', () => {
    const html = wrapMermaidSource('graph TD\n  A --> B\n  C["<script>x"]')
    assert.match(html, /class="mermaid mermaidChart"/)
    assert.match(html, /A --&gt; B/)
    assert.match(html, /&lt;script&gt;/)
    assert.equal(html.includes('<script>'), false)
})

test('collectMermaidNodes skips processed diagrams', () => {
    const processed = { getAttribute: (name) => (name === 'data-processed' ? 'true' : null) }
    const pending = { getAttribute: () => null }
    const root = {
        querySelectorAll: () => [processed, pending]
    }
    assert.deepEqual(collectMermaidNodes(root), [pending])
})

test('getMermaidLibUrl points to ui/libs mermaid bundle', () => {
    assert.match(getMermaidLibUrl(), /ui\/libs\/mermaid\.min\.js$/)
})

test('renderMermaidCharts runs mermaid on pending nodes', async () => {
    const pending = { getAttribute: () => null }
    const root = { querySelectorAll: () => [pending] }
    let ran = null
    await renderMermaidCharts(root, async () => ({
        run: async (opts) => {
            ran = opts
        }
    }))
    assert.equal(ran.nodes[0], pending)
    assert.equal(ran.suppressErrors, true)
})

test('renderMermaidCharts tries again after the mermaid script fails', async () => {
    let ran = false
    const preview = await installPreviewDocument((el, kind, attempt) => {
        if (kind !== 'script') {
            return
        }
        if (attempt === 1) {
            el.onerror()
            return
        }
        preview.window.mermaid = {
            initialize () {},
            async run () {
                ran = true
            }
        }
        el.onload()
    })
    const root = { querySelectorAll: () => [{ getAttribute: () => null }] }
    try {
        await assert.rejects(() => renderMermaidCharts(root), /Failed to load mermaid/)
        await renderMermaidCharts(root)
        assert.equal(preview.counts.script, 2)
        assert.equal(ran, true)
    } finally {
        preview.restore()
    }
})

test('isMathFenceLanguage accepts math latex katex', () => {
    assert.equal(isMathFenceLanguage('math'), true)
    assert.equal(isMathFenceLanguage('latex'), true)
    assert.equal(isMathFenceLanguage('katex'), true)
    assert.equal(isMathFenceLanguage('MATH title="eq"'), true)
    assert.equal(isMathFenceLanguage('tex'), false)
    assert.equal(isMathFenceLanguage('javascript'), false)
})

test('wrapMathSource escapes HTML and marks display vs inline', () => {
    const display = wrapMathSource('E = mc^2 <script>', true)
    assert.match(display, /class="markdown-math markdown-math-display"/)
    assert.match(display, /&lt;script&gt;/)
    assert.equal(display.includes('<script>'), false)
    const inline = wrapMathSource('x_1', false)
    assert.match(inline, /class="markdown-math markdown-math-inline"/)
    assert.match(inline, />x_1</)
})

test('rewriteMarkdownMath wraps dollar and latex delimiters', () => {
    const html = rewriteMarkdownMath('Inline $a_1$ and $$E = mc^2$$ plus \\(x^2\\) and \\[\\frac{1}{2}\\].')
    assert.match(html, /markdown-math-inline">a_1</)
    assert.match(html, /markdown-math-display">E = mc\^2</)
    assert.match(html, /markdown-math-inline">x\^2</)
    assert.match(html, /markdown-math-display">\\frac\{1\}\{2\}</)
})

test('rewriteMarkdownMath does not rewrite math inside code', () => {
    const fenced = rewriteMarkdownMath('```js\nconst x = $a_1$\n```\n')
    assert.equal(fenced.includes('markdown-math'), false)
    assert.match(fenced, /\$a_1\$/)
    const inline = rewriteMarkdownMath('Use `$a_1$` in text')
    assert.equal(inline.includes('markdown-math'), false)
    assert.match(inline, /`\$a_1\$`/)
})

test('rewriteMarkdownMath does not treat currency as math', () => {
    const html = rewriteMarkdownMath('It costs $5 and $10.')
    assert.equal(html.includes('markdown-math'), false)
    assert.equal(html, 'It costs $5 and $10.')
})

test('collectMathNodes skips processed formulas', () => {
    const processed = { getAttribute: (name) => (name === 'data-processed' ? 'true' : null) }
    const pending = { getAttribute: () => null }
    const root = {
        querySelectorAll: () => [processed, pending]
    }
    assert.deepEqual(collectMathNodes(root), [pending])
})

test('getKatexLibUrl points to ui/libs katex bundle', () => {
    assert.match(getKatexLibUrl(), /ui\/libs\/katex\/katex\.min\.js$/)
})

test('renderMathFormulas renders pending nodes with katex', async () => {
    const pending = {
        className: 'markdown-math markdown-math-display',
        classList: { contains: (name) => name === 'markdown-math-display' },
        textContent: 'E = mc^2',
        getAttribute: () => null,
        setAttribute: function (name, value) {
            this[name] = value
        }
    }
    const root = { querySelectorAll: () => [pending] }
    const rendered = []
    await renderMathFormulas(root, async () => ({
        render: (tex, node, opts) => {
            rendered.push({ tex, node, opts })
        }
    }))
    assert.equal(rendered.length, 1)
    assert.equal(rendered[0].tex, 'E = mc^2')
    assert.equal(rendered[0].opts.displayMode, true)
    assert.equal(rendered[0].opts.throwOnError, false)
    assert.equal(pending['data-processed'], 'true')
})

test('renderMathFormulas tries again after the katex script fails', async () => {
    let rendered = 0
    const preview = await installPreviewDocument((el, kind, attempt) => {
        if (kind === 'link') {
            el.onload()
            return
        }
        if (attempt === 1) {
            el.onerror()
            return
        }
        preview.window.katex = {
            render () {
                rendered += 1
            }
        }
        el.onload()
    })
    const pending = {
        className: 'markdown-math',
        textContent: 'x',
        getAttribute: () => null,
        setAttribute () {}
    }
    const root = { querySelectorAll: () => [pending] }
    try {
        await assert.rejects(() => renderMathFormulas(root), /Failed to load katex/)
        await renderMathFormulas(root)
        assert.equal(preview.counts.script, 2)
        assert.equal(rendered, 1)
    } finally {
        preview.restore()
    }
})

test('extractMarkdownFootnotes turns refs into superscripts and drops unused defs', () => {
    const { markdown, footnotes } = extractMarkdownFootnotes(
        'See note.[^1] Again.[^1] Unused stays out.\n\n[^1]: First footnote\n[^2]: Unused\n'
    )
    assert.equal(footnotes.length, 1)
    assert.equal(footnotes[0].number, 1)
    assert.equal(footnotes[0].body, 'First footnote')
    assert.equal(footnotes[0].refs.length, 2)
    assert.match(markdown, /class="markdown-footnote-ref"/)
    assert.match(markdown, />1<\/a><\/sup>/)
    assert.equal(markdown.includes('[^1]'), false)
    assert.equal(markdown.includes('[^2]'), false)
    assert.equal(markdown.includes('Unused stays out.'), true)
})

test('extractMarkdownFootnotes keeps multiline body and skips code', () => {
    const { markdown, footnotes } = extractMarkdownFootnotes(
        'Ref.[^note]\n\n```\n[^note]: not a def\n```\n\n[^note]: Line one\n    Line two\n'
    )
    assert.equal(footnotes.length, 1)
    assert.equal(footnotes[0].body, 'Line one\nLine two')
    assert.match(markdown, /```\n\[\^note\]: not a def\n```/)
})

test('appendMarkdownFootnotes renders ordered list with backrefs', () => {
    const html = appendMarkdownFootnotes('<p>Hi</p>', [{
        id: 'fn-1',
        body: 'Hello',
        refs: ['fnref-1-1', 'fnref-1-2']
    }], (body) => `<p>${body}</p>`)
    assert.match(html, /<section class="markdown-footnotes">/)
    assert.match(html, /id="fn-1"/)
    assert.match(html, /Hello <a href="#fnref-1-1"/)
    assert.match(html, /class="markdown-footnote-backref">↩<sup>1<\/sup>/)
    assert.match(html, /href="#fnref-1-2"/)
})

test('extractMarkdownAbbreviations strips defs and apply wraps whole words', () => {
    const { markdown, abbreviations } = extractMarkdownAbbreviations(
        'HTML and HTML5 are related.\n\n*[HTML5]: HyperText Markup Language 5\n*[HTML]: HyperText Markup Language\n'
    )
    assert.deepEqual(abbreviations.map((item) => item.term), ['HTML5', 'HTML'])
    assert.equal(markdown.includes('*[HTML'), false)
    const wrapped = applyMarkdownAbbreviations(markdown, abbreviations)
    assert.match(wrapped, /<abbr class="markdown-abbr" title="HyperText Markup Language 5">HTML5<\/abbr>/)
    assert.match(wrapped, /<abbr class="markdown-abbr" title="HyperText Markup Language">HTML<\/abbr>/)
    assert.equal((wrapped.match(/markdown-abbr/g) || []).length, 2)
})

test('abbreviations and footnotes do not rewrite inside code', () => {
    const { markdown: fnMarkdown, footnotes } = extractMarkdownFootnotes(
        'Code `[^1]` stays.\n\n[^1]: hidden\n'
    )
    assert.equal(footnotes.length, 0)
    assert.match(fnMarkdown, /`\[\^1\]`/)
    const { markdown, abbreviations } = extractMarkdownAbbreviations(
        'Use `HTML` in code.\n\n*[HTML]: HyperText Markup Language\n'
    )
    const wrapped = applyMarkdownAbbreviations(markdown, abbreviations)
    assert.match(wrapped, /`HTML`/)
    assert.equal(wrapped.includes('<abbr'), false)
})

test('extractMarkdownFrontMatter strips yaml and renders a table', () => {
    const { markdown, html, fields } = extractMarkdownFrontMatter(
        '---\ntitle: Demo\ntags:\n  - preview\n  - extra\n---\n# Hello\n'
    )
    assert.equal(markdown, '# Hello\n')
    assert.equal(fields.title, 'Demo')
    assert.deepEqual(fields.tags, ['preview', 'extra'])
    assert.match(html, /class="markdown-frontmatter"/)
    assert.match(html, /markdown-frontmatter-title">文档属性/)
    assert.match(html, /<th>title<\/th><td>Demo<\/td>/)
})

test('extractMarkdownFrontMatter accepts BOM, blank lines and CRLF', () => {
    const source = '\uFEFF\r\n---\r\ntitle: Demo\r\nstatus: fixture\r\n---\r\n# Hello\r\n'
    const { markdown, html, fields } = extractMarkdownFrontMatter(source)
    assert.equal(fields.title, 'Demo')
    assert.equal(fields.status, 'fixture')
    assert.match(markdown, /^# Hello/)
    assert.match(html, /文档属性/)
    assert.equal(markdown.includes('title: Demo'), false)
})

test('rewriteMarkdownAlerts wraps github alert quotes', () => {
    const html = rewriteMarkdownAlerts('> [!NOTE]\n> extra info\n', (body) => `<p>${body}</p>`)
    assert.match(html, /class="markdown-alert markdown-alert-note"/)
    assert.match(html, /markdown-alert-title">注意/)
    assert.match(html, /<p>extra info<\/p>/)
    assert.equal(html.includes('[!NOTE]'), false)
})

test('rewriteMarkdownDefinitionLists builds dt/dd pairs', () => {
    const html = rewriteMarkdownDefinitionLists('HTML\n: Hyper Text\n\nW3C\n: Consortium\n: also extra\n', (value) => value)
    assert.match(html, /<dl class="markdown-deflist">/)
    assert.match(html, /<dt>HTML<\/dt><dd>Hyper Text<\/dd>/)
    assert.match(html, /<dt>W3C<\/dt><dd>Consortium<\/dd><dd>also extra<\/dd>/)
})

test('rewriteMarkdownHighlight and emoji skip code', () => {
    assert.match(rewriteMarkdownHighlight('==hi== and `==no==`'), /<mark>hi<\/mark>/)
    assert.equal(rewriteMarkdownHighlight('`==no==`').includes('<mark>'), false)
    assert.equal(rewriteMarkdownEmoji(':tada: and `:rocket:`'), '🎉 and `:rocket:`')
})

test('rewriteMarkdownWikilinks and toc placeholder', () => {
    const withToc = rewriteMarkdownTocPlaceholder('See [[toc]] here')
    assert.match(withToc, /markdown-toc-placeholder/)
    const links = rewriteMarkdownWikilinks('[[guide]] and [[guide|显示名]]')
    assert.match(links, /class="markdown-wikilink" href="#guide">guide/)
    assert.match(links, /href="#guide">显示名/)
})

test('rewriteMarkdownSubSup leaves strikethrough alone', () => {
    const html = rewriteMarkdownSubSup('H~2~O and 19^th^ and ~~del~~')
    assert.match(html, /H<sub>2<\/sub>O/)
    assert.match(html, /19<sup>th<\/sup>/)
    assert.match(html, /~~del~~/)
})

test('heading attrs image size paragraph class and details', () => {
    assert.deepEqual(parseMarkdownBraceAttrs('#custom-heading .lead'), {
        id: 'custom-heading',
        className: 'lead'
    })
    const heading = renderMarkdownHeading('自定义标题 {#custom-heading}', 2, '自定义标题 {#custom-heading}', {
        slug: () => 'fallback'
    })
    assert.match(heading, /id="custom-heading"/)
    assert.equal(heading.includes('{#'), false)
    assert.match(rewriteMarkdownImageSize('![尺寸图](./assets/logo.png =200x80)'), /width="200" height="80"/)
    assert.match(rewriteMarkdownParagraphAttrs('段落带 class：{.lead}'), /<p class="lead">段落带 class：<\/p>/)
    const rich = rewriteMarkdownParagraphAttrs('**bold** {.lead}', (text) => `<strong>${text}</strong>`)
    assert.match(rich, /<p class="lead"><strong>\*\*bold\*\*<\/strong><\/p>/)
    const raw = rewriteMarkdownParagraphAttrs('a <b> {.lead}')
    assert.equal(raw.includes('<b>'), false)
    assert.match(raw, /<p class="lead">a &lt;b&gt;<\/p>/)
    const details = rewriteMarkdownDetails(
        '<details>\n<summary>open</summary>\n\n**x**\n</details>',
        (body) => `<p>${body}</p>`,
        (inline) => inline
    )
    assert.match(details, /<summary>open<\/summary>/)
    assert.match(details, /<p>\*\*x\*\*<\/p>/)
})

test('rewriteMarkdownDetails skips code and keeps only the open attribute', () => {
    const renderBlock = (body) => `BODY[${body}]`
    const renderInline = (inline) => inline
    const fenced = rewriteMarkdownDetails(
        '```html\n<details>\n<summary>x</summary>\nbody\n</details>\n```\n',
        renderBlock,
        renderInline
    )
    assert.match(fenced, /```html\n<details>/)
    assert.equal(fenced.includes('BODY['), false)

    const spanning = rewriteMarkdownDetails(
        '```\n<details>\n```\n后面的正文\n\n</details>\n',
        renderBlock,
        renderInline
    )
    assert.match(spanning, /```\n<details>\n```/)
    assert.match(spanning, /后面的正文/)
    assert.equal(spanning.includes('BODY['), false)

    const inlineCode = rewriteMarkdownDetails(
        '见 `<details><summary>x</summary>y</details>` 结束',
        renderBlock,
        renderInline
    )
    assert.match(inlineCode, /`<details><summary>x<\/summary>y<\/details>`/)
    assert.equal(inlineCode.includes('BODY['), false)

    const nested = rewriteMarkdownDetails(
        '<details>\n<summary>示例</summary>\n\n```\n</details>\n```\n\n仍然在内\n</details>\n',
        renderBlock,
        renderInline
    )
    assert.match(nested, /BODY\[[\s\S]*<\/details>[\s\S]*仍然在内\]/)

    const attrs = rewriteMarkdownDetails(
        '<details onclick="alert(1)" class="open" open>\n<summary style="color:red" open>标题</summary>\n\n正文\n</details>',
        renderBlock,
        renderInline
    )
    assert.match(attrs, /<details open>/)
    assert.equal(/<details[^>]*onclick/.test(attrs), false)
    assert.equal(/<details[^>]*class=/.test(attrs), false)
    assert.equal(attrs.includes('style'), false)
    assert.match(attrs, /<summary open>标题<\/summary>/)
    assert.match(attrs, /BODY\[\s*正文\s*\]/)
})

test('fillMarkdownToc replaces placeholder from heading ids', () => {
    const html = fillMarkdownToc(
        '<div class="markdown-toc-placeholder"></div><h2 id="a"><a class="markdown-heading-anchor" href="#a">#</a>Alpha</h2>'
    )
    assert.match(html, /<nav class="markdown-toc">/)
    assert.match(html, /href="#a">Alpha/)
    assert.equal(html.includes('markdown-toc-placeholder'), false)
})

test('isPlantumlFenceLanguage and wrapPlantumlSource', () => {
    assert.equal(isPlantumlFenceLanguage('plantuml'), true)
    assert.equal(isPlantumlFenceLanguage('puml'), true)
    assert.equal(isPlantumlFenceLanguage('uml'), true)
    assert.equal(isPlantumlFenceLanguage('javascript'), false)
    const html = wrapPlantumlSource('Alice -> Bob')
    assert.match(html, /class="plantuml"/)
    assert.match(html, /Alice -&gt; Bob/)
})

test('renderPlantumlDiagrams mounts svg in a no-script sandbox iframe', async () => {
    const payload = '<svg height="80" width="120"><a href="javascript:alert(document.cookie)">x</a></svg>'
    const node = createPlantumlHostNode('@startuml\nA -> B\n@enduml')
    const root = { querySelectorAll: () => [node] }
    await renderPlantumlDiagrams(root, async () => ({
        renderToString: (lines, ok) => ok(payload)
    }))
    const iframe = node.child
    assert.notEqual(node.innerHTML, payload)
    assert.equal(iframe.tagName, 'iframe')
    assert.equal(iframe.getAttribute('sandbox'), '')
    assert.equal(iframe.getAttribute('srcdoc').includes(payload), true)
    assert.equal(/allow-scripts|allow-same-origin/.test(iframe.getAttribute('sandbox')), false)
    assert.equal(iframe.style.height, '80px')
    assert.equal(node['data-processed'], 'true')
    assert.match(getPlantumlLibUrl(), /ui\/libs\/plantuml\/plantuml\.js$/)
    assert.equal(collectPlantumlNodes(root).length, 0)
})

test('renderPlantumlDiagrams tries again after the viz script fails', async () => {
    const preview = await installPreviewDocument((el, kind) => {
        if (kind === 'script') {
            el.onerror()
        }
    })
    const root = { querySelectorAll: () => [{ getAttribute: () => null }] }
    try {
        await assert.rejects(() => renderPlantumlDiagrams(root), /Failed to load plantuml/)
        await assert.rejects(() => renderPlantumlDiagrams(root), /Failed to load plantuml/)
        assert.equal(preview.counts.script, 2)
    } finally {
        preview.restore()
    }
})

test('buildPlantumlSandboxSrcdoc and parsePlantumlSvgSize', () => {
    const srcdoc = buildPlantumlSandboxSrcdoc('<svg height="12px">ok</svg>')
    assert.match(srcdoc, /<body><svg height="12px">ok<\/svg><\/body>/)
    assert.deepEqual(parsePlantumlSvgSize('<svg viewBox="0 0 40 90"></svg>'), {
        width: 40,
        height: 90
    })
})
