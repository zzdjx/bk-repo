import test from 'node:test'
import assert from 'node:assert/strict'
import { buildHtmlSandboxSrcdoc } from './htmlFilePreviewCore.js'

function resolveAssetUrl (url) {
    if (/^(https?:|data:)/i.test(url)) {
        return url
    }
    if (url.startsWith('../pic/')) {
        return `https://repo.example.com/web/generic/p/r/pic/${url.slice('../pic/'.length)}`
    }
    if (url.startsWith('vendor/')) {
        return `https://repo.example.com/web/generic/p/r/html/vendor/${url.slice('vendor/'.length)}`
    }
    return `https://repo.example.com/web/generic/p/r/${url.replace(/^\.\//, '')}`
}

test('buildHtmlSandboxSrcdoc keeps original stylesheet script and img urls without resolver', () => {
    const input = [
        '<link rel="stylesheet" href="./app.css">',
        '<script src="./app.js"></script>',
        '<img src="./logo.png" alt="logo">'
    ].join('')
    const srcdoc = buildHtmlSandboxSrcdoc(input)
    assert.match(srcdoc, /href="\.\/app\.css"/)
    assert.match(srcdoc, /src="\.\/app\.js"/)
    assert.match(srcdoc, /src="\.\/logo\.png"/)
})

test('buildHtmlSandboxSrcdoc rewrites relative resources and keeps CDN urls', () => {
    const srcdoc = buildHtmlSandboxSrcdoc(
        [
            '<!DOCTYPE html><html><head>',
            '<link rel="stylesheet" href="./app.css">',
            '<script src="vendor/marked.min.js"></script>',
            '<script src="https://cdn.jsdelivr.net/npm/marked@15.0.7/marked.min.js"></script>',
            '</head><body>',
            '<img src="../pic/architecture.png">',
            '<object data="../pic/architecture.svg" type="image/svg+xml"></object>',
            '<a href="https://evil.example">x</a>',
            '<a href="#top">top</a>',
            '</body></html>'
        ].join(''),
        { resolveAssetUrl }
    )
    assert.match(srcdoc, /href="https:\/\/repo\.example\.com\/web\/generic\/p\/r\/app\.css"/)
    assert.match(srcdoc, /src="https:\/\/repo\.example\.com\/web\/generic\/p\/r\/html\/vendor\/marked\.min\.js"/)
    assert.match(srcdoc, /src="https:\/\/cdn\.jsdelivr\.net\/npm\/marked@15\.0\.7\/marked\.min\.js"/)
    assert.match(srcdoc, /src="https:\/\/repo\.example\.com\/web\/generic\/p\/r\/pic\/architecture\.png"/)
    assert.match(srcdoc, /data="https:\/\/repo\.example\.com\/web\/generic\/p\/r\/pic\/architecture\.svg"/)
    assert.match(srcdoc, /<a href="https:\/\/evil\.example">/)
    assert.match(srcdoc, /<a href="#top">/)
    assert.doesNotMatch(srcdoc, /Content-Security-Policy/)
    assert.doesNotMatch(srcdoc, /default-src/)
})

test('buildHtmlSandboxSrcdoc injects opaque-origin guards and external link opening', () => {
    const srcdoc = buildHtmlSandboxSrcdoc(
        '<h1 id="top">Hello</h1><a href="https://evil.example">x</a><a href="#top">top</a>'
    )
    assert.match(srcdoc, /wrapHistoryMethod\('replaceState'\)/)
    assert.match(srcdoc, /document, 'cookie'/)
    assert.match(srcdoc, /source: 'bkrepo-preview'/)
    assert.match(srcdoc, /action: 'open-url'/)
    assert.match(srcdoc, /scrollIntoView/)
    assert.match(srcdoc, /<h1 id="top">Hello<\/h1>/)
    assert.match(srcdoc, /<a href="https:\/\/evil\.example">/)
})

test('buildHtmlSandboxSrcdoc injects into existing head', () => {
    const srcdoc = buildHtmlSandboxSrcdoc(
        '<!DOCTYPE html><html><head><title>t</title></head><body>Hi</body></html>'
    )
    assert.match(srcdoc, /<head><script>/)
    assert.match(srcdoc, /<\/script><title>t<\/title><\/head><body>Hi<\/body>/)
})

test('buildHtmlSandboxSrcdoc injects after charset meta', () => {
    const srcdoc = buildHtmlSandboxSrcdoc(
        '<!DOCTYPE html><html><head>\n  <meta charset="utf-8">\n  <title>t</title></head><body></body></html>'
    )
    assert.match(srcdoc, /<head>\s*<meta charset="utf-8">\s*<script>/)
    assert.match(srcdoc, /wrapHistoryMethod\('replaceState'\)/)
})

test('buildHtmlSandboxSrcdoc does not inject into </head> inside inline script', () => {
    const input = [
        '<!DOCTYPE html><html lang="zh-CN"><head>',
        '  <meta charset="utf-8">',
        '  <title>iMate</title>',
        '  <script>',
        'var Ce=\'<html xmlns="http://www.w3.org/1999/xhtml"><head></head><body>\'+Ce;',
        'switch (W) { case 0: return 6; }',
        '</script>',
        '</head><body><h1>ok</h1></body></html>'
    ].join('\n')
    const srcdoc = buildHtmlSandboxSrcdoc(input, { resolveAssetUrl })
    assert.match(
        srcdoc,
        /var Ce='<html xmlns="http:\/\/www.w3.org\/1999\/xhtml"><head><\/head><body>'/
    )
    assert.match(srcdoc, /switch \(W\) \{ case 0: return 6; \}/)
    const vendorScript = srcdoc.indexOf('var Ce=')
    const guard = srcdoc.indexOf("wrapHistoryMethod('replaceState')")
    assert.ok(guard >= 0 && guard < vendorScript)
})

test('buildHtmlSandboxSrcdoc ignores html and head tokens inside comments and scripts', () => {
    const input = [
        '<!DOCTYPE html>',
        '<!-- <html><head></head></html> -->',
        '<html><head><title>real</title></head>',
        '<body><script>document.write("<html><head></head></html>")</script></body></html>'
    ].join('')
    const srcdoc = buildHtmlSandboxSrcdoc(input)
    assert.match(srcdoc, /<!-- <html><head><\/head><\/html> -->/)
    assert.match(srcdoc, /document\.write\("<html><head><\/head><\/html>"\)/)
    assert.match(srcdoc, /<html><head><script>/)
})

test('buildHtmlSandboxSrcdoc rewrites css urls outside scripts only', () => {
    const srcdoc = buildHtmlSandboxSrcdoc(
        [
            '<style>body{background:url("./bg.png")}</style>',
            '<div style="background:url(../pic/hero.png)"></div>',
            '<script>var css="url(./bg.png)";</script>'
        ].join(''),
        { resolveAssetUrl }
    )
    assert.match(srcdoc, /url\("https:\/\/repo\.example\.com\/web\/generic\/p\/r\/bg\.png"\)/)
    assert.match(srcdoc, /url\("https:\/\/repo\.example\.com\/web\/generic\/p\/r\/pic\/hero\.png"\)/)
    assert.match(srcdoc, /var css="url\(\.\/bg\.png\)"/)
})
