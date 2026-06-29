"""
用 Wikimedia Commons (https://commons.wikimedia.org/) API 搜图。
Commons 没被 en wiki 的 IP 限流影响,且收录了大量第三方照片、漫画截图、动漫海报。

策略:
  1. 用角色名 + 关键词搜 commons 文件 (srsearch)
  2. 过滤: 只要 jpg/png/webp,排除 svg/icon/logo/stub
  3. 用 imageinfo 取 thumburl (300-500px)
"""
import json
import time
import urllib.parse
import urllib.request
from pathlib import Path

ROOT = Path("/Users/yangq/Codes/Idea-Party")
OUTPUT = ROOT / "scripts/preset-expansion/wiki_probe_commons.jsonl"
UA = "Mozilla/5.0 (compatible; IdeaParty-AvatarBot/1.0; contact: dev@ideaparty.local)"


def http_json(url, timeout=30):
    try:
        req = urllib.request.Request(url, headers={"User-Agent": UA, "Api-User-Agent": UA})
        with urllib.request.urlopen(req, timeout=timeout) as r:
            if r.status != 200: return None
            return json.loads(r.read())
    except: return None


def commons_search(query, limit=15):
    d = http_json(f"https://commons.wikimedia.org/w/api.php?action=query&format=json&list=search&srnamespace=6&srlimit={limit}&srsearch={urllib.parse.quote(query)}")
    if not d: return []
    return d.get('query', {}).get('search', [])


def commons_imageinfo(title, width=400):
    d = http_json(f"https://commons.wikimedia.org/w/api.php?action=query&format=json&prop=imageinfo&iiprop=url&iiurlwidth={width}&titles={urllib.parse.quote(title)}")
    if not d: return None
    for p in d.get('query', {}).get('pages', {}).values():
        infos = p.get('imageinfo', [])
        if infos:
            return infos[0].get('thumburl') or infos[0].get('url')
    return None


# 对每个占位角色,多种关键词尝试 commons 搜索
cases = [
    # slug, 多个 search query (从具体到通用)
    ("mayer", ["Julius Robert von Mayer", "Julius Mayer physicist", "Julius von Mayer portrait"]),
    ("jia-ling", ["贾玲", "Jia Ling actress", "Jia Ling 喜剧"], ),
    ("zhong-shan-shan", ["钟睒睒", "Zhong Shanshan", "Nongfu Spring founder"]),
    ("doris-lin", ["Doris Lin", "Doris Lin entrepreneur"]),
    ("zlatan", ["Zlatan Ibrahimović 2018", "Zlatan Ibrahimovic", "Zlatan AC Milan"]),
    ("rukawa-kaede", ["Rukawa Kaede", "Kaede Rukawa Slam Dunk", "Rukawa anime"]),
    ("kishimoto-masashi", ["Masashi Kishimoto 2010", "Masashi Kishimoto 2015", "Masashi Kishimoto portrait"]),
    ("oda-eiichiro", ["Eiichiro Oda", "Eiichiro Oda 2020", "Oda One Piece author"]),
    ("kubo-tite", ["Tite Kubo", "Tite Kubo Bleach author", "Tite Kubo portrait"]),
    ("wei-qing", ["卫青", "Wei Qing Han dynasty", "Wei Qing general"]),
    ("li-yuan-ba", ["李元霸", "Li Yuanba", "Yuanba Sui dynasty"]),
    ("bagration", ["Bagration Pyotr", "Bagration general 1812", "Bagration portrait"]),
]

results = {}
for slug, queries in cases:
    print(f"\n=== {slug} ===")
    found = None
    for q in queries:
        time.sleep(0.5)
        hits = commons_search(q, limit=10)
        # 过滤: 只要图片文件,排除 svg/icon/logo/stub
        for hit in hits:
            title = hit.get("title", "")
            low = title.lower()
            if any(k in low for k in [".svg", "icon", "logo", "stub", "blank", "replace_this"]):
                continue
            if not (low.endswith((".jpg", ".jpeg", ".png", ".webp", ".tif"))):
                continue
            # 取 imageinfo
            time.sleep(0.3)
            img_url = commons_imageinfo(title, width=400)
            if img_url:
                print(f"  ✓ {q} → {title}")
                print(f"    {img_url[:90]}")
                found = {"url": img_url, "title": title, "query": q}
                break
        if found: break
    if not found:
        print(f"  ✗ 没找到")
    results[slug] = found

with OUTPUT.open("w", encoding="utf-8") as f:
    for slug, r in results.items():
        f.write(json.dumps({"slug": slug, "commons": r}, ensure_ascii=False) + "\n")
print(f"\n结果写入 {OUTPUT}")