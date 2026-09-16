<template>
    <div class="glb-preview">
        <div class="glb-preview-stage">
            <model-viewer
                v-if="scriptReady && viewerSrc"
                ref="viewer"
                class="glb-preview-viewer"
                :src="viewerSrc"
                :alt="altText"
                camera-controls
                touch-action="pan-y"
                interaction-prompt="none"
                environment-image="neutral"
                shadow-intensity="1"
                shadow-softness="0.4"
            />
            <div v-if="!modelLoaded" class="glb-preview-loading">
                <Icon name="loading" size="48" class="glb-preview-spinner" />
                <span>{{ loadingText }}</span>
            </div>
            <div class="glb-preview-hint">{{ $t('previewGlbHint') }}</div>
        </div>
        <aside v-show="panelOpen" class="glb-preview-panel">
            <div class="glb-preview-panel-body">
            <div class="glb-preview-group">
                <div class="glb-preview-label">{{ $t('previewGlbAnimation') }}</div>
                <select
                    class="glb-preview-select"
                    :disabled="!animations.length"
                    :value="animationName"
                    @change="onAnimationChange"
                >
                    <option v-if="!animations.length" value="">{{ $t('previewGlbNoAnimation') }}</option>
                    <option
                        v-for="name in animations"
                        :key="name"
                        :value="name"
                    >
                        {{ name }}
                    </option>
                </select>
                <div class="glb-preview-row">
                    <button
                        type="button"
                        class="glb-preview-btn"
                        :disabled="!animations.length"
                        @click="togglePlay"
                    >
                        {{ paused ? $t('previewGlbPlay') : $t('previewGlbPause') }}
                    </button>
                    <button
                        type="button"
                        class="glb-preview-btn"
                        :disabled="!animations.length"
                        @click="cycleSpeed"
                    >
                        {{ timeScale }}×
                    </button>
                </div>
                <label class="glb-preview-check">
                    <input v-model="autoRotate" type="checkbox">
                    {{ $t('previewGlbAutoRotate') }}
                </label>
            </div>
            <div class="glb-preview-group">
                <label class="glb-preview-check">
                    <input v-model="shadows" type="checkbox">
                    {{ $t('previewGlbShadows') }}
                </label>
                <div class="glb-preview-label">
                    {{ $t('previewGlbExposure') }}
                    <span class="glb-preview-val">{{ exposure.toFixed(2) }}</span>
                </div>
                <input
                    v-model.number="exposure"
                    class="glb-preview-range"
                    type="range"
                    min="0.2"
                    max="2.5"
                    step="0.05"
                >
            </div>
            <div class="glb-preview-group">
                <div class="glb-preview-label">{{ $t('previewGlbVariants') }}</div>
                <select
                    class="glb-preview-select"
                    :disabled="!variants.length"
                    :value="variantName"
                    @change="onVariantChange"
                >
                    <option v-if="!variants.length" value="">{{ $t('previewGlbNoVariants') }}</option>
                    <option
                        v-for="name in variants"
                        :key="name"
                        :value="name"
                    >
                        {{ name }}
                    </option>
                </select>
            </div>
            <div class="glb-preview-group">
                <div class="glb-preview-label">{{ $t('previewGlbAssetInfo') }}</div>
                <div class="glb-preview-meta">
                    {{ $t('previewGlbAnimCount') }} {{ animations.length }}
                    · {{ $t('previewGlbVariantCount') }} {{ variants.length }}
                </div>
            </div>
            </div>
            <button
                type="button"
                class="glb-preview-toggle"
                :aria-label="$t('previewGlbHidePanel')"
                @click="panelOpen = false"
            >
                <i class="devops-icon icon-angle-double-right"></i>
                <span>{{ $t('previewGlbHidePanel') }}</span>
            </button>
        </aside>
        <button
            v-show="!panelOpen"
            type="button"
            class="glb-preview-reopen"
            :aria-label="$t('previewGlbShowPanel')"
            :title="$t('previewGlbShowPanel')"
            @click="panelOpen = true"
        >
            <i class="devops-icon icon-angle-double-left"></i>
        </button>
    </div>
