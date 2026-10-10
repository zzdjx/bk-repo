#!/usr/bin/env bash
# CI 上给 Merge Request 跑 backend 构建+测试：只跑改动模块及其下游依赖。
# 根构建文件（settings/buildSrc/gradle.properties 等）变更则全量。
#
# 用法:
#   scripts/ci-mr-affected-build.sh [--base <ref>] [--head <ref>] [--full] [--dry-run] [--] [gradle 参数...]
#
# base 解析顺序: --base > MR_BASE_REF > 蓝盾 MR 目标 commit/分支 > GitLab/GitHub 对应变量
# 推荐蓝盾 checkout 开启 pre-merge，这样 BK_CI_GIT_REPO_MR_TARGET_HEAD_COMMIT_ID 可直接当 base，不必再 fetch。

set -euo pipefail

PROGRAM=$(basename "$0")
SCRIPT_DIR=$(cd "$(dirname "$0")" && pwd)
ROOT_DIR=$(cd "$SCRIPT_DIR/.." && pwd)
BACKEND_DIR="$ROOT_DIR/src/backend"
BACKEND_PREFIX="src/backend/"

BASE_REF=""
HEAD_REF="HEAD"
FORCE_FULL=0
DRY_RUN=0
SELF_CHECK=0
GRADLE_ARGS=()

usage() {
    cat <<EOF
用法:
    $PROGRAM [OPTIONS] [--] [gradle 参数...]

    --base <ref>     对比基线（目标分支名或 commit）。不传则读 CI 环境变量
    --head <ref>     对比终点，默认 HEAD
    --full           强制全量 ./gradlew clean build
    --dry-run        只打印将要执行的 Gradle 命令，不构建
    --self-check     校验路径到模块的映射，然后退出
    -h, --help       帮助

环境变量（--base 未传时）:
    MR_BASE_REF
    BK_CI_GIT_REPO_MR_TARGET_HEAD_COMMIT_ID
    BK_CI_REPO_GIT_WEBHOOK_TARGET_BRANCH
    BK_CI_REPO_GIT_WEBHOOK_MR_TARGET_BRANCH
    BK_CI_GIT_MR_TARGET_BRANCH
    CI_MERGE_REQUEST_TARGET_BRANCH_NAME
    GITHUB_BASE_REF

示例:
    $PROGRAM --base origin/master
    $PROGRAM --base master --dry-run
    $PROGRAM --base master -- -Psnapshot=true
EOF
}

log() { echo "[$PROGRAM] $*"; }
die() { echo "[$PROGRAM] ERROR: $*" >&2; exit 1; }

while [ $# -gt 0 ]; do
    case "$1" in
        --base)
            [ $# -ge 2 ] || die "--base 需要参数"
            BASE_REF=$2
            shift 2
            ;;
        --head)
            [ $# -ge 2 ] || die "--head 需要参数"
            HEAD_REF=$2
            shift 2
            ;;
        --full)
            FORCE_FULL=1
            shift
            ;;
        --dry-run)
            DRY_RUN=1
            shift
            ;;
        --self-check)
            SELF_CHECK=1
            shift
            ;;
        -h | --help)
            usage
            exit 0
            ;;
        --)
            shift
            GRADLE_ARGS=("$@")
            break
            ;;
        -*)
            die "不可识别的参数: $1"
            ;;
        *)
            GRADLE_ARGS=("$@")
            break
            ;;
    esac
done

cd "$ROOT_DIR"

# 按路径长度降序，保证 biz-xxx 先于父模块匹配
list_project_dirs() {
    find "$BACKEND_DIR" \( -name build.gradle.kts -o -name build.gradle \) \
        ! -path '*/build/*' \
        ! -path '*/buildSrc/*' \
        | sed "s|^$BACKEND_DIR/||; s|/build\\.gradle\\.kts$||; s|/build\\.gradle$||" \
        | awk 'NF && $0 != "." { print length($0), $0 }' \
        | sort -nr \
        | awk '{ $1=""; sub(/^ /, ""); print }'
}

to_gradle_path() {
    printf ':%s\n' "$(printf '%s' "$1" | tr '/' ':')"
}

is_full_rebuild_path() {
    case "$1" in
        src/backend/build.gradle|src/backend/build.gradle.kts) return 0 ;;
        src/backend/settings.gradle|src/backend/settings.gradle.kts) return 0 ;;
        src/backend/gradle.properties) return 0 ;;
        src/backend/gradlew|src/backend/gradlew.bat) return 0 ;;
        src/backend/gradle/*) return 0 ;;
        src/backend/buildSrc|src/backend/buildSrc/*) return 0 ;;
        *) return 1 ;;
    esac
}

is_module_build_file() {
    case "$1" in
        */build.gradle|*/build.gradle.kts) return 0 ;;
        *) return 1 ;;
    esac
}

