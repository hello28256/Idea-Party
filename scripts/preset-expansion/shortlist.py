"""
为每个分类列出"待补足的高热度角色"。

约定:
  - 允许跨分类重合(与现有"毛泽东=三分类"保持一致),所以同一名字可出现在多类
  - 角色按热度(普通认知度)排序
  - 每个分类补到恰好 50 个:已有 + 新增 = 50
  - 输入:从 presets.json 读现有名字+分类
  - 输出:补足名单 JSON,按分类聚合

运行:python3 shortlist.py
"""
import json
from pathlib import Path
from collections import defaultdict

PRESETS = Path("/Users/yangq/Codes/Idea-Party/server/src/main/resources/presets.json")
OUT = Path("/Users/yangq/Codes/Idea-Party/scripts/preset-expansion/shortlist.json")

data = json.loads(PRESETS.read_text())
by_cat = defaultdict(set)
existing = {c["name"]: c for c in data}
for c in data:
    for cat in c.get("categories", []):
        by_cat[cat].add(c["name"])

print(f"现有 {len(data)} 个角色, {len(by_cat)} 个分类\n")
for cat in sorted(by_cat):
    print(f"  {cat}: {len(by_cat[cat])}")