</template>
<script>
    import { loadModelViewerElement } from '@repository/utils/modelViewer'
    import { loadSelfContainedGlbBytes } from '@repository/utils/glbSelfContained'

    const PLAYBACK_RATES = [0.5, 1, 2]
    const DEFAULT_SHADOW_INTENSITY = 1
    const DEFAULT_EXPOSURE = 1

    export default {
        name: 'GlbPreview',
        props: {
            src: {
                type: String,
                default: ''
            },
            altText: {
                type: String,
                default: ''
            },
            sizeHint: {
                type: String,
                default: ''
            }
        },
        data () {
            return {
                scriptReady: false,
                modelLoaded: false,
                viewerSrc: '',
                panelOpen: true,
                animations: [],
                variants: [],
                animationName: '',
                variantName: '',
                paused: true,
                autoRotate: false,
                shadows: true,
                exposure: DEFAULT_EXPOSURE,
                timeScale: 1,
                rateIndex: 1,
                viewerBound: false,
                validateAbort: null
            }
        },
        computed: {
            loadingText () {
                if (this.sizeHint) {
                    return this.$t('previewGlbLargeFileTip', [this.sizeHint])
                }
                return this.$t('fileLoadingTip')
            }
        },
        watch: {
            src: {
                immediate: true,
                handler (url) {
                    this.gateSrc(url)
                }
            },
            autoRotate (value) {
                this.withViewer(mv => {
                    mv.autoRotate = value
                })
            },
            shadows (value) {
                this.withViewer(mv => {
                    mv.shadowIntensity = value ? DEFAULT_SHADOW_INTENSITY : 0
                })
            },
            exposure (value) {
                this.withViewer(mv => {
                    mv.exposure = value
                })
            }
        },
        created () {
            loadModelViewerElement().then(() => {
                this.scriptReady = true
                this.$nextTick(() => this.bindViewer())
            }).catch(() => this.$emit('error'))
        },
        beforeDestroy () {
            this.abortValidation()
            this.unbindViewer()
            this.revokeViewerSrc()
        },
        methods: {
            viewerEl () {
                return this.$refs.viewer
            },
            withViewer (fn) {
                const mv = this.viewerEl()
                if (mv) fn(mv)
            },
            abortValidation () {
                if (this.validateAbort) {
                    this.validateAbort.abort()
                    this.validateAbort = null
                }
            },
            revokeViewerSrc () {
                const src = this.viewerSrc
                this.viewerSrc = ''
                if (src && src.indexOf('blob:') === 0 && typeof URL !== 'undefined' && URL.revokeObjectURL) {
                    this.$nextTick(() => URL.revokeObjectURL(src))
                }
            },
            resetViewerState () {
                this.unbindViewer()
                this.viewerBound = false
                this.revokeViewerSrc()
                this.modelLoaded = false
                this.animations = []
                this.variants = []
                this.animationName = ''
                this.variantName = ''
            },
            gateSrc (url) {
                this.abortValidation()
                this.resetViewerState()
                if (!url) return
                const controller = typeof AbortController === 'function' ? new AbortController() : null
                this.validateAbort = controller
                loadSelfContainedGlbBytes(url, { signal: controller && controller.signal }).then(bytes => {
                    if (controller && controller.signal.aborted) return
                    this.viewerSrc = URL.createObjectURL(new Blob([bytes], { type: 'model/gltf-binary' }))
                    this.$nextTick(() => this.bindViewer())
                }).catch(err => {
                    if (controller && controller.signal.aborted) return
                    if (err && err.name === 'AbortError') return
                    this.$emit('error', err && err.code === 'external' ? 'external' : undefined)
                })
            },
            bindViewer () {
                const mv = this.viewerEl()
                if (!mv || this.viewerBound) return
                this.viewerBound = true
                mv.addEventListener('load', this.onLoaded)
                mv.addEventListener('error', this.onError)
                mv.autoRotate = this.autoRotate
                mv.shadowIntensity = this.shadows ? DEFAULT_SHADOW_INTENSITY : 0
                mv.shadowSoftness = 0.4
                mv.exposure = this.exposure
                if (mv.loaded) {
                    this.onLoaded()
                }
            },
            unbindViewer () {
                const mv = this.viewerEl()
                if (!mv) return
                mv.removeEventListener('load', this.onLoaded)
                mv.removeEventListener('error', this.onError)
            },
            onLoaded () {
                const mv = this.viewerEl()
                if (!mv) return
                this.modelLoaded = true
                this.animations = Array.from(mv.availableAnimations || [])
                this.variants = Array.from(mv.availableVariants || [])
                if (this.animations.length) {
                    this.animationName = this.animations[0]
                    mv.animationName = this.animationName
                    mv.timeScale = this.timeScale
                    mv.play({ repetitions: Infinity })
                    this.paused = false
                } else {
                    this.animationName = ''
                    this.paused = true
                }
                this.variantName = this.variants[0] || ''
                if (this.variantName) {
                    mv.variantName = this.variantName
                }
            },
            onError () {
                this.$emit('error')
            },
            onAnimationChange (event) {
                const name = event.target.value
                this.animationName = name
                this.withViewer(mv => {
                    mv.animationName = name
                    mv.play({ repetitions: Infinity })
                    this.paused = false
                })
            },
            onVariantChange (event) {
                const name = event.target.value
                this.variantName = name
                this.withViewer(mv => {
                    mv.variantName = name
                })
            },
            togglePlay () {
                this.withViewer(mv => {
                    if (mv.paused) {
                        mv.play({ repetitions: Infinity })
                        this.paused = false
                    } else {
                        mv.pause()
                        this.paused = true
                    }
                })
            },
            cycleSpeed () {
                this.rateIndex = (this.rateIndex + 1) % PLAYBACK_RATES.length
                this.timeScale = PLAYBACK_RATES[this.rateIndex]
                this.withViewer(mv => {
                    mv.timeScale = this.timeScale
                })
            }
        }
    }
