"""
只改 307 个新加角色的 avatarUrl 字段:
  - 保留 name 字段中文不动
  - 把 avatarUrl 里的中文文件名映射为罗马化文件名
  - 用 renames.json 做映射

运行:python3 fix_avatar_urls.py
"""
import json
import shutil
import sys
from pathlib import Path

ROOT = Path("/Users/yangq/Codes/Idea-Party")
PRESETS = ROOT / "server/src/main/resources/presets.json"
OLD = ROOT / "server/src/main/resources/presets.json.bak4"
RENAMES = ROOT / "scripts/preset-expansion/renames.json"

# 读所有映射
renames = json.loads(RENAMES.read_text(encoding="utf-8"))
# renames 是: {chinese_name: romaji_slug}
# 但只对 307 个新加的有效

old_names = {c["name"] for c in json.loads(OLD.read_text(encoding="utf-8"))}
data = json.loads(PRESETS.read_text(encoding="utf-8"))
new_names = set(c["name"] for c in data) - old_names
print(f"新加角色: {len(new_names)}")

# 备份
shutil.copy2(PRESETS, PRESETS.with_suffix(".json.bak6"))
print(f"备份: {PRESETS.with_suffix('.json.bak6')}")

# 改 avatarUrl
fixed = 0
for c in data:
    if c["name"] not in new_names:
        continue
    url = c.get("avatarUrl", "")
    if not url:
        continue
    # /api/upload/avatars/presets/xxx.jpg -> 拿文件名
    fname = url.rsplit("/", 1)[-1]
    # 去后缀
    if "." in fname:
        stem, ext = fname.rsplit(".", 1)
    else:
        stem, ext = fname, "jpg"
    # stem 应该是中文,映射到 romaji
    if stem in renames:
        romaji = renames[stem]
        new_fname = f"{romaji}.{ext}"
        c["avatarUrl"] = f"/api/upload/avatars/presets/{new_fname}"
        fixed += 1
    else:
        # 可能是英文名(Adele, Lady Gaga)或已被处理过
        if all(ord(ch) < 128 for ch in stem):
            # 英文名:不强求改(已经 ASCII)
            pass
        else:
            print(f"  ⚠️ 未找到映射: {stem}")

print(f"\n修改 avatarUrl: {fixed} 条")

# 写
PRESETS.write_text(
    json.dumps(data, ensure_ascii=False, indent=2),
    encoding="utf-8",
)
print(f"已写: {PRESETS}")
