const AUDIO_SELECTOR = 'audio.pptxv-media-audio'
const VIDEO_SELECTOR = 'video.pptxv-media-video'
const AUDIO_WRAP_CLASS = 'pptx-live-has-audio'
const VIDEO_WRAP_CLASS = 'pptx-live-has-video'
const BOUND_ATTR = 'data-pptx-controls-bound'
const KEEP_EVENTS = ['loadedmetadata', 'canplay', 'play', 'playing', 'pause', 'volumechange']

function turnOnControls (el) {
    if (!el.controls) {
        el.controls = true
    }
    if (typeof el.hasAttribute === 'function' && el.hasAttribute('controls')) {
        return
    }
    if (typeof el.setAttribute === 'function') {
        el.setAttribute('controls', '')
    }
}

function stopBubble (event) {
    if (event && typeof event.stopPropagation === 'function') {
        event.stopPropagation()
    }
}

function bindControlKeepAlive (el, skipMuted) {
    if (!el) {
        return
    }
    if (typeof el.getAttribute === 'function' && el.getAttribute(BOUND_ATTR)) {
        return
    }
    if (typeof el.setAttribute === 'function') {
        el.setAttribute(BOUND_ATTR, '1')
    }
    if (typeof el.addEventListener !== 'function') {
        return
    }
    const keep = function () {
        if (skipMuted && el.muted) {
            return
        }
        turnOnControls(el)
    }
    for (let i = 0; i < KEEP_EVENTS.length; i++) {
        el.addEventListener(KEEP_EVENTS[i], keep)
    }
    el.addEventListener('click', stopBubble, true)
    el.addEventListener('pointerdown', stopBubble, true)
}

function enableMediaControls (root, selector, wrapClass, skipMuted) {
    if (!root || typeof root.querySelectorAll !== 'function') {
        return 0
    }
    const nodes = root.querySelectorAll(selector)
    let count = 0
    for (let i = 0; i < nodes.length; i++) {
        const el = nodes[i]
        if (skipMuted && el.muted) {
            continue
        }
        turnOnControls(el)
        bindControlKeepAlive(el, skipMuted)
        const wrap = typeof el.closest === 'function'
            ? el.closest('.pptxv-media')
            : el.parentElement
        if (wrap && wrap.classList) {
            wrap.classList.add(wrapClass)
        }
        count += 1
    }
    return count
}

export function enablePptxAudioControls (root) {
    return enableMediaControls(root, AUDIO_SELECTOR, AUDIO_WRAP_CLASS, true)
}

export function enablePptxVideoControls (root) {
    return enableMediaControls(root, VIDEO_SELECTOR, VIDEO_WRAP_CLASS, false)
}

export function enablePptxMediaControls (root) {
    return enablePptxAudioControls(root) + enablePptxVideoControls(root)
}
