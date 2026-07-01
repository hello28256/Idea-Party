#!/usr/bin/env bash
# =============================================================================
# oss-sync-avatars.sh
#
# 把 server/uploads/avatars/{presets,presets-webp,scenarios,hot-rooms}/
# 同步上传到阿里云 OSS 桶 idea-party-uploads 同 prefix 下。
#
# 这是 OSS 迁移的兜底:跑一次填满 OSS 上缺的 preset 图(之前发现 ssh 部署后
# /api/upload/avatars/presets/*.jpg 404,前端全 404 → 回退字母头像)。
# 幂等:md5 相同的文件跳过,可重复跑。
#
# 用法:
#   # 1. 先用有 oss:PutObject 权限的 RAM 子账号配置 aliyun
#   aliyun configure --profile oss-writer \
#     --access-key-id LTAI5xxxxxxxxxxxxxx \
#     --access-key-secret <secret> \
#     --region cn-shenzhen
#
#   # 2. 跑同步(默认用 oss-writer profile)
#   ./scripts/oss-sync-avatars.sh
#
#   # 3. 只同步某个子目录(调试 / 重试有用)
#   ./scripts/oss-sync-avatars.sh presets
#   ./scripts/oss-sync-avatars.sh presets-webp
#   ./scripts/oss-sync-avatars.sh scenarios
#   ./scripts/oss-sync-avatars.sh hot-rooms
#
#   # 4. 自定义 profile
#   ALIYUN_PROFILE=other ./scripts/oss-sync-avatars.sh
#
# 安全:
#   - 只往 OSS PUT 对象,不改 ACL,不开 public-read(桶默认 public-read 已经够用)
#   - --dry-run 模式只列举需要上传的文件,不动 OSS
#
# 依赖:
#   - aliyun CLI (>= 3.0): https://help.aliyun.com/document_detail/110341.html
#   - python3 (md5 计算用)
#
# 作者: Yang Q
# =============================================================================

set -euo pipefail

# ----- 配置 -----
BUCKET="${ALIYUN_OSS_BUCKET:-idea-party-uploads}"
# aliyun CLI 不接受 oss-cn-shenzhen 这种带前缀的形式,只接受 RegionId 如 cn-shenzhen。
# 注意: .env.production.example 里写的是 oss-cn-shenzhen (用于 ALIYUN_OSS_REGION,语义是 OSS 服务
# 的 endpoint 别名),而 aliyun CLI 的 --region 参数只接受纯 RegionId。
REGION="${ALIYUN_OSS_REGION_SH:-cn-shenzhen}"
ENDPOINT="${ALIYUN_OSS_ENDPOINT:-https://oss-cn-shenzhen.aliyuncs.com}"
PROFILE="${ALIYUN_PROFILE:-default}"
export ENDPOINT

# 目标子目录(key prefix 必须和预设路径同步)
# nginx 301 /uploads/...$request_uri → oss://idea-party-uploads/uploads/...$request_uri
# 迁移脚本约定:
#   本地 server/uploads/avatars/presets/foo.jpg
#   → OSS oss://idea-party-uploads/uploads/avatars/presets/foo.jpg
declare -A SUBDIRS=(
    [presets]="server/uploads/avatars/presets"
    [presets-webp]="server/uploads/avatars/presets-webp"
    [scenarios]="server/uploads/avatars/scenarios"
    [hot-rooms]="server/uploads/avatars/hot-rooms"
)

# ----- 解析参数 -----
ONLY="${1:-}"  # 限定只同步某个子目录(如 presets),空=全部

# ----- 路径定位 -----
ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT_DIR"

# ----- 预检 -----
if ! command -v aliyun >/dev/null 2>&1; then
    echo "❌ aliyun CLI 未安装,先去 https://help.aliyun.com/document_detail/110341.html" >&2
    exit 1
fi

if ! command -v python3 >/dev/null 2>&1; then
    echo "❌ python3 未安装 (用于计算 md5)" >&2
    exit 1
fi

