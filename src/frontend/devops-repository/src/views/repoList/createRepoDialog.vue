<template>
    <canway-dialog
        v-model="show"
        width="900"
        height-num="603"
        :title="title"
        @cancel="cancel">
        <bk-form class="mr10 repo-base-info" :label-width="150" :model="repoBaseInfo" :rules="rules" ref="repoBaseInfo">
            <bk-form-item :label="$t('repoType')" :required="true" property="type" error-display-type="normal">
                <bk-radio-group v-model="repoBaseInfo.type" class="repo-type-radio-group" @change="changeRepoType">
                    <bk-radio-button v-for="repo in repoEnum" :key="repo.label" :value="repo.value">
                        <div class="flex-column flex-center repo-type-radio">
                            <Icon size="32" :name="repo.value" />
                            <span>{{repo.label}}</span>
                        </div>
                    </bk-radio-button>
                </bk-radio-group>
            </bk-form-item>
            <bk-form-item :label="$t('repoName')" :required="true" property="name" error-display-type="normal">
                <bk-input class="w480" v-model.trim="repoBaseInfo.name" maxlength="32" show-word-limit
                    :placeholder="$t(repoBaseInfo.type === 'docker' ? 'repoDockerNamePlaceholder' : 'repoNamePlaceholder')">
                </bk-input>
                <div v-if="repoBaseInfo.type === 'docker'" class="form-tip">{{ $t('dockerRepoTip')}}</div>
            </bk-form-item>
            <bk-form-item :label="$t('accessPermission')">
                <card-radio-group
                    class="permission-card"
                    v-model="available"
                    :list="availableList">
                </card-radio-group>
            </bk-form-item>
            <bk-form-item :label="$t('isDisplay')">
                <bk-radio-group v-model="repoBaseInfo.display">
                    <bk-radio class="mr20" :value="true">{{ $t('enable') }}</bk-radio>
                    <bk-radio :value="false">{{ $t('disable') }}</bk-radio>
                </bk-radio-group>
            </bk-form-item>
            <bk-form-item
                :label="$t('bkPermissionCheck')"
                v-if="!specialRepoEnum.includes(repoBaseInfo.name) && rbacStatus">
                <bk-radio-group v-model="repoBaseInfo.configuration.settings.bkiamv3Check">
                    <bk-radio class="mr20" :value="true">{{ $t('open') }}</bk-radio>
                    <bk-radio :value="false">{{ $t('close') }}</bk-radio>
                </bk-radio-group>
            </bk-form-item>
            <template v-if="repoBaseInfo.type === 'rpm'">
                <bk-form-item :label="$t('enabledFileLists')">
                    <bk-checkbox v-model="repoBaseInfo.enabledFileLists"></bk-checkbox>
                </bk-form-item>
                <bk-form-item :label="$t('repodataDepth')" property="repodataDepth" error-display-type="normal">
                    <bk-input class="w480" v-model.trim="repoBaseInfo.repodataDepth"></bk-input>
                </bk-form-item>
                <bk-form-item :label="$t('groupXmlSet')" property="groupXmlSet" error-display-type="normal">
                    <bk-tag-input
                        class="w480"
                        :value="repoBaseInfo.groupXmlSet"
                        @change="(val) => {
                            repoBaseInfo.groupXmlSet = val.map(v => {
                                return v.replace(/^([^.]*)(\.xml)?$/, '$1.xml')
                            })
                        }"
                        :list="[]"
                        trigger="focus"
                        :clearable="false"
                        allow-create
                        has-delete-icon>
                    </bk-tag-input>
                </bk-form-item>
            </template>
            <bk-form-item :label="$t('description')">
                <bk-input type="textarea"
                    class="w480"
                    maxlength="200"
                    :rows="6"
                    v-model.trim="repoBaseInfo.description"
                    :placeholder="$t('repoDescriptionPlaceholder')">
                </bk-input>
            </bk-form-item>
        </bk-form>
        <template #footer>
            <bk-button @click="cancel">{{$t('cancel')}}</bk-button>
            <bk-button class="ml10" :loading="loading" theme="primary" @click="confirm">{{$t('confirm')}}</bk-button>
        </template>
        <iam-deny-dialog :visible.sync="showIamDenyDialog" :show-data="showData"></iam-deny-dialog>
    </canway-dialog>
