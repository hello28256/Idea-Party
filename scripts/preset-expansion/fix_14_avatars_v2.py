"""
v2 修复 14 个头像:
不走维基 API(限流),直接 GET Wikipedia HTML 页面,正则找 upload.wikimedia.org 原图 URL。
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

# 每个角色的维基页面 URL (先试英文,失败再中文)
WIKI_PAGES = {
    "毛利兰": [("en", "Ran_Mouri"), ("zh", "毛利兰")],
    "迈尔": [("en", "Julius_von_Mayer"), ("zh", "尤利乌斯·冯·迈尔")],
    "贾玲": [("en", "Jia_Ling"), ("zh", "贾玲")],
    "钟睒睒": [("en", "Zhong_Shanshan"), ("zh", "钟睒睒")],
    "多丽丝·林": [("en", "Doris_Lin")],
    "伊布": [("en", "Zlatan_Ibrahimović"), ("zh", "伊布拉希莫维奇")],
    "流川枫": [("en", "Rukawa_Kaede")],
    "岸本齐史": [("en", "Masashi_Kishimoto")],
    "尾田荣一郎": [("en", "Eiichiro_Oda")],
    "久保带人": [("en", "Tite_Kubo")],
    "朽木露琪亚": [("en", "Rukia_Kuchiki")],
    "卫青": [("en", "Wei_Qing_(general)"), ("zh", "卫青")],
    "李元霸": [("en", "Li_Yuanba")],
    "巴格拉季昂": [("en", "Pyotr_Bagration")],
}


def fetch(url):
    try:
        req = urllib.request.Request(url, headers={"User-Agent": UA})
        with urllib.request.urlopen(req, timeout=30) as r:
            if r.status == 200:
                return r.read()
    except Exception as e:
        print(f"  err {url[:80]}: {e}")
    return None


def find_image_urls(html):
    """从 HTML 找原图 URL (upload.wikimedia.org/.../xxx.jpg, 不含 /thumb/)"""
    if not html:
        return []
    # 匹配: https://upload.wikimedia.org/wikipedia/commons/xx/yy/FileName.jpg
    # 排除 /thumb/ 路径
    urls = re.findall(
        r'https?://upload\.wikimedia\.org/wikipedia/commons/[a-f0-9]/[a-f0-9]{2}/[^\s"\'<>]+\.(?:jpg|JPG|jpeg|JPEG)',
        html.decode("utf-8", errors="ignore"),
    )
    # 去重 + 过滤太小的图(可能只是 logo/icon)
    seen = set()
    out = []
    for u in urls:
        if u in seen:
            continue
        seen.add(u)
        out.append(u)
    return out


def find_image_in_box(html, name_keyword):
    """从 infobox 区域找图(更准确)"""
    # infobox 通常包含 class="infobox" 或 "biography"
    text = html.decode("utf-8", errors="ignore")
    # 找 infobox 块
    m = re.search(r'(<table[^>]*infobox[^>]*>.*?</table>)', text, re.DOTALL | re.IGNORECASE)
    if not m:
        m = re.search(r'(<table[^>]*biography[^>]*>.*?</table>)', text, re.DOTALL | re.IGNORECASE)
    if m:
        urls = find_image_urls(m.group(1).encode())
        if urls:
            return urls[0]
    return None


def main():
    data = json.loads(PRESETS.read_text(encoding="utf-8"))
    by_name = {c["name"]: c for c in data}
    out_log = []

    success, fail, skipped = 0, 0, 0
    for name, pages in WIKI_PAGES.items():
        url_field = by_name[name].get("avatarUrl", "")
        if not url_field:
            print(f"\n[{name}] 无 avatarUrl,跳过")
            skipped += 1
            continue
        target_fname = url_field.rsplit("/", 1)[-1]
        target_path = AVATAR_DIR / target_fname

        print(f"\n[{name}] -> {target_fname}")
        img_url = None
        for host, page in pages:
            page_url = f"https://{host}.wikipedia.org/wiki/{urllib.parse.quote(page)}"
            print(f"  试 {host}: {page_url}")
            html = fetch(page_url)
            if not html:
                continue
            # 先找 infobox 里的图
            img_url = find_image_in_box(html, name)
            if not img_url:
                # 退化:取页面里第一张上传图
                urls = find_image_urls(html)
                # 排除太短文件名/明显 logo
                urls = [u for u in urls if "logo" not in u.lower() and len(u) > 50]
                if urls:
                    img_url = urls[0]
            if img_url:
                print(f"  → {img_url[:90]}")
                break
            time.sleep(1)

        if not img_url:
            print(f"  ✗ 未找到")
            fail += 1
            out_log.append((name, "FAIL", ""))
            continue

        # 下载
        try:
            req = urllib.request.Request(img_url, headers={"User-Agent": UA, "Referer": "https://en.wikipedia.org/"})
            with urllib.request.urlopen(req, timeout=30) as r:
                data_bytes = r.read()
            if len(data_bytes) < 3000:
                print(f"  ✗ 文件过小 ({len(data_bytes)}B)")
                fail += 1
                continue
            target_path.write_bytes(data_bytes)
            print(f"  ✓ {target_path.name} ({len(data_bytes)} B)")
            success += 1
            out_log.append((name, "OK", img_url))
        except Exception as e:
            print(f"  ✗ 下载失败: {e}")
            fail += 1
            out_log.append((name, "DOWNLOAD_FAIL", str(e)))

        time.sleep(1.5)

    # 写报告
    (ROOT / "scripts/preset-expansion/fix_14_log.txt").write_text(
        "\n".join(f"{n}\t{s}\t{u}" for n, s, u in out_log),
        encoding="utf-8",
    )
    print(f"\n总结: 成功 {success}, 失败 {fail}, 跳过 {skipped}")
    print(f"日志: scripts/preset-expansion/fix_14_log.txt")


if __name__ == "__main__":
    main()
