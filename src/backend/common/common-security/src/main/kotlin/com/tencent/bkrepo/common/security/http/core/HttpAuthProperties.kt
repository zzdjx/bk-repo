package com.tencent.bkrepo.common.security.http.core

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties("security.auth")
data class HttpAuthProperties(
    /**
     * 是否开启权限校验。
     * 关闭后 [com.tencent.bkrepo.common.metadata.permission.PermissionManager] 直接放行，
     * 不作为 HTTP 鉴权 Handler 的总闸。
     */
    var enabled: Boolean = true,
    /**
     * 是否启用管理员缓存
     */
    var adminCacheEnabled: Boolean = true,
    /**
     * 是否启用 Basic 认证。改配置后需重启。
     */
    var basicEnabled: Boolean = true,
    /**
     * 是否启用 Platform 认证。误关会影响平台账号调用。改配置后需重启。
     */
    var platformEnabled: Boolean = true,
    /**
     * 是否启用 JWT 认证。关闭后不注册内置 JwtAuthHandler，不影响各服务自有 Bearer 逻辑。
     * 使用 jwt-enabled，避免与 security.auth.jwt.* 对象前缀冲突。改配置后需重启。
     */
    var jwtEnabled: Boolean = true,
    /**
     * 是否启用 OAuth 认证。关闭后不注册内置 OauthAuthHandler / JWT 内的 OAuth 回落。改配置后需重启。
     */
    var oauthEnabled: Boolean = false,
    /**
     * 是否启用 Temporary Token 认证。仅作用于公共 HTTP 鉴权链，Auth 服务拦截器无此分支。改配置后需重启。
     */
    var temporaryTokenEnabled: Boolean = true,
    /**
     * 是否启用 Sign 认证。误关会影响集群间签名调用。改配置后需重启。
     */
    var signEnabled: Boolean = true,
)
