"""
下载维基百科/Wikimedia 公开头像。

策略:
  1. 先中文维基 REST summary,404/失败时换英文维基
  2. 英文维基用 search 接口找正确 title(处理 redirect / disambiguation)
  3. 拿到 thumbnail/original URL 后下载
  4. 失败时生成首字母 SVG 占位
  5. 写回 generated.jsonl 的 avatarUrl 字段

断点续跑:已存在文件跳过;avatarUrl 已填的跳过。

运行:python3 download_avatars.py
"""
import json
import re
import sys
import time
import urllib.parse
import urllib.request
import urllib.error
from pathlib import Path

ROOT = Path("/Users/yangq/Codes/Idea-Party")
GENERATED = ROOT / "scripts/preset-expansion/generated.jsonl"
AVATAR_DIR = ROOT / "server/uploads/avatars/presets/"
UA = "IdeaParty-AvatarBot/1.0 (https://github.com/ideaparty; preset-expansion; contact: dev@ideaparty.local)"


def http_json(url: str, timeout: int = 20) -> dict | None:
    try:
        req = urllib.request.Request(url, headers={"User-Agent": UA, "Api-User-Agent": UA})
        with urllib.request.urlopen(req, timeout=timeout) as r:
            if r.status != 200:
                return None
            return json.loads(r.read())
    except (urllib.error.HTTPError, urllib.error.URLError, json.JSONDecodeError, TimeoutError, OSError):
        return None


def get_image(name: str) -> str | None:
    """中文维基 → 英文维基 search → 英文维基 summary"""
    encoded = urllib.parse.quote(name)
    # 1. 中文维基 REST
    d = http_json(f"https://zh.wikipedia.org/api/rest_v1/page/summary/{encoded}")
    if d and "thumbnail" in d:
        return d["thumbnail"].get("source") or d.get("originalimage", {}).get("source")
    # 2. 英文维基 search (找正确 title)
    search = http_json(
        f"https://en.wikipedia.org/w/api.php?action=opensearch&format=json&limit=1&search={urllib.parse.quote(name)}"
    )
    if search and isinstance(search, list) and len(search) >= 2 and search[1]:
        title = search[1][0]
        d = http_json(f"https://en.wikipedia.org/api/rest_v1/page/summary/{urllib.parse.quote(title)}")
        if d and "thumbnail" in d:
            return d["thumbnail"].get("source") or d.get("originalimage", {}).get("source")
    # 3. 兜底:英文维基直接 summary(用原名)
    d = http_json(f"https://en.wikipedia.org/api/rest_v1/page/summary/{encoded}")
    if d and "thumbnail" in d:
        return d["thumbnail"].get("source") or d.get("originalimage", {}).get("source")
    return None


def download(url: str, dest: Path) -> bool:
    try:
        req = urllib.request.Request(url, headers={"User-Agent": UA})
        with urllib.request.urlopen(req, timeout=30) as r:
            data = r.read()
        if len(data) < 1000:
            return False
        # 转 jpg
        dest.write_bytes(data)
        return True
    except Exception:
        return False


def make_placeholder(name: str, dest: Path) -> bool:
    ch = name.strip()[0] if name.strip() else "?"
    h = sum(ord(c) for c in name) % 360
    color1 = f"hsl({h}, 70%, 55%)"
    color2 = f"hsl({(h + 60) % 360}, 70%, 45%)"
    svg = f'''<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 200 200">
<defs><linearGradient id="g" x1="0" y1="0" x2="1" y2="1">
<stop offset="0" stop-color="{color1}"/><stop offset="1" stop-color="{color2}"/>
</linearGradient></defs>
<rect width="200" height="200" fill="url(#g)"/>
<text x="100" y="135" font-size="110" font-family="sans-serif" font-weight="700"
      text-anchor="middle" fill="white" opacity="0.9">{ch}</text>
</svg>'''
    dest.write_text(svg, encoding="utf-8")
    return True


def safe_slug(name: str) -> str:
    s = re.sub(r"[\s　]+", "-", name.strip())
    s = re.sub(r"[…·．・·]+", "-", s)  # 中点·等 → -
    s = re.sub(r"[^\w一-鿿-]", "", s)
    s = re.sub(r"-+", "-", s).strip("-").lower()
    return s or "x"


def main():
    if not GENERATED.exists():
        sys.exit("generated.jsonl 不存在")
    AVATAR_DIR.mkdir(parents=True, exist_ok=True)

    records = []
    for line in GENERATED.read_text(encoding="utf-8").splitlines():
        if line.strip():
            records.append(json.loads(line))

    print(f"共 {len(records)} 条\n")
    success, placeholder, fail = 0, 0, 0
    updated = []

    for i, rec in enumerate(records, 1):
        name = rec["name"]
        slug = rec.get("avatarSlug") or safe_slug(name)
        dest_jpg = AVATAR_DIR / f"{slug}.jpg"
        dest_svg = AVATAR_DIR / f"{slug}.svg"

        # 已有头像: 复用
        if dest_jpg.exists() and dest_jpg.stat().st_size > 2000:
            url_path = f"/api/upload/avatars/presets/{dest_jpg.name}"
            rec["avatarUrl"] = url_path
            updated.append(rec)
            success += 1
            if i % 30 == 0:
                print(f"  ✓ 已存在 [{i}/{len(records)}]")
            continue

        # 删除占位 svg (如果存在),允许重试
        if dest_svg.exists():
            dest_svg.unlink()

        img_url = get_image(name)
        if img_url and download(img_url, dest_jpg):
            rec["avatarUrl"] = f"/api/upload/avatars/presets/{dest_jpg.name}"
            success += 1
            print(f"  ↓ [{i}/{len(records)}] {name} → {dest_jpg.name}")
        else:
            make_placeholder(name, dest_svg)
            rec["avatarUrl"] = f"/api/upload/avatars/presets/{dest_svg.name}"
            placeholder += 1
            print(f"  ◇ [{i}/{len(records)}] {name} → 占位")

        updated.append(rec)
        time.sleep(0.2)

    with GENERATED.open("w", encoding="utf-8") as f:
        for r in updated:
            f.write(json.dumps(r, ensure_ascii=False) + "\n")

    print(f"\n总结: 维基下载 {success} | 占位图 {placeholder} | 失败 {fail}")


if __name__ == "__main__":
    main()
