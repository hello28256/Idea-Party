"""
第二轮 commons 搜索:
  - 更严格的关键词 (用 intitle: 限定文件名)
  - 用 generator=search + prop=imageinfo 一次拿全
  - 关键词必须包含角色姓氏 (避免假阳性)
"""
import json
import time
import urllib.parse
import urllib.request
from pathlib import Path

ROOT = Path("/Users/yangq/Codes/Idea-Party")
OUTPUT = ROOT / "scripts/preset-expansion/wiki_probe_commons_v2.jsonl"
UA = "Mozilla/5.0 (compatible; IdeaParty-AvatarBot/1.0; contact: dev@ideaparty.local)"


def commons_strict_search(query, limit=15):
    """一次拿 (titles + imageinfo thumburl)"""
    params = "action=query&format=json&generator=search&gsrnamespace=6&gsrlimit={limit}&prop=imageinfo&iiprop=url&iiurlwidth=400&gsrsearch={query}".format(
        limit=limit, query=urllib.parse.quote(query)
    )
    url = f"https://commons.wikimedia.org/w/api.php?{params}"
    try:
        req = urllib.request.Request(url, headers={"User-Agent": UA, "Api-User-Agent": UA})
        with urllib.request.urlopen(req, timeout=30) as r:
            if r.status != 200: return []
            d = json.loads(r.read())
    except: return []

    results = []
    for pid, p in d.get('query', {}).get('pages', {}).items():
        title = p.get("title", "")
        infos = p.get("imageinfo", [])
        if not infos: continue
        thumb = infos[0].get("thumburl") or infos[0].get("url")
        results.append({"title": title, "thumb": thumb, "size": infos[0].get("size", 0)})
    return results


def score(hit, must_words, forbid_words):
    title_l = hit["title"].lower()
    if not all(w in title_l for w in must_words): return -1
    if any(w in title_l for w in forbid_words): return -1
    if not hit["thumb"]: return -1
    if hit["size"] < 1000: return -1
    # 偏好简短文件名 (不太像组合图)
    return -len(title_l)


# 12 个剩余角色,每个指定必须包含的词 (must_words, 小写) + 排除词
cases = [
    ("mayer", ["mayer"], ["bridge", "yearbook", "panoramio", "yang", "jia ling"]),
    ("jia-ling", ["jia", "ling"], ["yang", "jia ling", "panoramio", "yan'an"]),
    ("zhong-shan-shan", ["zhong"], ["shanshan river", "panoramio", "bridge"]),
    ("doris-lin", [], ["pray", "總統", "president", "church"]),
    ("zlatan", ["zlatan"], []),
    ("rukawa-kaede", ["rukawa"], []),
    ("kishimoto", ["kishimoto"], ["bridge", "stream", "hakalau"]),
    ("oda", ["oda"], ["banane", "vendeuse", "banana"]),
    ("kubo", ["kubo"], ["yearbook", "south high"]),
    ("wei-qing", ["wei"], []),  # "wei qing" 太通用,先看结果再调
    ("li-yuan-ba", ["李元霸"], []),
    ("bagration", ["bagration"], []),
]

results = {}
for slug, must, forbid in cases:
    print(f"\n=== {slug} (must={must}, forbid={forbid}) ===")
    # 多种关键词尝试
    if slug == "mayer":
        queries = ['"Julius Robert Mayer"', '"Julius von Mayer"', 'Mayer physicist portrait']
    elif slug == "jia-ling":
        queries = ['"Jia Ling"', '"Jia Ling" actress', '"贾玲" portrait', '"Jia Ling" filmmaker']
    elif slug == "zhong-shan-shan":
        queries = ['"Zhong Shanshan"', '"Zhong Shanshan" businessman', '"钟睒睒"']
    elif slug == "doris-lin":
        queries = ['"Doris Lin"', '"Doris Lin" founder']
    elif slug == "zlatan":
        queries = ['"Zlatan Ibrahimović"', '"Zlatan" footballer', '"Zlatan Ibrahimovic"']
    elif slug == "rukawa-kaede":
        queries = ['"Rukawa"', '"Rukawa Kaede"', '"Kaede Rukawa"']
    elif slug == "kishimoto":
        queries = ['"Masashi Kishimoto"', '"Kishimoto Masashi"', '"Masashi Kishimoto" manga']
    elif slug == "oda":
        queries = ['"Eiichiro Oda"', '"Oda Eiichiro"', '"Eiichiro Oda" manga']
    elif slug == "kubo":
        queries = ['"Tite Kubo"', '"Tite" manga', '"Kubo Tite"']
    elif slug == "wei-qing":
        queries = ['"Wei Qing"', '"Wei Qing" general', '"卫青"']
    elif slug == "li-yuan-ba":
        queries = ['"李元霸"', '"Li Yuanba"']
    elif slug == "bagration":
        queries = ['"Bagration"', '"Pyotr Bagration"']
    else:
        queries = [slug]

    found = None
    for q in queries:
        time.sleep(0.5)
        hits = commons_strict_search(q, limit=10)
        hits.sort(key=lambda h: score(h, must, forbid), reverse=True)
        for h in hits:
            s = score(h, must, forbid)
            if s < 0: continue
            print(f"  ✓ [{q}] score={s} {h['title']}")
            print(f"    {h['thumb'][:90]}")
            found = h
            break
        if found: break
    if not found:
        print(f"  ✗ no hit")
    results[slug] = found

with OUTPUT.open("w", encoding="utf-8") as f:
    for slug, r in results.items():
        f.write(json.dumps({"slug": slug, "commons": r}, ensure_ascii=False) + "\n")
print(f"\n结果写入 {OUTPUT}")