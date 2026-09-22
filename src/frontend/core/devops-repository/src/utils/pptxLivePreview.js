import { createPptxViewer } from 'pptx-vanilla-viewer'
import { enablePptxMediaControls } from './pptxLiveAudioControls'
import { bindPptxMediaStreams } from './pptxLiveMediaStream'
import { listPptxMediaPaths, stripHeavyPptxMedia } from './pptxStripHeavyMedia'
import { applyPptxThumbFit, clearPptxThumbFit } from './pptxLiveThumbSize'

function resolveFileName (name) {
    if (!name) {
        return 'preview.pptx'
    }
    const normalized = String(name).replace(/\\/g, '/')
    const baseName = normalized.includes('/')
        ? normalized.slice(normalized.lastIndexOf('/') + 1)
        : normalized
    return baseName || 'preview.pptx'
}

function withFullscreenBlocked (task) {
    const proto = HTMLElement.prototype
    const original = proto.requestFullscreen
    const originalWebkit = proto.webkitRequestFullscreen
    const reject = function () {
        return Promise.reject(new Error('skip-fullscreen'))
    }
    proto.requestFullscreen = reject
    if (originalWebkit) {
        proto.webkitRequestFullscreen = reject
    }
    const restore = function () {
        proto.requestFullscreen = original
        if (originalWebkit) {
            proto.webkitRequestFullscreen = originalWebkit
        }
    }
    try {
        return Promise.resolve(task()).finally(restore)
    } catch (error) {
        restore()
        return Promise.reject(error)
    }
}

