"""
为 11 个 SVG 头像角色下载真实图片(从 Wikipedia)并落 .jpg。
失败兜底生成 jpg 字符占位图(PIL 绘制,不是 svg)。

运行: python3 fix_svg_avatars.py
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
PRESETS = ROOT / "server/src/main/resources/presets.json"
AVATAR_DIR = ROOT / "server/uploads/avatars/presets"

UA = "IdeaParty-AvatarBot/1.0 (preset-expansion; dev@ideaparty.local)"

# 角色 -> 维基优先搜索词(用英文时拿真人的概率最高)
WIKI_QUERY = {
    "迈尔": "Lothar Meyer chemist",
    "贾玲": "Jia Ling comedian",
    "钟睒睒": "Zhong Shanshan",
    "多丽丝·林": "Doris Lin",
    "流川枫": "Rukawa Kaede",  # 动漫角色 — 可能拿到角色图
    "岸本齐史": "Masashi Kishimoto",
    "尾田荣一郎": "Eiichiro Oda",
    "久保带人": "Tite Kubo",
    "卫青": "Wei Qing general",
    "李元霸": "Li Yuanba",
    "巴格拉季昂": "Pyotr Bagration",
}


def http_json(url, timeout=20):
    try:
        req = urllib.request.Request(url, headers={"User-Agent": UA, "Api-User-Agent": UA})
        with urllib.request.urlopen(req, timeout=timeout) as r:
            if r.status != 200:
                return None
            return json.loads(r.read())
    except (urllib.error.HTTPError, urllib.error.URLError, json.JSONDecodeError, TimeoutError, OSError):
        return None


def get_image(query):
    """中文维基 → 英文维基 search → 英文维基 summary"""
    # 1) 英文维基 search 拿到 canonical title(避免 redirect / disambiguation 失败)
    search = http_json(
        f"https://en.wikipedia.org/w/api.php?action=opensearch&format=json&limit=3&search={urllib.parse.quote(query)}"
    )
    titles = []
    if search and isinstance(search, list) and len(search) >= 2 and search[1]:
        titles.extend(search[1])

    # 2) 中文维基 REST(原名)
    encoded = urllib.parse.quote(query.split()[0])  # 取首词
    d = http_json(f"https://zh.wikipedia.org/api/rest_v1/page/summary/{encoded}")
    if d and (d.get("thumbnail") or d.get("originalimage")):
        return _pick(d)

    # 3) 英文维基按 title 试
    for t in titles:
        d = http_json(f"https://en.wikipedia.org/api/rest_v1/page/summary/{urllib.parse.quote(t)}")
        if d and (d.get("thumbnail") or d.get("originalimage")):
            return _pick(d)

    return None


def _pick(d):
    src = d.get("originalimage", {}).get("source") or d.get("thumbnail", {}).get("source")
    return src


def download(url, dest, timeout=30):
    try:
        req = urllib.request.Request(url, headers={"User-Agent": UA})
        with urllib.request.urlopen(req, timeout=timeout) as r:
            data = r.read()
        if len(data) < 2000:  # 太小肯定不是真头像
            return False
        # Wikimedia 多数是 jpg/png/jpeg — 原样保存,后缀统一改 .jpg(若不是 jpeg 转码)
        # 简单策略:按 mime 落
        ctype = r.headers.get_content_type() if hasattr(r.headers, "get_content_type") else "image/jpeg"
        if "png" in ctype:
            dest = dest.with_suffix(".png")
        dest.write_bytes(data)
        return dest
    except Exception as e:
        print(f"    [download fail] {e}")
        return False


def make_jpg_placeholder(name, dest):
    """PIL 绘制彩色 jpg 占位(首字母 + 渐变)"""
    try:
        from PIL import Image, ImageDraw, ImageFont
    except ImportError:
        # fallback: 写 1x1 灰 jpg
        dest.write_bytes(b"\xff\xd8\xff\xe0\x00\x10JFIF\x00\x01\x01\x00\x00\x01\x00\x01\x00\x00\xff\xdb\x00C\x00\x08\x06\x06\x07\x06\x05\x08\x07\x07\x07\t\t\x08\n\x0c\x14\r\x0c\x0b\x0b\x0c\x19\x12\x13\x0f\x14\x1d\x1a\x1f\x1e\x1d\x1a\x1c\x1c $.' \",#\x1c\x1c(7),01444\x1f'9=82<.342\xff\xc0\x00\x0b\x08\x00\x01\x00\x01\x01\x01\x11\x00\xff\xc4\x00\x1f\x00\x00\x01\x05\x01\x01\x01\x01\x01\x01\x00\x00\x00\x00\x00\x00\x00\x00\x01\x02\x03\x04\x05\x06\x07\x08\t\n\x0b\xff\xc4\x00\xb5\x10\x00\x02\x01\x03\x03\x02\x04\x03\x05\x05\x04\x04\x00\x00\x01}\x01\x02\x03\x00\x04\x11\x05\x12!1A\x06\x13Qa\x07\"q\x142\x81\x91\xa1\x08#B\xb1\xc1\x15R\xd1\xf0$3br\x82\x09\t\n\x16\x17\x18\x19\x1a%&'()*456789:CDEFGHIJSTUVWXYZcdefghijstuvwxyz\x83\x84\x85\x86\x87\x88\x89\x8a\x92\x93\x94\x95\x96\x97\x98\x99\x9a\xa2\xa3\xa4\xa5\xa6\xa7\xa8\xa9\xaa\xb2\xb3\xb4\xb5\xb6\xb7\xb8\xb9\xba\xc2\xc3\xc4\xc5\xc6\xc7\xc8\xc9\xca\xd2\xd3\xd4\xd5\xd6\xd7\xd8\xd9\xda\xe1\xe2\xe3\xe4\xe5\xe6\xe7\xe8\xe9\xea\xf1\xf2\xf3\xf4\xf5\xf6\xf7\xf8\xf9\xfa\xff\xda\x00\x08\x01\x01\x00\x00?\x00\xfb\xd0\xff\xd9")
        return dest

    img = Image.new("RGB", (200, 200), color=(80, 80, 80))
    draw = ImageDraw.Draw(img)
    h = sum(ord(c) for c in name) % 360
    for y in range(200):
        ratio = y / 200
        r = int(80 + (h % 100) * ratio)
        g = int(80 + ((h + 60) % 100) * ratio)
        b = int(80 + ((h + 120) % 100) * ratio)
        draw.line([(0, y), (200, y)], fill=(r, g, b))
    ch = (name.strip() or "?")[0]
    try:
        font = ImageFont.truetype("/System/Library/Fonts/Helvetica.ttc", 110)
    except Exception:
        font = ImageFont.load_default()
    bbox = draw.textbbox((0, 0), ch, font=font)
    w, h_ = bbox[2] - bbox[0], bbox[3] - bbox[1]
    draw.text(((200 - w) / 2 - bbox[0], (200 - h_) / 2 - bbox[1]), ch, fill="white", font=font)
    img.save(dest, "JPEG", quality=88)
    return dest


def main():
    if not PRESETS.exists():
        sys.exit("presets.json 缺失")
    AVATAR_DIR.mkdir(parents=True, exist_ok=True)
    data = json.loads(PRESETS.read_text(encoding="utf-8"))

    targets = [p for p in data if p.get("avatarUrl", "").lower().endswith(".svg")]
    # 额外:岳飞 这种 jpg 路径但目录里只有 svg 的,本次也补
    for p in data:
        av = p.get("avatarUrl", "")
        if av.endswith(".jpg"):
            fp = AVATAR_DIR / av.split("/")[-1]
            svg_fp = AVATAR_DIR / (av.split("/")[-1].rsplit(".", 1)[0] + ".svg")
            if not fp.exists() and svg_fp.exists():
                targets.append(p)

    # 去重
    seen = set()
    targets = [p for p in targets if not (p["id"] in seen or seen.add(p["id"]))]

    print(f"待处理 {len(targets)} 个角色\n")
    results = []

    for i, p in enumerate(targets, 1):
        name = p["name"]
        old_url = p["avatarUrl"]
        # 决定新文件名 slug
        if name == "岳飞":
            new_name = "yue-fei.jpg"
        else:
            slug = re.sub(r"[^\w-]", "", name.lower().replace("·", "-").replace(" ", "-"))
            slug = re.sub(r"-+", "-", slug).strip("-")
            new_name = f"{slug}.jpg"

        dest = AVATAR_DIR / new_name

        # 已存在就跳过
        if dest.exists() and dest.stat().st_size > 2000:
            url = f"/api/upload/avatars/presets/{new_name}"
            results.append((p, old_url, url, "reuse"))
            print(f"  ✓ [{i}/{len(targets)}] {name} -> {new_name} (已存在)")
            continue

        query = WIKI_QUERY.get(name, name)
        print(f"  ↓ [{i}/{len(targets)}] {name}  query='{query}'")
        img_url = get_image(query)
        if img_url:
            got = download(img_url, dest)
            if got:
                # 如果实际落的是 png,改 avatarUrl 后缀
                url = f"/api/upload/avatars/presets/{got.name}"
                results.append((p, old_url, url, "wiki"))
                print(f"      ✓ saved {got.name} ({got.stat().st_size//1024} KB)")
                continue

        # 兜底:jpg 占位
        make_jpg_placeholder(name, dest)
        url = f"/api/upload/avatars/presets/{new_name}"
        results.append((p, old_url, url, "placeholder"))
        print(f"      ◇ 占位 {new_name}")

        time.sleep(0.3)

    # 写回 presets.json
    for p, old_url, new_url, kind in results:
        p["avatarUrl"] = new_url

    PRESETS.write_text(json.dumps(data, ensure_ascii=False, indent=2), encoding="utf-8")

    print("\n=== 完成 ===")
    wiki_n = sum(1 for _, _, _, k in results if k == "wiki")
    reuse_n = sum(1 for _, _, _, k in results if k == "reuse")
    placeholder_n = sum(1 for _, _, _, k in results if k == "placeholder")
    print(f"维基: {wiki_n} | 复用: {reuse_n} | 占位: {placeholder_n}")


if __name__ == "__main__":
    main()