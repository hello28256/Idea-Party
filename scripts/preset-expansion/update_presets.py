"""
回写 presets.json: 对每个角色,如果磁盘上 {slug}.jpg 存在且 > 2000 bytes,
把 presets.json 中对应 entry 的 avatarUrl 从 .svg 改成 .jpg。
"""
import json
from pathlib import Path

ROOT = Path("/Users/yangq/Codes/Idea-Party")
PRESETS = ROOT / "server/src/main/resources/presets.json"
AVATAR_DIR = ROOT / "server/uploads/avatars/presets"


def main():
    with PRESETS.open("r", encoding="utf-8") as f:
        data = json.load(f)

    updated = 0
    unchanged = 0
    for p in data:
        url = p.get("avatarUrl", "")
        if not url.endswith(".svg"):
            continue
        slug = url.rsplit("/", 1)[-1].replace(".svg", "")
        jpg = AVATAR_DIR / f"{slug}.jpg"
        if jpg.exists() and jpg.stat().st_size > 2000:
            p["avatarUrl"] = f"/api/upload/avatars/presets/{slug}.jpg"
            updated += 1
        else:
            unchanged += 1

    with PRESETS.open("w", encoding="utf-8") as f:
        json.dump(data, f, ensure_ascii=False, indent=2)

    print(f"✓ 更新 {updated} 条 → jpg")
    print(f"  保持 {unchanged} 条 → svg (占位)")


if __name__ == "__main__":
    main()