"""
修复 14 个问题头像:为每个名字调维基,找到真人/真实图片,下载覆盖 .svg 占位。

策略:每个名字先走英文维基 REST summary(通常没消歧),如果无图,再试中文维基。
"""
import json
import os
import sys
import time
import urllib.request
import urllib.error
import urllib.parse
from pathlib import Path

ROOT = Path("/Users/yangq/Codes/Idea-Party")
PRESETS = ROOT / "server/src/main/resources/presets.json"
AVATAR_DIR = ROOT / "server/uploads/avatars/presets/"
UA = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36"


def http_json(url, timeout=20, retries=5):
    last = None
    for i in range(retries):
        try:
            req = urllib.request.Request(url, headers={"User-Agent": UA, "Api-User-Agent": UA, "Accept": "application/json"})
            with urllib.request.urlopen(req, timeout=timeout) as r:
                if r.status == 200:
                    return json.loads(r.read())
        except (urllib.error.HTTPError, urllib.error.URLError, json.JSONDecodeError, TimeoutError, OSError) as e:
            last = e
            wait = 5 + i * 10
            print(f"  重试 {i+1}/{retries} (wait {wait}s): {e}")
            time.sleep(wait)
    print(f"  仍失败: {last}")
    return None


def download(url, dest, timeout=30):
    try:
        req = urllib.request.Request(url, headers={"User-Agent": UA, "Referer": "https://en.wikipedia.org/"})
        with urllib.request.urlopen(req, timeout=timeout) as r:
            data = r.read()
        if len(data) < 3000:
            print(f"  跳过: 文件过小 ({len(data)}B)")
            return False
        dest.write_bytes(data)
        return True
    except Exception as e:
        print(f"  下载失败: {e}")
        return False


def find_avatar(wiki_queries, prefer_host="en"):
    """试多个 query,返回 (url, ext) 或 None"""
    for q in wiki_queries:
        for host in [prefer_host, "zh" if prefer_host == "en" else "en"]:
            url = f"https://{host}.wikipedia.org/api/rest_v1/page/summary/{urllib.parse.quote(q)}"
            d = http_json(url)
            if d and "thumbnail" in d and d.get("description"):
                desc = d.get("description", "").lower()
                # 跳过 "维基媒体消歧义页" / "disambiguation"
                if "disambiguation" in desc or "消歧义" in desc:
                    print(f"  {q} ({host}) 是消歧页")
                    time.sleep(1)
                    continue
                if "originalimage" in d:
                    return d["originalimage"]["source"], "jpg"
                return d["thumbnail"]["source"], "jpg"
            time.sleep(1.5)
    return None, None


# 14 个角色的精确查询(每名多个候选,先试英文,失败再中文)
QUERIES = {
    "毛利兰": ["Ran Mouri", "Mouri Ran", "Ran Mōri", "毛利兰", "兰 (名侦探柯南)"],
    "迈尔": ["Julius Robert von Mayer", "Julius von Mayer", "Robert Mayer (physicist)", "尤利乌斯·罗伯特·冯·迈尔"],
    "贾玲": ["Jia Ling", "Jia Ling (comedian)", "贾玲"],
    "钟睒睒": ["Zhong Shanshan", "Shanshan Zhong", "钟睒睒"],
    "多丽丝·林": ["Doris Lin", "Doris Yu-fang Lin", "多丽丝·林"],
    "伊布": ["Zlatan Ibrahimović", "Zlatan Ibrahimovic", "伊布拉希莫维奇", "伊布"],
    "流川枫": ["Rukawa Kaede", "Kaede Rukawa", "流川枫"],
    "岸本齐史": ["Masashi Kishimoto", "Kishimoto Masashi", "岸本齐史"],
    "尾田荣一郎": ["Eiichiro Oda", "Oda Eiichiro", "尾田荣一郎"],
    "久保带人": ["Tite Kubo", "Kubo Tite", "久保带人"],
    "朽木露琪亚": ["Rukia Kuchiki", "Kuchiki Rukia", "朽木露琪亚"],
    "卫青": ["Wei Qing (general)", "卫青"],
    "李元霸": ["Li Yuanba", "Yuanba Li", "李元霸"],
    "巴格拉季昂": ["Pyotr Bagration", "Bagration", "彼得·巴格拉季昂"],
}


def main():
    data = json.loads(PRESETS.read_text(encoding="utf-8"))
    by_name = {c["name"]: c for c in data}

    success, fail, skipped = 0, 0, 0
    for name, queries in QUERIES.items():
        url_field = by_name[name].get("avatarUrl", "")
        if not url_field:
            print(f"\n[{name}] 无 avatarUrl,跳过")
            skipped += 1
            continue
        target_fname = url_field.rsplit("/", 1)[-1]
        stem = target_fname.rsplit(".", 1)[0]
        ext = target_fname.rsplit(".", 1)[-1] if "." in target_fname else "jpg"
        target_path = AVATAR_DIR / target_fname

        print(f"\n[{name}] -> {target_fname}")
        img_url, img_ext = find_avatar(queries)
        if not img_url:
            print(f"  ✗ 未找到图片")
            fail += 1
            continue

        # 处理 thumb URL: 拿原图
        if "/thumb/" in img_url:
            # thumb URL 形如 /thumb/a/bb/Name.jpg/330px-Name.jpg
            # 转为原图: 去掉 /thumb/ 和 /尺寸px-xxx
            parts = img_url.split("/")
            # 找 thumb
            try:
                i = parts.index("thumb")
                orig = "/".join(parts[:i] + parts[i+1:-1])
                img_url = "https://upload.wikimedia.org" + orig
            except ValueError:
                pass

        if download(img_url, target_path):
            print(f"  ✓ 已下载: {img_url[:80]}")
            print(f"     -> {target_path.name} ({target_path.stat().st_size} B)")
            # 更新 url 字段(若扩展名变化)
            new_url = f"/api/upload/avatars/presets/{stem}.{img_ext or ext}"
            by_name[name]["avatarUrl"] = new_url
            success += 1
        else:
            fail += 1
        time.sleep(2)

    # 写回
    PRESETS.write_text(json.dumps(data, ensure_ascii=False, indent=2), encoding="utf-8")
    print(f"\n总结: 成功 {success}, 失败 {fail}, 跳过 {skipped}")


if __name__ == "__main__":
    main()
