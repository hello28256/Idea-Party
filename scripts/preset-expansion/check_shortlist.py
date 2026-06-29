"""
校验 shortlist.json:
  1. 每个分类的"已有 + 待新增"是否达到 50
  2. 列出每个分类的"真正需要新增"角色(去掉已在该分类的、跨分类去重)

运行:python3 check_shortlist.py
"""
import json
from pathlib import Path
from collections import defaultdict

PRESETS = Path("/Users/yangq/Codes/Idea-Party/server/src/main/resources/presets.json")
SHORTLIST = Path("/Users/yangq/Codes/Idea-Party/scripts/preset-expansion/shortlist.json")

data = json.loads(PRESETS.read_text())
short = json.loads(SHORTLIST.read_text())

# 现有名字按分类
existing_by_cat = defaultdict(set)
existing_names = set()
for c in data:
    existing_names.add(c["name"])
    for cat in c.get("categories", []):
        existing_by_cat[cat].add(c["name"])

# 短名单缺失校验
print("=" * 70)
print(f"presets.json 现有 {len(data)} 个角色")
print(f"分类数: {len(existing_by_cat)}")
print("=" * 70)

need_to_add_total = 0
all_new = set()
for cat in sorted(short):
    new = short[cat]
    exist_in_cat = existing_by_cat.get(cat, set())
    # 在 shortlist 里、但已在该分类的 → 不需要新增
    in_short_exist = [n for n in new if n in exist_in_cat]
    need = [n for n in new if n not in exist_in_cat]
    after_total = len(exist_in_cat) + len(new)  # 假设 shortlist 全新增
    print(f"\n--- {cat} ---")
    print(f"  已有: {len(exist_in_cat)}")
    print(f"  短名单条目: {len(new)} (其中 {len(in_short_exist)} 已在该分类, 实际需新增 {len(need)})")
    print(f"  若全部新增: 已有 + 新增 = {after_total}")
    if after_total < 50:
        deficit = 50 - after_total
        print(f"  ⚠️  仍差 {deficit} 个,需再补")
    need_to_add_total += len(need)
    all_new.update(need)

print(f"\n{'=' * 70}")
print(f"全部分类合计: 需新增角色(去重后) ≈ {len(all_new)}")
print(f"按分类合计(允许跨分类重复) = {need_to_add_total}")
print("=" * 70)
