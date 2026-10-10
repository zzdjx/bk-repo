import test from 'node:test'
import assert from 'node:assert/strict'
import {
    applyPptxThumbFit,
    clearPptxThumbFit,
    computePptxThumbScale,
    readUnscaledStageSize
} from './pptxLiveThumbSize.js'

function fakeStage (width, height) {
    const props = {}
    return {
        offsetWidth: width,
        offsetHeight: height,
        style: {
            transform: '',
            zoom: '',
            transformOrigin: '',
            width: '',
            height: '',
            setProperty (name, value) {
                props[name] = value
                if (name === 'transform') {
                    this.transform = value
                }
            },
            removeProperty (name) {
                delete props[name]
                if (name === 'transform') {
                    this.transform = ''
                }
                if (name === 'zoom') {
                    this.zoom = ''
                }
                if (name === 'width') {
                    this.width = ''
                }
                if (name === 'height') {
                    this.height = ''
                }
            },
            props
        }
    }
}

function fakeHost (stages, frameSize) {
    const props = {}
    const classes = new Set()
    const frame = {
        clientWidth: frameSize.width,
        clientHeight: frameSize.height
    }
    stages.forEach((stage) => {
        stage.parentElement = frame
    })
    return {
        props,
        classes,
        stages,
        style: {
            setProperty (name, value) {
                props[name] = value
            },
            removeProperty (name) {
                delete props[name]
            }
        },
        classList: {
            add (name) {
                classes.add(name)
            },
            remove (name) {
                classes.delete(name)
            }
        },
        querySelectorAll (selector) {
            assert.equal(selector, '.pptxv-thumb-frame > .pptxv-stage')
            return stages
        }
    }
}

test('computePptxThumbScale fills a 16:9 frame without extra inset', () => {
    const scale = computePptxThumbScale(
        { width: 1280, height: 720 },
        { width: 148, height: 89 }
    )
    assert.equal(scale, Math.min(148 / 1280, 89 / 720))
    assert.equal(1280 * scale <= 148, true)
    assert.equal(720 * scale <= 89, true)
})

test('computePptxThumbScale uses real canvas size so 1920 slides are not cropped', () => {
    const frame = { width: 105, height: 59 }
    const assumed1280 = Math.min(105 / 1280, 59 / 720)
    const actual = computePptxThumbScale({ width: 1920, height: 1080 }, frame)
    assert.equal(actual, Math.min(105 / 1920, 59 / 1080))
    assert.equal(1920 * actual <= 105, true)
    assert.equal(1080 * actual <= 59, true)
    assert.equal(1920 * assumed1280 > 105, true)
})

test('computePptxThumbScale rejects missing or non-positive boxes', () => {
    assert.equal(computePptxThumbScale(null, { width: 100, height: 50 }), null)
    assert.equal(computePptxThumbScale({ width: 1280, height: 720 }, { width: 0, height: 40 }), null)
    assert.equal(computePptxThumbScale({ width: 0, height: 720 }, { width: 100, height: 50 }), null)
})

test('readUnscaledStageSize reads the first thumbnail stage after clearing zoom', () => {
    const stage = fakeStage(1920, 1080)
    const host = fakeHost([stage], { width: 128, height: 72 })
    assert.deepEqual(readUnscaledStageSize(host), { width: 1920, height: 1080 })
    assert.equal(readUnscaledStageSize(null), null)
})

test('applyPptxThumbFit zooms every thumb stage to the same content box', () => {
    const first = fakeStage(1920, 1080)
    const second = fakeStage(1920, 1080)
    const host = fakeHost([first, second], { width: 128, height: 72 })
    assert.equal(applyPptxThumbFit(host), true)
    assert.equal(host.props['--pptx-slide-w'], '1920px')
    assert.equal(host.props['--pptx-slide-h'], '1080px')
    assert.equal(host.props['--pptx-slide-aspect'], String(1920 / 1080))
    assert.equal(host.props['--pptx-thumb-scale'], String(128 / 1920))
    assert.equal(first.style.zoom, String(128 / 1920))
    assert.equal(second.style.zoom, String(128 / 1920))
    assert.equal(first.style.width, '1920px')
    assert.equal(first.style.props.transform, 'none')
    assert.equal(host.classes.has('is-sized'), true)
})

test('clearPptxThumbFit removes scale vars and inline zoom', () => {
    const stage = fakeStage(1280, 720)
    const host = fakeHost([stage], { width: 128, height: 72 })
    applyPptxThumbFit(host)
    clearPptxThumbFit(host)
    assert.equal(host.props['--pptx-thumb-scale'], undefined)
    assert.equal(stage.style.zoom, '')
    assert.equal(host.classes.has('is-sized'), false)
})
