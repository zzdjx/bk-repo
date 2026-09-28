// Popups stay sandboxed: no allow-same-origin, no allow-popups-to-escape-sandbox.
// An opaque-origin window cannot read the preview page cookie jar or localStorage.
export const PREVIEW_CONTENT_SANDBOX = 'allow-scripts allow-popups allow-forms'

export function classifyAuthoredHref (raw) {
    if (raw == null) {
        return { kind: 'blocked' }
    }
    const text = String(raw).trim()
    if (!text) {
        return { kind: 'blocked' }
    }
    if (text.charAt(0) === '#') {
        return { kind: 'hash' }
    }
    const lower = text.toLowerCase()
    if (lower.startsWith('https://') || lower.startsWith('http://')) {
        return { kind: 'external', url: text }
    }
    if (text.startsWith('//')) {
        return { kind: 'external', url: `https:${text}` }
    }
    if (
        lower.startsWith('mailto:')
        || lower.startsWith('tel:')
        || lower.startsWith('javascript:')
        || lower.startsWith('data:')
    ) {
        return { kind: 'native' }
    }
    return { kind: 'relative' }
}

export function planAnchorActivation (input) {
    if (input && input.download) {
        return { kind: 'download' }
    }
    return classifyAuthoredHref(input ? input.href : '')
}

export function planFormActivation (input) {
    const action = input && input.action
    if (action == null || String(action).trim() === '') {
        return { kind: 'relative' }
    }
    return classifyAuthoredHref(action)
}

export function planScriptNavigation (input) {
    const url = input && input.url != null ? String(input.url) : ''
    if (!input || !input.userActivated) {
        return { kind: 'passthrough' }
    }
    const classified = classifyAuthoredHref(url)
    if (classified.kind === 'external') {
        return classified
    }
    return { kind: 'passthrough' }
}

export function buildPreviewOpenUrlMessage (url) {
    return {
        source: 'bkrepo-preview',
        action: 'open-url',
        url
    }
}

export function previewExternalUrlAllowed (url, pageUrl) {
    const classified = classifyAuthoredHref(url)
    if (classified.kind !== 'external') {
        return null
    }
    let target
    try {
        target = new URL(classified.url)
    } catch (e) {
        return null
    }
    if ((target.protocol !== 'http:' && target.protocol !== 'https:') || !target.hostname) {
        return null
    }
    if (!pageUrl) {
        return classified.url
    }
    let page
    try {
        page = new URL(pageUrl)
    } catch (e) {
        return null
    }
    if (target.hostname === page.hostname) {
        return null
    }
    return classified.url
}

export function readPreviewOpenUrlMessage (data, pageUrl) {
    if (!data || data.source !== 'bkrepo-preview' || data.action !== 'open-url') {
        return null
    }
    return previewExternalUrlAllowed(data.url, pageUrl)
}

export function openPreviewExternalUrl (url, deps) {
    const allowed = previewExternalUrlAllowed(url, deps && deps.pageUrl)
    if (!allowed) {
        return null
    }
    let opened = null
    try {
        opened = deps.open(allowed, '_blank', 'noopener,noreferrer')
    } catch (e) {
        opened = null
    }
    if (opened) {
        try {
            opened.opener = null
        } catch (e) {
            /* the new tab may already be cross-origin */
        }
        return opened
    }
    const message = buildPreviewOpenUrlMessage(allowed)
    if (deps && typeof deps.postMessage === 'function') {
        try {
            deps.postMessage(message, '*')
        } catch (e) {
            /* parent may be gone */
        }
    }
    if (deps && typeof deps.onBlocked === 'function') {
        deps.onBlocked(allowed)
    }
    return null
}

function isPreviewLinkElement (node) {
    if (!node || typeof node.getAttribute !== 'function' || typeof node.hasAttribute !== 'function') {
        return false
    }
    if (!node.hasAttribute('href')) {
        return false
    }
    const name = String(node.localName || node.tagName || '').toLowerCase()
    return name === 'a' || name === 'area'
}

function findPreviewLink (event) {
    const path = event.composedPath ? event.composedPath() : null
    if (path) {
        for (let i = 0; i < path.length; i += 1) {
            if (isPreviewLinkElement(path[i])) {
                return path[i]
            }
        }
    }
    const node = event.target
    if (node && node.closest) {
        return node.closest('a[href], area[href]')
    }
    return null
}

