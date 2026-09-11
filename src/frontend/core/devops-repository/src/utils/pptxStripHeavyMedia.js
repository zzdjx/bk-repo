export const MAX_EMBEDDED_MEDIA_BYTES = 256 * 1024 * 1024

const SIG_LOCAL = 0x04034b50
const SIG_CENTRAL = 0x02014b50
const SIG_EOCD = 0x06054b50
const SIG_ZIP64_EOCD = 0x06064b50
const SIG_ZIP64_LOCATOR = 0x07064b50
const SIG_DESCRIPTOR = 0x08074b50
const ZIP64_SENTINEL_32 = 0xffffffff
const ZIP64_SENTINEL_16 = 0xffff
const ZIP64_EXTRA_ID = 0x0001
const FLAG_DATA_DESCRIPTOR = 0x0008
const FLAG_UTF8 = 0x0800
const STORED_METHOD = 0
const LOCAL_HEADER_LEN = 30
const CENTRAL_HEADER_LEN = 46
const EOCD_LEN = 22
const ZIP64_LOCATOR_LEN = 20
const MAX_ZIP_COMMENT = 65535
const HEAVY_MEDIA_EXT = /\.(mp4|m4v|mov|qt|avi|wmv|webm|mkv|mpg|mpeg|3gp|asf|flv|ts|m2ts|mp3|wav|m4a|wma|aac|ogg|opus)$/i

function toUint8Array (input) {
    if (input instanceof Uint8Array) {
        return input
    }
    if (input instanceof ArrayBuffer) {
        return new Uint8Array(input)
    }
    return null
}

function u16 (view, offset) {
    return view.getUint16(offset, true)
}

function u32 (view, offset) {
    return view.getUint32(offset, true)
}

function u64 (view, offset) {
    const lo = view.getUint32(offset, true)
    const hi = view.getUint32(offset + 4, true)
    if (hi > 0x1fffff) {
        throw new Error('zip64 size exceeds safe integer')
    }
    return hi * 0x100000000 + lo
}

function decodeName (bytes) {
    try {
        return new TextDecoder('utf-8').decode(bytes)
    } catch (e) {
        return ''
    }
}

function normalizeZipPath (name) {
    return String(name || '').replace(/\\/g, '/').replace(/^\/+/, '')
}

function isEmbeddedAv (path) {
    const normalized = normalizeZipPath(path).toLowerCase()
    return normalized.includes('/media/') && HEAVY_MEDIA_EXT.test(normalized)
}

