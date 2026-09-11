import test from 'node:test'
import assert from 'node:assert/strict'
import { bindPptxMediaStreams, buildPptxPartUrl } from './pptxLiveMediaStream.js'

test('buildPptxPartUrl keeps token and sets part', () => {
    const url = buildPptxPartUrl(
        'https://repo.example/web/preview/api/file/onlinePreview/p/r/deck.pptx?token=abc',
        'ppt/media/media1.mp4'
    )
    const parsed = new URL(url)
    assert.equal(parsed.searchParams.get('token'), 'abc')
    assert.equal(parsed.searchParams.get('part'), 'ppt/media/media1.mp4')
})

test('bindPptxMediaStreams maps data-element-id to part url', () => {
    const wrap = {
        getAttribute (name) {
            return name === 'data-element-id' ? 'vid-1' : ''
        }
    }
    const video = {
        tagName: 'VIDEO',
        src: 'blob:http://localhost/empty',
        currentSrc: 'blob:http://localhost/empty',
        attrs: {},
        getAttribute (name) {
            return this.attrs[name] || ''
        },
        setAttribute (name, value) {
            this.attrs[name] = value
        },
        closest () {
            return wrap
        },
        load () {
            this.loaded = true
        }
    }
    const root = {
        querySelectorAll (selector) {
            assert.equal(selector, 'video.pptxv-media-video, audio.pptxv-media-audio')
            return [video]
        }
    }
    const count = bindPptxMediaStreams(root, {
        mediaBaseUrl: 'https://repo.example/preview.pptx?token=t',
        viewer: {
            getSlides () {
                return [{ elements: [{ id: 'vid-1', mediaPath: '../media/media1.mp4' }] }]
            }
        }
    })
    assert.equal(count, 1)
    assert.equal(new URL(video.src).searchParams.get('part'), 'ppt/media/media1.mp4')
    assert.equal(video.loaded, true)
})

test('bindPptxMediaStreams falls back to a single catalog video', () => {
    const video = {
        tagName: 'VIDEO',
        src: '',
        attrs: {},
        getAttribute (name) {
            return this.attrs[name] || ''
        },
        setAttribute (name, value) {
            this.attrs[name] = value
        },
        closest () {
            return null
        },
        load () {}
    }
    const root = {
        querySelectorAll () {
            return [video]
        }
    }
    const count = bindPptxMediaStreams(root, {
        mediaBaseUrl: 'https://repo.example/preview.pptx',
        mediaPaths: ['ppt/media/clip.mp4']
    })
    assert.equal(count, 1)
    assert.equal(new URL(video.src).searchParams.get('part'), 'ppt/media/clip.mp4')
})

test('bindPptxMediaStreams no-ops without mediaBaseUrl', () => {
    assert.equal(bindPptxMediaStreams({ querySelectorAll () { return [] } }, {}), 0)
})