function scrollPreviewHash (event, link, targetDocument, targetWindow) {
    const hashLink = event.target && event.target.closest
        ? event.target.closest('a[href^="#"]')
        : null
    const anchor = hashLink || link
    const href = String(anchor.getAttribute('href') || '').trim()
    if (!href || href === '#' || href.charAt(0) !== '#') {
        event.preventDefault()
        return
    }
    if (event.defaultPrevented) {
        return
    }
    const id = decodeURIComponent(href.slice(1))
    const target = targetDocument.getElementById(id) || targetDocument.getElementsByName(id)[0]
    if (!target) {
        event.preventDefault()
        return
    }
    event.preventDefault()
    target.scrollIntoView({ behavior: 'smooth', block: 'start' })
    try {
        if (targetWindow.history && targetWindow.history.replaceState) {
            targetWindow.history.replaceState(null, '', href)
        }
    } catch (e) {
        /* opaque-origin srcdoc cannot adopt the parent URL */
    }
}

function isPreviewUserActivated (targetWindow) {
    const nav = targetWindow.navigator
    return !!(nav && nav.userActivation && nav.userActivation.isActive)
}

function attachPreviewContentLinkListeners (root, deps) {
    function onClick (event) {
        if (event.type === 'auxclick' && event.button !== 1) {
            return
        }
        const link = findPreviewLink(event)
        if (!link) {
            return
        }
        const decision = planAnchorActivation({
            href: link.getAttribute('href'),
            download: link.hasAttribute('download')
        })
        if (decision.kind === 'download') {
            return
        }
        if (decision.kind === 'hash') {
            if (typeof deps.scrollHash === 'function') {
                deps.scrollHash(event, link)
            }
            return
        }
        if (decision.kind === 'external') {
            event.preventDefault()
            event.stopPropagation()
            openPreviewExternalUrl(decision.url, deps)
            return
        }
        if (decision.kind === 'native') {
            return
        }
        event.preventDefault()
        event.stopPropagation()
    }
    function onSubmit (event) {
        const form = event.target
        if (!form || typeof form.getAttribute !== 'function') {
            return
        }
        const name = String(form.localName || form.tagName || '').toLowerCase()
        if (name !== 'form') {
            return
        }
        const decision = planFormActivation({ action: form.getAttribute('action') })
        if (decision.kind === 'external') {
            form.setAttribute('target', '_blank')
            return
        }
        if (decision.kind === 'native') {
            return
        }
        event.preventDefault()
        event.stopPropagation()
    }
    root.addEventListener('click', onClick, true)
    root.addEventListener('auxclick', onClick, true)
    root.addEventListener('submit', onSubmit, true)
    return function unbindPreviewContentLinks () {
        root.removeEventListener('click', onClick, true)
        root.removeEventListener('auxclick', onClick, true)
        root.removeEventListener('submit', onSubmit, true)
    }
}

function createGuardDeps (targetWindow, nativeOpen) {
    return {
        open (url, target, features) {
            return nativeOpen(url, target, features)
        },
        postMessage (message, origin) {
            if (targetWindow.parent && targetWindow.parent !== targetWindow) {
                targetWindow.parent.postMessage(message, origin)
            }
        },
        onBlocked () {}
    }
}

function hookPreviewWindowOpen (targetWindow, nativeOpen, deps) {
    targetWindow.open = function openPreviewGuarded (url) {
        const userActivated = isPreviewUserActivated(targetWindow)
        const decision = planScriptNavigation({
            url: url == null ? '' : String(url),
            userActivated
        })
        if (decision.kind === 'external') {
            return openPreviewExternalUrl(decision.url, deps)
        }
        // The content sandbox does not grant allow-popups-to-escape-sandbox.
        // User-activated non-http opens stay closed; http(s) uses openPreviewExternalUrl.
        if (decision.kind === 'blocked' || userActivated) {
            return null
        }
        return nativeOpen.apply(targetWindow, arguments)
    }
}

function readLocationHrefDescriptor (loc) {
    try {
        let current = loc
        while (current) {
            const descriptor = Object.getOwnPropertyDescriptor(current, 'href')
            if (descriptor) {
                return descriptor
            }
            current = Object.getPrototypeOf(current)
        }
    } catch (e) {
        /* host location objects can hide property descriptors */
    }
    return null
}

