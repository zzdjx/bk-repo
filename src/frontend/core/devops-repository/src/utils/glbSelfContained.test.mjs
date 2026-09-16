import test from 'node:test'
import assert from 'node:assert/strict'
import {
    GLB_HEADER_BYTES,
    GLB_CHUNK_HEADER_BYTES,
    inspectGlbJson,
    inspectSelfContainedGlb,
    loadSelfContainedGlbBytes,
    decodeJsonChunk
} from './glbSelfContained.js'

const GLB_MAGIC = 0x46546C67
const GLB_JSON_CHUNK_TYPE = 0x4E4F534A
const GLB_BIN_CHUNK_TYPE = 0x004E4942

function padJson (json) {
    const jsonBytes = new TextEncoder().encode(json)
    const pad = (4 - (jsonBytes.length % 4)) % 4
    const padded = new Uint8Array(jsonBytes.length + pad)
    padded.set(jsonBytes)
    padded.fill(0x20, jsonBytes.length)
    return padded
}

function glbWithChunks (chunks) {
    let payload = 0
    for (let i = 0; i < chunks.length; i++) {
        payload += GLB_CHUNK_HEADER_BYTES + chunks[i].payload.length
    }
    const total = GLB_HEADER_BYTES + payload
    const out = new Uint8Array(total)
    const view = new DataView(out.buffer)
    view.setUint32(0, GLB_MAGIC, true)
    view.setUint32(4, 2, true)
    view.setUint32(8, total, true)
    let offset = GLB_HEADER_BYTES
    for (let i = 0; i < chunks.length; i++) {
        const chunk = chunks[i]
        view.setUint32(offset, chunk.payload.length, true)
        view.setUint32(offset + 4, chunk.type, true)
        out.set(chunk.payload, offset + GLB_CHUNK_HEADER_BYTES)
        offset += GLB_CHUNK_HEADER_BYTES + chunk.payload.length
    }
    return out
}

function jsonOnlyGlb (json) {
    return glbWithChunks([{ type: GLB_JSON_CHUNK_TYPE, payload: padJson(json) }])
}

function mockFullFetch (fileBytes) {
    return async (url, options = {}) => {
        assert.equal(options.headers && options.headers.Range, undefined)
        const copy = fileBytes.buffer.slice(fileBytes.byteOffset, fileBytes.byteOffset + fileBytes.byteLength)
        return {
            ok: true,
            status: 200,
            headers: {
                get (name) {
                    if (String(name).toLowerCase() === 'content-length') {
                        return String(fileBytes.length)
                    }
                    return null
                }
            },
            arrayBuffer: async () => copy
        }
    }
}

function assertBlocked (fn, code) {
    assert.throws(fn, err => err.code === code)
}

test('decodeJsonChunk strips GLB space padding', () => {
    assert.equal(decodeJsonChunk(new TextEncoder().encode('{"a":1}   ')), '{"a":1}')
})

test('inspectGlbJson allows missing uri, empty uri and data uri', () => {
    assert.equal(inspectGlbJson('{"asset":{"version":"2.0"},"buffers":[{"byteLength":12}]}'), '')
    assert.equal(inspectGlbJson('{"images":[{"uri":"data:image/png;base64,QQ=="}]}'), '')
    assert.equal(inspectGlbJson('{"images":[{"uri":"  DATA:application/octet-stream,x"}]}'), '')
    assert.equal(inspectGlbJson('{"buffers":[{"uri":"   "}]}'), '')
})

test('inspectGlbJson rejects external and relative uris', () => {
    const blocked = [
        'https://evil.example/a.bin',
        'http://127.0.0.1/x',
        '//evil.example/a.bin',
        'file:///etc/passwd',
        'blob:https://x/1',
        'textures/x.png',
        '../secret.bin',
        '/generic/p1/r/a.bin'
    ]
    blocked.forEach(uri => {
        assert.equal(inspectGlbJson(`{"buffers":[{"uri":"${uri}"}]}`), 'external', uri)
    })
    assert.equal(inspectGlbJson('{"extras":{"images":[{"URI":"http://127.0.0.1/x"}]}}'), 'external')
    assert.equal(inspectGlbJson('{"images":[{"uri":{"path":"x.bin"}}]}'), 'invalid')
    assert.equal(inspectGlbJson('{'), 'invalid')
    assert.equal(inspectGlbJson('[]'), 'invalid')
})

