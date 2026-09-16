export const GLB_HEADER_BYTES = 12
export const GLB_CHUNK_HEADER_BYTES = 8
export const MAX_JSON_CHUNK_BYTES = 8 * 1024 * 1024

const GLB_MAGIC = 0x46546C67
const GLB_VERSION = 2
const GLB_JSON_CHUNK_TYPE = 0x4E4F534A
const GLB_BIN_CHUNK_TYPE = 0x004E4942
const JSON_ALIGNMENT_BYTES = 4
const URI_FIELD = 'uri'
const DATA_URI_PREFIX = 'data:'

function toUint8Array (input) {
    if (input instanceof Uint8Array) {
        return input
    }
    if (input instanceof ArrayBuffer) {
        return new Uint8Array(input)
    }
    return new Uint8Array(0)
}

function isolateBytes (input) {
    const u8 = toUint8Array(input)
    if (u8.byteOffset === 0 && u8.byteLength === u8.buffer.byteLength) {
        return u8
    }
    const copy = new Uint8Array(u8.byteLength)
    copy.set(u8)
    return copy
}

function blocked (code) {
    const err = new Error(code)
    err.code = code
    return err
}

export function isAllowedGlbUri (uri) {
    const trimmed = String(uri || '').trim()
    return !trimmed || trimmed.toLowerCase().startsWith(DATA_URI_PREFIX)
}

export function decodeJsonChunk (bytes) {
    const u8 = toUint8Array(bytes)
    let end = u8.byteLength
    while (end > 0) {
        const pad = u8[end - 1]
        if (pad !== 0x20 && pad !== 0x00) {
            break
        }
        end--
    }
    return new TextDecoder('utf-8').decode(u8.subarray(0, end))
}

function collectUris (root, out) {
    const stack = [root]
    while (stack.length) {
        const node = stack.pop()
        if (Array.isArray(node)) {
            for (let i = 0; i < node.length; i++) {
                stack.push(node[i])
            }
            continue
        }
        if (!node || typeof node !== 'object') {
            continue
        }
        const keys = Object.keys(node)
        for (let i = 0; i < keys.length; i++) {
            const key = keys[i]
            const value = node[key]
            if (key.toLowerCase() === URI_FIELD) {
                if (value == null) {
                    continue
                }
                if (typeof value !== 'string') {
                    throw blocked('invalid')
                }
                out.push(value)
                continue
            }
            if (value && typeof value === 'object') {
                stack.push(value)
            }
        }
    }
}

export function inspectGlbJson (json) {
    let root
    try {
        root = JSON.parse(json)
    } catch (e) {
        return 'invalid'
    }
    if (!root || typeof root !== 'object' || Array.isArray(root)) {
        return 'invalid'
    }
    const uris = []
    try {
        collectUris(root, uris)
    } catch (e) {
        return 'invalid'
    }
    for (let i = 0; i < uris.length; i++) {
        if (!isAllowedGlbUri(uris[i])) {
            return 'external'
        }
    }
    return ''
}

/**
 * 按真实字节校验整份 GLB：声明长度必须等于文件大小，chunk 必须正好铺满文件。
 * 只允许 JSON + 可选 BIN。拒绝额外 JSON/未知 chunk，避免 loader 覆盖或跳过我们没检过的数据。
 * 返回与即将交给 model-viewer 的同一份字节。
 */
export function inspectSelfContainedGlb (input) {
    const bytes = isolateBytes(input)
    if (bytes.byteLength < GLB_HEADER_BYTES + GLB_CHUNK_HEADER_BYTES) {
        throw blocked('invalid')
    }
    const view = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength)
    if (view.getUint32(0, true) !== GLB_MAGIC || view.getUint32(4, true) !== GLB_VERSION) {
        throw blocked('invalid')
    }
    const declaredLength = view.getUint32(8, true)
    if (declaredLength !== bytes.byteLength) {
        throw blocked('invalid')
    }
    let offset = GLB_HEADER_BYTES
    let chunkIndex = 0
    let jsonBytes = null
    while (offset < bytes.byteLength) {
        if (offset + GLB_CHUNK_HEADER_BYTES > bytes.byteLength) {
            throw blocked('invalid')
        }
        const chunkLength = view.getUint32(offset, true)
        const chunkType = view.getUint32(offset + 4, true)
        offset += GLB_CHUNK_HEADER_BYTES
        if (
            chunkLength > bytes.byteLength - offset ||
            chunkLength % JSON_ALIGNMENT_BYTES !== 0
        ) {
            throw blocked('invalid')
        }
        if (chunkIndex === 0) {
            if (
                chunkType !== GLB_JSON_CHUNK_TYPE ||
                chunkLength <= 0 ||
                chunkLength > MAX_JSON_CHUNK_BYTES
            ) {
                throw blocked('invalid')
            }
            jsonBytes = bytes.subarray(offset, offset + chunkLength)
        } else if (chunkIndex === 1) {
            if (chunkType !== GLB_BIN_CHUNK_TYPE) {
                throw blocked('invalid')
            }
        } else {
            throw blocked('invalid')
        }
        offset += chunkLength
        chunkIndex += 1
    }
    if (offset !== bytes.byteLength || jsonBytes == null) {
        throw blocked('invalid')
    }
    const violation = inspectGlbJson(decodeJsonChunk(jsonBytes))
    if (violation) {
        throw blocked(violation)
    }
    return bytes
}

function isPartialContent (res) {
    if (!res || res.status !== 206) {
        return false
    }
    const contentRange = res.headers && res.headers.get
        ? (res.headers.get('Content-Range') || '')
        : ''
    return /\/(\d+)\s*$/.test(contentRange)
}

/**
 * 拉取完整 GLB，校验后再把同一份字节交给调用方做成 Blob。
 * 不要用原 URL 给 model-viewer 二次下载。
 */
export async function loadSelfContainedGlbBytes (url, options = {}) {
    const fetchFn = options.fetch || fetch
    const res = await fetchFn(url, {
        method: 'GET',
        credentials: 'include',
        signal: options.signal
    })
    if (!res.ok || isPartialContent(res)) {
        throw blocked('invalid')
    }
    return inspectSelfContainedGlb(await res.arrayBuffer())
}