function hookPreviewLocation (targetWindow, deps) {
    const loc = targetWindow.location
    if (!loc) {
        return
    }
    const descriptor = readLocationHrefDescriptor(loc)
    const nativeGet = descriptor && descriptor.get
    const nativeSet = descriptor && descriptor.set
    let hrefValue = ''
    try {
        hrefValue = typeof nativeGet === 'function' ? nativeGet.call(loc) : loc.href
    } catch (e) {
        hrefValue = ''
    }
    try {
        Object.defineProperty(loc, 'href', {
            configurable: true,
            enumerable: true,
            get () {
                if (typeof nativeGet === 'function') {
                    return nativeGet.call(loc)
                }
                return hrefValue
            },
            set (next) {
                const raw = next == null ? '' : String(next)
                const decision = planScriptNavigation({
                    url: raw,
                    userActivated: isPreviewUserActivated(targetWindow)
                })
                if (decision.kind === 'external') {
                    openPreviewExternalUrl(decision.url, deps)
                    return
                }
                if (decision.kind === 'blocked') {
                    return
                }
                if (typeof nativeSet === 'function') {
                    nativeSet.call(loc, next)
                    return
                }
                hrefValue = raw
            }
        })
    } catch (e) {
        /* location.href may be non-configurable */
    }
    wrapLocationMethod(loc, 'assign', targetWindow, deps)
    wrapLocationMethod(loc, 'replace', targetWindow, deps)
}

function wrapLocationMethod (loc, name, targetWindow, deps) {
    const original = loc[name]
    if (typeof original !== 'function') {
        return
    }
    try {
        loc[name] = function previewGuardedLocation (next) {
            const decision = planScriptNavigation({
                url: next == null ? '' : String(next),
                userActivated: isPreviewUserActivated(targetWindow)
            })
            if (decision.kind === 'external') {
                openPreviewExternalUrl(decision.url, deps)
                return
            }
            if (decision.kind === 'blocked') {
                return
            }
            return original.call(loc, next)
        }
    } catch (e) {
        /* Location methods are often non-writable */
    }
}

function hookFormSubmit (targetWindow) {
    let FormCtor = null
    try {
        FormCtor = targetWindow.HTMLFormElement
    } catch (e) {
        return
    }
    if (!FormCtor || !FormCtor.prototype || typeof FormCtor.prototype.submit !== 'function') {
        return
    }
    const original = FormCtor.prototype.submit
    try {
        FormCtor.prototype.submit = function previewGuardedFormSubmit () {
            if (isPreviewUserActivated(targetWindow)) {
                const decision = planFormActivation({ action: this.getAttribute('action') })
                if (decision.kind === 'external') {
                    this.setAttribute('target', '_blank')
                } else {
                    return undefined
                }
            }
            return original.call(this)
        }
    } catch (e) {
        /* prototype may be non-writable */
    }
}

export function bindPreviewContentLinks (root, deps) {
    const bound = Object.assign({}, deps)
    if (typeof bound.scrollHash !== 'function') {
        bound.scrollHash = function scrollHash (event, link) {
            const doc = root.ownerDocument
            const view = doc && doc.defaultView
            if (!doc || !view) {
                event.preventDefault()
                return
            }
            scrollPreviewHash(event, link, doc, view)
        }
    }
    return attachPreviewContentLinkListeners(root, bound)
}

export function installPreviewNavigationGuard (targetWindow, targetDocument) {
    const nativeOpen = typeof targetWindow.open === 'function'
        ? targetWindow.open.bind(targetWindow)
        : function openUnavailable () {
            return null
        }
    const deps = createGuardDeps(targetWindow, nativeOpen)
    deps.scrollHash = function scrollHash (event, link) {
        scrollPreviewHash(event, link, targetDocument, targetWindow)
    }
    attachPreviewContentLinkListeners(targetDocument, deps)
    try {
        hookPreviewWindowOpen(targetWindow, nativeOpen, deps)
    } catch (e) {
        /* window.open may be non-writable */
    }
    try {
        hookPreviewLocation(targetWindow, deps)
    } catch (e) {
        /* location may be non-configurable */
    }
    hookFormSubmit(targetWindow)
}

export function previewNavigationGuardSource () {
    const parts = [
        classifyAuthoredHref,
        planAnchorActivation,
        planFormActivation,
        planScriptNavigation,
        buildPreviewOpenUrlMessage,
        previewExternalUrlAllowed,
        openPreviewExternalUrl,
        isPreviewLinkElement,
        findPreviewLink,
        scrollPreviewHash,
        isPreviewUserActivated,
        attachPreviewContentLinkListeners,
        createGuardDeps,
        hookPreviewWindowOpen,
        readLocationHrefDescriptor,
        hookPreviewLocation,
        wrapLocationMethod,
        hookFormSubmit,
        installPreviewNavigationGuard
    ].map((fn) => fn.toString())
    // Production minify renames the function declaration inside toString(), but a
    // hardcoded call would keep the original name and throw before any listener attaches.
    const installName = installPreviewNavigationGuard.name
    return `(function () {\n${parts.join('\n')}\n${installName}(window, document);\n})();`
}
