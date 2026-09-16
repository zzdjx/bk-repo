import {
    buildHtmlSandboxSrcdoc as buildHtmlSandboxSrcdocCore
} from '@repository/utils/htmlFilePreviewCore'

export {
    buildHtmlSandboxSrcdoc as buildHtmlSandboxSrcdocCore
} from '@repository/utils/htmlFilePreviewCore'

export function buildHtmlSandboxSrcdoc (htmlSource, { resolveAssetUrl } = {}) {
    return buildHtmlSandboxSrcdocCore(htmlSource, { resolveAssetUrl })
}
