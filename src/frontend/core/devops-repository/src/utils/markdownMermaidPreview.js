const MERMAID_NODE_SELECTOR = '.mermaid, .mermaidChart'

let mermaidModulePromise = null
let mermaidInitialized = false

function getMermaidScriptUrl () {
    if (typeof location === 'undefined') {
        return '/ui/libs/mermaid.min.js'
    }
    const subPath = (typeof window !== 'undefined' && window.BK_SUBPATH) || '/'
    return `${location.origin}${subPath}ui/libs/mermaid.min.js`
}

function loadScript (src) {
    return new Promise((resolve, reject) => {
        if (typeof document === 'undefined') {
            reject(new Error('Mermaid renderer requires a browser'))
            return
        }
        const script = document.createElement('script')
        script.src = src
        script.onload = () => resolve()
        script.onerror = () => reject(new Error(`Failed to load mermaid: ${src}`))
        document.head.appendChild(script)
    })
}

function initMermaid (mermaid) {
    if (!mermaidInitialized) {
        mermaid.initialize({
            startOnLoad: false,
            securityLevel: 'strict',
            theme: 'neutral',
            fontFamily: 'PingFangSC-Medium, "PingFang SC", Avenir, Helvetica, Arial, sans-serif'
        })
        mermaidInitialized = true
    }
    return mermaid
}

export function getMermaidLibUrl () {
    return getMermaidScriptUrl()
}

function defaultLoadMermaid () {
    if (typeof window !== 'undefined' && window.mermaid) {
        return Promise.resolve(initMermaid(window.mermaid))
    }
    if (!mermaidModulePromise) {
        mermaidModulePromise = loadScript(getMermaidScriptUrl()).then(() => {
            if (typeof window === 'undefined' || !window.mermaid) {
                throw new Error('Mermaid renderer is not available')
            }
            return initMermaid(window.mermaid)
        })
    }
    return mermaidModulePromise
}

export function collectMermaidNodes (root) {
    if (!root || typeof root.querySelectorAll !== 'function') {
        return []
    }
    return Array.from(root.querySelectorAll(MERMAID_NODE_SELECTOR)).filter((node) => {
        return node && node.getAttribute('data-processed') !== 'true'
    })
}

export async function renderMermaidCharts (root, loadMermaid = defaultLoadMermaid) {
    const nodes = collectMermaidNodes(root)
    if (!nodes.length) {
        return
    }
    const mermaid = await loadMermaid()
    if (!mermaid || typeof mermaid.run !== 'function') {
        throw new Error('Mermaid renderer is not available')
    }
    await mermaid.run({
        nodes,
        suppressErrors: true
    })
}
