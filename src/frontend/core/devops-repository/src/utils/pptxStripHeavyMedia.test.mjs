import test from 'node:test'
import assert from 'node:assert/strict'
import { listPptxMediaPaths, stripHeavyPptxMedia } from './pptxStripHeavyMedia.js'

const SIG_LOCAL = 0x04034b50
const SIG_CENTRAL = 0x02014b50
const SIG_EOCD = 0x06054b50
const STORED = 0

const CRC_TABLE = (function buildCrcTable () {
    const table = new Uint32Array(256)
    for (let n = 0; n < 256; n++) {
        let crc = n
        for (let k = 0; k < 8; k++) {
            crc = (crc & 1) ? (0xedb88320 ^ (crc >>> 1)) : (crc >>> 1)
        }
        table[n] = crc >>> 0
    }
    return table
})()

function crc32 (bytes) {
    let crc = 0xffffffff
    for (let i = 0; i < bytes.length; i++) {
        crc = CRC_TABLE[(crc ^ bytes[i]) & 0xff] ^ (crc >>> 8)
    }
    return (crc ^ 0xffffffff) >>> 0
}

function encodeName (name) {
    return new TextEncoder().encode(name)
}

function concatBytes (chunks) {
    let total = 0
    for (let i = 0; i < chunks.length; i++) {
        total += chunks[i].length
    }
    const out = new Uint8Array(total)
    let offset = 0
    for (let i = 0; i < chunks.length; i++) {
        out.set(chunks[i], offset)
        offset += chunks[i].length
    }
    return out
}

function buildStoredZip (files) {
    const locals = []
    const centrals = []
    let offset = 0
    for (let i = 0; i < files.length; i++) {
        const nameBytes = encodeName(files[i].name)
        const data = files[i].data
        const crc = crc32(data)
        const local = new Uint8Array(30 + nameBytes.length + data.length)
        const localView = new DataView(local.buffer)
        localView.setUint32(0, SIG_LOCAL, true)
        localView.setUint16(4, 20, true)
        localView.setUint16(8, STORED, true)
        localView.setUint32(14, crc, true)
        localView.setUint32(18, data.length, true)
        localView.setUint32(22, data.length, true)
        localView.setUint16(26, nameBytes.length, true)
        local.set(nameBytes, 30)
        local.set(data, 30 + nameBytes.length)
        const central = new Uint8Array(46 + nameBytes.length)
        const centralView = new DataView(central.buffer)
        centralView.setUint32(0, SIG_CENTRAL, true)
        centralView.setUint16(4, 20, true)
        centralView.setUint16(6, 20, true)
        centralView.setUint16(10, STORED, true)
        centralView.setUint32(16, crc, true)
        centralView.setUint32(20, data.length, true)
        centralView.setUint32(24, data.length, true)
        centralView.setUint16(28, nameBytes.length, true)
        centralView.setUint32(42, offset, true)
        central.set(nameBytes, 46)
        locals.push(local)
        centrals.push(central)
        offset += local.length
    }
    const cdOffset = offset
    const cd = concatBytes(centrals)
    const eocd = new Uint8Array(22)
    const eocdView = new DataView(eocd.buffer)
    eocdView.setUint32(0, SIG_EOCD, true)
    eocdView.setUint16(8, files.length, true)
    eocdView.setUint16(10, files.length, true)
    eocdView.setUint32(12, cd.length, true)
    eocdView.setUint32(16, cdOffset, true)
    return concatBytes(locals.concat([cd, eocd]))
}

function readStoredZip (bytes) {
    const view = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength)
    let eocd = -1
    for (let i = bytes.length - 22; i >= 0; i--) {
        if (view.getUint32(i, true) === SIG_EOCD) {
            eocd = i
            break
        }
    }
    assert.ok(eocd >= 0, 'eocd')
    const count = view.getUint16(eocd + 10, true)
    let cursor = view.getUint32(eocd + 16, true)
    const files = {}
    for (let n = 0; n < count; n++) {
        assert.equal(view.getUint32(cursor, true), SIG_CENTRAL)
        const method = view.getUint16(cursor + 10, true)
        const size = view.getUint32(cursor + 24, true)
        const nameLen = view.getUint16(cursor + 28, true)
        const extraLen = view.getUint16(cursor + 30, true)
        const commentLen = view.getUint16(cursor + 32, true)
        const localOffset = view.getUint32(cursor + 42, true)
        const name = new TextDecoder().decode(bytes.subarray(cursor + 46, cursor + 46 + nameLen))
        const localNameLen = view.getUint16(localOffset + 26, true)
        const localExtraLen = view.getUint16(localOffset + 28, true)
        const dataStart = localOffset + 30 + localNameLen + localExtraLen
        assert.equal(method, STORED)
        files[name] = bytes.subarray(dataStart, dataStart + size)
        cursor += 46 + nameLen + extraLen + commentLen
    }
    return files
}

