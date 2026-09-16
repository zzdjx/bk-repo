import JSZip from 'jszip'
import Viewer from 'viewerjs'

const DATE_TOKEN_RE = /(?:y{1,4}|d{1,4}|年|月|日)/i
const DISPIMG_RE = /DISPIMG\s*\(\s*["']([^"']+)["']/i
const CELL_IMAGES_ENTRY = 'xl/cellimages.xml'
// OOXML 关系命名空间，用于读取 r:embed 属性，不会发起网络请求。
const OFFICE_RELATIONSHIP_NAMESPACE = 'http://schemas.openxmlformats.org/officeDocument/2006/relationships'

// 中文 Excel/WPS 的内置格式 31 只有 ID，没有 formatCode，需要在增强层补充。
const LOCALIZED_DATE_FORMAT_FALLBACKS = new Map([[31, 'yyyy"年"m"月"d"日"']])

function normalizeBuffer (source) {
    if (source instanceof ArrayBuffer) return source
    if (ArrayBuffer.isView(source)) {
        return source.buffer.slice(source.byteOffset, source.byteOffset + source.byteLength)
    }
    return source
}

function normalizeZipTarget (target) {
    const normalized = String(target || '').replace(/\\/g, '/').replace(/^\//, '')
    const parts = (normalized.startsWith('xl/') ? normalized : `xl/${normalized}`).split('/')
    return parts.reduce((resolved, part) => {
        if (!part || part === '.') return resolved
        if (part === '..') resolved.pop()
        else resolved.push(part)
        return resolved
    }, []).join('/')
}

function xmlElements (document, localName) {
    return Array.from(document.getElementsByTagNameNS('*', localName))
}

function parseXml (xml, errorMessage) {
    const document = new DOMParser().parseFromString(xml, 'application/xml')
    if (xmlElements(document, 'parsererror').length) throw new Error(errorMessage)
    return document
}

function columnIndexFromAddress (address) {
    const letters = String(address || '').match(/^[A-Z]+/i)
    if (!letters) return -1
    return letters[0].toUpperCase().split('').reduce((value, letter) =>
        value * 26 + letter.charCodeAt(0) - 64, 0) - 1
}

function mimeForExtension (extension) {
    const ext = String(extension || '').toLowerCase()
    if (ext === 'jpg' || ext === 'jpeg') return 'image/jpeg'
    if (ext === 'svg') return 'image/svg+xml'
    return `image/${ext || 'png'}`
}

function isExcelDateFormat (format) {
    if (!format || typeof format !== 'string') return false
    const normalized = format
        .replace(/"[^"]*"/g, '')
        .replace(/\\./g, '')
        .replace(/\[([hms]+)]/gi, '$1')
        .replace(/\[[^\]]*]/g, '')
        .replace(/[_*]./g, '')
    return DATE_TOKEN_RE.test(normalized)
}

function isNumericText (value) {
    return (typeof value === 'number' && Number.isFinite(value))
        || (typeof value === 'string' && value.trim() !== '' && Number.isFinite(Number(value)))
}

function excelSerialToDate (value, date1904) {
    const epoch = Date.UTC(date1904 ? 1904 : 1899, date1904 ? 0 : 11, date1904 ? 1 : 30)
    return new Date(epoch + Number(value) * 86400000)
}

function isMinuteToken (format, index) {
    const before = format.slice(0, index).toLowerCase()
    const after = format.slice(index).toLowerCase()
    return before.lastIndexOf('h') > Math.max(before.lastIndexOf('y'), before.lastIndexOf('d'))
        && (before.trimEnd().endsWith(':') || after.includes(':'))
}

function formatExcelDate (format, value, date1904) {
    const date = value instanceof Date ? value : excelSerialToDate(value, date1904)
    const pad = number => String(number).padStart(2, '0')
    const weekdays = ['星期日', '星期一', '星期二', '星期三', '星期四', '星期五', '星期六']
    const source = String(format).split(';')[0].replace(/\[\$-[^\]]+]/gi, '')
    const hasAmPm = /AM\/PM|A\/P/i.test(source)
    let output = ''
    let index = 0

    while (index < source.length) {
        const current = source[index]
        if (current === '"') {
            const end = source.indexOf('"', index + 1)
            if (end < 0) break
            output += source.slice(index + 1, end)
            index = end + 1
            continue
        }
        if (current === '\\') {
            output += source[index + 1] || ''
            index += 2
            continue
        }
        if (current === '_' || current === '*') {
            index += 2
            continue
        }
        if (current === '[') {
            const end = source.indexOf(']', index + 1)
            index = end < 0 ? source.length : end + 1
            continue
        }

        const match = source.slice(index).match(/^(yyyy|yy|mmmm|mmm|mm|m|dddd|ddd|dd|d|hh|h|ss|s|AM\/PM|A\/P)/i)
        if (!match) {
            output += current
            index++
            continue
        }

        const token = match[0]
        const lower = token.toLowerCase()
        const month = date.getUTCMonth() + 1
        const day = date.getUTCDate()
        const hour24 = date.getUTCHours()
        const hour = hasAmPm ? (hour24 % 12 || 12) : hour24
        const replacements = {
            yyyy: String(date.getUTCFullYear()),
            yy: String(date.getUTCFullYear()).slice(-2),
            mmmm: String(month),
            mmm: String(month),
            dddd: weekdays[date.getUTCDay()],
            ddd: weekdays[date.getUTCDay()].replace('星期', '周'),
            dd: pad(day),
            d: String(day),
            hh: pad(hour),
            h: String(hour),
            ss: pad(date.getUTCSeconds()),
            s: String(date.getUTCSeconds()),
            'am/pm': hour24 < 12 ? 'AM' : 'PM',
            'a/p': hour24 < 12 ? 'A' : 'P'
        }
        if (lower === 'm' || lower === 'mm') {
            const number = isMinuteToken(source, index) ? date.getUTCMinutes() : month
            output += lower === 'mm' ? pad(number) : String(number)
        } else {
            output += replacements[lower]
        }
        index += token.length
    }
    return output
}

