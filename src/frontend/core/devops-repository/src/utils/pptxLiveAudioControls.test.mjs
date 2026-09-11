import test from 'node:test'
import assert from 'node:assert/strict'
import { enablePptxAudioControls, enablePptxVideoControls } from './pptxLiveAudioControls.js'

function fakeRoot (nodes) {
    return {
        querySelectorAll (selector) {
            assert.equal(selector, 'audio.pptxv-media-audio')
            return nodes
        }
    }
}

test('enablePptxAudioControls turns on native controls for unmuted audio', () => {
    const wrap = {
        classList: {
            added: [],
            add (name) {
                this.added.push(name)
            }
        }
    }
    const audio = {
        muted: false,
        controls: false,
        closest () {
            return wrap
        }
    }
    assert.equal(enablePptxAudioControls(fakeRoot([audio])), 1)
    assert.equal(audio.controls, true)
    assert.deepEqual(wrap.classList.added, ['pptx-live-has-audio'])
})

test('enablePptxAudioControls skips muted play-across-slides audio', () => {
    const audio = {
        muted: true,
        controls: false,
        closest () {
            return null
        }
    }
    assert.equal(enablePptxAudioControls(fakeRoot([audio])), 0)
    assert.equal(audio.controls, false)
})

test('enablePptxAudioControls no-ops without a root', () => {
    assert.equal(enablePptxAudioControls(null), 0)
})

test('enablePptxVideoControls turns on native controls even when muted', () => {
    const wrap = {
        classList: {
            added: [],
            add (name) {
                this.added.push(name)
            }
        }
    }
    const video = {
        muted: true,
        controls: false,
        attrs: {},
        closest () {
            return wrap
        },
        getAttribute (name) {
            return this.attrs[name] || ''
        },
        hasAttribute (name) {
            return Object.prototype.hasOwnProperty.call(this.attrs, name)
        },
        setAttribute (name, value) {
            this.attrs[name] = value
        },
        addEventListener () {}
    }
    const root = {
        querySelectorAll (selector) {
            assert.equal(selector, 'video.pptxv-media-video')
            return [video]
        }
    }
    assert.equal(enablePptxVideoControls(root), 1)
    assert.equal(video.controls, true)
    assert.equal(video.attrs.controls, '')
    assert.deepEqual(wrap.classList.added, ['pptx-live-has-video'])
})