</script>
<style lang="scss" scoped>
.glb-preview {
    position: relative;
    display: flex;
    width: 100%;
    height: 100%;
    min-height: 0;
    background: #f0f2f5;
}
.glb-preview-stage {
    position: relative;
    flex: 1;
    min-width: 0;
    min-height: 0;
    background: #f0f2f5;
}
.glb-preview-viewer {
    width: 100%;
    height: 100%;
    background: #f0f2f5;
    --poster-color: #f0f2f5;
}
.glb-preview-viewer::part(default-progress-bar) {
    display: none;
}
.glb-preview-loading {
    position: absolute;
    inset: 0;
    z-index: 3;
    display: flex;
    align-items: center;
    justify-content: center;
    gap: 12px;
    background: #f0f2f5;
    color: #313238;
    font-size: 16px;
}
.glb-preview-spinner {
    animation: glb-rotate-loading 1s linear infinite;
}
@keyframes glb-rotate-loading {
    0% {
        transform: rotateZ(0);
    }
    100% {
        transform: rotateZ(360deg);
    }
}
.glb-preview-hint {
    position: absolute;
    left: 12px;
    top: 12px;
    z-index: 4;
    max-width: min(420px, 70%);
    padding: 6px 10px;
    border-radius: 4px;
    background: rgba(255, 255, 255, 0.88);
    color: #63656e;
    font-size: 12px;
    line-height: 18px;
}
.glb-preview-panel {
    box-sizing: border-box;
    display: flex;
    flex: none;
    flex-direction: column;
    width: 280px;
    height: 100%;
    min-height: 0;
    overflow: hidden;
    border-left: 1px solid #dcdee5;
    background: #fafbfd;
    color: #313238;
    font-size: 12px;
}
.glb-preview-panel-body {
    flex: 1;
    min-height: 0;
    padding: 16px;
    overflow-y: auto;
}
.glb-preview-toggle {
    box-sizing: border-box;
    display: flex;
    flex: none;
    align-items: center;
    gap: 8px;
    width: 100%;
    padding: 12px 16px;
    border: 0;
    border-top: 1px solid #dcdee5;
    background: transparent;
    color: #3a84ff;
    font-size: 12px;
    line-height: 20px;
    cursor: pointer;
    &:hover {
        background: #f0f1f5;
    }
}
.glb-preview-reopen {
    box-sizing: border-box;
    position: absolute;
    right: 0;
    bottom: 16px;
    z-index: 5;
    display: flex;
    align-items: center;
    justify-content: center;
    width: 32px;
    height: 48px;
    padding: 0;
    border: 1px solid #dcdee5;
    border-right: 0;
    border-radius: 4px 0 0 4px;
    background: #fafbfd;
    color: #3a84ff;
    font-size: 16px;
    cursor: pointer;
    &:hover {
        background: #f0f1f5;
    }
}
.glb-preview-group {
    margin-bottom: 16px;
    padding-bottom: 16px;
    border-bottom: 1px solid #dcdee5;
}
.glb-preview-group:last-child {
    margin-bottom: 0;
    padding-bottom: 0;
    border-bottom: 0;
}
.glb-preview-label {
    margin-bottom: 8px;
    color: #979ba5;
    letter-spacing: .04em;
}
.glb-preview-val {
    float: right;
    color: #313238;
    font-variant-numeric: tabular-nums;
}
.glb-preview-select,
.glb-preview-btn {
    width: 100%;
    padding: 6px 8px;
    border: 1px solid #c4c6cc;
    border-radius: 2px;
    background: #fff;
    color: #313238;
}
.glb-preview-row {
    display: flex;
    gap: 8px;
    margin-top: 8px;
}
.glb-preview-row > * {
    flex: 1;
}
.glb-preview-btn {
    cursor: pointer;
}
.glb-preview-btn:disabled,
.glb-preview-select:disabled {
    cursor: not-allowed;
    opacity: .6;
}
.glb-preview-check {
    display: flex;
    align-items: center;
    gap: 8px;
    margin-top: 8px;
    cursor: pointer;
}
.glb-preview-range {
    width: 100%;
    accent-color: #3a84ff;
}
.glb-preview-meta {
    color: #63656e;
    line-height: 20px;
}
</style>
