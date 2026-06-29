"""
Dry-run 探测维基: 对 79 个占位 svg 角色逐一查维基,只查询不下文件,把每个角色能匹配到的维基条目打印出来供人工审查。

策略:
  1. 先用 slug 中可能的英文部分(如 luka-doncic → "Luka Doncic")查 en wiki
  2. 再用中文名查 zh wiki
  3. 都失败则输出 None

输出: name | slug | best_url | en_title | zh_title | description
"""
import json
import re
import time
import urllib.parse
import urllib.request
import urllib.error
from pathlib import Path

ROOT = Path("/Users/yangq/Codes/Idea-Party")
PRESETS = ROOT / "server/src/main/resources/presets.json"
AVATAR_DIR = ROOT / "server/uploads/avatars/presets"
UA = "IdeaParty-AvatarBot/1.0 (https://github.com/ideaparty; preset-expansion; contact: dev@ideaparty.local)"

OUTPUT = ROOT / "scripts/preset-expansion/wiki_probe_results.jsonl"


def http_json(url, timeout=20):
    try:
        req = urllib.request.Request(url, headers={"User-Agent": UA, "Api-User-Agent": UA})
        with urllib.request.urlopen(req, timeout=timeout) as r:
            if r.status != 200:
                return None
            return json.loads(r.read())
    except Exception:
        return None


def slug_to_english(slug):
    """把 slug 切回可能的英文名,如 'luka-doncic' -> 'Luka Doncic' """
    # 移除 pinyin tone numbers: 'kai-wen-xi-si-te-luo-mu' 这种还原度差,只尝试基础替换
    s = slug.replace("-", " ")
    return s


def probe(name, slug):
    """返回 dict: {name, slug, en_url, en_title, zh_url, zh_title, chosen}"""
    en_url = zh_url = None
    en_title = zh_title = None
    en_desc = zh_desc = None

    # 1. 英文维基: 直接用 slug 试
    en_guess = slug_to_english(slug)
    encoded = urllib.parse.quote(en_guess.replace(" ", "_"))
    d = http_json(f"https://en.wikipedia.org/api/rest_v1/page/summary/{encoded}")
    if d and "thumbnail" in d:
        en_url = d["thumbnail"].get("source") or d.get("originalimage", {}).get("source")
        en_title = d.get("title")
        en_desc = d.get("description")
    else:
        # opensearch 找正确 title
        search = http_json(
            f"https://en.wikipedia.org/w/api.php?action=opensearch&format=json&limit=1&search={urllib.parse.quote(en_guess)}"
        )
        if search and isinstance(search, list) and len(search) >= 2 and search[1]:
            t = search[1][0]
            d = http_json(f"https://en.wikipedia.org/api/rest_v1/page/summary/{urllib.parse.quote(t.replace(' ', '_'))}")
            if d and "thumbnail" in d:
                en_url = d["thumbnail"].get("source") or d.get("originalimage", {}).get("source")
                en_title = d.get("title")
                en_desc = d.get("description")

    # 2. 中文维基
    d = http_json(f"https://zh.wikipedia.org/api/rest_v1/page/summary/{urllib.parse.quote(name)}")
    if d and "thumbnail" in d:
        zh_url = d["thumbnail"].get("source") or d.get("originalimage", {}).get("source")
        zh_title = d.get("title")
        zh_desc = d.get("description")

    chosen = en_url or zh_url  # 优先英文(更稳定)

    return {
        "name": name,
        "slug": slug,
        "en_url": en_url,
        "en_title": en_title,
        "en_desc": en_desc,
        "zh_url": zh_url,
        "zh_title": zh_title,
        "zh_desc": zh_desc,
        "chosen": chosen,
    }


def main():
    with PRESETS.open("r", encoding="utf-8") as f:
        data = json.load(f)

    targets = []
    for p in data:
        url = p.get("avatarUrl", "")
        if not url.endswith(".svg"):
            continue
        slug = url.rsplit("/", 1)[-1].replace(".svg", "")
        f_path = AVATAR_DIR / f"{slug}.svg"
        if f_path.exists() and f_path.stat().st_size < 2000:
            targets.append((p["name"], slug))

    print(f"待探测: {len(targets)} 个角色\n")
    with OUTPUT.open("w", encoding="utf-8") as out:
        for i, (name, slug) in enumerate(targets, 1):
            r = probe(name, slug)
            out.write(json.dumps(r, ensure_ascii=False) + "\n")
            status = "✓" if r["chosen"] else "✗"
            picked = ""
            if r["chosen"] == r["en_url"] and r["en_title"]:
                picked = f"EN: {r['en_title']}"
            elif r["chosen"] == r["zh_url"] and r["zh_title"]:
                picked = f"ZH: {r['zh_title']}"
            print(f"{status} [{i:2d}/{len(targets)}] {name:10s} | {slug:30s} | {picked}")
            time.sleep(0.4)  # 防限流

    print(f"\n结果已写入 {OUTPUT}")


if __name__ == "__main__":
    main()