"""
批量下载维基头像,只对 probe 结果中 url 非空的 68 个角色执行。

策略:
  - 读 wiki_probe_results.jsonl + wiki_probe_round2.jsonl 合并
  - 对每个有 url 的角色,下载到 {slug}.jpg (覆盖占位 svg)
  - 文件已存在且 size > 2000 跳过(断点续跑)
  - 下载失败保持 svg 占位不动
  - 每次请求 sleep 0.3s 防限流
"""
import json
import time
import urllib.request
from pathlib import Path

ROOT = Path("/Users/yangq/Codes/Idea-Party")
AVATAR_DIR = ROOT / "server/uploads/avatars/presets"
R1 = ROOT / "scripts/preset-expansion/wiki_probe_results.jsonl"
R2 = ROOT / "scripts/preset-expansion/wiki_probe_round2.jsonl"
UA = "IdeaParty-AvatarBot/1.0 (https://github.com/ideaparty; preset-expansion; contact: dev@ideaparty.local)"


def merge_records():
    records = {}
    for path in [R1, R2]:
        for l in path.read_text(encoding="utf-8").splitlines():
            if l.strip():
                r = json.loads(l)
                slug = r["slug"]
                if slug in records:
                    records[slug].update(r)
                else:
                    records[slug] = r
    return records


def download(url, dest):
    try:
        req = urllib.request.Request(url, headers={"User-Agent": UA})
        with urllib.request.urlopen(req, timeout=30) as r:
            data = r.read()
        if len(data) < 1000:
            return False
        dest.write_bytes(data)
        return True
    except Exception as e:
        return False


def main():
    records = merge_records()
    targets = []
    for slug, r in records.items():
        url = r.get("en_url") or r.get("zh_url")
        if url:
            targets.append((r["name"], slug, url))

    print(f"计划下载 {len(targets)} 个角色\n")
    success = skip = fail = 0
    for i, (name, slug, url) in enumerate(targets, 1):
        dest = AVATAR_DIR / f"{slug}.jpg"
        if dest.exists() and dest.stat().st_size > 2000:
            skip += 1
            print(f"  ◯ [{i}/{len(targets)}] {name:8s} 已存在,跳过")
            continue
        # 删除占位 svg (如存在)
        svg = AVATAR_DIR / f"{slug}.svg"
        if svg.exists():
            svg.unlink()
        if download(url, dest):
            success += 1
            print(f"  ✓ [{i}/{len(targets)}] {name:8s} → {dest.name} ({dest.stat().st_size // 1024}KB)")
        else:
            fail += 1
            print(f"  ✗ [{i}/{len(targets)}] {name:8s} 下载失败")
        time.sleep(0.3)

    print(f"\n下载: {success} | 跳过: {skip} | 失败: {fail}")


if __name__ == "__main__":
    main()