function resolveCellValue (cell) {
    if (!cell) return undefined
    if (cell.type === 6 && cell.value && typeof cell.value === 'object') return cell.value.result
    return cell.value
}

function resolveFormula (cell) {
    if (!cell) return ''
    try {
        const value = cell.value
        if (value && typeof value === 'object' && typeof value.formula === 'string') return value.formula
        return typeof cell.formula === 'string' ? cell.formula : ''
    } catch (error) {
        // 个别 ExcelJS 值类型的 getter 在内部值为空时会抛错，公式增强直接跳过该单元格。
        return ''
    }
}

function createMediaUrl (media) {
    if (!media || !media.buffer) return ''
    return URL.createObjectURL(new Blob([normalizeBuffer(media.buffer)], {
        type: mimeForExtension(media.extension)
    }))
}

async function parseExcelDateCells (zip) {
    const stylesEntry = zip.file('xl/styles.xml')
    const workbookEntry = zip.file('xl/workbook.xml')
    const workbookRelsEntry = zip.file('xl/_rels/workbook.xml.rels')
    if (!stylesEntry || !workbookEntry) return new Map()

    const [stylesXml, workbookXml, workbookRelsXml] = await Promise.all([
        stylesEntry.async('string'),
        workbookEntry.async('string'),
        workbookRelsEntry ? workbookRelsEntry.async('string') : Promise.resolve('')
    ])
    const stylesDocument = parseXml(stylesXml, 'Failed to parse Excel style XML.')
    const workbookDocument = parseXml(workbookXml, 'Failed to parse Excel workbook XML.')
    const workbookRelsDocument = workbookRelsXml
        ? parseXml(workbookRelsXml, 'Failed to parse Excel workbook relationship XML.')
        : null

    const formats = new Map(LOCALIZED_DATE_FORMAT_FALLBACKS)
    xmlElements(stylesDocument, 'numFmt').forEach(item => {
        formats.set(Number(item.getAttribute('numFmtId')), item.getAttribute('formatCode'))
    })
    const cellXfs = xmlElements(stylesDocument, 'cellXfs')[0]
    const styleFormats = cellXfs
        ? Array.from(cellXfs.children).filter(item => item.localName === 'xf').map(item =>
            formats.get(Number(item.getAttribute('numFmtId'))))
        : []

    const relationTargets = new Map((workbookRelsDocument
        ? xmlElements(workbookRelsDocument, 'Relationship')
        : []).map(relation => [
        relation.getAttribute('Id'),
        normalizeZipTarget(relation.getAttribute('Target'))
    ]))
    const workbookProperties = xmlElements(workbookDocument, 'workbookPr')[0]
    const date1904 = workbookProperties
        && /^(1|true)$/i.test(workbookProperties.getAttribute('date1904') || '')
    const dateCells = new Map()
    const sheets = xmlElements(workbookDocument, 'sheet')

    for (let sheetIndex = 0; sheetIndex < sheets.length; sheetIndex++) {
        const sheet = sheets[sheetIndex]
        const relationshipId = sheet.getAttribute('r:id') || sheet.getAttributeNS(
            OFFICE_RELATIONSHIP_NAMESPACE, 'id'
        )
        const sheetPath = relationTargets.get(relationshipId) || `xl/worksheets/sheet${sheetIndex + 1}.xml`
        const sheetEntry = zip.file(sheetPath)
        if (!sheetEntry) continue
        const sheetDocument = parseXml(
            await sheetEntry.async('string'),
            'Failed to parse Excel worksheet XML.'
        )

        for (const cell of xmlElements(sheetDocument, 'c')) {
            const cellType = cell.getAttribute('t')
            if (cellType && cellType !== 'n' && cellType !== 'd') continue
            const format = styleFormats[Number(cell.getAttribute('s') || 0)]
            if (!isExcelDateFormat(format)) continue
            const valueElement = xmlElements(cell, 'v')[0]
            const rawValue = valueElement && Number(valueElement.textContent)
            if (!Number.isFinite(rawValue)) continue
            const address = cell.getAttribute('r')
            const rowNumber = Number((String(address).match(/\d+$/) || [0])[0])
            const columnIndex = columnIndexFromAddress(address)
            if (!rowNumber || columnIndex < 0) continue
            dateCells.set(`${sheetIndex}:${rowNumber - 1}:${columnIndex}`, formatExcelDate(
                format, rawValue, date1904
            ))
        }
    }
    return dateCells
}

