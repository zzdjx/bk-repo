export function computePptxThumbScale (canvas, frame) {
    const canvasWidth = Number(canvas && canvas.width)
    const canvasHeight = Number(canvas && canvas.height)
    const boxWidth = Number(frame && frame.width)
    const boxHeight = Number(frame && frame.height)
    if (!(canvasWidth > 0) || !(canvasHeight > 0) || !(boxWidth > 0) || !(boxHeight > 0)) {
        return null
    }
    const scale = Math.min(boxWidth / canvasWidth, boxHeight / canvasHeight)
    if (!(scale > 0) || !Number.isFinite(scale)) {
        return null
    }
    return scale
}

function listThumbStages (root) {
    if (!root || typeof root.querySelectorAll !== 'function') {
        return []
    }
    return Array.prototype.slice.call(root.querySelectorAll('.pptxv-thumb-frame > .pptxv-stage'))
}

function readNativeStageSize (stage) {
    if (!stage) {
        return null
    }
    const style = stage.style
    const prevTransform = style.transform
    const prevZoom = style.zoom
    style.setProperty('transform', 'none', 'important')
    style.zoom = '1'
    const width = stage.offsetWidth
    const height = stage.offsetHeight
    style.transform = prevTransform
    style.zoom = prevZoom
    if (!(width > 0) || !(height > 0)) {
        return null
    }
    return { width: width, height: height }
}

export function readUnscaledStageSize (root) {
    const stages = listThumbStages(root)
    if (!stages.length) {
        return null
    }
    return readNativeStageSize(stages[0])
}

function applyStageZoom (stage, canvas, zoom) {
    const style = stage.style
    style.setProperty('transform', 'none', 'important')
    style.transformOrigin = '0 0'
    style.width = canvas.width + 'px'
    style.height = canvas.height + 'px'
    style.zoom = String(zoom)
}

export function applyPptxThumbFit (host) {
    if (!host || !host.style) {
        return false
    }
    const stages = listThumbStages(host)
    const size = readNativeStageSize(stages[0])
    if (!size) {
        return false
    }
    host.style.setProperty('--pptx-slide-w', size.width + 'px')
    host.style.setProperty('--pptx-slide-h', size.height + 'px')
    host.style.setProperty('--pptx-slide-aspect', String(size.width / size.height))
    const frame = stages[0].parentElement
    const scale = computePptxThumbScale(size, {
        width: frame ? frame.clientWidth : 0,
        height: frame ? frame.clientHeight : 0
    })
    if (scale == null) {
        return false
    }
    host.style.setProperty('--pptx-thumb-scale', String(scale))
    for (let index = 0; index < stages.length; index += 1) {
        applyStageZoom(stages[index], size, scale)
    }
    if (host.classList && typeof host.classList.add === 'function') {
        host.classList.add('is-sized')
    }
    return true
}

export function clearPptxThumbFit (host) {
    if (!host || !host.style) {
        return
    }
    host.style.removeProperty('--pptx-slide-w')
    host.style.removeProperty('--pptx-slide-h')
    host.style.removeProperty('--pptx-slide-aspect')
    host.style.removeProperty('--pptx-thumb-scale')
    const stages = listThumbStages(host)
    for (let index = 0; index < stages.length; index += 1) {
        const style = stages[index].style
        style.removeProperty('transform')
        style.removeProperty('transform-origin')
        style.removeProperty('width')
        style.removeProperty('height')
        style.removeProperty('zoom')
    }
    if (host.classList && typeof host.classList.remove === 'function') {
        host.classList.remove('is-sized')
    }
}
