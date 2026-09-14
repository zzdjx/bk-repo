--[[
Tencent is pleased to support the open source community by making BK-CI 蓝鲸持续集成平台 available.

Copyright (C) 2019 Tencent.  All rights reserved.

BK-CI 蓝鲸持续集成平台 is licensed under the MIT license.

A copy of the MIT License is included in this file.


Terms of the MIT License:
---------------------------------------------------
Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated documentation files (the "Software"), to deal in the Software without restriction, including without limitation the rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the Software, and to permit persons to whom the Software is furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
]]

_M = {}
function _M:verify_mobile_gateway()
    --- 移动网关登录对接(下面ms表示mobile site的意思)
    local ms_timestamp = ngx.var.http_timestamp
    local ms_signature = ngx.var.http_signature
    local ms_staffid = ngx.var.http_staffid
    local ms_staffname = ngx.var.http_staffname
    local ms_x_ext_data = ngx.var.http_x_ext_data
    local ms_x_rio_seq = ngx.var.http_x_rio_seq
    if ms_timestamp ~= nil and ms_signature ~= nil and ms_staffid ~= nil and ms_staffname ~= nil
            and ms_x_ext_data ~= nil and ms_x_rio_seq ~= nil then
        local timestamp = ngx.time()
        local absTime = math.abs(tonumber(ms_timestamp) - timestamp)
        if absTime < 180 then
            local token = config.mobileSiteToken
            local input = ms_timestamp .. token .. ms_x_rio_seq .. "," .. ms_staffid .. "," .. ms_staffname .. "," ..
                    ms_x_ext_data .. ms_timestamp
            local resty_sha256 = require "resty.sha256"
            local resty_str = require "resty.string"
            local sha256 = resty_sha256:new()
            sha256:update(input)
            local digest = sha256:final()
            local my_signature = string.upper(resty_str.str_to_hex(digest))
            if ms_signature == my_signature then
                return ms_staffname
            end
        end
    end
    return nil
end

--- ticket 模式：GET {bk_login_url}/user/get_info?bk_ticket=，只取 username。
--- bk_login_url 配登录服务根地址，path 写在代码里。oauth.url 留给 odc/太湖登录。
--- 成功码是 ret==0，用户名在 data.username。
function _M:verify_bk_ticket(bk_ticket)
    local user_cache = ngx.shared.user_info_store
    local user_cache_value = user_cache:get(bk_ticket)
    if user_cache_value ~= nil then
        return user_cache_value
    end

    local base = config.bk_login_url or ""
    local qpos = string.find(base, "?", 1, true)
    if qpos then
        base = string.sub(base, 1, qpos - 1)
    end
    if string.sub(base, -1) == "/" then
        base = string.sub(base, 1, -2)
    end
    local addr = base .. "/user/get_info?bk_ticket=" .. ngx.escape_uri(bk_ticket)

    local http_cli = http.new()
    --- 分阶段设置超时：连接超时2s，发送超时3s，读取超时10s
    http_cli:set_timeouts(2000, 3000, 10000)
    local res, err = http_cli:request_uri(addr, {
        method = "GET",
        ssl_verify = false,
        headers = {
            ["Accept"] = "application/json"
        }
    })
    if not res then
        ngx.log(ngx.ERR, "failed to request login get_info: ", err)
        ngx.exit(401)
        return
    end
    if res.status ~= 200 then
        ngx.log(ngx.STDERR, "failed to request login get_info, status: ", res.status)
        ngx.exit(401)
        return
    end
    local result = json.decode(res.body)
    if result == nil then
        ngx.log(ngx.ERR, "failed to parse login get_info response")
        ngx.exit(401)
        return
    end
    if result.ret ~= 0 then
        ngx.log(ngx.INFO, "invalid bk_ticket, ret: ", tostring(result.ret), " msg: ", tostring(result.msg))
        ngx.exit(401)
        return
    end
    if result.data == nil or result.data.username == nil or result.data.username == "" then
        ngx.log(ngx.ERR, "invalid login get_info response, missing username")
        ngx.exit(401)
        return
    end
    user_cache_value = result.data.username
    user_cache:set(bk_ticket, user_cache_value, 180)
    return user_cache_value
