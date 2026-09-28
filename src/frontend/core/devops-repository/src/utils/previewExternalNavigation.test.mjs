import test from 'node:test'
import assert from 'node:assert/strict'
import {
    bindPreviewContentLinks,
    classifyAuthoredHref,
    installPreviewNavigationGuard,
    openPreviewExternalUrl,
    planAnchorActivation,
    previewExternalUrlAllowed,
    planFormActivation,
    planScriptNavigation,
    PREVIEW_CONTENT_SANDBOX,
    previewNavigationGuardSource,
    readPreviewOpenUrlMessage
} from './previewExternalNavigation.js'

test('authored links classify the same way for html, jsx, and markdown', () => {
    assert.equal(classifyAuthoredHref('https://example.com/docs').url, 'https://example.com/docs')
    assert.equal(classifyAuthoredHref('http://example.com/docs').url, 'http://example.com/docs')
    assert.equal(classifyAuthoredHref('//example.com/docs').url, 'https://example.com/docs')
    assert.equal(classifyAuthoredHref('  HTTPS://example.com/docs  ').url, 'HTTPS://example.com/docs')
    assert.equal(classifyAuthoredHref('#top').kind, 'hash')
    assert.equal(classifyAuthoredHref('./other.html').kind, 'relative')
    assert.equal(classifyAuthoredHref('mailto:a@example.com').kind, 'native')
    assert.equal(classifyAuthoredHref('tel:10086').kind, 'native')
    assert.equal(classifyAuthoredHref('javascript:alert(1)').kind, 'native')
    assert.equal(classifyAuthoredHref('data:text/html,hi').kind, 'native')
    assert.equal(planAnchorActivation({
        href: 'https://example.com/file.zip',
        download: true
    }).kind, 'download')
    assert.equal(classifyAuthoredHref('https://example.com/self').url, 'https://example.com/self')
    assert.equal(planFormActivation({ action: 'https://example.com/submit' }).url, 'https://example.com/submit')
    assert.equal(planFormActivation({ action: 'https://example.com/search' }).url, 'https://example.com/search')
    assert.equal(planFormActivation({ action: './next.html' }).kind, 'relative')
    assert.equal(classifyAuthoredHref('./other.md').kind, 'relative')
    assert.equal(classifyAuthoredHref('#markdown-外链').kind, 'hash')
    assert.equal(classifyAuthoredHref('https://example.com/raw').url, 'https://example.com/raw')
})

test('failed window.open posts open-url and keeps the url for another click', () => {
    const messages = []
    const blocked = []
    const opened = openPreviewExternalUrl('//example.com/docs', {
        open () {
            return null
        },
        postMessage (message, origin) {
            messages.push({ message, origin })
        },
        onBlocked (url) {
            blocked.push(url)
        }
    })
    assert.equal(opened, null)
    assert.deepEqual(messages, [{
        origin: '*',
        message: {
            source: 'bkrepo-preview',
            action: 'open-url',
            url: 'https://example.com/docs'
        }
    }])
    assert.deepEqual(blocked, ['https://example.com/docs'])
    assert.equal(readPreviewOpenUrlMessage(messages[0].message), 'https://example.com/docs')
    assert.equal(readPreviewOpenUrlMessage({
        source: 'bkrepo-preview',
        action: 'open-url',
        url: 'javascript:alert(1)'
    }), null)
    assert.equal(readPreviewOpenUrlMessage({
        source: 'bkrepo-preview',
        action: 'open-url',
        url: 'https://preview.example/generic/p/r/a.html?preview=true'
    }, 'https://preview.example/ui/p/file'), null)
    assert.equal(readPreviewOpenUrlMessage({
        source: 'bkrepo-preview',
        action: 'open-url',
        url: 'http://preview.example/a.html'
    }, 'https://preview.example/ui/p/file'), null)
    assert.equal(previewExternalUrlAllowed(
        'https://user:secret@preview.example/a.html',
        'https://preview.example/ui'
    ), null)
    assert.equal(readPreviewOpenUrlMessage({
        source: 'bkrepo-preview',
        action: 'open-url',
        url: 'https://example.com/docs'
    }, 'https://preview.example/ui/p/file'), 'https://example.com/docs')
})

test('sandbox guard opens authored absolute links and leaves relative clicks', () => {
    const harness = createHarness()
    installPreviewNavigationGuard(harness.win, harness.doc)
    const external = click(harness, link('https://example.com/docs', { id: 'abs-https' }))
    assert.equal(external.defaultPrevented, true)
    assert.deepEqual(harness.opened, [{
        url: 'https://example.com/docs',
        target: '_blank',
        features: 'noopener,noreferrer'
    }])
    harness.opened.length = 0
    const relative = click(harness, link('./other.html'))
    assert.equal(relative.defaultPrevented, true)
    assert.equal(harness.opened.length, 0)
    assert.equal(harness.location.href, 'about:srcdoc')
    const download = click(harness, link('https://example.com/file.zip', { download: '' }))
    assert.equal(download.defaultPrevented, false)
    const form = formElement('https://example.com/submit', 'post')
    const submit = dispatch(harness, 'submit', form)
    assert.equal(submit.defaultPrevented, false)
    assert.equal(form.getAttribute('target'), '_blank')
    assert.equal(form.getAttribute('method'), 'post')
    harness.win.navigator.userActivation.isActive = true
    harness.location.assign('https://example.com/from-script')
    assert.equal(harness.location.href, 'about:srcdoc')
    assert.equal(harness.opened.at(-1).url, 'https://example.com/from-script')
    harness.win.navigator.userActivation.isActive = false
    harness.location.assign('https://example.com/auto')
    assert.equal(harness.location.href, 'https://example.com/auto')
})

