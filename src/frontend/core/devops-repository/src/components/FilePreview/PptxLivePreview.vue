<template>
    <div class="pptx-live-preview" :class="{ 'is-presenting': presenting }">
        <aside v-show="!presenting" ref="thumbsHost" class="pptx-live-thumbs"></aside>
        <div class="pptx-live-main">
            <div ref="stageWrap" class="pptx-live-stage" @click="onStageClick"></div>
            <footer v-show="!presenting" class="pptx-live-dock">
                <div class="pptx-live-dock-cluster">
                    <button type="button" class="pptx-live-btn" @click.stop="goPrev">{{ $t('pptxLivePrev') }}</button>
                    <button type="button" class="pptx-live-btn" @click.stop="advance">{{ $t('pptxLiveNext') }}</button>
                    <button type="button" class="pptx-live-btn pptx-live-btn-primary" @click.stop="togglePresent">{{ $t('pptxLivePresent') }}</button>
                </div>
                <span class="pptx-live-status">{{ statusText }}</span>
            </footer>
        </div>
    </div>
</template>
<script>
    import { createPptxLivePlayer } from '@repository/utils/pptxLivePreview'

    export default {
        name: 'PptxLivePreview',
        props: {
            bytes: {
                type: [Uint8Array, ArrayBuffer],
                required: true
            },
            fileName: {
                type: String,
                default: ''
            },
            mediaBaseUrl: {
                type: String,
                default: ''
            }
        },
        data () {
            return {
                status: null,
                player: null,
                resizeObserver: null,
                presenting: false,
                hadFullscreen: false
            }
        },
        computed: {
            statusText () {
                if (!this.status) {
                    return this.$t('pptxLiveHint')
                }
                return this.$t('pptxLivePage', [this.status.slide, this.status.total])
            },
            showEnded () {
                return Boolean(this.status && this.status.extra === 'ended')
            }
        },
        watch: {
            bytes: {
                handler () {
                    this.reload()
                }
            }
        },
        mounted () {
            document.addEventListener('fullscreenchange', this.onFullscreenChange)
            document.addEventListener('webkitfullscreenchange', this.onFullscreenChange)
            window.addEventListener('keydown', this.onKeydown)
            if (typeof ResizeObserver !== 'undefined' && this.$refs.stageWrap) {
                this.resizeObserver = new ResizeObserver(() => {
                    if (this.player) {
                        this.player.resize()
                    }
                })
                this.resizeObserver.observe(this.$refs.stageWrap)
            }
            this.reload()
        },
        beforeDestroy () {
            this.teardown()
        },
        methods: {
            toBytes () {
                if (this.bytes instanceof ArrayBuffer) {
                    return new Uint8Array(this.bytes)
                }
                return this.bytes
            },
            async reload () {
                this.teardownPlayer()
                const wrap = this.$refs.stageWrap
                if (!wrap || !this.bytes) {
                    return
                }
                this.player = createPptxLivePlayer(wrap, {
                    fileName: this.fileName,
                    mediaBaseUrl: this.mediaBaseUrl,
                    onStatus: (status) => {
                        this.status = status
                    }
                })
                try {
                    await this.player.load(this.toBytes())
                    this.$nextTick(() => {
                        if (this.player) {
                            this.player.attachThumbRail(this.$refs.thumbsHost)
                            this.player.resize()
                        }
                    })
                } catch (error) {
                    this.$emit('error', error)
                }
            },
            advance () {
                if (this.player) {
                    this.player.advance()
                }
            },
            goPrev () {
                if (this.player) {
                    this.player.goPrev()
                }
            },
            isOurFullscreen () {
                const el = document.fullscreenElement || document.webkitFullscreenElement
                return Boolean(el && this.$el && this.$el.contains(el))
            },
            syncPlayerSize () {
                this.$nextTick(() => {
                    if (this.player) {
                        this.player.resize()
                    }
                })
            },
            setPresenting (presenting) {
                this.presenting = presenting
                document.documentElement.classList.toggle('pptx-live-presenting', presenting)
                this.syncPlayerSize()
            },
            onFullscreenChange () {
                const inFs = this.isOurFullscreen()
                if (this.hadFullscreen && !inFs && this.presenting) {
                    this.hadFullscreen = false
                    this.setPresenting(false)
                    return
                }
                this.hadFullscreen = inFs
                this.syncPlayerSize()
            },
            onStageClick () {
                if (this.showEnded) {
                    this.advance()
                }
            },
            onKeydown (event) {
                if (event.code === 'Escape' && this.presenting) {
                    event.preventDefault()
                    this.exitPresent()
                    return
                }
                if (!this.showEnded) {
                    return
                }
                if (event.code === 'Space' || event.code === 'Enter' || event.code === 'ArrowRight') {
                    event.preventDefault()
                    this.advance()
                }
            },
            enterPresent () {
                this.setPresenting(true)
                const root = this.$el
                const enter = root && (root.requestFullscreen || root.webkitRequestFullscreen)
                const replay = this.player
                    ? this.player.replayIfIdle()
                    : Promise.resolve()
                replay.then(() => {
                    if (!enter || !this.presenting) {
                        return
                    }
                    return enter.call(root)
                }).catch(function () {})
            },
            exitPresent () {
                this.hadFullscreen = false
                this.setPresenting(false)
                if (!this.isOurFullscreen()) {
                    return
                }
                const exit = document.exitFullscreen || document.webkitExitFullscreen
                if (exit) {
                    Promise.resolve(exit.call(document)).catch(function () {})
                }
            },
            togglePresent () {
                if (this.presenting) {
                    this.exitPresent()
                    return
                }
                this.enterPresent()
            },
            teardownPlayer () {
                if (this.player) {
                    this.player.destroy()
                    this.player = null
                }
            },
            teardown () {
                this.exitPresent()
                document.removeEventListener('fullscreenchange', this.onFullscreenChange)
                document.removeEventListener('webkitfullscreenchange', this.onFullscreenChange)
                window.removeEventListener('keydown', this.onKeydown)
                if (this.resizeObserver) {
                    this.resizeObserver.disconnect()
                    this.resizeObserver = null
                }
                this.teardownPlayer()
            }
        }
    }