end

function _M:verify_ticket(bk_ticket, input_type)
    return self:verify_bk_ticket(bk_ticket)
end

function _M:verify_bk_token(auth_url, token, force_refresh)
    local user_cache = ngx.shared.user_info_store
    --- force_refresh 为 true 时跳过本地缓存，直接向远端拉取最新用户信息（用于时区等信息变更后主动刷新）
    local user_cache_value = nil
    if not force_refresh then
        user_cache_value = user_cache:get(token)
    end
    if user_cache_value == nil then
        local http_cli = http.new()
        local oauth = config.oauth
        local query = "bk_token=" .. token
        local addr = "http://" .. auth_url .. "/api/c/compapi/v2/bk_login/get_user/?" .. query
        local auth_content = '{"bk_app_code":"' .. oauth.app_code .. '","bk_app_secret":"' .. oauth.app_secret .. '","bk_token":"' .. token .. '"}'
        --- 开始连接
        http_cli:set_timeout(3000)
        http_cli:connect(addr)
        --- 发送请求
        local res, err = http_cli:request_uri(addr, {
            method = "GET",
            ssl_verify = false,
            headers = {
                ["X-Bkapi-Authorization"] = auth_content
            }
        })
        --- 判断是否出错了
        if not res then
            ngx.log(ngx.ERR, "failed to request apigw: error", err)
            ngx.exit(401)
            return
        end
        --- 判断返回的状态码是否是200
        if res.status ~= 200 then
            ngx.log(ngx.STDERR, "failed to request apigw, status: ", res.status)
            ngx.exit(401)
            return
        end
        --- 转换JSON的返回数据为TABLE
        local result = json.decode(res.body)
        --- 判断JSON转换是否成功
        if result == nil then
            ngx.log(ngx.ERR, "failed to parse apigw  response：", res.body)
            ngx.exit(401)
            return
        end

        --- 判断返回码:Q!
        if result.code ~= 0 then
            if result.code == 1302403 then
                ngx.log(ngx.ERR, "is_login code is 1302403 , need Authentication")
                ngx.header["X-DEVOPS-ERROR-RETURN"] = '{"code": 440,"message": "' .. result.message .. '", "data": 1302403,"traceId":null }'
                ngx.header["X-DEVOPS-ERROR-STATUS"] = 440
                ngx.exit(401)
            end
            ngx.log(ngx.INFO, "invalid user token: ", result.message)
            ngx.exit(401)
            return
        end
        local cache_data = {
            ["bk_username"] = result.data.bk_username,
            ["time_zone"] = result.data.time_zone
        }
        user_cache:set(token, json.encode(cache_data), 180)
        return result.data.bk_username, result.data.time_zone
    end
    --- 兼容旧缓存（仅字符串 bk_username 未做 json 序列化），此时无 time_zone
    local ok, user_data = pcall(json.decode, user_cache_value)
    if not ok or type(user_data) ~= "table" then
        return user_cache_value, nil
    end
    return user_data.bk_username, user_data.time_zone
end