async function parseWpsCellImages (zip) {
    const imageEntry = zip.file(CELL_IMAGES_ENTRY)
    const relationEntry = zip.file('xl/_rels/cellimages.xml.rels')
    if (!imageEntry || !relationEntry) return new Map()

    const [imageXml, relationXml] = await Promise.all([
        imageEntry.async('string'),
        relationEntry.async('string')
    ])
    const imageDocument = parseXml(imageXml, 'Failed to parse WPS cell image XML.')
    const relationDocument = parseXml(relationXml, 'Failed to parse WPS cell image relationship XML.')
    const targets = new Map(xmlElements(relationDocument, 'Relationship').map(relation => [
        relation.getAttribute('Id'),
        normalizeZipTarget(relation.getAttribute('Target'))
    ]))
    const images = new Map()
    for (const cellImage of xmlElements(imageDocument, 'cellImage')) {
        const property = xmlElements(cellImage, 'cNvPr')[0]
        const blip = xmlElements(cellImage, 'blip')[0]
        const imageId = property && property.getAttribute('name')
        const relationId = blip && (blip.getAttribute('r:embed') || blip.getAttributeNS(
            OFFICE_RELATIONSHIP_NAMESPACE, 'embed'
        ))
        const target = targets.get(relationId)
        const mediaEntry = target && zip.file(target)
        if (!imageId || !mediaEntry) continue
        const extension = target.split('.').pop().toLowerCase()
        const buffer = await mediaEntry.async('uint8array')
        images.set(imageId, {
            id: imageId,
            extension,
            buffer
        })
    }
    return images
}

