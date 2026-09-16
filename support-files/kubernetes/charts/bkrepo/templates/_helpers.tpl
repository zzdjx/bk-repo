{{/*
Return the proper Docker Image Registry Secret Names
*/}}
{{- define "bkrepo.imagePullSecrets" -}}
{{- include "common.images.pullSecrets" (dict "images" (list
    .Values.gateway.image
    .Values.repository.image
    .Values.auth.image
    .Values.init.curl.image
    .Values.init.mongodb.image
    .Values.init.iam.image
    .Values.generic.image
    .Values.docker.image
    .Values.npm.image
    .Values.pypi.image
    .Values.helm.image
    .Values.job.image
    .Values.maven.image
    .Values.opdata.image
    .Values.preview.image
    .Values.replication.image
    .Values.s3.image
) "global" .Values.global) -}}
{{- end -}}

{{/*
Create the name of the service account to use
*/}}
{{- define "bkrepo.serviceAccountName" -}}
{{- if .Values.serviceAccount.create -}}
    {{ default (printf "%s-foo" (include "common.names.fullname" .)) .Values.serviceAccount.name }}
{{- else -}}
    {{ default "default" .Values.serviceAccount.name }}
{{- end -}}
{{- end -}}

{{/*
define init project id
*/}}
{{- define "bkrepo.init.project" -}}
{{- if .Values.gateway.enableMultiTenantMode -}}
{{- $tenantId := default "system" .Values.gateway.oPTenantId -}}
{{- printf "%s.blueking" $tenantId -}}
{{- else -}}
{{- printf "blueking" -}}
{{- end -}}
{{- end -}}



{{/*
Create a default fully qualified mongodb subchart.
We truncate at 63 chars because some Kubernetes name fields are limited to this (by the DNS naming spec).
*/}}
{{- define "bkrepo.mongodb.fullname" -}}
{{- if .Values.mongodb.fullnameOverride -}}
{{- .Values.mongodb.fullnameOverride | trunc 63 | trimSuffix "-" -}}
{{- else -}}
{{- $name := default "mongodb" .Values.mongodb.nameOverride -}}
{{- printf "%s-%s" .Release.Name $name | trunc 63 | trimSuffix "-" -}}
{{- end -}}
{{- end -}}

{{/*
Return the mongodb connection uri
*/}}
{{- define "bkrepo.mongodbUri" -}}
{{- if eq .Values.mongodb.enabled true -}}
{{- printf "mongodb://%s:%s@%s:27017/%s" .Values.mongodb.auth.username (include "bkrepo.mongodb.password" . | urlquery) (include "bkrepo.mongodb.fullname" .) .Values.mongodb.auth.database -}}
{{- else -}}
{{- include "bkrepo.externalMongodb.uri" . -}}
{{- end -}}
{{- end -}}

{{/*
Return the label key of bk-repo scope
*/}}
{{- define "bkrepo.labelValues.scope" -}}
    {{- printf "bk.repo.scope" -}}
{{- end -}}

{{/*
Return the label value of bk-repo scope backend
*/}}
{{- define "bkrepo.labelValues.scope.backend" -}}
    {{- printf "backend" -}}
{{- end -}}

{{/*
Return the label value of bk-repo scope gateway
*/}}
{{- define "bkrepo.labelValues.scope.gateway" -}}
    {{- printf "gateway" -}}
{{- end -}}

{{/*
Return the proper image name
{{ include "bkrepo.images.image" ( dict "imageRoot" .Values.path.to.the.image "global" $) }}
*/}}
{{- define "bkrepo.images.image" -}}
{{- $registryName := .imageRoot.registry -}}
{{- $repositoryName := .imageRoot.repository -}}
{{- $tag := .imageRoot.tag | toString -}}
{{- if .global }}
    {{- if .global.imageRegistry }}
     {{- $registryName = .global.imageRegistry -}}
    {{- end -}}
{{- end -}}
{{- if .bkrepo.imageRegistry }}
    {{- $registryName = .bkrepo.imageRegistry -}}
{{- end -}}
{{- if .bkrepo.imageTag }}
    {{- $tag = .bkrepo.imageTag -}}
{{- end -}}
{{- if $registryName }}
{{- printf "%s/%s:%s" $registryName $repositoryName $tag -}}
{{- else -}}
{{- printf "%s:%s" $repositoryName $tag -}}
{{- end -}}
{{- end -}}

{{/*
Return the effective JVM option.
Service-level jvmOption takes precedence over common.jvmOption.
*/}}
{{- define "bkrepo.jvmOption" -}}
{{- default .common.jvmOption .service.jvmOption -}}
{{- end -}}