</template>
<script>
    import CardRadioGroup from '@repository/components/CardRadioGroup'
    import iamDenyDialog from '@repository/components/IamDenyDialog/IamDenyDialog'
    import { repoEnum, specialRepoEnum } from '@repository/store/publicEnum'
    import { mapActions, mapState } from 'vuex'

    const getRepoBaseInfo = () => {
        return {
            type: 'generic',
            name: '',
            public: false,
            system: false,
            enabledFileLists: false,
            repodataDepth: 0,
            interceptors: [],
            groupXmlSet: [],
            description: '',
            display: true,
            mobile: {
                enable: false,
                filename: '',
                metadata: ''
            },
            web: {
                enable: false,
                filename: '',
                metadata: ''
            },
            ip_segment: {
                enable: false,
                officeNetwork: false,
                ipSegment: '',
                whitelistUser: ''
            },
            configuration: {
                settings: {
                    bkiamv3Check: false
                }
            }
        }
    }

    export default {
        name: 'createRepo',
        components: { CardRadioGroup, iamDenyDialog },
        data () {
            return {
                repoEnum,
                specialRepoEnum,
                show: false,
                loading: false,
                repoBaseInfo: getRepoBaseInfo(),
                showIamDenyDialog: false,
                showData: {},
                title: this.$t('createRepository'),
                rbacStatus: false,
                accessControl: 'DEFAULT'
            }
        },
        computed: {
            ...mapState(['userInfo']),
            projectId () {
                return this.$route.params.projectId
            },
            isCommunity () {
                // 是否为社区版
                return RELEASE_MODE === 'community'
            },
            genericInterceptorsList () {
                return this.isCommunity ? ['mobile', 'web'] : ['mobile', 'web', 'ip_segment']
            },
            rules () {
                const filenameRule = [
                    {
                        required: true,
                        message: this.$t('pleaseFileName'),
                        trigger: 'blur'
                    }
                ]
                const metadataRule = [
                    {
                        required: true,
                        message: this.$t('pleaseMetadata'),
                        trigger: 'blur'
                    },
                    {
                        regex: /^[^\s]+:[^\s]+/,
                        message: this.$t('metadataRule'),
                        trigger: 'blur'
                    }
                ]
                const ipSegmentRule = [
                    {
                        required: true,
                        message: this.$t('pleaseIpSegment'),
                        trigger: 'blur'
                    },
                    {
                        validator: function (val) {
                            const ipList = val.split(',')
                            return ipList.every(ip => {
                                if (!ip) return true
                                return ipList.every(ip => /(([0-9]|[1-9][0-9]|1[0-9]{2}|2[0-4][0-9]|25[0-5])\.){3}([0-9]|[1-9][0-9]|1[0-9]{2}|2[0-4][0-9]|25[0-5])(\b\/([0-9]|[1-2][0-9]|3[0-2])\b)/.test(ip))
                            })
                        },
                        message: this.$t('ipSegmentRule'),
                        trigger: 'blur'
                    }
                ]
                return {
                    type: [
                        {
                            required: true,
                            message: this.$t('pleaseSelect') + this.$t('repoType'),
                            trigger: 'blur'
                        }
                    ],
                    name: [
                        {
                            required: true,
                            message: this.$t('pleaseInput') + this.$t('repoName'),
                            trigger: 'blur'
                        },
                        {
                            regex: this.repoBaseInfo.type === 'docker' ? /^[a-z][a-z0-9\-_]{1,31}$/ : /^[a-zA-Z][a-zA-Z0-9\-_]{1,31}$/,
                            message: this.$t(this.repoBaseInfo.type === 'docker' ? 'repoDockerNamePlaceholder' : 'repoNamePlaceholder'),
                            trigger: 'blur'
                        },
                        {
                            validator: this.asynCheckRepoName,
                            message: this.$t('repoName') + ' ' + this.$t('exist'),
                            trigger: 'blur'
                        }
                    ],
                    repodataDepth: [
                        {
                            regex: /^(0|[1-9][0-9]*)$/,
                            message: this.$t('pleaseInput') + this.$t('legit') + this.$t('repodataDepth'),
                            trigger: 'blur'
                        }
                    ],
                    groupXmlSet: [
                        {
                            validator: arr => {
                                return arr.every(v => {
                                    return /\.xml$/.test(v)
                                })
                            },
                            message: this.$t('pleaseInput') + this.$t('legit') + this.$t('groupXmlSet') + `(.xml${this.$t('type')})`,
                            trigger: 'change'
                        }
                    ],
                    'mobile.filename': filenameRule,
                    'mobile.metadata': metadataRule,
                    'web.filename': filenameRule,
                    'web.metadata': metadataRule,
                    'ip_segment.ipSegment': this.repoBaseInfo.ip_segment.officeNetwork ? {} : ipSegmentRule
                }
            },
            available: {
                get () {
                    if (this.repoBaseInfo.public) return 'public'
                    if (this.accessControl === 'DIR_CTRL') return 'folder'
                    if (this.accessControl === 'STRICT') return 'strict'
                    return 'default'
                },
                set (val) {
                    if (val === 'public') {
                        this.repoBaseInfo.public = true
                        this.accessControl = null
                    } else if (val === 'folder') {
                        this.repoBaseInfo.public = false
                        this.accessControl = 'DIR_CTRL'
                    } else if (val === 'strict') {
                        this.repoBaseInfo.public = false
                        this.accessControl = 'STRICT'
                    } else {
                        this.repoBaseInfo.public = false
                        this.accessControl = 'DEFAULT'
                    }
                }
            },
            availableList () {
                if (this.repoBaseInfo.type === 'generic' || this.repoBaseInfo.type === 'ddc') {
                    return [
                        { label: this.$t('permissionTitle.default'), value: 'default', tip: this.$t('permissionTip.default') },
                        { label: this.$t('permissionTitle.strict'), value: 'strict', tip: this.$t('permissionTip.strict') },
                        { label: this.$t('permissionTitle.folder'), value: 'folder', tip: this.$t('permissionTip.folder') },
                        { label: this.$t('permissionTitle.public'), value: 'public', tip: this.$t('permissionTip.public') }
                    ]
                } else {
                    return [
                        { label: this.$t('permissionTitle.default'), value: 'default', tip: this.$t('permissionTip.default') },
                        { label: this.$t('permissionTitle.public'), value: 'public', tip: this.$t('permissionTip.public') }
                    ]
                }
            }
        },
        methods: {
            ...mapActions(['createRepo', 'checkRepoName', 'getPermissionUrl', 'getIamPermissionStatus', 'createOrUpdateRootPermission']),
            showDialogHandler () {
                this.show = true
                this.repoBaseInfo = getRepoBaseInfo()
                this.$refs.repoBaseInfo && this.$refs.repoBaseInfo.clearError()
                this.getIamPermissionStatus().then(res => {
                    this.rbacStatus = res
                })
            },
            cancel () {
                this.show = false
            },
            asynCheckRepoName () {
                return this.checkRepoName({
                    projectId: this.projectId,
                    name: this.repoBaseInfo.name
                }).then(res => !res)
            },
            changeRepoType () {
                if (this.repoBaseInfo.type === 'docker') this.repoBaseInfo.name = ''
                this.$refs.repoBaseInfo.clearError()
            },
            async confirm () {
                await this.$refs.repoBaseInfo.validate()
                const interceptors = []
                if (this.repoBaseInfo.type === 'generic' || this.repoBaseInfo.type === 'ddc') {
                    ['mobile', 'web', 'ip_segment'].forEach(type => {
                        const { enable, filename, metadata, ipSegment, whitelistUser, officeNetwork } = this.repoBaseInfo[type]
                        if (['mobile', 'web'].includes(type)) {
                            enable && interceptors.push({
                                type: type.toUpperCase(),
                                rules: { filename, metadata }
                            })
                        } else {
                            enable && interceptors.push({
                                type: type.toUpperCase(),
                                rules: {
                                    ipSegment: ipSegment.split(','),
                                    whitelistUser: whitelistUser.split(','),
                                    officeNetwork
                                }
                            })
                        }
                    })
                }
                const body = {
                    projectId: this.projectId,
                    type: this.repoBaseInfo.type.toUpperCase(),
                    name: this.repoBaseInfo.name,
                    public: this.repoBaseInfo.public,
                    display: this.repoBaseInfo.display,
                    description: this.repoBaseInfo.description,
                    category: this.repoBaseInfo.type === 'generic' ? 'LOCAL' : 'COMPOSITE',
                    configuration: {
                        type: this.repoBaseInfo.type === 'generic' ? 'local' : 'composite',
                        settings: {
                            system: this.repoBaseInfo.system,
                            interceptors: interceptors.length ? interceptors : undefined,
                            ...(
                                this.repoBaseInfo.type === 'rpm'
                                    ? {
                                        enabledFileLists: this.repoBaseInfo.enabledFileLists,
                                        repodataDepth: this.repoBaseInfo.repodataDepth,
                                        groupXmlSet: this.repoBaseInfo.groupXmlSet
                                    }
                                    : {}
                            )
                        }
                    }
                }
                if (!specialRepoEnum.includes(this.repoBaseInfo.name)) {
                    body.configuration.settings.bkiamv3Check = this.repoBaseInfo.configuration.settings.bkiamv3Check
                }
                this.loading = true
                this.createRepo({
                    body: body
                }).then(() => {
                    this.saveRepoMode()
                }).catch(err => {
                    if (err.status === 403) {
                        this.getPermissionUrl({
                            body: {
                                projectId: this.projectId,
                                action: 'WRITE',
                                resourceType: 'PROJECT',
                                uid: this.userInfo.name
                            }
                        }).then(res => {
                            if (res !== '') {
                                this.showIamDenyDialog = true
                                this.showData = {
                                    projectId: this.projectId,
                                    repoName: '',
                                    action: 'WRITE',
                                    url: res
                                }
                            } else {
                                this.$bkMessage({
                                    theme: 'error',
                                    message: err.message
                                })
                            }
                        })
                    }
                }).finally(() => {
                    this.loading = false
                })
            },
            saveRepoMode () {
                const body = {
                    projectId: this.projectId,
                    repoName: this.repoBaseInfo.name,
                    accessControlMode: this.accessControl
                }
                this.createOrUpdateRootPermission({
                    body: body
                }).then(() => {
                    this.$bkMessage({
                        theme: 'success',
                        message: this.$t('create') + this.$t('space') + this.$t('repository') + this.$t('space') + this.$t('success')
                    })
                    this.cancel()
                    this.$emit('refresh')
                }).catch(() => {
                    this.$bkMessage({
                        theme: 'error',
                        message: this.$t('create') + this.$t('space') + this.$t('repository') + this.$t('space') + this.$t('abnormal')
                    })
                    this.cancel()
                    this.$emit('refresh')
                })
            }
        }
    }
</script>
<style lang="scss" scoped>
.repo-base-info {
    max-height: 442px;
    overflow-y: auto;
    .repo-type-radio-group {
        display: grid;
        grid-template: auto / repeat(6, 80px);
        gap: 20px;
        ::v-deep .bk-form-radio-button {
            .bk-radio-button-text {
                height: auto;
                line-height: initial;
                padding: 0;
                border-radius: 2px;
            }
        }
        .repo-type-radio {
            position: relative;
            padding: 5px;
            width: 80px;
            height: 60px;
        }
    }
    .member-selector{
        ::v-deep.bk-tag-selector .bk-tag-input {
            height: auto;
        }
        width: 250px;
    }
    .permission-card {
        ::v-deep .bk-form-radio-button {
            margin-top: 10px;
            .card-radio {
                min-width: 180px;
                max-width: 315px;
                height: 90px;
                .card-tip {
                    word-break: break-all;
                    white-space: normal;
                    width: 300px;
                }
            }
            .bk-radio-button-text {
                height: auto;
                line-height: initial;
                padding: 0;
                border: 0 none;
            }
        }
    }
}
</style>
