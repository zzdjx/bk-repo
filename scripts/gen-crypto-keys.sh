#!/usr/bin/env bash
# 生成 bkrepo security.crypto 密钥材料，只打印到 stdout，不写文件。
# RSA 为单行 Base64（PKCS#8 DER，无 PEM 头）。AES 为 ASCII 字节串（key 32、IV 16）。
# privateKeyStr2048PKCS1 字段名是历史遗留，内容仍是 PKCS#8（Java/hutool 按 PKCS#8 解）。
# 二进制部署必填，把输出填进 /etc/bkrepo/application.yaml 或 repo.env。
# helm 部署留空即可，chart 渲染时会随机生成；想固定密钥时：
#   bash scripts/gen-crypto-keys.sh --helm > crypto-values.yaml && helm ... -f crypto-values.yaml

set -euo pipefail

MODE=all
if [[ "${1:-}" == "--helm" ]]; then
    MODE=helm
elif [[ -n "${1:-}" ]]; then
    echo >&2 "usage: $0 [--helm]"
    exit 1
fi

if ! command -v openssl >/dev/null 2>&1; then
    echo >&2 "ERROR: openssl is required"
    exit 1
fi

b64_der() {
    openssl base64 -A
}

# 私钥只在内存里流转不落盘：mktemp 在 BSD/macOS 上必须带模板参数，
# 且 set -e 中途退出时 rm 不会执行，会把私钥留在 /tmp
rsa_keypair() {
    local bits=$1
    local pem private_b64 public_b64
    pem=$(openssl genrsa "$bits" 2>/dev/null)
    private_b64=$(printf '%s\n' "$pem" | openssl pkcs8 -topk8 -inform PEM -outform DER -nocrypt | b64_der)
    public_b64=$(printf '%s\n' "$pem" | openssl rsa -pubout -outform DER 2>/dev/null | b64_der)
    printf '%s %s\n' "$private_b64" "$public_b64"
}

# 三对统一 2048，与 helm 渲染时 genCA 出的位数对齐
read -r PRIVATE_KEY_STR PUBLIC_KEY_STR < <(rsa_keypair 2048)
read -r PRIVATE_KEY_STR_2048_PKCS8 PUBLIC_KEY_STR_2048_PKCS8 < <(rsa_keypair 2048)
read -r PRIVATE_KEY_STR_2048_PKCS1 PUBLIC_KEY_STR_2048_PKCS1 < <(rsa_keypair 2048)
AES_KEY=$(openssl rand -hex 16)
AES_IV=$(openssl rand -hex 8)

emit_helm() {
    cat <<EOF
common:
  config:
    security:
      crypto:
        privateKeyStr: "${PRIVATE_KEY_STR}"
        publicKeyStr: "${PUBLIC_KEY_STR}"
        privateKeyStr2048PKCS8: "${PRIVATE_KEY_STR_2048_PKCS8}"
        publicKeyStr2048PKCS8: "${PUBLIC_KEY_STR_2048_PKCS8}"
        privateKeyStr2048PKCS1: "${PRIVATE_KEY_STR_2048_PKCS1}"
        publicKeyStr2048PKCS1: "${PUBLIC_KEY_STR_2048_PKCS1}"
        aesKey: "${AES_KEY}"
        aesIv: "${AES_IV}"
EOF
}

if [[ "$MODE" == "helm" ]]; then
    emit_helm
    exit 0
fi

echo "# Helm values: bash $0 --helm > crypto-values.yaml && helm ... -f crypto-values.yaml"
emit_helm
cat <<EOF

# application.yaml
security:
  crypto:
    privateKeyStr: "${PRIVATE_KEY_STR}"
    publicKeyStr: "${PUBLIC_KEY_STR}"
    privateKeyStr2048PKCS8: "${PRIVATE_KEY_STR_2048_PKCS8}"
    publicKeyStr2048PKCS8: "${PUBLIC_KEY_STR_2048_PKCS8}"
    privateKeyStr2048PKCS1: "${PRIVATE_KEY_STR_2048_PKCS1}"
    publicKeyStr2048PKCS1: "${PUBLIC_KEY_STR_2048_PKCS1}"
    aesKey: "${AES_KEY}"
    aesIv: "${AES_IV}"

# repo.env
BK_REPO_CRYPTO_PRIVATE_KEY_STR=${PRIVATE_KEY_STR}
BK_REPO_CRYPTO_PUBLIC_KEY_STR=${PUBLIC_KEY_STR}
BK_REPO_CRYPTO_PRIVATE_KEY_STR_2048_PKCS8=${PRIVATE_KEY_STR_2048_PKCS8}
BK_REPO_CRYPTO_PUBLIC_KEY_STR_2048_PKCS8=${PUBLIC_KEY_STR_2048_PKCS8}
BK_REPO_CRYPTO_PRIVATE_KEY_STR_2048_PKCS1=${PRIVATE_KEY_STR_2048_PKCS1}
BK_REPO_CRYPTO_PUBLIC_KEY_STR_2048_PKCS1=${PUBLIC_KEY_STR_2048_PKCS1}
BK_REPO_CRYPTO_AES_KEY=${AES_KEY}
BK_REPO_CRYPTO_AES_IV=${AES_IV}
EOF