test('activated relative location.href navigates and non-http window.open stays inside the sandbox', () => {
    const harness = createHarness()
    const navigated = []
    let current = 'about:srcdoc'
    Object.defineProperty(harness.location, 'href', {
        configurable: true,
        enumerable: true,
        get () {
            return current
        },
        set (next) {
            current = String(next)
            navigated.push(current)
        }
    })
    installPreviewNavigationGuard(harness.win, harness.doc)
    harness.win.navigator.userActivation.isActive = true
    harness.location.href = './page.html'
    assert.deepEqual(navigated, ['./page.html'])
    assert.equal(harness.location.href, './page.html')
    ;['about:blank', 'data:text/html,hi', 'javascript:alert(1)', './page.html', 'mailto:a@example.com', ''].forEach((url) => {
        assert.equal(harness.win.open(url), null)
    })
    assert.equal(harness.opened.length, 0)
    assert.equal(harness.win.open('https://example.com/docs'), null)
    assert.deepEqual(harness.opened, [{
        url: 'https://example.com/docs',
        target: '_blank',
        features: 'noopener,noreferrer'
    }])
    assert.deepEqual(navigated, ['./page.html'])
    harness.win.navigator.userActivation.isActive = false
    harness.win.open('about:blank')
    assert.equal(harness.opened.at(-1).url, 'about:blank')
})

test('iframe guard source calls the installer by its runtime name', () => {
    const source = previewNavigationGuardSource()
    const installName = installPreviewNavigationGuard.name
    assert.match(source, new RegExp(`function ${installName}\\s*\\(`))
    assert.match(source, new RegExp(`${installName}\\(window, document\\);`))
    const harness = createHarness()
    new Function('window', 'document', source)(harness.win, harness.doc)
    const external = click(harness, link('https://example.com/docs'))
    assert.equal(external.defaultPrevented, true)
    assert.equal(harness.opened[0].url, 'https://example.com/docs')
    harness.win.navigator.userActivation.isActive = true
    harness.location.href = './page.html'
    assert.equal(harness.location.href, './page.html')
    assert.equal(harness.win.open('about:blank'), null)
    assert.equal(harness.opened.length, 1)
})

test('content sandbox allows popups and forms but not top navigation, downloads, or sandbox escape', () => {
    assert.equal(
        PREVIEW_CONTENT_SANDBOX,
        'allow-scripts allow-popups allow-forms'
    )
    assert.equal(PREVIEW_CONTENT_SANDBOX.includes('allow-popups-to-escape-sandbox'), false)
    assert.equal(PREVIEW_CONTENT_SANDBOX.includes('allow-same-origin'), false)
    assert.equal(PREVIEW_CONTENT_SANDBOX.includes('allow-top-navigation'), false)
    assert.equal(PREVIEW_CONTENT_SANDBOX.includes('allow-downloads'), false)
})

test('script navigation without a click is left alone and browser schemes stay available while activated', () => {
    assert.equal(planScriptNavigation({
        url: 'https://example.com/auto',
        userActivated: false
    }).kind, 'passthrough')
    assert.equal(planScriptNavigation({
        url: './stay.html',
        userActivated: true
    }).kind, 'passthrough')
    assert.equal(planScriptNavigation({
        url: '',
        userActivated: true
    }).kind, 'passthrough')
    assert.equal(planScriptNavigation({
        url: 'javascript:void(0)',
        userActivated: true
    }).kind, 'passthrough')
    assert.equal(planScriptNavigation({
        url: 'mailto:a@example.com',
        userActivated: true
    }).kind, 'passthrough')
    assert.equal(planScriptNavigation({
        url: 'https://example.com/from-script',
        userActivated: true
    }).url, 'https://example.com/from-script')
})

test('javascript, mailto, tel, and data clicks keep the browser default', () => {
    const harness = createHarness()
    installPreviewNavigationGuard(harness.win, harness.doc)
    ;['javascript:void(0)', 'mailto:a@example.com', 'tel:10086', 'data:text/html,hi'].forEach((href) => {
        const event = click(harness, link(href))
        assert.equal(event.defaultPrevented, false)
        assert.equal(event.stopped, undefined)
    })
    assert.equal(harness.opened.length, 0)
})

