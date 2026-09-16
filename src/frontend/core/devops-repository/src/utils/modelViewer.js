const MODEL_VIEWER_DIR = 'ui/libs/model-viewer/'

function originBase () {
    const subPath = window.BK_SUBPATH || '/'
    const normalized = subPath.endsWith('/') ? subPath : `${subPath}/`
    return `${location.origin}${normalized}`
}

export function modelViewerAssetBase () {
    return originBase() + MODEL_VIEWER_DIR
}

export function configureModelViewerDecoders () {
    const base = modelViewerAssetBase()
    self.ModelViewerElement = self.ModelViewerElement || {}
    self.ModelViewerElement.dracoDecoderLocation = `${base}draco/`
    self.ModelViewerElement.meshoptDecoderLocation = `${base}meshopt_decoder.js`
}

export function loadModelViewerElement () {
    configureModelViewerDecoders()
    if (window.customElements && window.customElements.get('model-viewer')) {
        return Promise.resolve()
    }
    return new Promise((resolve, reject) => {
        const script = document.createElement('script')
        script.type = 'module'
        script.src = `${modelViewerAssetBase()}model-viewer.min.js`
        script.onload = () => {
            configureModelViewerDecoders()
            resolve()
        }
        script.onerror = () => reject(new Error('model-viewer script failed'))
        document.head.appendChild(script)
    })
}