function _M:verify_bk_token_muti_tenant(auth_url, token, force_refresh)
    local user_cache = ngx.shared.user_info_store
    --- force_refresh 为 true 时跳过本地缓存，直接向远端拉取最新用户信息（用于租户切换后主动刷新）
    local user_cache_value = nil
    if not force_refresh then
        user_cache_value = user_cache:get(token)
    end
    if user_cache_value == nil then
        local http_cli = http.new()
        local oauth = config.oauth
        local query = "bk_token=" .. token
        local addr = "http://" .. auth_url .. "/api/bk-login/prod/login/api/v3/open/bk-tokens/userinfo/?" .. query
        local auth_content = '{"bk_app_code":"' .. oauth.app_code .. '","bk_app_secret":"' .. oauth.app_secret .. '","bk_token":"' .. token .. '"}'
        --- 开始连接
        http_cli:set_timeout(3000)
        http_cli:connect(addr)
        --- 发送请求
        local res, err = http_cli:request_uri(addr, {
            method = "GET",
            ssl_verify = false,
            headers = {
                ["X-Bkapi-Authorization"] = auth_content,
                ["X-Bk-Tenant-Id"] = config.op_tenant_id
            }
        })
        --- 判断是否出错了
        if not res then
            ngx.log(ngx.ERR, "failed to request apigw: error", err)
            ngx.exit(401)
            return
        end
        --- 判断返回的状态码是否是200
        if res.status ~= 200 then
            ngx.log(ngx.STDERR, "failed to request apigw, status: ", res.status)
            ngx.exit(401)
            return
        end
        --- 转换JSON的返回数据为TABLE
        local result = json.decode(res.body)
        --- 判断JSON转换是否成功
        if result == nil then
            ngx.log(ngx.ERR, "failed to parse apigw  response：", res.body)
            ngx.exit(401)
            return
        end

        --- 判断返回码：HTTP 200 不代表验票成功，bk-login 对无效 token 也可能带用户字段
        if result.code ~= 0 then
            if result.code == 1302403 then
                ngx.log(ngx.ERR, "is_login code is 1302403 , need Authentication")
                ngx.header["X-DEVOPS-ERROR-RETURN"] = '{"code": 440,"message": "' .. result.message .. '", "data": 1302403,"traceId":null }'
                ngx.header["X-DEVOPS-ERROR-STATUS"] = 440
                ngx.exit(401)
            end
            ngx.log(ngx.INFO, "invalid user token: ", result.message)
            ngx.exit(401)
            return
        end
        if result.data == nil or result.data.bk_username == nil or result.data.bk_username == "" then
            ngx.log(ngx.ERR, "invalid apigw userinfo response, missing bk_username: ", res.body)
            ngx.exit(401)
            return
        end

        local cache_data = {
            ["bk_username"] = result.data.bk_username,
            ["display_name"] = result.data.display_name,
            ["tenant_id"] = result.data.tenant_id,
            ["time_zone"] = result.data.time_zone
        }
        user_cache:set(token, json.encode(cache_data), 180)
        return result.data.bk_username, result.data.display_name, result.data.tenant_id, result.data.time_zone
    else
        local user_data = json.decode(user_cache_value)
        return user_data.bk_username, user_data.display_name, user_data.tenant_id, user_data.time_zone
    end
end

function _M:verify_bkrepo_token(bkrepo_login_token)
    local user_cache = ngx.shared.user_info_store
    local user_cache_value = user_cache:get(bkrepo_login_token)
    if user_cache_value == nil then
        --- 初始化HTTP连接
        local httpc = http.new()
        local addr = "http://" .. hostUtil:get_addr("auth")
        local path = "/api/user/verify?bkrepo_ticket=" .. bkrepo_login_token
        if config.service_name ~= nil and config.service_name ~= "" then
            path = "/auth" .. path
        end

        --- 开始连接
        httpc:set_timeout(3000)
        httpc:connect(addr)
        --- 发送请求
        local res, err = httpc:request_uri(addr, {
            path = path,
            method = "GET",
            headers = {
                ["authorization"] = config.bkrepo.authorization,
                ["Accept"] = "application/json",
                ["Content-Type"] = "application/json",
            }
        })
        --- 判断是否出错了
        if not res then
            ngx.log(ngx.ERR, "failed to request verify_bkrepo_token: ", err)
            ngx.exit(401)
            return
        end
        --- 判断返回的状态码是否是200
        if res.status ~= 200 then
            ngx.log(ngx.STDERR, "failed to request verify_bkrepo_token, status: ", res.status)
            ngx.exit(401)
            return
        end
        --- 设置HTTP保持连接
        httpc:set_keepalive(60000, 5)
        --- 转换JSON的返回数据为TABLE
        local result = json.decode(res.body)
        --- 判断JSON转换是否成功
        if result == nil then
            ngx.log(ngx.ERR, "failed to parse verify_bkrepo_token response：", res.body)
            ngx.exit(401)
            return
        end

        --- 判断返回码:Q!
        if result.code ~= 0 then
            ngx.log(ngx.INFO, "invalid verify_bkrepo_token: ", result.message)
            ngx.exit(401)
            return
        end
        user_cache_value = result.data.user_id
        user_cache:set(bkrepo_login_token, user_cache_value, 180)
    end
    return user_cache_value
