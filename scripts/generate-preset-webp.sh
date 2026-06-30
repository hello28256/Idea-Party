#!/usr/bin/env bash
# generate-preset-webp.sh — 把 server/uploads/avatars/presets/*.jpg 压缩成 192x192 WebP
#
# 目的:推荐角色头像在 UI 上 48px 显示(@2x = 96px),源 jpg 平均 42KB,网络慢;
#       WebP @2x 平均 ~6KB,首屏加载快 70%。
#
# 输出目录:server/uploads/avatars/presets-webp/ 与源文件同 basename。
# nginx 用 Accept 头协商:浏览器支持 WebP 时返回 webp,否则回退到 jpg。
#
# 用法:
#   ./scripts/generate-preset-webp.sh                 # 全量生成
#   ./scripts/generate-preset-webp.sh "王阳明.jpg" ... # 只生成指定文件
#
# 部署后只跑一次;后续新增预设头像时重跑。

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SRC_DIR="$REPO_ROOT/server/uploads/avatars/presets"
DST_DIR="$REPO_ROOT/server/uploads/avatars/presets-webp"

# 192 = 48px @ 4x retina;给后续扩展留余量,实际显示 48px。
# WebP quality 80:视觉无损区间,文件大小比 75 还小 15%。
SIZE=192
QUALITY=80

if [ ! -d "$SRC_DIR" ]; then
  echo "ERROR: source dir not found: $SRC_DIR"
  exit 1
fi

mkdir -p "$DST_DIR"

# 用户指定了具体文件:只处理这些;否则处理全部 jpg/jpeg
if [ $# -gt 0 ]; then
  files=("$@")
else
  files=()
  while IFS= read -r f; do
    files+=("$f")
  done < <(find "$SRC_DIR" -maxdepth 1 -type f \( -iname '*.jpg' -o -iname '*.jpeg' \) | sort)
fi

if [ ${#files[@]} -eq 0 ]; then
  echo "No jpg files to process"
  exit 0
fi

# 先用 Pillow 缩到 192x192(保持纵横比,居中裁剪)再 pipe 给 cwebp:
#   - 缩放保留原图比例,cwebp 编码更高效
#   - 中心裁剪成 192x192,头像不会因为源图是横图/竖图变形
#   - cwebp 从 stdin 读 PNG(质量更高),所以输出 png 到 stdout
total=${#files[@]}
ok=0
fail=0

for src in "${files[@]}"; do
  # 转绝对路径:
  #   1) 已经是绝对路径(find 返回的) -> 原样用
  #   2) 相对路径(用户 CLI 传的) -> 在 $SRC_DIR 下找(用户用 `name.jpg` 调用最方便)
  case "$src" in
    /*) abs_src="$src" ;;
    *)  abs_src="$SRC_DIR/$src" ;;
  esac
  name="$(basename "$abs_src")"
  base="${name%.*}"
  dst="$DST_DIR/$base.webp"
  # cwebp 不支持 stdin,所以先让 Pillow 输出 PNG 到临时文件,再喂给 cwebp
  tmp_png="$(mktemp -t preset-webp.XXXXXX.png)"
  trap 'rm -f "$tmp_png"' RETURN

  python3 - "$abs_src" "$SIZE" "$tmp_png" <<'PY'
import sys
from PIL import Image
src, size, out = sys.argv[1], int(sys.argv[2]), sys.argv[3]
im = Image.open(src).convert('RGB')
w, h = im.size
# 中心裁剪成正方形:取较小边
side = min(w, h)
left = (w - side) // 2
top = (h - side) // 2
im = im.crop((left, top, left + side, top + side))
im = im.resize((size, size), Image.LANCZOS)
im.save(out, format='PNG', optimize=True)
PY
  py_rc=$?
  if [ $py_rc -ne 0 ] || [ ! -s "$tmp_png" ]; then
    fail=$((fail + 1))
    echo "FAIL (pillow): $name"
    rm -f "$tmp_png"
    continue
  fi
  cwebp -quiet -q "$QUALITY" "$tmp_png" -o "$dst" 2>/dev/null
  webp_rc=$?
  rm -f "$tmp_png"
  if [ $webp_rc -eq 0 ] && [ -s "$dst" ]; then
    ok=$((ok + 1))
  else
    fail=$((fail + 1))
    rm -f "$dst"
    echo "FAIL (cwebp): $name"
  fi
done

# 统计:总大小变化
src_bytes=$(du -sk "$SRC_DIR" 2>/dev/null | awk '{print $1*1024}')
dst_bytes=$(du -sk "$DST_DIR" 2>/dev/null | awk '{print $1*1024}')

echo "Done: $ok ok, $fail fail  (total $total)"
echo "Source jpg total: ${src_bytes} bytes"
echo "Output webp total: ${dst_bytes} bytes"
if [ "$src_bytes" -gt 0 ]; then
  pct=$(awk "BEGIN{printf \"%.1f\", (1 - $dst_bytes/$src_bytes) * 100}")
  echo "Saved: ${pct}%"
fi