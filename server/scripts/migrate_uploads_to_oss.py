#!/usr/bin/env python3
"""
把 server/uploads/avatars/ 整个推到阿里云 OSS 桶 idea-party-uploads/uploads/avatars/。

用法:
  python3 migrate_uploads_to_oss.py --dry-run     # 只打印计划,不上传
  python3 migrate_uploads_to_oss.py                # 真传

依赖: pip install oss2 (或 pip3 install --user oss2)
环境变量: ALIYUN_OSS_ENDPOINT, ALIYUN_OSS_BUCKET, ALIYUN_OSS_KEY_PREFIX,
         ALIYUN_STS_ACCESS_KEY_ID, ALIYUN_STS_ACCESS_KEY_SECRET

为什么用 RAM 用户的 AK 而不是走 STS:
  迁移脚本是运维一次性操作,在服务器上手动跑 1 次,不需要 STS 临时凭证。
  复用同一对 RAM 用户 AK,权限也是 PutObject(角色绑的策略已经限定到 uploads/* 前缀)。
  跑完即可删除这对 AK 或仅保留最小权限(参考 memory/oss-bucket-name-hyphen.md)。

key 约定:
  本地 server/uploads/avatars/presets/foo.jpg
  → OSS oss://idea-party-uploads/uploads/avatars/presets/foo.jpg
  这样 nginx 301 /uploads/... 就能命中(nginx 看到 /uploads/...$request_uri 拼上 bucket domain)。
"""
import argparse
import mimetypes
import os
import sys
from pathlib import Path

try:
    import oss2
except ImportError:
    print("ERROR: 需要 oss2. 装: pip3 install --user oss2", file=sys.stderr)
    sys.exit(1)


def parse_args() -> argparse.Namespace:
    p = argparse.ArgumentParser(description="把 server/uploads/avatars/ 推到阿里云 OSS")
    p.add_argument("--dry-run", action="store_true", help="只打印计划,不上传")
    p.add_argument(
        "--src",
        default=os.environ.get("UPLOADS_SRC", "./uploads/avatars"),
        help="本地源目录(默认 ./uploads/avatars,可被 UPLOADS_SRC 覆盖)",
    )
    return p.parse_args()


def main() -> int:
    args = parse_args()

    endpoint = os.environ.get("ALIYUN_OSS_ENDPOINT", "").strip()
    bucket_name = os.environ.get("ALIYUN_OSS_BUCKET", "").strip()
    key_prefix = os.environ.get("ALIYUN_OSS_KEY_PREFIX", "uploads/").strip()
    access_key_id = os.environ.get("ALIYUN_STS_ACCESS_KEY_ID", "").strip()
    access_key_secret = os.environ.get("ALIYUN_STS_ACCESS_KEY_SECRET", "").strip()

    missing = [n for n, v in [
        ("ALIYUN_OSS_ENDPOINT", endpoint),
        ("ALIYUN_OSS_BUCKET", bucket_name),
        ("ALIYUN_STS_ACCESS_KEY_ID", access_key_id),
        ("ALIYUN_STS_ACCESS_KEY_SECRET", access_key_secret),
    ] if not v]
    if missing:
        print(f"ERROR: 环境变量未设置: {', '.join(missing)}", file=sys.stderr)
        return 1

    src = Path(args.src).resolve()
    if not src.is_dir():
        print(f"ERROR: 源目录不存在: {src}", file=sys.stderr)
        return 1

    # 列所有要传的本地文件(相对 src)
    files: list[Path] = []
    for p in src.rglob("*"):
        if p.is_file():
            files.append(p)
    print(f"[INFO] 源目录: {src}")
    print(f"[INFO] 文件数: {len(files)}")
    print(f"[INFO] 目标 bucket: {bucket_name} (endpoint: {endpoint})")
    print(f"[INFO] key 前缀: {key_prefix}")

    if args.dry_run:
        print("\n[DRY-RUN] 不实际传,只列前 5 个 key:")
        for p in files[:5]:
            rel = p.relative_to(src)
            oss_key = f"{key_prefix}{rel.as_posix()}"
            print(f"  {p} → oss://{bucket_name}/{oss_key}")
        if len(files) > 5:
            print(f"  ... 还有 {len(files) - 5} 个")
        return 0

    # 真传
    auth = oss2.Auth(access_key_id, access_key_secret)
    bucket = oss2.Bucket(auth, endpoint, bucket_name)

    ok, fail, skip = 0, 0, 0
    for p in files:
        rel = p.relative_to(src)
        oss_key = f"{key_prefix}{rel.as_posix()}"
        content_type, _ = mimetypes.guess_type(p.name)
        headers = {"Content-Type": content_type} if content_type else None
        try:
            # put_from_file: 走分片上传,> 5MB 也行
            bucket.put_object_from_file(oss_key, str(p), headers=headers)
            ok += 1
            if ok % 20 == 0:
                print(f"  [{ok}/{len(files)}] {rel}")
        except oss2.exceptions.OssError as e:
            print(f"  [FAIL] {rel}: {e}", file=sys.stderr)
            fail += 1
        except Exception as e:
            print(f"  [FAIL] {rel}: {type(e).__name__}: {e}", file=sys.stderr)
            fail += 1

    print(f"\n[DONE] ok={ok}, fail={fail}, total={len(files)}")
    return 0 if fail == 0 else 2


if __name__ == "__main__":
    sys.exit(main())
