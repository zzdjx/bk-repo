const textType = [
    'txt',
    'sh',
    'bat',
    'json',
    'yaml',
    'yml',
    'xml',
    'log',
    'properties',
    'sql'
]

const formatType = [
    'docx',
    'pdf',
    'wps',
    'doc',
    'docm',
    'xls',
    'xlsm',
    'ppt',
    'pptx',
    'vsd',
    'rtf',
    'odt',
    'wmf',
    'emf',
    'dps',
    'et',
    'ods',
    'ots',
    'tsv',
    'odp',
    'otp',
    'sxi',
    'ott',
    'vsdx',
    'fodt',
    'fods',
    'xltx',
    'tga',
    'psd',
    'dotm',
    'ett',
    'xlt',
    'xltm',
    'wpt',
    'dot',
    'xlam',
    'dotx',
    'xla',
    'pages',
    'eps'
]

const isHtmlFormatType = [
    'xlsm', 'xlt', 'xltm', 'et', 'ett', 'xlam'
]

const excelType = [
    'xls', 'xlsx', 'csv'
]

const picType = [
    'jpg', 'jpeg', 'png', 'gif', 'bmp', 'ico', 'jfif', 'webp', 'svg'
]

const markdownType = [
    'md'
]

const jsxType = [
    'jsx'
]

const htmlFileType = [
    'html',
    'htm'
]

const xmindType = [
    'xmind'
]

const compressedType = [
    'rar', 'zip', 'gz', 'tgz', 'tar', 'jar'
]

const mediaVideoType = [
    'mp4',
    'webm'
]

const mediaAudioType = [
    'mp3',
    'wav',
    'ogg',
    'oga',
    'm4a'
]

// 与 preview 服务 FileType.CODES 对齐（html 已拆到 isHtmlFile 做渲染预览）
// 含原文本类后缀：统一走 onlinePreview + Monaco
const codeType = [
    'java',
    'c',
    'php',
    'go',
    'python',
    'py',
    'js',
    'ftl',
    'css',
    'lua',
    'sh',
    'rb',
    'yaml',
    'yml',
    'json',
    'h',
    'cpp',
    'cs',
    'aspx',
    'jsp',
    'sql',
    'ini',
    'toml',
    'txt',
    'bat',
    'xml',
    'log',
    'properties'
]

function getFileSuffix (param) {
    if (!param) {
        return ''
    }
    const normalized = String(param).replace(/\\/g, '/')
    const baseName = normalized.includes('/')
        ? normalized.slice(normalized.lastIndexOf('/') + 1)
        : normalized
    const dotIndex = baseName.lastIndexOf('.')
    if (dotIndex < 0) {
        // getPreviewInfo 的 suffix 响应字段会被直接用于类型判断，例如 "java"。
        return baseName.toLowerCase()
    }
    if (dotIndex === baseName.length - 1) {
        return ''
    }
    return baseName.slice(dotIndex + 1).toLowerCase()
}

function findFileType (param, types) {
    const suffix = getFileSuffix(param)
    return types.find(type => type === suffix)
}

// 判断文本类型
export function isText (param) {
    return findFileType(param, textType)
}

// 判断代码类型（走 preview onlinePreview + Monaco）
export function isCode (param) {
    return findFileType(param, codeType)
}

// 判断预览可转换的类型（转换的为pdf或者html）
export function isFormatType (param) {
    return findFileType(param, formatType)
}

// 判断转换成html的类型
export function isHtmlType (param) {
    return findFileType(param, isHtmlFormatType)
}

export function isPic (param) {
    return findFileType(param, picType)
}

export function isExcel (param) {
    return findFileType(param, excelType)
}

export function isMarkdown (param) {
    return findFileType(param, markdownType)
}

export function isJsx (param) {
    return findFileType(param, jsxType)
}

// 单 HTML 文件渲染预览（勿与 isHtmlType / Excel→HTML 混淆）
export function isHtmlFile (param) {
    return findFileType(param, htmlFileType)
}

export function isXmind (param) {
    return findFileType(param, xmindType)
}

export function isCompressed (param) {
    return findFileType(param, compressedType)
}

export function isMediaVideo (param) {
    return Boolean(findFileType(param, mediaVideoType))
}

export function isMediaAudio (param) {
    return Boolean(findFileType(param, mediaAudioType))
}

export function isMedia (param) {
    return isMediaVideo(param) || isMediaAudio(param)
}

// 判断可预览的类型(不包括pic)
export function isDisplayType (param) {
    return isText(param) || isFormatType(param) || isExcel(param) || isXmind(param)
}

// 判断可预览的类型(包括pic)
export function isOutDisplayType (param) {
    return isText(param) || isCode(param) || isFormatType(param) || isExcel(param) || isPic(param) || isMarkdown(param) || isJsx(param) || isHtmlFile(param) || isXmind(param)
}
