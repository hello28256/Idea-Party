#!/usr/bin/env python3
"""
简化版 upload_to_cos: 不依赖 deploy.py 模板字符串, 直接跑上传。

替代 deploy.py 内联生成的 upload_uploads.py, 走同一份 SDK (cos-python-sdk-v5)
和同一份 manifest (.cos-manifest.json), 行为一致:

  - 增量模式: 跳过 manifest 中 mtime+size 一致的文件
  - 并发 4 个文件上传
  - 失败 warn 不阻断
  - 写新 manifest

使用:
  python3 scripts/upload_to_cos.py [--dry-run] [--limit 10]
"""
import argparse
import hashlib
import json
import mimetypes
import os
import sys
import time
from concurrent.futures import ThreadPoolExecutor, as_completed
from pathlib import Path

from qcloud_cos import CosConfig, CosS3Client


def md5_of(p):
    h = hashlib.md5()
    with open(p, 'rb') as f:
        for chunk in iter(lambda: f.read(1024 * 1024), b''):
            h.update(chunk)
    return h.hexdigest()


def load_manifest():
    p = Path(MANIFEST_PATH)
    if not p.is_file():
        return {}
    try:
        return json.load(p.open(encoding='utf-8'))
    except Exception:
        return {}


def save_manifest(m):
    p = Path(MANIFEST_PATH)
    tmp = p.with_suffix(p.suffix + '.tmp')
    json.dump(m, tmp.open('w', encoding='utf-8'),
              ensure_ascii=False, sort_keys=True, indent=2)
    os.replace(tmp, p)


def put_one(client, bucket, p, key):
    ct = mimetypes.guess_type(p.name)[0]
    # Body 必须是 file-like 或 bytes,不能是 Path/str
    # 用 rb 模式开 file handle,SDK 读完会自动关
    kwargs = {
        'Bucket': bucket,
        'Key': key,
        'Body': open(p, 'rb'),
        'CacheControl': 'public, max-age=31536000, immutable',
    }
    if ct:
        kwargs['ContentType'] = ct
    return client.put_object(**kwargs)


def main():
    global MANIFEST_PATH
    p = argparse.ArgumentParser()
    p.add_argument('--dry-run', action='store_true')
    p.add_argument('--limit', type=int, default=0)
    p.add_argument('--subdirs', nargs='+',
                   default=['presets', 'presets-webp', 'hot-rooms', 'scenarios', 'brand'])
    p.add_argument('--manifest', default='server/uploads/avatars/.cos-manifest.json')
    p.add_argument('--parallel', type=int, default=4)
    args = p.parse_args()

    MANIFEST_PATH = args.manifest

    config = CosConfig(
        Region=os.environ['TENCENT_COS_REGION'],
        SecretId=os.environ['TENCENT_COS_SECRET_ID'],
        SecretKey=os.environ['TENCENT_COS_SECRET_KEY'],
        Scheme='https',
        Timeout=30,
    )
    client = CosS3Client(config)
    bucket = os.environ['TENCENT_COS_BUCKET']

    manifest = load_manifest()
    new_manifest = dict(manifest)
    active_keys = set()

    t0 = time.time()
    total_ok = total_fail = total_skip = 0
    for sub in args.subdirs:
        src = Path('server/uploads/avatars') / sub
        if not src.is_dir():
            print(f'[upload] [{sub}] skip: dir not exist')
            continue
        files = [p for p in src.rglob('*') if p.is_file()]
        print(f'[upload] [{sub}] {len(files)} files')

        to_put = []
        skip = 0
        for p in files:
            rel = p.relative_to(src).as_posix()
            key = f'uploads/avatars/{sub}/{rel}'
            active_keys.add(key)
            st = p.stat()
            mtime = int(st.st_mtime)
            size = st.st_size
            prev = manifest.get(key)
            if (prev and prev.get('mtime') == mtime
                    and prev.get('size') == size and prev.get('md5')):
                skip += 1
                continue
            to_put.append((p, key, mtime, size))
        if args.limit and len(to_put) > args.limit:
            to_put = to_put[:args.limit]

        ok = fail = 0
        with ThreadPoolExecutor(max_workers=args.parallel) as ex:
            futures = {ex.submit(put_one, client, bucket, p, key): (p, key, mt, sz)
                       for (p, key, mt, sz) in to_put}
            for fut in as_completed(futures):
                p, key, mt, sz = futures[fut]
                try:
                    fut.result()
                    ok += 1
                    new_manifest[key] = {'mtime': mt, 'size': sz, 'md5': md5_of(p)}
                    if ok % 20 == 0:
                        print(f'[upload] [{sub}] {ok}/{len(to_put)} put')
                except Exception as e:
                    print(f'  [FAIL] {p.relative_to(src)}: {type(e).__name__}: {e}')
                    fail += 1
        print(f'[upload] [{sub}] done: put={ok} skip={skip} fail={fail}')
        total_ok += ok
        total_fail += fail
        total_skip += skip

        if args.dry_run:
            print('[dry-run] stop after first subdir')
            return

    removed = [k for k in new_manifest if k not in active_keys]
    for k in removed:
        del new_manifest[k]
    save_manifest(new_manifest)
    if removed:
        print(f'[upload] manifest pruned: {len(removed)} deleted keys')

    elapsed = time.time() - t0
    print(f'[upload] all done: put={total_ok} skip={total_skip} fail={total_fail} in {elapsed:.1f}s')


if __name__ == '__main__':
    main()
