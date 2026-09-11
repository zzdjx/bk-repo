import { createDefaultExcelOptions, createExcelPreviewSession } from '@repository/utils/excelPreview'

const EXCEL_ENHANCEMENT_TIMEOUT = 8000
const EXCEL_DOUBLE_TAP_INTERVAL = 450
const EXCEL_DOUBLE_TAP_DISTANCE = 24
const EXCEL_TOUCH_SELECTION_DELAY = 50

function browserDisplayScale () {
    const scale = window.innerWidth ? window.outerWidth / window.innerWidth : 1
    return Number.isFinite(scale) && scale > 0 ? scale : 1
}

function browserPixelRatio () {
    return window.devicePixelRatio || 1
}

const EXCEL_INITIAL_PIXEL_RATIO = browserPixelRatio()

function withTimeout (promise, timeout) {
    let timer
    return Promise.race([
        promise,
        new Promise((resolve, reject) => {
            timer = setTimeout(() => reject(new Error('Excel preview enhancement timed out.')), timeout)
        })
    ]).finally(() => clearTimeout(timer))
}

export default {
    data () {
        return {
            excelOptions: createDefaultExcelOptions(),
            excelPreviewKey: 0,
            excelPreparationId: 0,
            excelPreviewSession: null,
            excelRawSource: null,
            excelCurrentSheetIndex: 0,
            excelSelectedCell: null,
            excelOpenTimer: null,
            excelDoubleClickHandler: null,
            excelPointerUpHandler: null,
            excelTouchTimer: null,
            excelLastTap: null,
            excelLastTouchTime: 0,
            excelImageDrawingPatch: null
        }
    },
    mounted () {
        this.excelDoubleClickHandler = event => this.handleExcelDoubleClick(event)
        this.excelPointerUpHandler = event => this.handleExcelPointerUp(event)
        this.$el.addEventListener('dblclick', this.excelDoubleClickHandler, true)
        this.$el.addEventListener('pointerup', this.excelPointerUpHandler, true)
    },
    beforeDestroy () {
        this.$el.removeEventListener('dblclick', this.excelDoubleClickHandler, true)
        this.$el.removeEventListener('pointerup', this.excelPointerUpHandler, true)
        this.destroyExcelPreview()
    },
    errorCaptured (error, vm) {
        if (!this.excelPreviewSession) return
        let component = vm
        while (component && component !== this) {
            if (component.$options && component.$options.name === 'VueOfficeExcel') {
                this.fallbackExcelPreview(error)
                break
            }
            component = component.$parent
        }
        // 不阻止异常继续向上传播，保留现有错误监控与日志。
    },
    methods: {
        async prepareExcelPreview (source, isLegacyXls = false) {
            this.destroyExcelPreview()
            const preparationId = this.excelPreparationId
            this.excelRawSource = source
            this.excelOptions = createDefaultExcelOptions(isLegacyXls)
            this.excelCurrentSheetIndex = 0
            let previewSource = source

            if (!isLegacyXls) {
                const sessionPromise = createExcelPreviewSession(source)
                try {
                    const session = await withTimeout(
                        sessionPromise,
                        EXCEL_ENHANCEMENT_TIMEOUT
                    )
                    if (preparationId !== this.excelPreparationId) {
                        session.destroy()
                        return false
                    }
                    this.excelPreviewSession = session
                    this.excelOptions = session.options
                    previewSource = session.source
                } catch (error) {
                    // 超时后解析仍可能在后台结束，完成后及时释放解析结果。
                    sessionPromise.then(session => session.destroy()).catch(() => {})
                    if (preparationId !== this.excelPreparationId) return false
                    console.warn('[ExcelPreview] enhancement initialization failed, use original vue-office.', error)
                }
            }
            if (preparationId !== this.excelPreparationId) return false
            this.dataSource = previewSource
            this.excelPreviewKey++
            return true
        },
        handleExcelRenderError (error) {
            if (!this.fallbackExcelPreview(error)) {
                console.error('[ExcelPreview] vue-office render failed.', error)
            }
        },
        handleExcelRendered () {
            if (this.excelPreviewSession && !this.patchExcelImageDrawing()) {
                this.fallbackExcelPreview(new Error('Failed to initialize Excel image drawing enhancement.'))
            }
        },
        handleExcelSwitchSheet (index) {
            this.excelCurrentSheetIndex = Number(index) || 0
            this.excelSelectedCell = null
            this.excelLastTap = null
        },
        handleExcelCellSelected ({ rowIndex, columnIndex }) {
            this.excelSelectedCell = {
                sheetIndex: this.excelCurrentSheetIndex,
                rowIndex,
                columnIndex
            }
        },
        handleExcelDoubleClick (event) {
            if (!this.excelPreviewSession || !this.excelSelectedCell) return
            // 部分移动浏览器会在双击手势后再合成 dblclick，避免重复打开图片。
            if (Date.now() - this.excelLastTouchTime <= EXCEL_DOUBLE_TAP_INTERVAL) return
            if (!this.isExcelTableEvent(event)) return
            this.openSelectedExcelImage()
        },
        handleExcelPointerUp (event) {
            if (!this.excelPreviewSession || !['touch', 'pen'].includes(event.pointerType)) return
            if (!event.isPrimary || !this.isExcelTableEvent(event)) return
            this.excelLastTouchTime = Date.now()
            const tap = {
                time: this.excelLastTouchTime,
                x: event.clientX,
                y: event.clientY
            }
            clearTimeout(this.excelTouchTimer)
            // vue-office 会在触摸结束后同步选中单元格，稍后再读取选择结果。
            this.excelTouchTimer = setTimeout(() => {
                this.excelTouchTimer = null
                if (!this.excelSelectedCell) return
                const cellKey = this.selectedExcelCellKey()
                const lastTap = this.excelLastTap
                const isDoubleTap = lastTap
                    && lastTap.cellKey === cellKey
                    && tap.time - lastTap.time <= EXCEL_DOUBLE_TAP_INTERVAL
                    && Math.hypot(tap.x - lastTap.x, tap.y - lastTap.y) <= EXCEL_DOUBLE_TAP_DISTANCE
                this.excelLastTap = isDoubleTap ? null : { ...tap, cellKey }
                if (isDoubleTap) this.openSelectedExcelImage()
            }, EXCEL_TOUCH_SELECTION_DELAY)
        },
        isExcelTableEvent (event) {
            // vue-office 通过 canvas 绘制表格，实际鼠标事件由其上方的 overlayer 接收。
            const tableArea = event.target.closest && event.target.closest(
                '.x-spreadsheet-overlayer, .x-spreadsheet-table'
            )
            return Boolean(tableArea && this.$el.contains(tableArea))
        },
        selectedExcelCellKey () {
            const { sheetIndex, rowIndex, columnIndex } = this.excelSelectedCell
            return `${sheetIndex}:${rowIndex}:${columnIndex}`
        },
        openSelectedExcelImage () {
            const { sheetIndex, rowIndex, columnIndex } = this.excelSelectedCell
            if (sheetIndex !== this.excelCurrentSheetIndex) return
            const image = this.excelPreviewSession.findImage(
                sheetIndex, rowIndex, columnIndex
            )
            if (!image) return
            clearTimeout(this.excelOpenTimer)
            // 等双击事件完整结束后再打开 Viewer，避免当前事件影响弹层。
            this.excelOpenTimer = setTimeout(() => {
                this.excelOpenTimer = null
                if (this.excelPreviewSession) this.excelPreviewSession.openImage(image)
            }, 0)
        },
        patchExcelImageDrawing () {
            if (!this.excelPreviewSession) return true
            const canvas = this.$el.querySelector('.x-spreadsheet-table')
            const context = canvas && canvas.getContext('2d')
            if (!context) return false
            if (this.excelImageDrawingPatch && context === this.excelImageDrawingPatch.context) return true
            this.restoreExcelImageDrawing()

            const originalDrawImage = context.drawImage
            const drawImageWrapper = function (...args) {
                // 依赖 @vue-office/excel 1.7.14 的 9 参数图片绘制逻辑，升级组件时需重新确认。
                // 图片和表格统一使用当前 DPR，避免浏览器缩放或移动端模拟造成坐标错位。
                if (args.length === 9) {
                    const pixelRatioChange = browserPixelRatio() / EXCEL_INITIAL_PIXEL_RATIO
                    const correction = pixelRatioChange / browserDisplayScale()
                    if (Number.isFinite(correction)) {
                        for (let index = 5; index <= 8; index++) args[index] *= correction
                    }
                }
                return originalDrawImage.apply(this, args)
            }
            try {
                context.drawImage = drawImageWrapper
                this.excelImageDrawingPatch = { context, originalDrawImage, drawImageWrapper }
                return true
            } catch (error) {
                console.warn('[ExcelPreview] image scale correction is unavailable.', error)
                return false
            }
        },
        restoreExcelImageDrawing () {
            const patch = this.excelImageDrawingPatch
            if (patch && patch.context.drawImage === patch.drawImageWrapper) {
                patch.context.drawImage = patch.originalDrawImage
            }
            this.excelImageDrawingPatch = null
        },
        fallbackExcelPreview (error) {
            if (!this.excelPreviewSession || !this.excelRawSource) return false
            console.warn('[ExcelPreview] enhance rendering failed, fallback to original vue-office.', error)
            this.restoreExcelImageDrawing()
            this.excelPreviewSession.destroy()
            this.excelPreviewSession = null
            this.excelOptions = createDefaultExcelOptions(this.excelOptions.xls)
            const source = this.excelRawSource
            const preparationId = this.excelPreparationId
            this.previewExcel = false
            this.dataSource = null
            this.$nextTick(() => {
                if (preparationId !== this.excelPreparationId) return
                this.dataSource = source
                this.excelPreviewKey++
                this.previewExcel = true
            })
            return true
        },
        destroyExcelPreview () {
            this.excelPreparationId++
            clearTimeout(this.excelOpenTimer)
            clearTimeout(this.excelTouchTimer)
            this.excelOpenTimer = null
            this.excelTouchTimer = null
            this.excelLastTap = null
            this.excelLastTouchTime = 0
            this.restoreExcelImageDrawing()
            if (this.excelPreviewSession) this.excelPreviewSession.destroy()
            this.excelPreviewSession = null
            this.excelRawSource = null
            this.excelSelectedCell = null
        }
    }
}
