#!/usr/bin/env python3
"""
从阿里云 OSS 迁移 uploads/avatars/ 全部文件到腾讯云 COS。

前提:
  - 服务器已装 oss2 (v1) 和 cos-python-sdk-v5
  - .env.production 里有 ALIYUN_OSS_* 和 TENCENT_COS_*
  - 腾讯云桶 idea-party-uploads-1361890600 已建,子账号有 ListObject/PutObject

使用:
  python3 scripts/migrate_oss_to_cos.py [--dry-run] [--bucket-dir uploads/avatars]

流程:
  1. 列出 OSS 桶 uploads/avatars/* 全部 key
  2. 对每个 key: 从 OSS 下载到内存
  3. 上传到 COS 同 key
  4. 打印成功/失败统计

限速: 默认每个文件 sleep 0.01s,避免 OSS 限流
"""

import argparse
import os
import sys
import time
from pathlib import Path

import oss2
from qcloud_cos import CosConfig, CosS3Client


def load_env():
    """从 .env.production 加载(简化版, 不引 deploy.py 的逻辑)"""
    env_file = Path.cwd() / ".env.production"
    if not env_file.is_file():
        sys.exit(f"❌ {env_file} 不存在")
    for raw in env_file.read_text(encoding="utf-8").splitlines():
        line = raw.strip()
        if not line or line.startswith("#"):
            continue
        if line.startswith("export "):
            line = line[len("export "):].lstrip()
        if "=" not in line:
            continue
        k, _, v = line.partition("=")
        k, v = k.strip(), v.strip().strip('"').strip("'")
        if (k.startswith("ALIYUN_") or k.startswith("TENCENT_COS_")) and k not in os.environ:
            os.environ[k] = v


def list_oss_keys(bucket, prefix):
    """列 OSS 桶 prefix/ 下所有 key, 返回 generator"""
    return oss2.ObjectIterator(bucket, prefix=prefix)


def main():
    p = argparse.ArgumentParser()
    p.add_argument("--dry-run", action="store_true", help="只列要传的文件,不真传")
    p.add_argument("--bucket-dir", default="uploads/avatars/", help="OSS 子目录前缀,默认 uploads/avatars/")
    p.add_argument("--limit", type=int, default=0, help="只传 N 个文件 (测试用), 0 = 全传")
    p.add_argument("--sleep", type=float, default=0.01, help="每文件上传后 sleep (秒), 默认 0.01")
    args = p.parse_args()

    load_env()

    # OSS client (v1 SDK)
    oss_auth = oss2.Auth(
        os.environ.get("ALIYUN_OSS_ACCESS_KEY_ID", ""),
        os.environ.get("ALIYUN_OSS_ACCESS_KEY_SECRET", ""),
    )
    # STS 临时凭证或 RAM 长期 AK 都行 (都用 AccessKeyId/Secret)
    oss_bucket = oss2.Bucket(
        oss_auth,
        os.environ.get("ALIYUN_OSS_ENDPOINT", ""),
        os.environ.get("ALIYUN_OSS_BUCKET", ""),
    )

    # COS client
    cos_config = CosConfig(
        Region=os.environ.get("TENCENT_COS_REGION", ""),
        SecretId=os.environ.get("TENCENT_COS_SECRET_ID", ""),
        SecretKey=os.environ.get("TENCENT_COS_SECRET_KEY", ""),
        Scheme="https",
        Timeout=30,
    )
    cos_client = CosS3Client(cos_config)
    cos_bucket = os.environ.get("TENCENT_COS_BUCKET", "")

    print(f"== 数据迁移 ==")
    print(f"  OSS: {os.environ.get('ALIYUN_OSS_BUCKET', '')}/{args.bucket_dir}")
    print(f"  COS: {cos_bucket}/{args.bucket_dir}")
    print(f"  dry-run: {args.dry_run}")
    print(f"  limit: {args.limit or 'all'}")
    print()

    # 列 OSS
    keys = []
    for obj in oss2.ObjectIterator(oss_bucket, prefix=args.bucket_dir):
        if obj.key.endswith("/"):  # 跳过目录
            continue
        keys.append(obj.key)
        if args.limit and len(keys) >= args.limit:
            break

    print(f"找到 {len(keys)} 个文件")
    if args.dry_run:
        for k in keys[:20]:
            print(f"  {k}")
        if len(keys) > 20:
            print(f"  ... 还有 {len(keys) - 20} 个")
        return

    # 同步
    ok = fail = skip = 0
    t0 = time.time()
    for i, key in enumerate(keys, 1):
        # head 一下 COS 已有这个 key 没 (用 head_object)
        try:
            cos_client.head_object(Bucket=cos_bucket, Key=key)
            skip += 1
            if i % 100 == 0:
                print(f"  [{i}/{len(keys)}] {key} 已存在 skip")
            continue
        except Exception:
            pass  # COS 上没这个 key, 正常

        try:
            # 1. 从 OSS 下载到临时内存 (头像小, 几 KB~1MB)
            result = oss_bucket.get_object(key)
            body = b""
            for chunk in result:
                body += chunk
            # 2. 上传到 COS
            cos_client.put_object(
                Bucket=cos_bucket,
                Key=key,
                Body=body,
                CacheControl="public, max-age=31536000, immutable",
            )
            ok += 1
            if i % 50 == 0 or i == len(keys):
                elapsed = time.time() - t0
                print(f"  [{i}/{len(keys)}] put {key} ({len(body)} bytes, {elapsed:.0f}s)")
        except Exception as e:
            print(f"  [FAIL] {key}: {type(e).__name__}: {e}")
            fail += 1

        if args.sleep > 0:
            time.sleep(args.sleep)

    elapsed = time.time() - t0
    print()
    print(f"== 完成 ==")
    print(f"  put: {ok}")
    print(f"  skip: {skip}")
    print(f"  fail: {fail}")
    print(f"  用时: {elapsed:.1f}s")


if __name__ == "__main__":
    main()