{{/*
Return the value of auth url
*/}}
{{- define "bkrepo.oci.authUrl" -}}
{{- if and .Values.gateway.service.nodeIP (and .Values.gateway.service.dockerNodePort (or (eq .Values.gateway.service.type "NodePort") (eq .Values.gateway.service.type "LoadBalancer"))) -}}
    {{- printf "%s:%s/v2/auth" .Values.gateway.service.nodeIP (.Values.gateway.service.dockerNodePort | toString) -}}
{{- else -}}
    {{- if eq "subpath" .Values.bkWebSiteAccess.mode -}}
    {{- printf "%s%s/docker/v2/auth" .Values.gateway.host .Values.bkWebSiteAccess.subPath -}}
    {{- else -}}
    {{- printf "%s/docker/v2/auth" .Values.gateway.host -}}
    {{- end -}}
{{- end -}}
{{- end -}}

{{/*
True if value is a Platform auth header: "Platform " + base64(accessKey:secretKey).
*/}}
{{- define "bkrepo.platformAuth.valid" -}}
{{- $auth := . | toString | trim -}}
{{- if hasPrefix "Platform " $auth -}}
{{- $payload := trimPrefix "Platform " $auth | trim -}}
{{- if regexMatch "^[A-Za-z0-9+/]+=*$" $payload -}}
{{- $decoded := $payload | b64dec -}}
{{- $parts := splitn ":" 2 $decoded -}}
{{- if and $parts._0 $parts._1 -}}
true
{{- end -}}
{{- end -}}
{{- end -}}
{{- end -}}

{{/*
Return the value of authorization.

New installs must set gateway.accessKey and gateway.secretKey.
Upgrades may keep a valid gateway.authorization and leave accessKey/secretKey empty.
*/}}
{{- define "bkrepo.authorization" -}}
{{- $ak := .Values.gateway.accessKey | toString -}}
{{- $sk := .Values.gateway.secretKey | toString -}}
{{- if and $ak $sk -}}
{{- if or (eq $ak "18b61c9c-901b-4ea3-89c3-1f74be944b66") (eq $sk "Us8ZGDXPqk86cwMukYABQqCZLAkM3K") -}}
{{- fail "gateway.accessKey/secretKey must not use the publicly known default pair. Set unique values." -}}
{{- end -}}
{{- printf "Platform %s" (printf "%s:%s" $ak $sk | b64enc) -}}
{{- else if .Release.IsInstall -}}
{{- fail "New installs must set gateway.accessKey and gateway.secretKey (do not rely on gateway.authorization). Example: --set gateway.accessKey=<ak> --set gateway.secretKey=<sk>" -}}
{{- else -}}
{{- $auth := .Values.gateway.authorization | toString | trim -}}
{{- if ne (include "bkrepo.platformAuth.valid" $auth | trim) "true" -}}
{{- fail "Upgrade requires a valid gateway.authorization (Platform <base64(accessKey:secretKey)>) or set both gateway.accessKey and gateway.secretKey." -}}
{{- end -}}
{{- $auth -}}
{{- end -}}
{{- end -}}


{{- define "bkrepo.subPath" -}}
{{- if eq "subpath" .Values.bkWebSiteAccess.mode -}}
{{ printf "%s(/?)(.*)" .Values.bkWebSiteAccess.subPath }}
{{- else -}}
{{ printf "/" }}
{{- end -}}
{{- end -}}

{{- define "bkrepo.webSubPath" -}}
{{- if eq "subpath" .Values.bkWebSiteAccess.mode -}}
{{ printf "%s/" .Values.bkWebSiteAccess.subPath }}
{{- else -}}
{{ printf "/" }}
{{- end -}}
{{- end -}}

{{/*
Escape special characters for Kafka JAAS configuration
Escapes backslash and double quote characters
*/}}
{{- define "bkrepo.jaasEscape" -}}
{{- . | replace "\\" "\\\\" | replace "\"" "\\\"" -}}
{{- end -}}

{{/*
Generate Kafka SASL JAAS configuration string
*/}}
{{- define "bkrepo.kafka.jaasConfig" -}}
{{- $username := include "bkrepo.jaasEscape" .Values.kafka.username -}}
{{- $password := include "bkrepo.jaasEscape" .Values.kafka.password -}}
{{- printf "org.apache.kafka.common.security.scram.ScramLoginModule required username=\"%s\" password=\"%s\";" $username $password -}}
{{- end -}}

{{- define "bkrepo.backupEncryptKey" -}}
{{- $key := default "" .Values.common.config.backup.encryptKey -}}
{{- if $key -}}
{{- $len := len $key -}}
{{- if and (ne $len 16) (ne $len 24) (ne $len 32) -}}
{{- fail "common.config.backup.encryptKey must be 16, 24 or 32 bytes (AES)" -}}
{{- end -}}
{{- end -}}
{{- end -}}