end

function _M:verify_ci_token(ci_login_token)
    local user_cache = ngx.shared.user_info_store
    local user_cache_value = user_cache:get(ci_login_token)
    if user_cache_value == nil then
        --- 初始化HTTP连接
        local httpc = http.new()
        --- 分阶段设置超时：连接超时2s，发送超时3s，读取超时10s
        httpc:set_timeouts(2000, 3000, 10000)
        --- 开始连接
        local ok, conn_err = httpc:connect(config.bkci.host, config.bkci.port)
        if not ok then
            ngx.log(ngx.ERR, "verify_ci_token(): failed to connect bkci server: ", conn_err,
                    " host=", tostring(config.bkci.host), " port=", tostring(config.bkci.port))
            ngx.exit(401)
            return
        end
        local res, err = httpc:request({
            path = '/auth/api/external/third/login/verifyToken',
            method = "GET",
            headers = {
                ["Host"] = config.bkci.host,
                ["Accept"] = "application/json",
                ["Content-Type"] = "application/json",
                ["X-DEVOPS-CI-LOGIN-TOKEN"] = ci_login_token
            }
        })
        --- 判断是否出错了
        if not res then
            ngx.log(ngx.ERR, "failed to request get_ticket: ", err)
            ngx.exit(401)
            return
        end
        --- 判断返回的状态码是否是200
        if res.status ~= 200 then
            ngx.log(ngx.STDERR, "failed to request get_ticket, status: ", res.status)
            ngx.exit(401)
            return
        end
        --- 转换请求内容
        local responseBody = res:read_body()
        --- 设置HTTP保持连接
        httpc:set_keepalive(60000, 5)
        --- 转换JSON的返回数据为TABLE
        local result = json.decode(responseBody)
        --- 判断JSON转换是否成功
        if result == nil then
            ngx.log(ngx.ERR, "failed to parse get_ticket response：", responseBody)
            ngx.exit(401)
            return
        end
        user_cache_value = result.data
        user_cache:set(ci_login_token, user_cache_value, 60)
    end
    return user_cache_value
end

function _M:verify_tai_token(tai_token)
    local user_cache = ngx.shared.user_info_store
    local user_cache_value = user_cache:get(tai_token)
    if user_cache_value == nil then
        local http_cli = http.new()
        oauth = config.oauth
        local addr = "https://" .. oauth.url .. "/prod/bk_token_check/?bk_token=" .. tai_token
        local auth_content = '{"bk_app_code":"' .. oauth.app_code .. '","bk_app_secret":"' .. oauth.app_secret .. '"}'
        --- 开始连接
        http_cli:set_timeout(3000)
        http_cli:connect(addr)
        --- 发送请求
        local res, err = http_cli:request_uri(addr, {
            method = "GET",
            ssl_verify = false,
            headers = {
                ["X-Bkapi-Authorization"] = auth_content
            }
        })
        --- 判断是否出错了
        if not res then
            ngx.log(ngx.ERR, "failed to request tai token: error", err)
            ngx.exit(401)
            return
        end
        --- 判断返回的状态码是否是200
        if res.status ~= 200 then
            ngx.log(ngx.STDERR, "failed to request tai token, status: ", res.status)
            ngx.exit(401)
            return
        end
        --- 转换JSON的返回数据为TABLE
        local result = json.decode(res.body)
        --- 判断JSON转换是否成功
        if result == nil then
            ngx.log(ngx.ERR, "failed to parse tai token response：", res.body)
            ngx.exit(401)
            return
        end

        --- 判断返回名字
        if result.result ~= true or result.code ~= "00" then
            ngx.log(ngx.INFO, "invalid user tai token: ", result.message)
            ngx.exit(401)
            return
        end
        user_cache_value = result.data.username
        user_cache:set(tai_token, user_cache_value, 180)
    end
    return user_cache_value
end

return _M
