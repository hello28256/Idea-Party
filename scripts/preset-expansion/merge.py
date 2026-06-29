"""
合并 generated.jsonl 到 presets.json。

逻辑:
  1. 读 presets.json 现有记录
  2. 读 generated.jsonl,每条转 Character 字段格式
  3. 同一 name 在多分类里的:合并 categories (去重)
  4. UUID 用 uuid4 生成
  5. era / speakingStyle / persona 留空
  6. 备份原文件 → presets.json.bak
  7. 写新文件

运行:python3 merge.py
"""
import json
import shutil
import sys
import uuid
from pathlib import Path

ROOT = Path("/Users/yangq/Codes/Idea-Party")
GENERATED = ROOT / "scripts/preset-expansion/generated.jsonl"
PRESETS = ROOT / "server/src/main/resources/presets.json"
BACKUP = PRESETS.with_suffix(".json.bak4")

if not GENERATED.exists():
    sys.exit("generated.jsonl 不存在")
if not PRESETS.exists():
    sys.exit(f"presets.json 不存在: {PRESETS}")


def main():
    # 1. 读现有
    existing = json.loads(PRESETS.read_text(encoding="utf-8"))
    by_name = {c["name"]: c for c in existing}

    # 2. 读 generated
    new_recs = []
    for line in GENERATED.read_text(encoding="utf-8").splitlines():
        if line.strip():
            new_recs.append(json.loads(line))

    # 3. 按 name 聚合 categories
    grouped: dict[str, dict] = {}
    for r in new_recs:
        name = r["name"]
        if name not in grouped:
            grouped[name] = {
                "name": name,
                "description": r["description"],
                "prompt": r["prompt"],
                "avatarUrl": r.get("avatarUrl", ""),
                "categories": [r["category"]],
            }
        else:
            if r["category"] not in grouped[name]["categories"]:
                grouped[name]["categories"].append(r["category"])

    added = 0
    skipped_existing = 0
    for name, g in grouped.items():
        if name in by_name:
            # 已存在,合并分类
            cats = set(by_name[name].get("categories", [])) | set(g["categories"])
            by_name[name]["categories"] = sorted(cats)
            skipped_existing += 1
            continue
        new_c = {
            "id": str(uuid.uuid4()),
            "name": name,
            "description": g["description"],
            "prompt": g["prompt"],
            "avatarUrl": g["avatarUrl"],
            "era": "",
            "speakingStyle": "",
            "persona": "",
            "categories": g["categories"],
        }
        existing.append(new_c)
        added += 1

    # 4. 备份
    shutil.copy2(PRESETS, BACKUP)
    print(f"已备份原文件 → {BACKUP}")

    # 5. 写
    PRESETS.write_text(
        json.dumps(existing, ensure_ascii=False, indent=2),
        encoding="utf-8",
    )
    print(f"已写新文件: {PRESETS}")
    print(f"\n新增角色: {added} | 已存在仅合并分类: {skipped_existing}")
    print(f"总角色数: {len(existing)}")


if __name__ == "__main__":
    main()
