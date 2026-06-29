"""
把 generated_v3.jsonl 里生成的 prompt 写回 server/src/main/resources/presets.json。

匹配规则 (从粗到细, 命中即停):
  1) name 精确匹配 + categories[0] 与 generated_v3 的 category 一致
  2) name 精确匹配 (兜底, 不限 category; 如果多条同 name, 取第一条)

不匹配的角色会写到 unmatched.json 供人工 review。

不直接覆盖, 先写到 presets.json.next, 你确认 diff 后手动替换。

用法:
  python3 scripts/preset-expansion/merge_v3_to_presets.py
"""

import json
import sys
from collections import defaultdict
from pathlib import Path

ROOT = Path("/Users/yangq/Codes/Idea-Party")
PRESETS = ROOT / "server" / "src" / "main" / "resources" / "presets.json"
PRESETS_NEXT = ROOT / "server" / "src" / "main" / "resources" / "presets.json.next"
GEN = ROOT / "scripts" / "preset-expansion" / "generated_v3.jsonl"
UNMATCHED = ROOT / "scripts" / "preset-expansion" / "unmatched.json"


def load_generated() -> list[dict]:
    if not GEN.exists():
        sys.exit(f"找不到 {GEN}, 先跑 regen_via_system_api.py")
    out = []
    for line in GEN.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if not line:
            continue
        rec = json.loads(line)
        if rec.get("prompt"):
            out.append(rec)
    print(f"[*] generated_v3 有效记录: {len(out)}")
    return out


def main():
    if not PRESETS.exists():
        sys.exit(f"找不到 {PRESETS}")
    presets = json.loads(PRESETS.read_text(encoding="utf-8"))
    print(f"[*] presets.json 总条目: {len(presets)}")

    # 按 name 分桶, 同时记录 categories
    by_name: dict[str, list[dict]] = defaultdict(list)
    for p in presets:
        by_name[p["name"]].append(p)

    gen = load_generated()

    matched = []
    unmatched = []
    change_log = []

    for rec in gen:
        name = rec["name"]
        cat = rec.get("category")
        candidates = by_name.get(name, [])
        target = None
        if cat and candidates:
            for c in candidates:
                if cat in c.get("categories", []):
                    target = c
                    break
        if target is None and candidates:
            target = candidates[0]

        if target is None:
            unmatched.append(rec)
            continue

        old_prompt = target.get("prompt", "")
        new_prompt = rec["prompt"]
        target["prompt"] = new_prompt
        matched.append(rec)
        change_log.append(
            {
                "name": name,
                "category": cat,
                "old_len": len(old_prompt),
                "new_len": len(new_prompt),
            }
        )

    PRESETS_NEXT.write_text(
        json.dumps(presets, ensure_ascii=False, indent=2),
        encoding="utf-8",
    )

    UNMATCHED.write_text(
        json.dumps(unmatched, ensure_ascii=False, indent=2),
        encoding="utf-8",
    )

    print(f"\n[+] matched: {len(matched)}")
    print(f"[!] unmatched (presets.json 里找不到这个 name): {len(unmatched)}")
    if unmatched:
        print(f"    详见 {UNMATCHED.name}")
    print(f"\n[+] 已写入 {PRESETS_NEXT.relative_to(ROOT)}")
    print(f"[*] 变更前/后长度对比 (前 20 条):")
    for c in change_log[:20]:
        print(f"    [{c['category']:18s}] {c['name']:10s}  {c['old_len']:>5d} -> {c['new_len']:>5d}")
    if len(change_log) > 20:
        print(f"    ... 共 {len(change_log)} 条")

    print("\n[!] 还没动 presets.json 本体。请对比 diff:")
    print(f"    diff {PRESETS} {PRESETS_NEXT}")
    print("    确认无误后:")
    print(f"    mv {PRESETS_NEXT} {PRESETS}")


if __name__ == "__main__":
    main()