test('hash clicks stay in the document even with surrounding whitespace', () => {
    const harness = createHarness()
    const target = { scrollIntoView () { target.scrolled = true } }
    harness.doc.getElementById = (id) => (id === 'top' ? target : null)
    installPreviewNavigationGuard(harness.win, harness.doc)
    const event = click(harness, link('  #top'))
    assert.equal(event.defaultPrevented, true)
    assert.equal(target.scrolled, true)
    assert.equal(harness.location.href, 'about:srcdoc')
})

test('a failed location hook still guards later clicks', () => {
    const harness = createHarness()
    Object.defineProperty(harness.location, 'assign', {
        configurable: false,
        writable: false,
        value () {
            throw new Error('native assign')
        }
    })
    installPreviewNavigationGuard(harness.win, harness.doc)
    const event = click(harness, link('https://example.com/docs'))
    assert.equal(event.defaultPrevented, true)
    assert.equal(harness.opened[0].url, 'https://example.com/docs')
})

test('markdown root click opens absolute links and does not hook page navigation', () => {
    const opened = []
    const blocked = []
    const root = createRoot()
    const location = { href: 'https://preview.example/file', assign (url) { this.href = url } }
    bindPreviewContentLinks(root, {
        pageUrl: 'https://preview.example/file',
        open (url, target, features) {
            opened.push({ url, target, features })
            return { opener: {} }
        },
        postMessage () {
            throw new Error('postMessage should not run after a successful open')
        },
        onBlocked (url) {
            blocked.push(url)
        }
    })
    const event = clickOn(root, link('https://example.com/raw'))
    assert.equal(event.defaultPrevented, true)
    assert.equal(opened[0].url, 'https://example.com/raw')
    assert.equal(opened[0].features, 'noopener,noreferrer')
    assert.equal(blocked.length, 0)
    assert.equal(location.href, 'https://preview.example/file')
    const relative = clickOn(root, link('./other.md'))
    assert.equal(relative.defaultPrevented, true)
    assert.equal(opened.length, 1)
    const sameHost = clickOn(root, link('https://preview.example/generic/p/r/a.html?preview=true'))
    assert.equal(sameHost.defaultPrevented, true)
    assert.equal(opened.length, 1)
    const sameHostHttp = clickOn(root, link('http://preview.example:8443/a.html'))
    assert.equal(sameHostHttp.defaultPrevented, true)
    assert.equal(opened.length, 1)
})

function link (href, extra) {
    return element('a', Object.assign({ href }, extra || {}))
}

function formElement (action, method) {
    return element('form', { action, method })
}

function element (tagName, attrs) {
    return {
        tagName,
        localName: tagName,
        attrs: Object.assign({}, attrs),
        parentElement: null,
        getAttribute (name) {
            return Object.prototype.hasOwnProperty.call(this.attrs, name) ? this.attrs[name] : null
        },
        hasAttribute (name) {
            return Object.prototype.hasOwnProperty.call(this.attrs, name)
        },
        setAttribute (name, value) {
            this.attrs[name] = String(value)
        },
        closest () {
            return null
        }
    }
}

function createHarness () {
    const listeners = { click: [], auxclick: [], submit: [] }
    const opened = []
    const messages = []
    const location = {
        href: 'about:srcdoc',
        assign (url) {
            this.href = String(url)
        },
        replace (url) {
            this.href = String(url)
        }
    }
    const doc = {
        addEventListener (type, fn) {
            listeners[type].push(fn)
        },
        removeEventListener () {},
        getElementById () {
            return null
        },
        getElementsByName () {
            return []
        }
    }
    const win = {
        document: doc,
        location,
        navigator: { userActivation: { isActive: true } },
        parent: {
            postMessage (message, origin) {
                messages.push({ message, origin })
            }
        },
        history: { replaceState () {} },
        open (url, target, features) {
            opened.push({ url, target, features })
            return null
        }
    }
    return { win, doc, listeners, opened, messages, location }
}

function click (harness, target) {
    return dispatch(harness, 'click', target)
}

function dispatch (harness, type, target) {
    const event = {
        type,
        target,
        button: 0,
        defaultPrevented: false,
        preventDefault () {
            this.defaultPrevented = true
        },
        stopPropagation () {
            this.stopped = true
        },
        composedPath () {
            return [target]
        }
    }
    harness.listeners[type].forEach((fn) => fn(event))
    return event
}

function createRoot () {
    const listeners = { click: [], auxclick: [], submit: [] }
    return {
        listeners,
        addEventListener (type, fn) {
            listeners[type].push(fn)
        },
        removeEventListener (type, fn) {
            listeners[type] = listeners[type].filter((item) => item !== fn)
        }
    }
}

function clickOn (root, target) {
    const event = {
        type: 'click',
        target,
        button: 0,
        defaultPrevented: false,
        preventDefault () {
            this.defaultPrevented = true
        },
        stopPropagation () {
            this.stopped = true
        },
        composedPath () {
            return [target]
        }
    }
    root.listeners.click.forEach((fn) => fn(event))
    return event
}