{{/*
Read a persisted secret: data key, nested application.yml, else dotted key.
*/}}
{{- define "bkrepo.secretKey.fromConfigMap" -}}
{{- $existing := "" -}}
{{- if and .cm .cm.data -}}
{{- if .dataKey -}}
{{- $existing = index .cm.data .dataKey | default "" | toString | trim -}}
{{- end -}}
{{- if not $existing -}}
{{- $yml := index .cm.data "application.yml" | default "" | fromYaml -}}
{{- $cur := $yml -}}
{{- range .nested -}}
{{- if kindIs "map" $cur -}}
{{- $cur = index $cur . -}}
{{- else -}}
{{- $cur = "" -}}
{{- end -}}
{{- end -}}
{{- if kindIs "string" $cur -}}
{{- $existing = $cur | trim -}}
{{- end -}}
{{- if and (not $existing) .dotted $yml -}}
{{- $existing = index $yml .dotted | default "" | toString | trim -}}
{{- end -}}
{{- end -}}
{{- end -}}
{{- if hasPrefix "${" ($existing | toString | trim) -}}
{{- else -}}
{{- $existing -}}
{{- end -}}
{{- end -}}

{{/*
Resolve: explicit values > lookup ConfigMap > generate.
Lookup reuses as-is so a later upgrade does not rotate the key.
Writes the result back to setDict so toYaml common.config emits it.
Caches on .Values.common so one render yields one value.
generator: "alphaNum" (default, length bytes) or "rsa" (PKCS#1 PEM private key).
rsa borrows genCA for its 2048-bit key and drops the cert, because genPrivateKey
is hardcoded to 4096 and neither takes a bit-length argument. 2048 keeps helm
aligned with scripts/gen-crypto-keys.sh.
*/}}
{{- define "bkrepo.secretKey.resolve" -}}
{{- $root := .root -}}
{{- $value := .value | toString | trim -}}
{{- if hasPrefix "${" $value -}}
{{- $value = "" -}}
{{- end -}}
{{- $out := "" -}}
{{- if $value -}}
{{- $out = $value -}}
{{- else if index $root.Values.common .cacheKey -}}
{{- $out = index $root.Values.common .cacheKey -}}
{{- else -}}
{{- $cmName := printf "%s-common" (include "common.names.fullname" $root) -}}
{{- $cm := lookup "v1" "ConfigMap" $root.Release.Namespace $cmName -}}
{{- $existing := include "bkrepo.secretKey.fromConfigMap" (dict
    "cm" $cm
    "dataKey" .dataKey
    "nested" .nested
    "dotted" .dotted) | trim -}}
{{- if $existing -}}
{{- $out = $existing -}}
{{- else if eq (.generator | default "alphaNum") "rsa" -}}
{{- $out = (genCA "bkrepo-crypto" 1).Key | trim -}}
{{- else -}}
{{- $out = randAlphaNum (.length | default 32) -}}
{{- end -}}
{{- $_ := set $root.Values.common .cacheKey $out -}}
{{- end -}}
{{- if and .setDict $out -}}
{{- $_ := set .setDict .setKey $out -}}
{{- end -}}
{{- $out -}}
{{- end -}}

{{/*
Service inter-auth key. Empty -> lookup ConfigMap, else generate.
Explicit as-is. Does not rotate keys; ops drives 3-phase via values.
*/}}
{{- define "bkrepo.serviceSecretKey" -}}
{{- include "bkrepo.secretKey.resolve" (dict
    "root" .
    "value" .Values.common.config.security.service.secretKey
    "cacheKey" "_generatedServiceSecretKey"
    "dataKey" "serviceSecretKey"
    "nested" (list "security" "service" "secretKey")
    "dotted" "security.service.secretKey"
    "setDict" .Values.common.config.security.service
    "setKey" "secretKey") -}}
{{- end -}}

{{/*
Verify-only. Explicit current key: previous comes from values (empty clears).
Empty current: reuse previous from ConfigMap if any.
*/}}
{{- define "bkrepo.servicePreviousSecretKey" -}}
{{- $explicitCurr := .Values.common.config.security.service.secretKey | toString | trim -}}
{{- $explicitPrev := .Values.common.config.security.service.previousSecretKey | toString | trim -}}
{{- $prev := "" -}}
{{- if $explicitCurr -}}
{{- $prev = $explicitPrev -}}
{{- else -}}
{{- $cmName := printf "%s-common" (include "common.names.fullname" .) -}}
{{- $cm := lookup "v1" "ConfigMap" .Release.Namespace $cmName -}}
{{- $prev = include "bkrepo.secretKey.fromConfigMap" (dict
    "cm" $cm
    "dataKey" "servicePreviousSecretKey"
    "nested" (list "security" "service" "previousSecretKey")
    "dotted" "security.service.previousSecretKey") | trim -}}
{{- end -}}
{{- $_ := set .Values.common.config.security.service "previousSecretKey" $prev -}}
{{- $prev -}}
{{- end -}}