function createViewerOptions (title) {
    return {
        navbar: false,
        title: () => title,
        toolbar: {
            zoomIn: true,
            zoomOut: true,
            oneToOne: true,
            reset: true,
            rotateLeft: true,
            rotateRight: true,
            flipHorizontal: true,
            flipVertical: true
        },
        focus: false,
        keyboard: false,
        loop: false,
        transition: false,
        hide: () => {
            const activeElement = document.activeElement
            if (activeElement && activeElement.closest && activeElement.closest('.viewer-container')) {
                activeElement.blur()
            }
        }
    }
}

export function createDefaultExcelOptions (isLegacyXls = false) {
    return {
        xls: isLegacyXls, // 预览xlsx文件设为false；预览xls文件设为true
        minColLength: 0, // excel最少渲染多少列，如果想实现xlsx文件内容有几列，就渲染几列，可以将此值设置为0.
        minRowLength: 0, // excel最少渲染多少行，如果想实现根据xlsx实际函数渲染，可以将此值设置为0.
        widthOffset: 10, // 如果渲染出来的结果感觉单元格宽度不够，可以在默认渲染的列表宽度上再加 Npx宽
        heightOffset: 10, // 在默认渲染的列表高度上再加 Npx高
        // 底层通过exceljs获取excel文件内容，通过该钩子函数，可以对获取的excel文件内容进行修改，比如某个单元格的数据显示不正确，可以在此自行修改每个单元格的value值。
        beforeTransformData: workbook => workbook,
        // 将获取到的excel数据进行处理之后且渲染到页面之前，可通过transformData对即将渲染的数据及样式进行修改，此时每个单元格的text值就是即将渲染到页面上的内容。
        transformData: workbookData => workbookData
    }
}

