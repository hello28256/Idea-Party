"""
改 307 个新加角色名为罗马化英文。

步骤:
  1. 读 presets.json.bak4 (旧 280 个) + presets.json (现 587)
  2. 找出"新加的 307 个名字" (present in new, not in old)
  3. 对每个新名字:
     - 查 OVERRIDES 表(手工指定)
     - 查 pypinyin 自动转(剩余中文)
  4. 生成映射 name_old -> name_new (ASCII)
  5. 更新 presets.json:
     - name 字段
     - avatarUrl 字段(从 /xxx.jpg 改到 /<slug>.jpg)
  6. 重命名头像文件 (server/uploads/avatars/presets/)
  7. 输出 renames.json 报告

运行:python3 rename_presets.py [--dry-run]
"""
import json
import os
import re
import shutil
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))
from name_overrides import OVERRIDES

from pypinyin import lazy_pinyin, Style

ROOT = Path("/Users/yangq/Codes/Idea-Party")
PRESETS = ROOT / "server/src/main/resources/presets.json"
OLD = ROOT / "server/src/main/resources/presets.json.bak4"
AVATAR_DIR = ROOT / "server/uploads/avatars/presets/"
REPORT = ROOT / "scripts/preset-expansion/renames.json"


def to_romaji(name: str) -> str:
    if name in OVERRIDES:
        return OVERRIDES[name]
    # 拆 ·
    parts = []
    for seg in name.split("·"):
        if all(ord(c) < 128 for c in seg):
            parts.append(seg.lower())
        else:
            p = lazy_pinyin(seg, style=Style.NORMAL)
            parts.append("-".join(p))
    s = "-".join(p for p in parts if p)
    return s or "x"


def main():
    dry = "--dry-run" in sys.argv
    old_names = {c["name"] for c in json.loads(OLD.read_text(encoding="utf-8"))}
    data = json.loads(PRESETS.read_text(encoding="utf-8"))
    new_names = sorted({c["name"] for c in data} - old_names)
    print(f"新加角色: {len(new_names)}")

    # 找映射
    renames = {}
    duplicates = []
    used_slugs = set()
    for n in new_names:
        romaji = to_romaji(n)
        # 去重: 已有同 slug 走加后缀
        base = romaji
        i = 2
        while romaji in used_slugs:
            romaji = f"{base}-{i}"
            i += 1
        used_slugs.add(romaji)
        renames[n] = romaji

    # 检测重名(同一个 romaji 来自不同 n)
    by_romaji = {}
    for n, r in renames.items():
        by_romaji.setdefault(r, []).append(n)
    for r, ns in by_romaji.items():
        if len(ns) > 1:
            duplicates.append((r, ns))

    print(f"\n映射: {len(renames)} 个")
    if duplicates:
        print(f"\n重名警告({len(duplicates)}):")
        for r, ns in duplicates:
            print(f"  {r} <- {ns}")
    if dry:
        print("\n[DRY RUN] 全部映射:")
        for n, r in sorted(renames.items()):
            print(f"  {n} -> {r}")
        return

    # 备份
    shutil.copy2(PRESETS, PRESETS.with_suffix(".json.bak5"))
    print(f"\n备份: {PRESETS.with_suffix('.json.bak5')}")

    # 更新 presets.json
    updated = []
    for c in data:
        if c["name"] in renames:
            new_name = renames[c["name"]]
            c["name"] = new_name
            # 更新 avatarUrl: 替换文件名
            url = c.get("avatarUrl", "")
            if url:
                # /api/upload/avatars/presets/OLD.<ext> -> /api/upload/avatars/presets/NEW.<ext>
                old_file = url.rsplit("/", 1)[-1]
                ext = old_file.rsplit(".", 1)[-1] if "." in old_file else "jpg"
                new_file = f"{new_name}.{ext}"
                c["avatarUrl"] = f"/api/upload/avatars/presets/{new_file}"
        updated.append(c)

    # 重命名头像文件
    moved = 0
    failed = 0
    for c in updated:
        url = c.get("avatarUrl", "")
        if not url:
            continue
        new_file = url.rsplit("/", 1)[-1]
        new_path = AVATAR_DIR / new_file
        if new_path.exists():
            continue
        # 找老文件: 用原名 slug
        # 因为我们没存原名,只能扫盘找匹配
        # 策略: 老 slug 应该是 c['name'] 之前的 (中文) 或 部分已知
        # 简化: 用 renames 反向 — 但 c['name'] 已经被改了
        # 备份 presets.json.bak4 找老名
        pass

    # 上面逻辑不全,改用备份
    old_data = json.loads(OLD.read_text(encoding="utf-8"))
    old_by_id = {c["id"]: c for c in old_data}
    for c in updated:
        if c["id"] in old_by_id:
            continue  # 老角色不动
        # 找新加的: 跟 renames 找老名
        # 但 name 已改,无法直接反查
        # 用新 name 反查 slug
        pass

    # 简化: 直接按 avatarUrl 老路径扫盘
    # 老 avatarUrl 在原始 merged 后的 587 个文件里: 用 renames 反向构造
    # 我们备份了 merged 后的 587 没动,只动了 name.
    # 重新读 原 merged 587
    PRESETS_BEFORE = PRESETS.with_suffix(".json.bak5")
    before = json.loads(PRESETS_BEFORE.read_text(encoding="utf-8"))
    for old_c in before:
        if old_c["name"] in old_names:
            continue  # 老角色不动
        new_name = renames[old_c["name"]]
        old_url = old_c.get("avatarUrl", "")
        if not old_url:
            continue
        old_file = old_url.rsplit("/", 1)[-1]
        old_path = AVATAR_DIR / old_file
        if not old_path.exists():
            continue
        ext = old_file.rsplit(".", 1)[-1] if "." in old_file else "jpg"
        new_file = f"{new_name}.{ext}"
        new_path = AVATAR_DIR / new_file
        if old_path != new_path:
            try:
                old_path.rename(new_path)
                moved += 1
            except Exception as e:
                print(f"  ✗ 重命名失败: {old_path} -> {new_path}: {e}")
                failed += 1

    print(f"\n头像文件重命名: 成功 {moved}, 失败 {failed}")

    # 写新 presets.json
    PRESETS.write_text(
        json.dumps(updated, ensure_ascii=False, indent=2),
        encoding="utf-8",
    )
    print(f"已写: {PRESETS}")

    # 写报告
    REPORT.write_text(
        json.dumps(renames, ensure_ascii=False, indent=2),
        encoding="utf-8",
    )
    print(f"报告: {REPORT}")


if __name__ == "__main__":
    main()
