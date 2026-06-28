#!/usr/bin/env bash
# 从维基百科拉取真实头像，覆盖 server/uploads/avatars/presets/ 下的占位图。
#
# 用法:
#   ./scripts/refresh-preset-avatar.sh <slug> [search_query]
#   例: ./scripts/refresh-preset-avatar.sh wang-xing "Wang Xing businessman"
#
# 流程（参考 server/src/main/java/com/ideaparty/service/FirecrawlService.java 的两条 API 路径）:
#   1. list=search  → 解析出消歧后的英文维基条目标题
#   2. summary API   → 拿 originalimage.source 直链
#   3. curl 下载     → 覆盖预设头像
#
# 不带 search_query 时默认按 slug 转空格作为搜词；同名人物需要消歧时显式传 search_query。

set -euo pipefail

if [[ $# -lt 1 ]]; then
    echo "用法: $0 <slug> [search_query]" >&2
    echo "例:   $0 wang-xing \"Wang Xing businessman\"" >&2
    exit 1
fi

SLUG="$1"
SEARCH_QUERY="${2:-${SLUG//-/ }}"

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
AVATAR_DIR="$ROOT_DIR/server/uploads/avatars/presets"
TARGET_FILE="$AVATAR_DIR/$SLUG.jpg"

if [[ ! -d "$AVATAR_DIR" ]]; then
    echo "❌ 头像目录不存在: $AVATAR_DIR" >&2
    exit 1
fi

UA="IdeaParty/1.0 (contact@idea-party.local)"

echo "🔍 搜索: $SEARCH_QUERY"

# Step 1: list=search 解析消歧后的英文维基条目标题
TITLE=$(curl -s "https://en.wikipedia.org/w/api.php?action=query&list=search&srsearch=$(python3 -c "import urllib.parse,sys; print(urllib.parse.quote(sys.argv[1]))" "$SEARCH_QUERY")&srlimit=5&srnamespace=0&format=json" \
    -H "User-Agent: $UA" \
    | python3 -c "
import json, sys
d = json.load(sys.stdin)
results = d.get('query', {}).get('search', [])
for r in results:
    title = r.get('title', '')
    if 'disambiguation' in title.lower() or '消歧义' in title:
        continue
    print(title)
    break
")

if [[ -z "$TITLE" ]]; then
    echo "❌ 未在英文维基找到 '$SEARCH_QUERY' 的对应条目" >&2
    exit 1
fi

echo "✅ 命中条目: $TITLE"

# Step 2: summary API 拿 originalimage 直链
SUMMARY=$(curl -s "https://en.wikipedia.org/api/rest_v1/page/summary/$(python3 -c "import urllib.parse,sys; print(urllib.parse.quote(sys.argv[1].replace(' ', '_')))" "$TITLE")" \
    -H "User-Agent: $UA")

# 二次校验：summary API 返回 type=disambiguation 时说明 list=search 命中的还是消歧页，
# 此时拿不到图，要让用户显式传 search_query 消歧，而不是静默吞错。
PAGE_TYPE=$(echo "$SUMMARY" | python3 -c "import json,sys; print(json.load(sys.stdin).get('type', ''))")
if [[ "$PAGE_TYPE" == "disambiguation" ]]; then
    echo "❌ '$TITLE' 是消歧义页，请用 search_query 明确指向目标条目" >&2
    echo "   例: $0 $SLUG \"Aleksandr Vasilevsky\"" >&2
    exit 1
fi

IMG_URL=$(echo "$SUMMARY" | python3 -c "
import json, sys
d = json.load(sys.stdin)
oi = d.get('originalimage') or {}
print(oi.get('source', ''))
")

if [[ -z "$IMG_URL" ]]; then
    echo "❌ 条目 '$TITLE' 没有 originalimage（无 infobox 头像）" >&2
    exit 1
fi

echo "🖼  头像直链: $IMG_URL"

# Step 3: 下载到临时目录，验证后再覆盖
TMP_FILE=$(mktemp -t avatar.XXXXXX)
trap "rm -f $TMP_FILE" EXIT

curl -L -s -o "$TMP_FILE" "$IMG_URL" -H "User-Agent: $UA"

# 简单校验：必须是真 JPEG/PNG，且尺寸 >= 50x50，避免下载到图标/SVG 占位
FILE_TYPE=$(file -b --mime-type "$TMP_FILE")
case "$FILE_TYPE" in
    image/jpeg|image/png) ;;
    *) echo "❌ 下载的不是合法图片（$FILE_TYPE），跳过覆盖" >&2; exit 1 ;;
esac

# 覆盖前备份
if [[ -f "$TARGET_FILE" ]]; then
    cp "$TARGET_FILE" "${TARGET_FILE}.bak"
    echo "📦 已备份旧文件到 ${TARGET_FILE}.bak"
fi

cp "$TMP_FILE" "$TARGET_FILE"
echo "✅ 已写入: $TARGET_FILE"

# 提示：建议用 vision 肉眼验证一次
echo "💡 提示: 用 mmx vision describe --image $TARGET_FILE 肉眼确认是预期人物"