function containsBytes (haystack, needle) {
    const text = new TextDecoder().decode(haystack)
    return text.indexOf(needle) >= 0
}

test('stripHeavyPptxMedia leaves non-zip bytes unchanged', () => {
    const ole = new Uint8Array([0xd0, 0xcf, 0x11, 0xe0, 0xa1, 0xb1, 0x1a, 0xe1, 1, 2, 3])
    const result = stripHeavyPptxMedia(ole, 10)
    assert.equal(result.bytes, ole)
    assert.deepEqual(result.stripped, [])
})

test('stripHeavyPptxMedia keeps small embedded video', () => {
    const zip = buildStoredZip([
        { name: '[Content_Types].xml', data: encodeName('<Types/>') },
        { name: 'ppt/media/clip.mp4', data: encodeName('tiny-video') }
    ])
    const result = stripHeavyPptxMedia(zip, 100)
    assert.equal(result.bytes, zip)
    assert.deepEqual(result.stripped, [])
})

test('stripHeavyPptxMedia replaces oversized video and keeps slides', () => {
    const slide = encodeName('slide-body-unique')
    const video = encodeName('X'.repeat(64) + 'VIDEO-PAYLOAD-UNIQUE')
    const image = encodeName('png-bytes-unique')
    const zip = buildStoredZip([
        { name: 'ppt/slides/slide1.xml', data: slide },
        { name: 'ppt/media/video1.mp4', data: video },
        { name: 'ppt/media/image1.png', data: image }
    ])
    const result = stripHeavyPptxMedia(zip, 20)
    assert.deepEqual(result.stripped, ['ppt/media/video1.mp4'])
    assert.ok(result.bytes.length < zip.length)
    assert.equal(containsBytes(result.bytes, 'VIDEO-PAYLOAD-UNIQUE'), false)
    const files = readStoredZip(result.bytes)
    assert.equal(new TextDecoder().decode(files['ppt/slides/slide1.xml']), 'slide-body-unique')
    assert.equal(new TextDecoder().decode(files['ppt/media/image1.png']), 'png-bytes-unique')
    assert.equal(files['ppt/media/video1.mp4'].length, 0)
})

test('stripHeavyPptxMedia keeps oversized images in ppt/media', () => {
    const image = new Uint8Array(80)
    image.fill(7)
    const zip = buildStoredZip([
        { name: 'ppt/slides/slide1.xml', data: encodeName('slide') },
        { name: 'ppt/media/image1.png', data: image }
    ])
    const result = stripHeavyPptxMedia(zip, 20)
    assert.equal(result.bytes, zip)
    assert.deepEqual(result.stripped, [])
})

test('stripHeavyPptxMedia is a no-op after videos are already empty', () => {
    const zip = buildStoredZip([
        { name: 'ppt/slides/slide1.xml', data: encodeName('slide') },
        { name: 'ppt/media/huge.mp4', data: new Uint8Array(80) }
    ])
    const first = stripHeavyPptxMedia(zip, 10)
    const second = stripHeavyPptxMedia(first.bytes, 10)
    assert.equal(second.bytes, first.bytes)
    assert.deepEqual(second.stripped, [])
})

test('listPptxMediaPaths returns embedded av even after stripping', () => {
    const zip = buildStoredZip([
        { name: 'ppt/slides/slide1.xml', data: encodeName('slide') },
        { name: 'ppt/media/video1.mp4', data: new Uint8Array(80) },
        { name: 'ppt/media/image1.png', data: new Uint8Array(80) }
    ])
    assert.deepEqual(listPptxMediaPaths(zip), ['ppt/media/video1.mp4'])
    const slim = stripHeavyPptxMedia(zip, 0)
    assert.deepEqual(slim.stripped, ['ppt/media/video1.mp4'])
    assert.equal(readStoredZip(slim.bytes)['ppt/media/video1.mp4'].length, 0)
    assert.deepEqual(listPptxMediaPaths(slim.bytes), ['ppt/media/video1.mp4'])
})
