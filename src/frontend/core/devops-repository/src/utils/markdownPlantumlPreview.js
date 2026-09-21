const PLANTUML_NODE_SELECTOR = '.plantuml'
const DEFAULT_FRAME_HEIGHT = 240

let plantumlModulePromise = null

function getPlantumlBaseUrl () {
    if (typeof location === 'undefined') {
        return '/ui/libs/plantuml/'
    }
    const subPath = (typeof window !== 'undefined' && window.BK_SUBPATH) || '/'
    return `${location.origin}${subPath}ui/libs/plantuml/`
}

function loadScript (src) {
    return new Promise((resolve, reject) => {
        if (typeof document === 'undefined') {
            reject(new Error('PlantUML renderer requires a browser'))
            return
        }
        const script = document.createElement('script')
        script.src = src
        script.onload = () => resolve()
        script.onerror = () => reject(new Error(`Failed to load plantuml viz: ${src}`))
        document.head.appendChild(script)
    })
}

function defaultLoadPlantuml () {
    if (!plantumlModulePromise) {
        const base = getPlantumlBaseUrl()
        plantumlModulePromise = loadScript(`${base}viz-global.js`).then(() => {
            const specifier = `${base}plantuml.js`
            return import(/* webpackIgnore: true */ specifier)
        }).then((mod) => {
            if (!mod || typeof mod.renderToString !== 'function') {
                throw new Error('PlantUML renderer is not available')
            }
            return mod
        })
    }
    return plantumlModulePromise
}

export function getPlantumlLibUrl () {
    return `${getPlantumlBaseUrl()}plantuml.js`
}

export function collectPlantumlNodes (root) {
    if (!root || typeof root.querySelectorAll !== 'function') {
        return []
    }
    return Array.from(root.querySelectorAll(PLANTUML_NODE_SELECTOR)).filter((node) => {
        return node && node.getAttribute('data-processed') !== 'true'
    })
}

function sourceFromNode (node) {
    return node && node.textContent ? String(node.textContent).replace(/^\s+|\s+$/g, '') : ''
}

function ownerDocumentOf (node) {
    if (node && node.ownerDocument) {
        return node.ownerDocument
    }
    if (typeof document !== 'undefined') {
        return document
    }
    return null
}

export function parsePlantumlSvgSize (svg) {
    const text = String(svg || '')
    const heightAttr = text.match(/\bheight="([\d.]+)(?:px)?"/i)
    const widthAttr = text.match(/\bwidth="([\d.]+)(?:px)?"/i)
    const viewBox = text.match(/\bviewBox="\s*[-\d.]+\s+[-\d.]+\s+([\d.]+)\s+([\d.]+)\s*"/i)
    const width = widthAttr ? Number(widthAttr[1]) : (viewBox ? Number(viewBox[1]) : 0)
    const height = heightAttr ? Number(heightAttr[1]) : (viewBox ? Number(viewBox[2]) : 0)
    return {
        width: Number.isFinite(width) && width > 0 ? width : 0,
        height: Number.isFinite(height) && height > 0 ? height : 0
    }
}

export function buildPlantumlSandboxSrcdoc (svg) {
    const body = String(svg == null ? '' : svg)
    return '<!DOCTYPE html><html><head><meta charset="utf-8">'
        + '<style>html,body{margin:0;padding:0;background:transparent;}svg{max-width:100%;height:auto;display:block;margin:0 auto;}</style>'
        + `</head><body>${body}</body></html>`
}

function clearNode (node) {
    if (!node) {
        return
    }
    if ('textContent' in node) {
        node.textContent = ''
        return
    }
    while (node.firstChild) {
        node.removeChild(node.firstChild)
    }
}

export function mountPlantumlSandboxFrame (node, svg) {
    const doc = ownerDocumentOf(node)
    if (!node || !doc || typeof doc.createElement !== 'function' || typeof node.appendChild !== 'function') {
        throw new Error('PlantUML sandbox frame requires a DOM node')
    }
    const size = parsePlantumlSvgSize(svg)
    const iframe = doc.createElement('iframe')
    iframe.setAttribute('sandbox', '')
    iframe.setAttribute('referrerpolicy', 'no-referrer')
    iframe.setAttribute('title', 'PlantUML diagram')
    iframe.setAttribute('class', 'plantuml-frame')
    iframe.setAttribute('srcdoc', buildPlantumlSandboxSrcdoc(svg))
    iframe.style.border = '0'
    iframe.style.display = 'block'
    iframe.style.margin = '0 auto'
    iframe.style.width = '100%'
    iframe.style.maxWidth = size.width ? `${size.width}px` : '100%'
    iframe.style.height = `${size.height || DEFAULT_FRAME_HEIGHT}px`
    clearNode(node)
    node.appendChild(iframe)
    return iframe
}

function renderOne (plantuml, node) {
    const lines = sourceFromNode(node).split(/\r?\n/)
    return new Promise((resolve) => {
        plantuml.renderToString(lines, (svg) => {
            try {
                mountPlantumlSandboxFrame(node, svg)
            } catch (error) {
                clearNode(node)
                node.textContent = error && error.message ? error.message : 'PlantUML render failed'
            }
            node.setAttribute('data-processed', 'true')
            resolve()
        }, (message) => {
            clearNode(node)
            node.textContent = message || 'PlantUML render failed'
            node.setAttribute('data-processed', 'true')
            resolve()
        })
    })
}

export async function renderPlantumlDiagrams (root, loadPlantuml = defaultLoadPlantuml) {
    const nodes = collectPlantumlNodes(root)
    if (!nodes.length) {
        return
    }
    const plantuml = await loadPlantuml()
    if (!plantuml || typeof plantuml.renderToString !== 'function') {
        throw new Error('PlantUML renderer is not available')
    }
    for (const node of nodes) {
        await renderOne(plantuml, node)
    }
}