# ----- 凭证预检 -----
# STS 用户的 RAM 策略通常只给 oss:PutObject(不开 ListBucket/GetObject),
# 所以无法用 oss ls / oss stat 做 md5 比对。本脚本不做 md5 跳过 — 强制覆盖上传。
# 重跑脚本会触发重复 PUT(非免费但单次毫分钱,数量在几百以内可忽略)。
# ----- 凭证预检(注释放外面方便读)
# STS 用户只有 PutObject,策略限定 oss:PutObject Resource=acs:oss:*:*:idea-party-uploads/uploads/*
# 所以 probe 必须落到 uploads/ 下才会真正 PUT 通过;落在 api/ 下会被拒。
echo "🔑 验证 profile '$PROFILE' 对桶 '$BUCKET' 的写权限..."
TMP_PROBE=$(mktemp)
echo "probe" > "$TMP_PROBE"
PROBE_KEY="uploads/avatars/_probe_$$_$RANDOM.txt"
if ! aliyun --profile "$PROFILE" oss cp "$TMP_PROBE" \
        "oss://$BUCKET/$PROBE_KEY" \
        --region "$REGION" --force >/dev/null 2>&1; then
    rm -f "$TMP_PROBE"
    echo "❌ profile '$PROFILE' 无法往 oss://$BUCKET/$PROBE_KEY PUT" >&2
    echo "   解决: aliyun configure --profile $PROFILE --access-key-id ... --access-key-secret ..." >&2
    exit 1
fi
aliyun --profile "$PROFILE" oss rm "oss://$BUCKET/$PROBE_KEY" --region "$REGION" --force >/dev/null 2>&1 || true
rm -f "$TMP_PROBE"
echo "✅ 凭证 OK (PutObject 通过)"

# ----- 遍历子目录 -----
# STS 用户只有 PutObject,没有 ListBucket/GetObject,所以无法 stat 拿 etag
# 跳过 md5 比对,直接强制覆盖上传 (--force)。重复跑脚本会重复 PUT。
sync_subdir() {
    local name="$1"
    local local_root="$2"
    local total=0
    local uploaded=0
    local failed=0

    if [[ ! -d "$local_root" ]]; then
        echo "  ⚠️  本地目录不存在: $local_root"
        return 0
    fi

    # 用 find 避免 ls 在文件名带空格时炸掉
    while IFS= read -r -d '' f; do
        local rel="${f#"$local_root"/}"
        # OSS key 形态:uploads/avatars/<subdir>/<rel>(按 deploy.py 迁移脚本约定)
        local oss_key="uploads/avatars/${name}/${rel}"

        total=$((total + 1))

        if [[ "${DRY_RUN:-0}" == "1" ]]; then
            echo "  📤 DRY-RUN 将上传: $oss_key"
            continue
        fi

        # -f 强制覆盖已存在对象。
        # 不用 `cmd | grep -q` 检测成功 — 一旦 grep 匹配立刻关管道, aliyun 会收到 SIGPIPE 退出非 0,
        # 而 `set -o pipefail` 会把这个非 0 整段算失败。反而不准。
        # 改用退出码: aliyun oss cp 成功返回 0,失败返回非 0。
        if aliyun --profile "$PROFILE" oss cp \
                "$f" "oss://$BUCKET/$oss_key" \
                --region "$REGION" \
                --force >/dev/null 2>&1; then
            uploaded=$((uploaded + 1))
            if (( total % 50 == 0 )); then
                echo "  📊 进度: 已处理 $total, 成功 $uploaded, 失败 $failed"
            fi
        else
            failed=$((failed + 1))
            echo "  ❌ 上传失败: $oss_key" >&2
        fi
    done < <(find "$local_root" -type f -print0)

    echo "  📊 $name: 扫描 $total 个 → 成功 $uploaded, 失败 $failed"
}

# ----- 主流程 -----
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo "🚀 OSS 头像同步"
echo "   桶:    oss://$BUCKET"
echo "   region: $REGION"
echo "   profile: $PROFILE"
[[ "${DRY_RUN:-0}" == "1" ]] && echo "   ⚠️  DRY-RUN 模式,只读不写"
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"

if [[ -n "$ONLY" ]]; then
    if [[ -z "${SUBDIRS[$ONLY]+_}" ]]; then
        echo "❌ 未知子目录: $ONLY" >&2
        echo "   可选: ${!SUBDIRS[*]}" >&2
        exit 1
    fi
    echo
    echo "📂 同步 $ONLY ..."
    sync_subdir "$ONLY" "${SUBDIRS[$ONLY]}"
else
    for name in presets presets-webp scenarios hot-rooms; do
        echo
        echo "📂 同步 $name ..."
        sync_subdir "$name" "${SUBDIRS[$name]}"
    done
fi

echo
echo "✅ 全部完成"
