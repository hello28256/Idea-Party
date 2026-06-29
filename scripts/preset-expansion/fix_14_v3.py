"""
v3 修复 14 个头像:
- 用 commons.wikimedia.org/wiki/Special:FilePath/xxx.jpg 下载原图
- 先从英文维基页面 HTML 找图文件名(走 en, 不走 commons API)
- 失败 fallback: 试常见命名变体
"""
import json
import re
import sys
import time
import urllib.request
import urllib.parse
from pathlib import Path

ROOT = Path("/Users/yangq/Codes/Idea-Party")
PRESETS = ROOT / "server/src/main/resources/presets.json"
AVATAR_DIR = ROOT / "server/uploads/avatars/presets/"
UA = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36"

# 14 个角色的维基页面 + 备用常见文件名
TARGETS = {
    "毛利兰": ("Ran_Mouri", ["Ran_Mouri.png", "Mouri_Ran.png"]),  # 动漫角色,可能只有 PNG
    "迈尔": ("Julius_von_Mayer", ["Julius_von_Mayer.jpg", "Julius_Robert_von_Mayer.jpg"]),
    "贾玲": ("Jia_Ling", ["Jia_Ling_2019.jpg", "Jia_Ling_at_event.jpg", "Jia_Ling_film.jpg"]),
    "钟睒睒": ("Zhong_Shanshan", ["Zhong_Shanshan.jpg", "Zhong_Shanshan_2017.jpg"]),
    "多丽丝·林": ("Doris_Lin", ["Doris_Lin.jpg"]),  # 早期 Yahoo 联创
    "伊布": ("Zlatan_Ibrahimović", []),  # already done
    "流川枫": ("Rukawa_Kaede", ["Rukawa.png"]),  # 动漫角色
    "岸本齐史": ("Masashi_Kishimoto", ["Masashi_Kishimoto_2014.jpg", "Masashi_Kishimoto_2019.jpg", "Masashi_Kishimoto_2.jpg"]),
    "尾田荣一郎": ("Eiichiro_Oda", ["Eiichiro_Oda_2019.jpg", "Eiichiro_Oda_2018.jpg"]),
    "久保带人": ("Tite_Kubo", ["Tite_Kubo_2014.jpg", "Tite_Kubo.jpg"]),
    "朽木露琪亚": ("Rukia_Kuchiki", ["Rukia.png"]),  # 动漫角色
    "卫青": ("Wei_Qing_(general)", ["Wei_Qing.jpg", "Wei_Qing_(Han_dynasty).jpg"]),
    "李元霸": ("Li_Yuanba", []),  # 虚构人物,可能无图
    "巴格拉季昂": ("Pyotr_Bagration", ["Bagration.jpg", "Pyotr_Bagration.jpg"]),
}


def fetch(url, timeout=25):
    try:
        req = urllib.request.Request(url, headers={"User-Agent": UA, "Accept-Language": "en-US,en;q=0.9"})
        with urllib.request.urlopen(req, timeout=timeout) as r:
            if r.status == 200:
                return r.read()
    except Exception as e:
        return f"ERR:{type(e).__name__}:{str(e)[:60]}"
    return None


def get_image_filename_from_wiki(page):
    """从维基 HTML 拿第一张上传图的原文件名"""
    url = f"https://en.wikipedia.org/wiki/{urllib.parse.quote(page)}"
    html = fetch(url)
    if not html or isinstance(html, bytes) is False:
        return None
    # 找 infobox 里的图
    m = re.search(rb'(<table[^>]*infobox[^>]*>.*?</table>)', html, re.DOTALL | re.IGNORECASE)
    src = m.group(1) if m else html
    # 找原图(不要 /thumb/)
    urls = re.findall(rb'https?://upload\.wikimedia\.org/wikipedia/commons/[a-f0-9]/[a-f0-9]{2}/[^\s"\'<>]+\.(?:jpg|JPG|jpeg|JPEG|png|PNG)', src)
    if not urls:
        # 退化: 找全页上传图
        urls = re.findall(rb'https?://upload\.wikimedia\.org/wikipedia/commons/[a-f0-9]/[a-f0-9]{2}/[^\s"\'<>]+\.(?:jpg|JPG|jpeg|JPEG|png|PNG)', html)
    if urls:
        for u in urls:
            ud = u.decode()
            if "logo" not in ud.lower() and "icon" not in ud.lower() and "shackle" not in ud.lower():
                # 提取文件名
                fname = ud.split("/")[-1]
                return urllib.parse.unquote(fname)
    return None


def download_from_filepath(filename, dest):
    """用 commons FilePath 拿原图"""
    url = f"https://commons.wikimedia.org/wiki/Special:FilePath/{urllib.parse.quote(filename)}"
    try:
        req = urllib.request.Request(url, headers={"User-Agent": UA})
        with urllib.request.urlopen(req, timeout=30) as r:
            if r.status == 200:
                ct = r.headers.get("Content-Type", "")
                data = r.read()
                if "image" in ct and len(data) > 5000:
                    dest.write_bytes(data)
                    return True, len(data), ct
    except Exception as e:
        return False, str(e)[:80], ""
    return False, "not image", ""


def main():
    data = json.loads(PRESETS.read_text(encoding="utf-8"))
    by_name = {c["name"]: c for c in data}

    success, fail, skipped = 0, 0, 0
    log = []
    for name, (wiki_page, fallback_names) in TARGETS.items():
        url_field = by_name[name].get("avatarUrl", "")
        if not url_field:
            print(f"\n[{name}] 无 avatarUrl,跳过")
            skipped += 1
            continue
        target_fname = url_field.rsplit("/", 1)[-1]
        target_path = AVATAR_DIR / target_fname
        # 已知伊布已下载,跳过
        if name == "伊布" and target_path.exists() and target_path.stat().st_size > 5000:
            print(f"\n[{name}] 已下载,跳过")
            success += 1
            continue

        print(f"\n[{name}] -> {target_fname}")
        # 1. 试从维基拿文件名
        wiki_fname = get_image_filename_from_wiki(wiki_page)
        candidates = []
        if wiki_fname:
            candidates.append(wiki_fname)
        candidates.extend(fallback_names)
        # 兜底: 简单变形
        candidates.append(f"{wiki_page}.jpg")
        candidates.append(f"{wiki_page}.png")
        # 去重
        seen = set()
        candidates = [c for c in candidates if c and not (c in seen or seen.add(c))]

        ok = False
        for fname in candidates:
            print(f"  试: {fname}")
            r = download_from_filepath(fname, target_path)
            if r[0]:
                print(f"  ✓ {target_path.name} ({r[1]} B, {r[2]})")
                ok = True
                success += 1
                break
            else:
                print(f"  ✗ {r[1]}")
            time.sleep(2)
        if not ok:
            print(f"  ✗ 全部失败")
            fail += 1
        log.append((name, "OK" if ok else "FAIL", wiki_fname or ""))
        time.sleep(3)

    (ROOT / "scripts/preset-expansion/fix14_v3_log.txt").write_text(
        "\n".join(f"{n}\t{s}\t{f}" for n, s, f in log), encoding="utf-8"
    )
    print(f"\n总结: 成功 {success}, 失败 {fail}, 跳过 {skipped}")


if __name__ == "__main__":
    main()
