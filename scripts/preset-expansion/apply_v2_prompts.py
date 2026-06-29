"""
应用 generated_v2.jsonl 的新 prompt 到 presets.json:
  - 只改 307 个新加角色的 prompt 字段
  - 不动 name / avatarUrl / description / categories
  - 备份原文件
"""
import json
import shutil
from pathlib import Path

ROOT = Path("/Users/yangq/Codes/Idea-Party")
V2 = ROOT / "scripts/preset-expansion/generated_v2.jsonl"
PRESETS = ROOT / "server/src/main/resources/presets.json"
OLD = ROOT / "server/src/main/resources/presets.json.bak4"

v2 = {}
for line in V2.read_text(encoding="utf-8").splitlines():
    if line.strip():
        r = json.loads(line)
        v2[r["name"]] = r["prompt"]
print(f"v2 prompt 数量: {len(v2)}")

old_names = {c["name"] for c in json.loads(OLD.read_text(encoding="utf-8"))}
data = json.loads(PRESETS.read_text(encoding="utf-8"))

# 备份
shutil.copy2(PRESETS, PRESETS.with_suffix(".json.bak7"))
print(f"备份: {PRESETS.with_suffix('.json.bak7')}")

# 应用
updated = 0
missing = 0
for c in data:
    if c["name"] not in old_names:
        # 新角色
        if c["name"] in v2:
            c["prompt"] = v2[c["name"]]
            updated += 1
        else:
            missing += 1
            print(f"  ⚠️ v2 缺: {c['name']}")

print(f"\n更新 prompt: {updated}, v2 缺失: {missing}")

PRESETS.write_text(json.dumps(data, ensure_ascii=False, indent=2), encoding="utf-8")
print(f"已写: {PRESETS}")