# stdin: project dirs (longest first). stdout: 命中的相对目录，未命中为空
map_backend_file() {
    local rel=$1
    local pdir
    case "$rel" in
        "$BACKEND_PREFIX"*) rel=${rel#"$BACKEND_PREFIX"} ;;
        *) return 0 ;;
    esac
    while IFS= read -r pdir; do
        [ -n "$pdir" ] || continue
        case "$rel" in
            "$pdir"|"$pdir"/*)
                printf '%s\n' "$pdir"
                return 0
                ;;
        esac
    done
}

contains_line() {
    local needle=$1
    local hay=$2
    case $'\n'"$hay"$'\n' in
        *$'\n'"$needle"$'\n'*) return 0 ;;
        *) return 1 ;;
    esac
}

append_line() {
    local line=$1
    local var=$2
    if [ -z "$var" ]; then
        printf '%s\n' "$line"
    elif contains_line "$line" "$var"; then
        printf '%s\n' "$var"
    else
        printf '%s\n%s\n' "$var" "$line"
    fi
}

# 父模块的 build.gradle 变更会作用到子项目，把子项目一并纳入
expand_children() {
    local parent=$1
    local all_dirs=$2
    local child
    printf '%s\n' "$parent"
    while IFS= read -r child; do
        [ -n "$child" ] || continue
        case "$child" in
            "$parent"/*) printf '%s\n' "$child" ;;
        esac
    done <<EOF
$all_dirs
EOF
}

pick_base_ref() {
    if [ -n "$BASE_REF" ]; then
        printf '%s\n' "$BASE_REF"
        return
    fi
    set -- \
        "${MR_BASE_REF:-}" \
        "${BK_CI_GIT_REPO_MR_TARGET_HEAD_COMMIT_ID:-}" \
        "${BK_CI_REPO_GIT_WEBHOOK_TARGET_BRANCH:-}" \
        "${BK_CI_REPO_GIT_WEBHOOK_MR_TARGET_BRANCH:-}" \
        "${BK_CI_GIT_MR_TARGET_BRANCH:-}" \
        "${CI_MERGE_REQUEST_TARGET_BRANCH_NAME:-}" \
        "${GITHUB_BASE_REF:-}"
    local v
    for v in "$@"; do
        if [ -n "$v" ]; then
            printf '%s\n' "$v"
            return
        fi
    done
}

resolve_commit() {
    local ref=$1
    if git rev-parse --verify -q "${ref}^{commit}" >/dev/null; then
        git rev-parse "${ref}^{commit}"
        return
    fi
    if git rev-parse --verify -q "origin/${ref}^{commit}" >/dev/null; then
        git rev-parse "origin/${ref}^{commit}"
        return
    fi
    if git rev-parse --is-shallow-repository >/dev/null 2>&1 \
        && [ "$(git rev-parse --is-shallow-repository)" = "true" ]; then
        log "浅克隆，尝试 unshallow"
        git fetch --unshallow --no-tags || git fetch --deepen=200 --no-tags || true
    fi
    log "fetch origin $ref"
    git fetch --no-tags origin "$ref:refs/remotes/origin/$ref" 2>/dev/null \
        || git fetch --no-tags origin "$ref" || true
    if git rev-parse --verify -q "${ref}^{commit}" >/dev/null; then
        git rev-parse "${ref}^{commit}"
        return
    fi
    if git rev-parse --verify -q "origin/${ref}^{commit}" >/dev/null; then
        git rev-parse "origin/${ref}^{commit}"
        return
    fi
    return 1
}

run_gradle() {
    [ -x "$BACKEND_DIR/gradlew" ] || die "找不到 $BACKEND_DIR/gradlew"
    local gradle_cmd=("$BACKEND_DIR/gradlew" -p "$BACKEND_DIR" clean)
    gradle_cmd+=("$@")
    gradle_cmd+=(--continue)
    if [ ${#GRADLE_ARGS[@]} -gt 0 ]; then
        gradle_cmd+=("${GRADLE_ARGS[@]}")
    fi
    log "执行: ${gradle_cmd[*]}"
    if [ "$DRY_RUN" -eq 1 ]; then
        return 0
    fi
    "${gradle_cmd[@]}"
}

self_check() {
    local dirs mapped
    dirs=$(list_project_dirs)
    [ -n "$dirs" ] || die "self-check: 没有扫到 Gradle 模块"
    contains_line "repository/biz-repository" "$dirs" || die "self-check: 缺少 repository/biz-repository"
    contains_line "common/common-artifact/artifact-api" "$dirs" || die "self-check: 缺少 artifact-api"

    mapped=$(printf '%s\n' "$dirs" | map_backend_file "src/backend/repository/biz-repository/src/main/Foo.kt")
    [ "$mapped" = "repository/biz-repository" ] || die "self-check: biz-repository 映射失败 ($mapped)"

    mapped=$(printf '%s\n' "$dirs" | map_backend_file "src/backend/common/common-artifact/artifact-api/src/x.kt")
    [ "$mapped" = "common/common-artifact/artifact-api" ] || die "self-check: artifact-api 映射失败 ($mapped)"

    mapped=$(printf '%s\n' "$dirs" | map_backend_file "src/backend/core/maven/biz-maven/src/main/A.kt")
    [ "$mapped" = "core/maven/biz-maven" ] || die "self-check: biz-maven 映射失败 ($mapped)"

    mapped=$(to_gradle_path "common/common-artifact/artifact-api")
    [ "$mapped" = ":common:common-artifact:artifact-api" ] || die "self-check: gradle path 失败 ($mapped)"

    is_full_rebuild_path "src/backend/buildSrc/src/main/kotlin/Versions.kt" || die "self-check: buildSrc 应全量"
    is_full_rebuild_path "src/backend/settings.gradle.kts" || die "self-check: settings 应全量"
    is_full_rebuild_path "src/backend/repository/biz-repository/src/main/Foo.kt" && die "self-check: 业务文件不应全量"

    mapped=$(printf '%s\n' "$dirs" | map_backend_file "src/frontend/foo.js")
    [ -z "$mapped" ] || die "self-check: 前端文件不应映射到 backend 模块"

    mapped=$(printf '%s\n' "$dirs" | map_backend_file "src/backend/auth/build.gradle.kts")
    [ "$mapped" = "auth" ] || die "self-check: auth/build.gradle.kts 应映射到 auth ($mapped)"
    contains_line "auth/biz-auth" "$(expand_children "auth" "$dirs")" || die "self-check: auth 的 build 文件应带上 biz-auth"

    log "self-check 通过"
}

if [ "$SELF_CHECK" -eq 1 ]; then
    self_check
    exit 0
fi

if [ ! -d "$BACKEND_DIR" ]; then
    die "找不到 backend 目录: $BACKEND_DIR"
fi

if [ "$FORCE_FULL" -eq 1 ]; then
    log "指定 --full，全量构建+测试"
    run_gradle build
    exit 0
fi

BASE_INPUT=$(pick_base_ref)
[ -n "$BASE_INPUT" ] || die "未提供对比基线。传 --base <目标分支>，或在 CI 里确保 MR 目标分支环境变量存在"

log "base=$BASE_INPUT head=$HEAD_REF"
BASE_COMMIT=$(resolve_commit "$BASE_INPUT") || {
    log "无法解析 base=$BASE_INPUT，回退全量构建+测试"
    run_gradle build
    exit 0
}
HEAD_COMMIT=$(resolve_commit "$HEAD_REF") || die "无法解析 head=$HEAD_REF"

log "base_commit=$BASE_COMMIT"
log "head_commit=$HEAD_COMMIT"

if ! git merge-base "$BASE_COMMIT" "$HEAD_COMMIT" >/dev/null 2>&1; then
    log "找不到 merge-base，回退全量构建+测试"
    run_gradle build
    exit 0
fi

CHANGED=$(git diff --name-only "$BASE_COMMIT...$HEAD_COMMIT" --) \
    || die "git diff 失败 ($BASE_COMMIT...$HEAD_COMMIT)"
if [ -z "$CHANGED" ]; then
    log "相对 $BASE_INPUT 没有文件改动，跳过构建"
    exit 0
fi

log "改动文件:"
printf '%s\n' "$CHANGED" | sed 's/^/  /'

PROJECT_DIRS=$(list_project_dirs)
AFFECTED=""
FULL=0

while IFS= read -r file; do
    [ -n "$file" ] || continue
    if is_full_rebuild_path "$file"; then
        log "根构建文件变更: $file"
        FULL=1
        break
    fi
    case "$file" in
        "$BACKEND_PREFIX"*) ;;
        *) continue ;;
    esac
    mapped=$(printf '%s\n' "$PROJECT_DIRS" | map_backend_file "$file")
    [ -n "$mapped" ] || continue
    AFFECTED=$(append_line "$mapped" "$AFFECTED")
    if is_module_build_file "$file"; then
        children=$(expand_children "$mapped" "$PROJECT_DIRS")
        while IFS= read -r child; do
            [ -n "$child" ] || continue
            AFFECTED=$(append_line "$child" "$AFFECTED")
        done <<EOF
$children
EOF
    fi
done <<EOF
$CHANGED
EOF

if [ "$FULL" -eq 1 ]; then
    log "触发全量构建+测试"
    run_gradle build
    exit 0
fi

if [ -z "$AFFECTED" ]; then
    log "没有 backend 模块改动，跳过构建"
    exit 0
fi

log "受影响模块:"
TASKS=()
while IFS= read -r pdir; do
    [ -n "$pdir" ] || continue
    gpath=$(to_gradle_path "$pdir")
    log "  $gpath"
    TASKS+=("${gpath}:buildDependents")
done <<EOF
$AFFECTED
EOF

run_gradle "${TASKS[@]}"
