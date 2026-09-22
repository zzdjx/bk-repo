const MATH_NODE_SELECTOR = '.markdown-math'

let katexModulePromise = null

function getKatexBaseUrl () {
    if (typeof location === 'undefined') {
        return '/ui/libs/katex/'
    }
    const subPath = (typeof window !== 'undefined' && window.BK_SUBPATH) || '/'
    return `${location.origin}${subPath}ui/libs/katex/`
}

function loadScript (src) {
    return new Promise((resolve, reject) => {
        if (typeof document === 'undefined') {
            reject(new Error('KaTeX renderer requires a browser'))
            return
        }
        const script = document.createElement('script')
        script.src = src
        script.onload = () => resolve()
        script.onerror = () => reject(new Error(`Failed to load katex: ${src}`))
        document.head.appendChild(script)
    })
}

function loadStylesheet (href) {
    return new Promise((resolve, reject) => {
        if (typeof document === 'undefined') {
            reject(new Error('KaTeX renderer requires a browser'))
            return
        }
        const existing = document.getElementById('bk-repo-katex-css')
        if (existing) {
            resolve()
            return
        }
        const link = document.createElement('link')
        link.id = 'bk-repo-katex-css'
        link.rel = 'stylesheet'
        link.href = href
        link.onload = () => resolve()
        link.onerror = () => reject(new Error(`Failed to load katex css: ${href}`))
        document.head.appendChild(link)
    })
}

function defaultLoadKatex () {
    if (typeof window !== 'undefined' && window.katex) {
        return Promise.resolve(window.katex)
    }
    if (!katexModulePromise) {
        const base = getKatexBaseUrl()
        katexModulePromise = Promise.all([
            loadStylesheet(`${base}katex.min.css`),
            loadScript(`${base}katex.min.js`)
        ]).then(() => {
            if (typeof window === 'undefined' || !window.katex) {
                throw new Error('KaTeX renderer is not available')
            }
            return window.katex
        }).catch((error) => {
            katexModulePromise = null
            throw error
        })
    }
    return katexModulePromise
}

export function getKatexLibUrl () {
    return `${getKatexBaseUrl()}katex.min.js`
}

export function collectMathNodes (root) {
    if (!root || typeof root.querySelectorAll !== 'function') {
        return []
    }
    return Array.from(root.querySelectorAll(MATH_NODE_SELECTOR)).filter((node) => {
        return node && node.getAttribute('data-processed') !== 'true'
    })
}

function isDisplayMath (node) {
    if (!node) {
        return false
    }
    if (typeof node.classList !== 'undefined' && node.classList.contains('markdown-math-display')) {
        return true
    }
    const className = node.className == null ? '' : String(node.className)
    return className.split(/\s+/).includes('markdown-math-display')
}

export async function renderMathFormulas (root, loadKatex = defaultLoadKatex) {
    const nodes = collectMathNodes(root)
    if (!nodes.length) {
        return
    }
    const katex = await loadKatex()
    if (!katex || typeof katex.render !== 'function') {
        throw new Error('KaTeX renderer is not available')
    }
    nodes.forEach((node) => {
        const tex = node.textContent == null ? '' : String(node.textContent)
        try {
            katex.render(tex, node, {
                displayMode: isDisplayMath(node),
                throwOnError: false,
                output: 'html',
                trust: false
            })
        } catch (error) {
            node.setAttribute('title', error && error.message ? error.message : String(error))
        }
        node.setAttribute('data-processed', 'true')
    })
}
