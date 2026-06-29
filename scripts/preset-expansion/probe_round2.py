"""
第二轮探测: 对 probe_round1 中失败的/可能错配的角色,用更精准的关键词重试。

策略:
  - 用 description 字段构造更精确的英文名 (例如 "NBA球星 罗斯" → "Derrick Rose")
  - 已知 disambiguation 的角色,直接试具体条目
"""
import json
import time
import urllib.parse
import urllib.request
from pathlib import Path

ROOT = Path("/Users/yangq/Codes/Idea-Party")
PRESETS = ROOT / "server/src/main/resources/presets.json"
UA = "IdeaParty-AvatarBot/1.0 (https://github.com/ideaparty; preset-expansion; contact: dev@ideaparty.local)"

# 精准关键词 (slug → 英文 wiki title hint)
OVERRIDES = {
    "jokic": "Nikola Jokić",
    "thompson": "Klay Thompson",
    "aguero": "Sergio Agüero",
    "owen": "Michael Owen",
    "westbrook": "Russell Westbrook",
    "duncan": "Tim Duncan",
    "johnson": "Magic Johnson",
    "mccgrady": "Tracy McGrady",
    "hillary": "Hillary Clinton",
    "cameron": "David Cameron",
    "cha-wei-si": "Hugo Chávez",
    "xing-deng-bao": "Paul von Hindenburg",
    "watson": "James Watson",
    "crick": "Francis Crick",
    "morgan": "Thomas Hunt Morgan",
    "jenner": "Edward Jenner",
    "mayer": "Julius Robert von Mayer",
    "jia-ling": "Jia Ling",
    "pierre-omidyar": "Pierre Omidyar",
    "kai-wen-xi-si-te-luo-mu": "Kevin Systrom",
    "doris-lin": "Doris Lin",
    "lawrence": "D. H. Lawrence",
    "llosa": "Mario Vargas Llosa",
    "neruda": "Pablo Neruda",
    "kubo-tite": "Tite Kubo",
    "agasa-hakase": "Professor Agasa",
    "kagome": "Kagome Higurashi",
    "nats": "Natsu Dragneel",
    "erza": "Erza Scarlet",
    "li-yuan-ba": "Li Yuanba",
    "wei-qing": "Wei Qing",
    "rose": "Derrick Rose",
    "gray": "Gray Fullbuster",
    "lucy": "Lucy Heartfilia",
    "clark": "Arthur C. Clarke",
    "ke-er": "Helmut Kohl",
    "moltke": "Helmuth von Moltke the Elder",
    "schlieffen": "Alfred von Schlieffen",
    "kishimoto-masashi": "Masashi Kishimoto",
    "oda-eiichiro": "Eiichiro Oda",
    "kr-oz": "Old Joe Kennedy",
    "zhong-shan-shan": "Zhong Shanshan",
}


def http_json(url, timeout=20):
    try:
        req = urllib.request.Request(url, headers={"User-Agent": UA, "Api-User-Agent": UA})
        with urllib.request.urlopen(req, timeout=timeout) as r:
            if r.status != 200:
                return None
            return json.loads(r.read())
    except Exception:
        return None


def fetch(title):
    encoded = urllib.parse.quote(title.replace(" ", "_"))
    return http_json(f"https://en.wikipedia.org/api/rest_v1/page/summary/{encoded}")


def main():
    with PRESETS.open("r", encoding="utf-8") as f:
        data = json.load(f)

    targets = [(p["name"], p["avatarUrl"].rsplit("/", 1)[-1].replace(".svg", "")) for p in data if p.get("avatarUrl", "").endswith(".svg")]

    OUTPUT = ROOT / "scripts/preset-expansion/wiki_probe_round2.jsonl"
    out = OUTPUT.open("w", encoding="utf-8")

    for i, (name, slug) in enumerate(targets, 1):
        if slug not in OVERRIDES:
            continue
        title = OVERRIDES[slug]
        d = fetch(title)
        result = {"name": name, "slug": slug, "hint": title}
        if d:
            result["en_url"] = d.get("thumbnail", {}).get("source") or d.get("originalimage", {}).get("source")
            result["en_title"] = d.get("title")
            result["en_desc"] = d.get("description")
        else:
            # 找 redirects
            search = http_json(f"https://en.wikipedia.org/w/api.php?action=opensearch&format=json&limit=1&search={urllib.parse.quote(title)}")
            if search and isinstance(search, list) and len(search) >= 2 and search[1]:
                t = search[1][0]
                d = fetch(t)
                if d:
                    result["en_url"] = d.get("thumbnail", {}).get("source") or d.get("originalimage", {}).get("source")
                    result["en_title"] = d.get("title")
                    result["en_desc"] = d.get("description")
        out.write(json.dumps(result, ensure_ascii=False) + "\n")
        status = "✓" if result.get("en_url") else "✗"
        print(f"{status} {name:10s} | {slug:28s} | {result.get('en_title', '?')}")
        time.sleep(0.4)

    out.close()
    print(f"\n写入 {OUTPUT}")


if __name__ == "__main__":
    main()