export function createPptxLivePlayer (container, options) {
    const onStatus = (options && options.onStatus) || function () {}
    const fileName = resolveFileName(options && options.fileName)
    const mediaBaseUrl = (options && options.mediaBaseUrl) || ''
    let viewer = null
    let showEnded = false
    let thumbHost = null
    let onThumbClick = null
    let stopMediaWatch = null
    let mediaPaths = []

    function bindStreamMedia () {
        if (mediaBaseUrl) {
            bindPptxMediaStreams(container, {
                viewer: viewer,
                mediaBaseUrl: mediaBaseUrl,
                mediaPaths: mediaPaths
            })
        }
        enablePptxMediaControls(container)
    }

    function isPresenting () {
        return Boolean(viewer && typeof viewer.getMode === 'function' && viewer.getMode() === 'present')
    }

    function emitStatus (extra) {
        if (!viewer) {
            return
        }
        const total = typeof viewer.getSlideCount === 'function' ? viewer.getSlideCount() : 0
        const current = typeof viewer.getCurrentSlide === 'function' ? viewer.getCurrentSlide() : 0
        onStatus({
            slide: current + 1,
            total: total,
            extra: extra || (showEnded ? 'ended' : '')
        })
    }

    function tryEnableLoop () {
        try {
            const editor = viewer && viewer.editor
            const actions = editor && typeof editor.getEditActions === 'function'
                ? editor.getEditActions()
                : null
            if (actions && typeof actions.updatePresentationSettings === 'function') {
                actions.updatePresentationSettings({ loopContinuously: true })
            }
        } catch (e) {
            /* ignore */
        }
    }

    function enterInPage () {
        if (!viewer || typeof viewer.enterPresentation !== 'function') {
            return Promise.resolve()
        }
        return withFullscreenBlocked(function () {
            return viewer.enterPresentation()
        }).catch(function () {
            // 引擎会自行退回页内放映
        }).then(function () {
            showEnded = false
            const root = container && container.querySelector('.pptxv')
            if (root) {
                root.classList.add('pptxv-no-show-popup')
            }
            bindStreamMedia()
            emitStatus()
        })
    }

    function watchMedia () {
        if (!container || typeof MutationObserver === 'undefined') {
            bindStreamMedia()
            return function () {}
        }
        const observer = new MutationObserver(function () {
            bindStreamMedia()
        })
        observer.observe(container, {
            childList: true,
            subtree: true,
            attributes: true,
            attributeFilter: ['controls']
        })
        bindStreamMedia()
        return function () {
            observer.disconnect()
        }
    }

    function needsReenter () {
        return !isPresenting() || showEnded
    }

    function replayIfIdle () {
        if (!viewer || !needsReenter()) {
            return Promise.resolve()
        }
        return enterInPage()
    }

    function load (bytes) {
        destroy()
        showEnded = false
        // 无 mediaBaseUrl 时不剥离：远程预览不能 part= 回源，必须留着包内音视频。
        const stripped = stripHeavyPptxMedia(bytes, mediaBaseUrl ? 0 : undefined)
        const source = stripped.bytes
        mediaPaths = listPptxMediaPaths(source)
        return new Promise(function (resolve, reject) {
            let settled = false
            const finish = function (error) {
                if (settled) {
                    return
                }
                settled = true
                if (error) {
                    reject(error)
                } else {
                    resolve()
                }
            }
            try {
                viewer = createPptxViewer(container, {
                    source: source,
                    fileName: fileName,
                    editable: false,
                    autosave: false,
                    showToolbar: false,
                    showThumbnails: true,
                    showFormatToolbar: false,
                    showInspector: false,
                    onLoad: function (info) {
                        const total = (info && info.slideCount) || 0
                        if (total <= 0) {
                            finish(new Error('pptx has no slides'))
                            return
                        }
                        tryEnableLoop()
                        onStatus({ slide: 1, total: total, extra: '' })
                        enterInPage().then(function () { finish() }, finish)
                    },
                    onSlideChange: function () {
                        bindStreamMedia()
                        emitStatus()
                    },
                    onPresentationChange: function (presenting) {
                        bindStreamMedia()
                        if (presenting) {
                            showEnded = false
                            emitStatus()
                            return
                        }
                        showEnded = true
                        emitStatus('ended')
                    },
                    onError: function (message, cause) {
                        const error = cause instanceof Error
                            ? cause
                            : new Error(String(message || 'pptx-viewer-error'))
                        finish(error)
                    }
                })
                stopMediaWatch = watchMedia()
            } catch (error) {
                finish(error)
            }
        })
    }

    function advance () {
        if (!viewer) {
            return
        }
        if (needsReenter()) {
            enterInPage()
            return
        }
        viewer.next()
        if (!isPresenting()) {
            showEnded = true
            emitStatus('ended')
            return
        }
        emitStatus()
    }

    function goPrev () {
        if (!viewer) {
            return
        }
        if (needsReenter()) {
            enterThenGoTo(Math.max(0, viewer.getCurrentSlide() - 1))
            return
        }
        viewer.prev()
        emitStatus()
    }

    function enterThenGoTo (index) {
        enterInPage().then(function () {
            if (viewer && typeof viewer.goToSlide === 'function') {
                viewer.goToSlide(index)
            }
            emitStatus()
        })
    }

    function goToSlide (index) {
        if (!viewer || typeof viewer.goToSlide !== 'function') {
            return
        }
        const target = Math.max(0, index)
        if (needsReenter()) {
            enterThenGoTo(target)
            return
        }
        viewer.goToSlide(target)
        emitStatus()
    }

    function handleThumbClick (event) {
        const target = event.target
        const thumb = target && target.closest ? target.closest('.pptxv-thumb') : null
        if (!thumb) {
            return
        }
        event.preventDefault()
        event.stopPropagation()
        const index = Number(thumb.dataset.slideIndex)
        if (!Number.isFinite(index)) {
            return
        }
        goToSlide(index)
    }

    function moveThumbRail () {
        if (!thumbHost || !container) {
            return false
        }
        const rail = container.querySelector('.pptxv-thumbs')
        if (rail && rail.parentNode !== thumbHost) {
            thumbHost.appendChild(rail)
            return true
        }
        return Boolean(thumbHost.querySelector('.pptxv-thumbs'))
    }

    function attachThumbRail (host) {
        if (thumbHost && onThumbClick) {
            thumbHost.removeEventListener('click', onThumbClick, true)
        }
        thumbHost = host || null
        onThumbClick = handleThumbClick
        if (thumbHost && !moveThumbRail()) {
            window.setTimeout(function () {
                moveThumbRail()
                syncThumbFit()
            }, 0)
        }
        if (thumbHost) {
            thumbHost.addEventListener('click', onThumbClick, true)
            syncThumbFit()
        }
    }

    function syncThumbFit () {
        const sized = applyPptxThumbFit(thumbHost)
        if (sized || typeof window === 'undefined' || !thumbHost) {
            return
        }
        window.requestAnimationFrame(function () {
            applyPptxThumbFit(thumbHost)
        })
    }

    function resize () {
        if (viewer && typeof viewer.zoomToFit === 'function') {
            viewer.zoomToFit()
        }
        syncThumbFit()
    }

    function destroy () {
        showEnded = false
        if (stopMediaWatch) {
            stopMediaWatch()
            stopMediaWatch = null
        }
        if (viewer && typeof viewer.destroy === 'function') {
            try {
                viewer.destroy()
            } catch (e) {
                /* ignore */
            }
        }
        viewer = null
        mediaPaths = []
        if (thumbHost && onThumbClick) {
            thumbHost.removeEventListener('click', onThumbClick, true)
        }
        if (thumbHost) {
            clearPptxThumbFit(thumbHost)
            thumbHost.innerHTML = ''
        }
        thumbHost = null
        onThumbClick = null
        if (container) {
            container.innerHTML = ''
        }
    }

    return {
        load: load,
        advance: advance,
        goPrev: goPrev,
        goToSlide: goToSlide,
        attachThumbRail: attachThumbRail,
        replayIfIdle: replayIfIdle,
        resize: resize,
        destroy: destroy
    }
}