export async function createExcelPreviewSession (source) {
    const arrayBuffer = source instanceof Blob ? await source.arrayBuffer() : normalizeBuffer(source)
    // XLSX 只打开一次，日期样式和单元格图片共享同一个 ZIP 实例。
    const zip = await JSZip.loadAsync(arrayBuffer)
    const [xmlDateCells, wpsImages] = await Promise.all([
        parseExcelDateCells(zip),
        parseWpsCellImages(zip)
    ])
    let dateTextMap = new Map()
    let imageCellMap = new Map()
    let viewer = null
    let viewerSource = null
    let viewerUrl = ''

    function beforeTransformData (workbook) {
        dateTextMap = new Map()
        imageCellMap = new Map()
        const worksheets = Array.isArray(workbook.worksheets)
            ? workbook.worksheets
            : (workbook._worksheets || []).filter(Boolean)
        const date1904 = workbook && workbook.properties && workbook.properties.date1904
        const pendingImages = []

        worksheets.forEach((sheet, sheetIndex) => {
            const rows = sheet._rows || []
            rows.forEach((row, rowIndex) => {
                const cells = row._cells || []
                cells.forEach((cell, columnIndex) => {
                    const cellKey = `${sheetIndex}:${rowIndex}:${columnIndex}`
                    const format = cell.numFmt || (cell.style && cell.style.numFmt)
                        || (cell._style && cell._style.numFmt)
                    const value = resolveCellValue(cell)
                    const isDate = value instanceof Date && !Number.isNaN(value.getTime())
                    if (xmlDateCells.has(cellKey)) {
                        dateTextMap.set(cellKey, xmlDateCells.get(cellKey))
                    } else if ((typeof value === 'number' || isDate) && isExcelDateFormat(format)) {
                        dateTextMap.set(cellKey, formatExcelDate(
                            format, value, date1904
                        ))
                    }

                    const match = resolveFormula(cell).match(DISPIMG_RE)
                    if (match && wpsImages.has(match[1])) {
                        pendingImages.push({ sheet, sheetIndex, rowIndex, columnIndex, cell, image: wpsImages.get(match[1]) })
                    }
                })
            })

            ;(sheet._media || []).forEach((anchor, index) => {
                if (anchor.type !== 'image') return
                const media = (workbook.media || [])[anchor.imageId]
                if (!media || !media.buffer) return
                const topLeft = (anchor.range && anchor.range.tl) || {}
                const image = {
                    id: `drawing-${sheetIndex}-${index}`,
                    extension: media.extension,
                    buffer: media.buffer,
                    sheetIndex,
                    rowIndex: topLeft.nativeRow || 0,
                    columnIndex: topLeft.nativeCol || 0,
                    label: `${sheet.name} 图片`
                }
                const key = `${image.sheetIndex}:${image.rowIndex}:${image.columnIndex}`
                if (!imageCellMap.has(key)) imageCellMap.set(key, image)
            })
        })

        if (!workbook.media) workbook.media = []
        pendingImages.forEach(({ sheet, sheetIndex, rowIndex, columnIndex, cell, image }) => {
            // DISPIMG 是 WPS 扩展公式，vue-office 不识别；清空公式后复用现有 drawing 渲染链路。
            cell.value = null
            const mediaId = workbook.media.length
            workbook.media.push({
                type: 'image',
                name: `wps-cell-image-${image.id}-${sheetIndex}-${rowIndex}-${columnIndex}`,
                extension: image.extension,
                buffer: image.buffer
            })
            sheet.addImage(mediaId, {
                tl: { col: columnIndex, row: rowIndex },
                br: { col: columnIndex + 1, row: rowIndex + 1 },
                editAs: 'oneCell'
            })
            imageCellMap.set(`${sheetIndex}:${rowIndex}:${columnIndex}`, {
                ...image,
                sheetIndex,
                rowIndex,
                columnIndex,
                label: `${sheet.name}!${cell.address}`
            })
        })
        return workbook
    }

    function transformData (workbookData) {
        workbookData.forEach((sheet, sheetIndex) => {
            Object.keys(sheet.rows || {}).forEach(rowKey => {
                if (rowKey === 'len') return
                const row = sheet.rows[rowKey]
                Object.keys((row && row.cells) || {}).forEach(columnKey => {
                    const key = `${sheetIndex}:${rowKey}:${columnKey}`
                    const text = dateTextMap.get(key)
                    const renderedText = row.cells[columnKey].text
                    // vue-office 已正确格式化时保持原样，只修正仍显示为序列值的日期。
                    if (text !== undefined && isNumericText(renderedText)) {
                        row.cells[columnKey].text = text
                    }
                    if (imageCellMap.has(key)) {
                        row.cells[columnKey].text = ''
                        delete row.cells[columnKey].formula
                    }
                })
            })
        })
        return workbookData
    }

    function closeViewer () {
        if (viewer) viewer.destroy()
        if (viewerSource && viewerSource.parentNode) viewerSource.parentNode.removeChild(viewerSource)
        if (viewerUrl) URL.revokeObjectURL(viewerUrl)
        viewer = null
        viewerSource = null
        viewerUrl = ''
    }

    return {
        source: arrayBuffer,
        options: {
            ...createDefaultExcelOptions(false),
            beforeTransformData,
            transformData
        },
        findImage (sheetIndex, rowIndex, columnIndex) {
            return imageCellMap.get(`${sheetIndex}:${rowIndex}:${columnIndex}`)
        },
        openImage (image) {
            if (!image) return
            closeViewer()
            viewerUrl = createMediaUrl(image)
            if (!viewerUrl) return
            viewerSource = document.createElement('img')
            viewerSource.src = viewerUrl
            viewerSource.alt = image.label
            viewerSource.style.display = 'none'
            document.body.appendChild(viewerSource)
            viewer = new Viewer(viewerSource, createViewerOptions(image.label))
            viewer.view()
        },
        destroy () {
            closeViewer()
            xmlDateCells.clear()
            wpsImages.clear()
            dateTextMap.clear()
            imageCellMap.clear()
        }
    }
}