</script>
<style lang="scss" scoped>
.pptx-live-preview {
    position: fixed;
    inset: 0;
    display: flex;
    flex-direction: row;
    background: #111;
    color: #eee;
    z-index: 10000000;
}
.pptx-live-thumbs {
    flex: none;
    width: 176px;
    min-width: 176px;
    min-height: 0;
    overflow: hidden;
    background: #161616;
    border-right: 1px solid #333;
}
.pptx-live-thumbs ::v-deep .pptxv-thumbs {
    display: flex !important;
    width: 100%;
    height: 100%;
    border-right: 0;
    background: transparent;
}
.pptx-live-thumbs ::v-deep .pptxv-thumbs-footer,
.pptx-live-thumbs ::v-deep .pptxv-thumbs-add,
.pptx-live-thumbs ::v-deep .pptxv-thumb-section-actions {
    display: none !important;
}
.pptx-live-main {
    flex: 1;
    min-width: 0;
    min-height: 0;
    display: flex;
    flex-direction: column;
}
.pptx-live-dock {
    position: relative;
    display: flex;
    align-items: center;
    justify-content: center;
    flex: none;
    gap: 12px;
    padding: 10px 16px;
    background: #1c1c1c;
    border-top: 1px solid #333;
}
.pptx-live-dock-cluster {
    display: flex;
    gap: 8px;
    align-items: center;
}
.pptx-live-btn {
    font: inherit;
    padding: 6px 12px;
    border: 1px solid #555;
    border-radius: 4px;
    background: #2a2a2a;
    color: #eee;
    cursor: pointer;
}
.pptx-live-btn-primary {
    background: #2b6cb0;
    border-color: #2b6cb0;
    color: #fff;
}
.pptx-live-status {
    position: absolute;
    right: 16px;
    font-size: 13px;
    color: #ccc;
}
.pptx-live-stage {
    flex: 1;
    min-height: 0;
    position: relative;
    overflow: hidden;
    background: #0b0b0b;
}
.pptx-live-stage > * {
    width: 100%;
    height: 100%;
}
.pptx-live-preview.is-presenting {
    background: #000;
}
.pptx-live-preview.is-presenting .pptx-live-thumbs,
.pptx-live-preview.is-presenting .pptx-live-dock {
    display: none !important;
}
.pptx-live-preview.is-presenting .pptx-live-stage {
    background: #000;
}
.pptx-live-stage ::v-deep .pptxv-ribbon,
.pptx-live-stage ::v-deep .pptxv-titlebar,
.pptx-live-stage ::v-deep .pptxv-statusbar,
.pptx-live-stage ::v-deep .pptxv-format-toolbar,
.pptx-live-stage ::v-deep .pptxv-inspector,
.pptx-live-stage ::v-deep .pptxv-notes,
.pptx-live-stage ::v-deep .pptxv-comments,
.pptx-live-stage ::v-deep .pptxv-qat,
.pptx-live-stage ::v-deep .pptxv-compat-toasts-dismiss-all {
    display: none !important;
}
.pptx-live-preview.is-presenting ::v-deep .pptxv-present-toolbar-wrap,
.pptx-live-preview.is-presenting ::v-deep .pptxv-present-toolbar,
.pptx-live-preview.is-presenting ::v-deep .pptxv-presentation-touch-controls,
.pptx-live-preview.is-presenting ::v-deep .pptxv-showmenu,
.pptx-live-preview.is-presenting ::v-deep .pptxv-endshow,
.pptx-live-preview.is-presenting ::v-deep .pptxv-info-overlay,
.pptx-live-preview.is-presenting ::v-deep .pptxv-context-menu,
.pptx-live-preview.is-presenting ::v-deep .pptxv-presenter {
    display: none !important;
    visibility: hidden !important;
    opacity: 0 !important;
    pointer-events: none !important;
}
</style>
<style lang="scss">
html.pptx-live-presenting .pptxv-present-toolbar-wrap,
html.pptx-live-presenting .pptxv-present-toolbar,
html.pptx-live-presenting .pptxv-presentation-touch-controls,
html.pptx-live-presenting .pptxv-showmenu,
html.pptx-live-presenting .pptxv-endshow,
html.pptx-live-presenting .pptxv-info-overlay,
html.pptx-live-presenting .pptxv-context-menu,
html.pptx-live-presenting .pptxv-presenter,
.pptx-live-preview .pptxv-compat-toasts-dismiss-all {
    display: none !important;
    visibility: hidden !important;
    opacity: 0 !important;
    pointer-events: none !important;
}
.pptx-live-preview:not(.is-presenting) .pptxv-thumbs {
    display: flex !important;
}
.pptx-live-stage .pptxv-media.pptx-live-has-audio {
    overflow: visible;
    min-width: 280px;
    min-height: 40px;
    z-index: 8;
    pointer-events: auto;
}
.pptx-live-stage .pptxv-media.pptx-live-has-audio audio.pptxv-media-audio {
    display: block;
    width: 100%;
    height: 40px;
    min-height: 40px;
    color-scheme: dark;
}
.pptx-live-stage .pptxv-media.pptx-live-has-video {
    overflow: visible !important;
    z-index: 20;
    pointer-events: auto !important;
}
.pptx-live-stage .pptxv-media.pptx-live-has-video video.pptxv-media-video {
    display: block;
    width: 100%;
    height: 100%;
    color-scheme: dark;
    pointer-events: auto !important;
}
.pptx-live-stage .pptxv-media.pptx-live-has-video video.pptxv-media-video::-webkit-media-controls,
.pptx-live-stage .pptxv-media.pptx-live-has-audio audio.pptxv-media-audio::-webkit-media-controls {
    display: flex !important;
    opacity: 1 !important;
    visibility: visible !important;
}
</style>