function isHeavyMedia (path, size, maxBytes) {
    return size > maxBytes && isEmbeddedAv(path)
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

function findEocd (bytes, view) {
    const min = Math.max(0, bytes.length - EOCD_LEN - MAX_ZIP_COMMENT)
    for (let i = bytes.length - EOCD_LEN; i >= min; i--) {
        if (u32(view, i) !== SIG_EOCD) {
            continue
        }
        const commentLen = u16(view, i + 20)
        if (i + EOCD_LEN + commentLen === bytes.length) {
            return i
        }
    }
    throw new Error('eocd not found')
}

function readZip64Eocd (bytes, view, eocdOffset) {
    if (eocdOffset < ZIP64_LOCATOR_LEN) {
        throw new Error('zip64 locator missing')
    }
    const locator = eocdOffset - ZIP64_LOCATOR_LEN
    if (u32(view, locator) !== SIG_ZIP64_LOCATOR) {
        throw new Error('zip64 locator signature')
    }
    const zip64Offset = u64(view, locator + 8)
    if (zip64Offset + 56 > bytes.length) {
        throw new Error('zip64 eocd truncated')
    }
    if (u32(view, zip64Offset) !== SIG_ZIP64_EOCD) {
        throw new Error('zip64 eocd signature')
    }
    return {
        disk: u32(view, zip64Offset + 16),
        cdDisk: u32(view, zip64Offset + 20),
        totalEntries: u64(view, zip64Offset + 32),
        cdSize: u64(view, zip64Offset + 40),
        cdOffset: u64(view, zip64Offset + 48)
    }
}

function readEocd (bytes, view) {
    const eocdOffset = findEocd(bytes, view)
    const disk = u16(view, eocdOffset + 4)
    const cdDisk = u16(view, eocdOffset + 6)
    let totalEntries = u16(view, eocdOffset + 10)
    let cdSize = u32(view, eocdOffset + 12)
    let cdOffset = u32(view, eocdOffset + 16)
    if (
        totalEntries === ZIP64_SENTINEL_16
        || cdSize === ZIP64_SENTINEL_32
        || cdOffset === ZIP64_SENTINEL_32
    ) {
        const zip64 = readZip64Eocd(bytes, view, eocdOffset)
        totalEntries = zip64.totalEntries
        cdSize = zip64.cdSize
        cdOffset = zip64.cdOffset
        if (zip64.disk !== 0 || zip64.cdDisk !== 0) {
            throw new Error('multi-disk zip')
        }
    } else if (disk !== 0 || cdDisk !== 0) {
        throw new Error('multi-disk zip')
    }
    if (cdOffset + cdSize > bytes.length) {
        throw new Error('central directory truncated')
    }
    return { totalEntries, cdSize, cdOffset }
}

function applyZip64Extra (entry, extra) {
    if (!extra || extra.length < 4) {
        return
    }
    const view = new DataView(extra.buffer, extra.byteOffset, extra.byteLength)
    let i = 0
    while (i + 4 <= extra.length) {
        const id = view.getUint16(i, true)
        const size = view.getUint16(i + 2, true)
        const next = i + 4 + size
        if (next > extra.length) {
            break
        }
        if (id === ZIP64_EXTRA_ID) {
            let cursor = i + 4
            if (entry.uncompressedSize === ZIP64_SENTINEL_32 && cursor + 8 <= next) {
                entry.uncompressedSize = u64(view, cursor)
                cursor += 8
            }
            if (entry.compressedSize === ZIP64_SENTINEL_32 && cursor + 8 <= next) {
                entry.compressedSize = u64(view, cursor)
                cursor += 8
            }
            if (entry.localOffset === ZIP64_SENTINEL_32 && cursor + 8 <= next) {
                entry.localOffset = u64(view, cursor)
            }
            return
        }
        i = next
    }
}

function stripZip64Extra (extra) {
    if (!extra || extra.length === 0) {
        return extra || new Uint8Array(0)
    }
    const view = new DataView(extra.buffer, extra.byteOffset, extra.byteLength)
    const kept = []
    let i = 0
    while (i + 4 <= extra.length) {
        const id = view.getUint16(i, true)
        const size = view.getUint16(i + 2, true)
        const next = i + 4 + size
        if (next > extra.length) {
            return extra
        }
        if (id !== ZIP64_EXTRA_ID) {
            kept.push(extra.subarray(i, next))
        }
        i = next
    }
    if (!kept.length) {
        return new Uint8Array(0)
    }
    return concatBytes(kept)
}

function readCentralEntries (bytes, view, eocd) {
    const entries = []
    let cursor = eocd.cdOffset
    const end = eocd.cdOffset + eocd.cdSize
    for (let n = 0; n < eocd.totalEntries; n++) {
        if (cursor + CENTRAL_HEADER_LEN > end) {
            throw new Error('central entry truncated')
        }
        if (u32(view, cursor) !== SIG_CENTRAL) {
            throw new Error('central signature')
        }
        const nameLen = u16(view, cursor + 28)
        const extraLen = u16(view, cursor + 30)
        const commentLen = u16(view, cursor + 32)
        const recordEnd = cursor + CENTRAL_HEADER_LEN + nameLen + extraLen + commentLen
        if (recordEnd > end) {
            throw new Error('central name truncated')
        }
        const nameBytes = bytes.subarray(cursor + CENTRAL_HEADER_LEN, cursor + CENTRAL_HEADER_LEN + nameLen)
        const extra = extraLen
            ? bytes.subarray(cursor + CENTRAL_HEADER_LEN + nameLen, cursor + CENTRAL_HEADER_LEN + nameLen + extraLen)
            : new Uint8Array(0)
        const comment = commentLen
            ? bytes.subarray(recordEnd - commentLen, recordEnd)
            : new Uint8Array(0)
        const entry = {
            versionMadeBy: u16(view, cursor + 4),
            versionNeeded: u16(view, cursor + 6),
            flags: u16(view, cursor + 8),
            method: u16(view, cursor + 10),
            time: u16(view, cursor + 12),
            date: u16(view, cursor + 14),
            crc: u32(view, cursor + 16),
            compressedSize: u32(view, cursor + 20),
            uncompressedSize: u32(view, cursor + 24),
            diskStart: u16(view, cursor + 34),
            internalAttr: u16(view, cursor + 36),
            externalAttr: u32(view, cursor + 38),
            localOffset: u32(view, cursor + 42),
            zip64Sizes: false,
            nameBytes,
            extra,
            comment,
            fileName: decodeName(nameBytes)
        }
        entry.zip64Sizes = entry.compressedSize === ZIP64_SENTINEL_32 || entry.uncompressedSize === ZIP64_SENTINEL_32
        applyZip64Extra(entry, extra)
        entries.push(entry)
        cursor = recordEnd
    }
    return entries
}

function localRecordLength (bytes, view, entry) {
    const off = entry.localOffset
    if (off + LOCAL_HEADER_LEN > bytes.length) {
        throw new Error('local header truncated')
    }
    if (u32(view, off) !== SIG_LOCAL) {
        throw new Error('local signature')
    }
    const flags = u16(view, off + 6)
    const nameLen = u16(view, off + 26)
    const extraLen = u16(view, off + 28)
    const headerLen = LOCAL_HEADER_LEN + nameLen + extraLen
    if (off + headerLen + entry.compressedSize > bytes.length) {
        throw new Error('local data truncated')
    }
    let descLen = 0
    if (flags & FLAG_DATA_DESCRIPTOR) {
        const descOff = off + headerLen + entry.compressedSize
        const hasSig = descOff + 4 <= bytes.length && u32(view, descOff) === SIG_DESCRIPTOR
        if (entry.zip64Sizes) {
            descLen = hasSig ? 24 : 20
        } else {
            descLen = hasSig ? 16 : 12
        }
        if (descOff + descLen > bytes.length) {
            throw new Error('data descriptor truncated')
        }
    }
    return headerLen + entry.compressedSize + descLen
}

function utf8Flag (nameBytes) {
    for (let i = 0; i < nameBytes.length; i++) {
        if (nameBytes[i] > 127) {
            return FLAG_UTF8
        }
    }
    return 0
}

function encodeEmptyLocal (nameBytes) {
    const buf = new Uint8Array(LOCAL_HEADER_LEN + nameBytes.length)
    const view = new DataView(buf.buffer)
    view.setUint32(0, SIG_LOCAL, true)
    view.setUint16(4, 20, true)
    view.setUint16(6, utf8Flag(nameBytes), true)
    view.setUint16(8, STORED_METHOD, true)
    view.setUint16(26, nameBytes.length, true)
    buf.set(nameBytes, LOCAL_HEADER_LEN)
    return buf
}

function encodeCentral (entry, localOffset, compressedSize, uncompressedSize, crc, method, extra) {
    const extraBytes = extra || new Uint8Array(0)
    const buf = new Uint8Array(CENTRAL_HEADER_LEN + entry.nameBytes.length + extraBytes.length + entry.comment.length)
    const view = new DataView(buf.buffer)
    view.setUint32(0, SIG_CENTRAL, true)
    view.setUint16(4, entry.versionMadeBy, true)
    view.setUint16(6, entry.versionNeeded, true)
    view.setUint16(8, entry.flags, true)
    view.setUint16(10, method, true)
    view.setUint16(12, entry.time, true)
    view.setUint16(14, entry.date, true)
    view.setUint32(16, crc, true)
    view.setUint32(20, compressedSize, true)
    view.setUint32(24, uncompressedSize, true)
    view.setUint16(28, entry.nameBytes.length, true)
    view.setUint16(30, extraBytes.length, true)
    view.setUint16(32, entry.comment.length, true)
    view.setUint16(34, entry.diskStart, true)
    view.setUint16(36, entry.internalAttr, true)
    view.setUint32(38, entry.externalAttr, true)
    view.setUint32(42, localOffset, true)
    let cursor = CENTRAL_HEADER_LEN
    buf.set(entry.nameBytes, cursor)
    cursor += entry.nameBytes.length
    if (extraBytes.length) {
        buf.set(extraBytes, cursor)
        cursor += extraBytes.length
    }
    if (entry.comment.length) {
        buf.set(entry.comment, cursor)
    }
    return buf
}

function encodeEocd (entryCount, cdSize, cdOffset) {
    const buf = new Uint8Array(EOCD_LEN)
    const view = new DataView(buf.buffer)
    view.setUint32(0, SIG_EOCD, true)
    view.setUint16(8, entryCount, true)
    view.setUint16(10, entryCount, true)
    view.setUint32(12, cdSize, true)
    view.setUint32(16, cdOffset, true)
    return buf
}

function rebuildZip (bytes, view, entries, maxBytes) {
    const locals = []
    const centrals = []
    const stripped = []
    let offset = 0
    for (let i = 0; i < entries.length; i++) {
        const entry = entries[i]
        const size = Math.max(entry.uncompressedSize, entry.compressedSize)
        if (isHeavyMedia(entry.fileName, size, maxBytes)) {
            const local = encodeEmptyLocal(entry.nameBytes)
            const flags = utf8Flag(entry.nameBytes)
            const emptyEntry = {
                versionMadeBy: entry.versionMadeBy,
                versionNeeded: 20,
                flags,
                time: entry.time,
                date: entry.date,
                diskStart: 0,
                internalAttr: entry.internalAttr,
                externalAttr: entry.externalAttr,
                nameBytes: entry.nameBytes,
                comment: new Uint8Array(0)
            }
            centrals.push(encodeCentral(emptyEntry, offset, 0, 0, 0, STORED_METHOD, new Uint8Array(0)))
            locals.push(local)
            offset += local.length
            stripped.push(normalizeZipPath(entry.fileName))
            continue
        }
        if (entry.compressedSize > ZIP64_SENTINEL_32 || entry.uncompressedSize > ZIP64_SENTINEL_32) {
            throw new Error('remaining zip64 entry')
        }
        const recordLen = localRecordLength(bytes, view, entry)
        if (offset + recordLen > ZIP64_SENTINEL_32) {
            throw new Error('rewritten zip exceeds 4GB')
        }
        locals.push(bytes.subarray(entry.localOffset, entry.localOffset + recordLen))
        centrals.push(encodeCentral(
            entry,
            offset,
            entry.compressedSize,
            entry.uncompressedSize,
            entry.crc,
            entry.method,
            stripZip64Extra(entry.extra)
        ))
        offset += recordLen
    }
    if (!stripped.length) {
        return { bytes, stripped }
    }
    const cdOffset = offset
    let cdSize = 0
    for (let i = 0; i < centrals.length; i++) {
        cdSize += centrals[i].length
    }
    if (cdOffset + cdSize > ZIP64_SENTINEL_32 || centrals.length > ZIP64_SENTINEL_16) {
        throw new Error('rewritten eocd exceeds 32-bit zip')
    }
    return {
        bytes: concatBytes(locals.concat(centrals, [encodeEocd(centrals.length, cdSize, cdOffset)])),
        stripped
    }
}

export function listPptxMediaPaths (input) {
    const bytes = toUint8Array(input)
    if (!bytes || bytes.length < EOCD_LEN) {
        return []
    }
    try {
        const view = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength)
        if (u32(view, 0) !== SIG_LOCAL && u32(view, 0) !== SIG_EOCD) {
            return []
        }
        const eocd = readEocd(bytes, view)
        const entries = readCentralEntries(bytes, view, eocd)
        const paths = []
        for (let i = 0; i < entries.length; i++) {
            if (isEmbeddedAv(entries[i].fileName)) {
                paths.push(normalizeZipPath(entries[i].fileName))
            }
        }
        return paths
    } catch (e) {
        return []
    }
}

export function stripHeavyPptxMedia (input, maxBytes) {
    const bytes = toUint8Array(input)
    const limit = typeof maxBytes === 'number' && maxBytes >= 0 ? maxBytes : MAX_EMBEDDED_MEDIA_BYTES
    if (!bytes || bytes.length < EOCD_LEN) {
        return { bytes: bytes || input, stripped: [] }
    }
    try {
        const view = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength)
        if (u32(view, 0) !== SIG_LOCAL && u32(view, 0) !== SIG_EOCD) {
            return { bytes, stripped: [] }
        }
        const eocd = readEocd(bytes, view)
        const entries = readCentralEntries(bytes, view, eocd)
        return rebuildZip(bytes, view, entries, limit)
    } catch (e) {
        return { bytes, stripped: [] }
    }
}
