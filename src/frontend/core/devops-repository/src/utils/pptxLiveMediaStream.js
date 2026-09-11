const MEDIA_SELECTOR = 'video.pptxv-media-video, audio.pptxv-media-audio'
const VIDEO_EXT_BODY = 'mp4|m4v|mov|qt|avi|wmv|webm|mkv|mpg|mpeg|3gp|asf|flv|ts|m2ts'
const AV_EXT = new RegExp('\\.(' + VIDEO_EXT_BODY + '|mp3|wav|m4a|wma|aac|ogg|opus)$', 'i')
const VIDEO_EXT = new RegExp('\\.(' + VIDEO_EXT_BODY + ')$', 'i')

export function buildPptxPartUrl (previewUrl, partPath) {
    if (!previewUrl || !partPath) {
        return ''
    }
    try {
        const origin = (typeof window !== 'undefined' && window.location && window.location.origin)
            ? window.location.origin
            : 'http://localhost'
        const url = new URL(previewUrl, origin)
        url.searchParams.set('part', partPath)
        return url.toString()
    } catch (e) {
        return ''
    }
}

function normalizeMediaPath (value) {
    const raw = String(value || '').replace(/\\/g, '/').trim()
    if (!raw) {
        return ''
    }
    const name = raw.substring(raw.lastIndexOf('/') + 1)
    if (!name || name.indexOf('..') >= 0 || name.indexOf('\0') >= 0 || !AV_EXT.test(name)) {
        return ''
    }
    return 'ppt/media/' + name
}

function walkNodes (node, visit) {
    if (!node || typeof node !== 'object') {
        return
    }
    visit(node)
    const bags = [node.elements, node.children, node.shapes, node.items]
    for (let i = 0; i < bags.length; i++) {
        const list = bags[i]
        if (!Array.isArray(list)) {
            continue
        }
        for (let j = 0; j < list.length; j++) {
            walkNodes(list[j], visit)
        }
    }
}

function resolveSlides (viewer) {
    if (!viewer) {
        return []
    }
    if (typeof viewer.getSlides === 'function') {
        const slides = viewer.getSlides()
        if (Array.isArray(slides)) {
            return slides
        }
    }
    if (viewer.store && Array.isArray(viewer.store.slides)) {
        return viewer.store.slides
    }
    const editor = viewer.editor
    if (editor && typeof editor.getSlides === 'function') {
        const slides = editor.getSlides()
        if (Array.isArray(slides)) {
            return slides
        }
    }
    return []
}

function collectBindings (viewer) {
    const byId = {}
    const paths = []
    const slides = resolveSlides(viewer)
    for (let i = 0; i < slides.length; i++) {
        walkNodes(slides[i], function (node) {
            const path = normalizeMediaPath(
                node.mediaPath || node.media || node.src || node.path || ''
            )
            if (!path) {
                return
            }
            const id = node.id || node.elementId || node.shapeId
            if (id) {
                byId[String(id)] = path
            }
            if (paths.indexOf(path) === -1) {
                paths.push(path)
            }
        })
    }
    return { byId: byId, paths: paths }
}

function elementId (el) {
    if (!el) {
        return ''
    }
    if (typeof el.getAttribute === 'function') {
        const own = el.getAttribute('data-element-id')
        if (own) {
            return own
        }
        const wrap = typeof el.closest === 'function'
            ? el.closest('[data-element-id]')
            : null
        if (wrap && typeof wrap.getAttribute === 'function') {
            return wrap.getAttribute('data-element-id') || ''
        }
    }
    return (el.dataset && el.dataset.elementId) || ''
}

function isVideoPath (path) {
    return VIDEO_EXT.test(path)
}

function isVideoEl (el) {
    return Boolean(el && el.tagName && String(el.tagName).toLowerCase() === 'video')
}

function assignFallback (el, nodes, catalog) {
    const video = isVideoEl(el)
    const matches = catalog.filter(function (path) {
        return isVideoPath(path) === video
    })
    if (matches.length === 1) {
        return matches[0]
    }
    let ordinal = 0
    for (let i = 0; i < nodes.length; i++) {
        if (isVideoEl(nodes[i]) !== video) {
            continue
        }
        if (nodes[i] === el) {
            return matches[ordinal] || ''
        }
        ordinal += 1
    }
    return ''
}

function setStreamSrc (el, url) {
    if (!el || !url || el.getAttribute('data-pptx-stream-src') === url) {
        return false
    }
    const prev = el.currentSrc || el.src || ''
    if (prev.indexOf('blob:') === 0 && typeof URL !== 'undefined' && URL.revokeObjectURL) {
        try {
            URL.revokeObjectURL(prev)
        } catch (e) {
            /* ignore */
        }
    }
    el.src = url
    el.setAttribute('data-pptx-stream-src', url)
    el.controls = true
    if (typeof el.load === 'function') {
        el.load()
    }
    return true
}

export function bindPptxMediaStreams (root, options) {
    if (!root || typeof root.querySelectorAll !== 'function') {
        return 0
    }
    const mediaBaseUrl = options && options.mediaBaseUrl
    if (!mediaBaseUrl) {
        return 0
    }
    const bindings = collectBindings(options && options.viewer)
    const catalog = bindings.paths.slice()
    const extra = (options && options.mediaPaths) || []
    for (let i = 0; i < extra.length; i++) {
        const path = normalizeMediaPath(extra[i])
        if (path && catalog.indexOf(path) === -1) {
            catalog.push(path)
        }
    }
    const nodes = root.querySelectorAll(MEDIA_SELECTOR)
    let count = 0
    for (let i = 0; i < nodes.length; i++) {
        const el = nodes[i]
        const id = elementId(el)
        const path = (id && bindings.byId[id]) || assignFallback(el, nodes, catalog)
        if (!path) {
            continue
        }
        const url = buildPptxPartUrl(mediaBaseUrl, path)
        if (setStreamSrc(el, url)) {
            count += 1
        }
    }
    return count
}