{{/*
Return the initial admin password.
Must be explicitly set and must not be a publicly known default.
*/}}
{{- define "bkrepo.admin.password" -}}
{{- $password := .Values.common.password | toString -}}
{{- if or (eq $password "") (eq $password "password") (eq $password "blueking") -}}
{{- fail "common.password must be set to a strong value (do not use empty, 'password' or 'blueking'). Example: --set common.password=<your-password>" -}}
{{- else -}}
{{- $password -}}
{{- end -}}
{{- end -}}

{{/*
Return the in-cluster MongoDB password.
Must be explicitly set when mongodb.enabled=true and must not be the public default.
*/}}
{{- define "bkrepo.mongodb.password" -}}
{{- $password := .Values.mongodb.auth.password | toString -}}
{{- if or (eq $password "") (eq $password "bkrepo") -}}
{{- fail "mongodb.auth.password must be set to a strong value when mongodb.enabled=true (do not use empty or 'bkrepo'). Example: --set mongodb.auth.password=<your-password>" -}}
{{- else -}}
{{- $password -}}
{{- end -}}
{{- end -}}

{{/*
Return the external MongoDB URI.
Required when mongodb.enabled=false; reject the hardcoded default credentials.
*/}}
{{- define "bkrepo.externalMongodb.uri" -}}
{{- $uri := .Values.externalMongodb.uri | toString -}}
{{- if or (eq $uri "") (contains "bkrepo:bkrepo@" $uri) -}}
{{- fail "externalMongodb.uri must be set to a real MongoDB URI when mongodb.enabled=false (do not use default credentials bkrepo/bkrepo)." -}}
{{- else -}}
{{- $uri -}}
{{- end -}}
{{- end -}}

{{/*
JWT signing key. Empty -> lookup ConfigMap, else generate.
*/}}
{{- define "bkrepo.jwtSecretKey" -}}
{{- include "bkrepo.secretKey.resolve" (dict
    "root" .
    "value" .Values.common.config.security.auth.jwt.secretKey
    "cacheKey" "_generatedJwtSecretKey"
    "dataKey" "jwtSecretKey"
    "nested" (list "security" "auth" "jwt" "secretKey")
    "dotted" "security.auth.jwt.secretKey"
    "setDict" .Values.common.config.security.auth.jwt
    "setKey" "secretKey") -}}
{{- end -}}

{{- define "bkrepo.fdtpSecretKey" -}}
{{- $msg := "replicationUDP.secretKey is required (>=64 bytes). Set values.replicationUDP.secretKey" -}}
{{- $key := required $msg .Values.replicationUDP.secretKey -}}
{{- if lt (len $key) 64 -}}
{{- fail "replicationUDP.secretKey must be at least 64 bytes (HS512)" -}}
{{- end -}}
{{- $key -}}
{{- end -}}

{{/*
Fill security.crypto. Same path as JWT: explicit values > lookup ConfigMap > generate.
Only private keys are stored; each service derives the public key from the private one.
*/}}
{{- define "bkrepo.crypto.material" -}}
{{- $crypto := .Values.common.config.security.crypto -}}
{{- include "bkrepo.crypto.privateKey" (dict "root" . "key" "privateKeyStr") -}}
{{- include "bkrepo.crypto.privateKey" (dict "root" . "key" "privateKeyStr2048PKCS8") -}}
{{- include "bkrepo.crypto.privateKey" (dict "root" . "key" "privateKeyStr2048PKCS1") -}}
{{- include "bkrepo.secretKey.resolve" (dict
    "root" .
    "value" $crypto.aesKey
    "cacheKey" "_generatedCryptoAesKey"
    "nested" (list "security" "crypto" "aesKey")
    "dotted" "security.crypto.aesKey"
    "setDict" $crypto
    "setKey" "aesKey") -}}
{{- include "bkrepo.secretKey.resolve" (dict
    "root" .
    "value" $crypto.aesIv
    "cacheKey" "_generatedCryptoAesIv"
    "nested" (list "security" "crypto" "aesIv")
    "dotted" "security.crypto.aesIv"
    "setDict" $crypto
    "setKey" "aesIv"
    "length" 16) -}}
{{- end -}}

{{- define "bkrepo.crypto.privateKey" -}}
{{- $crypto := .root.Values.common.config.security.crypto -}}
{{- include "bkrepo.secretKey.resolve" (dict
    "root" .root
    "value" (index $crypto .key | default "")
    "cacheKey" (printf "_generatedCrypto_%s" .key)
    "nested" (list "security" "crypto" .key)
    "dotted" (printf "security.crypto.%s" .key)
    "setDict" $crypto
    "setKey" .key
    "generator" "rsa") -}}
{{- end -}}