test('inspectSelfContainedGlb accepts json-only and json+bin self-contained files', () => {
    const jsonOnly = jsonOnlyGlb('{"asset":{"version":"2.0"},"buffers":[{"byteLength":12}]}')
    const withBin = glbWithChunks([
        { type: GLB_JSON_CHUNK_TYPE, payload: padJson('{"asset":{"version":"2.0"}}') },
        { type: GLB_BIN_CHUNK_TYPE, payload: new Uint8Array([1, 2, 3, 4, 5, 6, 7, 8]) }
    ])
    assert.equal(inspectSelfContainedGlb(jsonOnly).byteLength, jsonOnly.byteLength)
    assert.equal(inspectSelfContainedGlb(withBin).byteLength, withBin.byteLength)
})

test('inspectSelfContainedGlb rejects header spoofing and extra unvalidated bytes', () => {
    assertBlocked(() => inspectSelfContainedGlb(new Uint8Array(64)), 'invalid')

    const trailing = jsonOnlyGlb('{"asset":{"version":"2.0"}}')
    const padded = new Uint8Array(trailing.length + 16)
    padded.set(trailing)
    assertBlocked(() => inspectSelfContainedGlb(padded), 'invalid')

    const declaredLie = jsonOnlyGlb('{"asset":{"version":"2.0"}}')
    const lieView = new DataView(declaredLie.buffer)
    lieView.setUint32(8, declaredLie.length + 32, true)
    assertBlocked(() => inspectSelfContainedGlb(declaredLie), 'invalid')
})

test('inspectSelfContainedGlb rejects a clean first JSON hiding a second JSON with uri', () => {
    const attack = glbWithChunks([
        { type: GLB_JSON_CHUNK_TYPE, payload: padJson('{}') },
        {
            type: GLB_JSON_CHUNK_TYPE,
            payload: padJson('{"buffers":[{"uri":"http://127.0.0.1/x"}]}')
        }
    ])
    assertBlocked(() => inspectSelfContainedGlb(attack), 'invalid')
})

test('inspectSelfContainedGlb rejects shrinking the first JSON chunk to skip uris', () => {
    const real = jsonOnlyGlb('{"buffers":[{"uri":"http://127.0.0.1/x"}]}')
    const forged = new Uint8Array(real)
    const view = new DataView(forged.buffer)
    view.setUint32(12, 4, true)
    forged.set(padJson('{}'), 20)
    assertBlocked(() => inspectSelfContainedGlb(forged), 'invalid')
})

test('inspectSelfContainedGlb rejects external uri in the real json chunk', () => {
    assertBlocked(
        () => inspectSelfContainedGlb(jsonOnlyGlb('{"buffers":[{"uri":"https://evil.example/x.bin"}]}')),
        'external'
    )
})

test('loadSelfContainedGlbBytes downloads the whole file and returns the inspected bytes', async () => {
    const glb = jsonOnlyGlb('{"asset":{"version":"2.0"},"buffers":[{"byteLength":12}]}')
    const bytes = await loadSelfContainedGlbBytes('https://repo.example/hero.glb', {
        fetch: mockFullFetch(glb)
    })
    assert.equal(bytes.byteLength, glb.byteLength)
    assert.deepEqual(Array.from(bytes), Array.from(glb))
})

test('loadSelfContainedGlbBytes rejects external uri before a blob is created', async () => {
    const glb = jsonOnlyGlb('{"buffers":[{"uri":"https://evil.example/x.bin"}]}')
    await assert.rejects(
        () => loadSelfContainedGlbBytes('https://repo.example/hero.glb', { fetch: mockFullFetch(glb) }),
        err => err.code === 'external'
    )
})
