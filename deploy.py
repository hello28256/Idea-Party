#!/usr/bin/env python3
"""
deploy.py — 一键部署 Idea-Party 到腾讯云 CVM

功能：
  - 用 rsync 把本地项目同步到 CVM（只传差异）
  - 远程执行 docker compose build + up -d
  - 支持查看状态 / 拉日志 / 重启单个服务

用法：
  python3 deploy.py                 # 完整部署（同步 + uploads + 构建 + 重启）
  python3 deploy.py --sync-only     # 只同步，不触发部署
  python3 deploy.py --skip-uploads  # 跳过 uploads volume 同步（仅调试）
  python3 deploy.py --status        # 查看容器状态
  python3 deploy.py --logs          # tail 所有服务日志
  python3 deploy.py --logs server   # tail 指定服务日志
  python3 deploy.py --restart server # 重启指定服务

依赖：Python 3.10+，本机已装 rsync / ssh，服务器已配 SSH 密钥登录
"""

from __future__ import annotations

import argparse
import json
import os
import shlex
import subprocess
import sys
import tempfile
import textwrap
import time
from pathlib import Path
from typing import Sequence

# =============================================================================
# Logger — 所有输出带 [DEBUG] 前缀，方便追溯
# =============================================================================
def log(msg: str) -> None:
    ts = time.strftime("%H:%M:%S")
    print(f"[{ts}] [DEBUG] {msg}", flush=True)


# Secret 字符串脱敏:把 "ALIYUN_STS_ACCESS_KEY_SECRET=GXZ9lVhF5EOY9fxWGVIDOM4uvruohp"
# 里的 value 替换成 "****",只保留前 4 字符用于排查。
# 避免 deploy 日志 / shell history / Claude chat 留下完整 Secret。
def _mask_secrets(s: str, secrets: list[str]) -> str:
    for secret in secrets:
        if not secret:
            continue
        if len(secret) > 4:
            s = s.replace(secret, secret[:4] + "****")
    return s


def die(msg: str, code: int = 1) -> None:
    print(f"[ERROR] {msg}", file=sys.stderr)
    sys.exit(code)


# =============================================================================
# .env loader — 从 .env.deploy 读取部署配置（不提交到 git，提交 .env.deploy.example）
# =============================================================================
DEPLOY_ENV_FILE = ".env.deploy"


def _load_dotenv(path: str) -> None:
    """最小化 .env 解析：KEY=VALUE 每行，# 注释，'/'\"' 包裹的引号自动剥除。
    已存在的环境变量不会被覆盖，方便命令行临时覆盖。"""
    p = Path(path)
    if not p.is_file():
        return
    for raw in p.read_text(encoding="utf-8").splitlines():
        line = raw.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, _, val = line.partition("=")
        os.environ.setdefault(key.strip(), val.strip().strip('"').strip("'"))


_load_dotenv(str(Path(__file__).parent / DEPLOY_ENV_FILE))


# =============================================================================
# Config — 敏感信息从环境变量 / .env.deploy 读取（不要把真实 IP / 密钥路径写进代码）
# =============================================================================
DEPLOY_HOST = os.environ.get("DEPLOY_HOST", "").strip()
DEPLOY_USER = os.environ.get("DEPLOY_USER", "ubuntu").strip()
SSH_KEY = os.environ.get("DEPLOY_SSH_KEY", "~/.ssh/id_ed25519").strip()
REMOTE_DIR = os.environ.get("DEPLOY_REMOTE_DIR", "/opt/ideaparty").strip()
REMOTE_ENV_FILE = os.environ.get("DEPLOY_REMOTE_ENV_FILE", ".env.production").strip()

# ---- Uploads volume sync (Step 1.5) ----
# Volume name that docker-compose maps to /app/uploads on the server container.
DEPLOY_UPLOADS_VOLUME = os.environ.get("DEPLOY_UPLOADS_VOLUME", "idea-server-uploads").strip()
# Helper image used by `docker run --rm` to copy local uploads into the volume.
# Must include `cp -a` (BusyBox >= 1.30 or GNU coreutils).
DEPLOY_UPLOADS_IMAGE = os.environ.get("DEPLOY_UPLOADS_IMAGE", "alpine:3.19").strip()
# Subdirs under server/uploads/avatars/ to sync into the volume.
# 新增子目录时,同步在 .gitignore 加 !server/uploads/avatars/<sub>/ 让 rsync 能带上文件。
# presets-webp 是由 scripts/generate-preset-webp.sh 预生成的 192x192 WebP 头像,
# nginx 通过 Accept 头协商命中这里,不再反代 Spring Boot。
# brand 是登录/注册页用的品牌图 (image.png, login-bg.png),
# 部署后由 deploy.py 同步进 OSS 桶 uploads/brand/ 路径,前端 BRAND_LOGO 常量引用。
DEPLOY_UPLOADS_SUBDIRS = ("presets", "presets-webp", "hot-rooms", "scenarios", "brand")
# Minimum preset file count inside container for verification to pass.
DEPLOY_UPLOADS_MIN_PRESETS = int(os.environ.get("DEPLOY_UPLOADS_MIN_PRESETS", "100"))
# OSS 增量上传 manifest: 记录每个 key 上次上传时的 {mtime, size, md5}。
# deploy.py action_upload_uploads 用本地 manifest 做 diff,跳过 mtime+size
# 都未变化的文件 — 不用 OSS HeadObject 权限即可做到增量,符合 RAM 最小权限原则。
# 文件不入 git,deploy 时随 rsync 上服务器,服务器上读写都用同一份。
OSS_MANIFEST_PATH = os.environ.get("DEPLOY_OSS_MANIFEST", "server/uploads/avatars/.oss-manifest.json").strip()

if not DEPLOY_HOST:
    die("DEPLOY_HOST 未设置。请在 .env.deploy 中配置（IP 地址或 SSH config alias）。")

# SSH target：含 @ 或为 alias（无点号）时直接用，否则拼成 user@host
SSH_TARGET = DEPLOY_HOST if ("@" in DEPLOY_HOST or "." not in DEPLOY_HOST) else f"{DEPLOY_USER}@{DEPLOY_HOST}"

# 保留旧变量名，后续 action_* 函数沿用
SERVER_HOST = DEPLOY_HOST
SERVER_USER = DEPLOY_USER

# rsync 排除项：本地构建产物、依赖、IDE 配置、密钥文件
RSYNC_EXCLUDES = [
    ".git",
    "node_modules",
    "dist",
    "target",
    ".env",                # 开发环境变量，绝不能传
    ".env.production",     # 生产密钥在服务器上手填，不从本地传
    ".env.deploy",         # 部署配置（IP / SSH 密钥路径）只在本机用，不传服务器
    ".DS_Store",
    "test-*.png",
    "test-*.mjs",
    "playwright-report",
    "test-results",
    "coverage",
    "deploy.py",           # 避免把 deploy 脚本自己同步到服务器
    "*.log",
    # OSS 增量上传 manifest 由 server 端 python 维护(每次 deploy 写一次),
    # 本地不入仓,deploy 时不要 rsync 删/覆盖 — 否则 --delete 会清空 server 端 manifest,
    # 下一跑只能全量 PUT。
    "server/uploads/avatars/.oss-manifest.json",
]


# =============================================================================
# Subprocess helpers — 统一错误处理
# =============================================================================
def _load_aliyun_env_from_file(env_file: str = ".env.production") -> None:
    """从本地 .env.production 加载 ALIYUN_* 变量到 os.environ。
    仅在变量未设置时填充,避免覆盖用户在 shell 里 export 的值。
    不支持的语法: 引号包裹 / 多行 / export 前缀(都用 grep -v 简单跳过)。
    """
    p = Path(env_file)
    if not p.is_file():
        return
    for raw in p.read_text(encoding="utf-8").splitlines():
        line = raw.strip()
        if not line or line.startswith("#"):
            continue
        if line.startswith("export "):
            line = line[len("export "):].lstrip()
        if "=" not in line:
            continue
        key, _, val = line.partition("=")
        key = key.strip()
        val = val.strip().strip('"').strip("'")
        if key.startswith("ALIYUN_") and key not in os.environ:
            os.environ[key] = val


def run(cmd: Sequence[str], *, check: bool = True, capture: bool = False, cwd: str | None = None) -> subprocess.CompletedProcess:
    """执行本地命令。check=True 时失败抛 CalledProcessError。

    安全: 打 DEBUG 日志前对 Secret 变量值脱敏,避免 STS / DB 密码
    之类进 shell history / chat 历史 / log 文件。
    """
    printable = " ".join(shlex.quote(c) for c in cmd)
    printable = _mask_secrets(printable, _collect_secrets())
    log(f"$ {printable}")
    return subprocess.run(
        list(cmd),
        check=check,
        capture_output=capture,
        text=True,
        cwd=cwd,
    )


def _collect_secrets() -> list[str]:
    """收集所有 *_SECRET / *_PASSWORD / *_TOKEN 变量值,准备脱敏。
    不匹配 *_KEY (会误伤 *_KEY_PREFIX 等公开配置) 与 STS_ROLE_ARN
    (ARN 不是 Secret,只是标识)。只在 log/print 时用,不改 os.environ,
    不影响实际进程传值。
    """
    secrets: list[str] = []
    for k, v in os.environ.items():
        if not v or len(v) < 8:
            continue
        kl = k.lower()
        if any(suffix in kl for suffix in ("secret", "password", "token")):
            secrets.append(v)
    return secrets


def ssh_cmd(remote_cmd: str, *, capture: bool = False, check: bool = True, forward_env: Sequence[str] = ()) -> subprocess.CompletedProcess:
    """在远程服务器上跑一条命令。check=False 用于 best-effort 操作
    （如 docker pull 镜像——已经缓存时仍要容忍非零退出）。

    forward_env: 要从当前 shell 转发到 SSH 远端的环境变量名列表。
    SSH 走的是新 shell,默认不继承本机环境变量,需要显式 SendEnv(env 名要在
    ~/.ssh/config 或 /etc/ssh_config 配 AcceptEnv)。
    简单办法:用 VAR=val $VAR2=val2 cmd 语法直接在命令前拼。

    安全: 转发 Secret 时只把变量名打到 debug 日志,值要脱敏。Secret 不进
    log/print/chat 历史。本函数返回的是 subprocess.CompletedProcess,不
    自动打印 stdout/stderr(调用方决定是否打印),所以 Secret 不会从本函数
    泄到日志。
    """
    ssh_path = os.path.expanduser(SSH_KEY)
    if forward_env:
        # 必须先 export 再跑 remote_cmd,否则 "VAR=val cd /dir" 会被 shell 解析成
        # cd "VAR=val cd /dir" (cd 拿整段当参数,fallback 到 $HOME,后续 && 链断)
        # 用 bash -c 包一层,内部先 export,再跑 remote_cmd
        env_prefix_parts = []
        for name in forward_env:
            val = os.environ.get(name, "")
            if val:
                # 用 shlex.quote 包裹值,防止空格/特殊字符破坏 export 语法
                env_prefix_parts.append(f"export {name}={shlex.quote(val)}")
        env_prefix = "; ".join(env_prefix_parts)
        # shlex.quote 整个 remote_cmd 防止 cmd 含单引号破坏 bash -c
        final_cmd = f"bash -c {shlex.quote(env_prefix + '; ' + remote_cmd)}"
    else:
        final_cmd = remote_cmd
    cmd = ["ssh", "-i", ssh_path, "-o", "BatchMode=yes", "-o", "StrictHostKeyChecking=accept-new", SSH_TARGET, final_cmd]
    return run(cmd, capture=capture, check=check)


# Dry-run 模式下跳过 verification —— 没有真同步，凭空检查会假阳性。
def _is_dry_run() -> bool:
    """判断当前是否 dry-run 模式（run 被 monkey-patch 过）。"""
    src = getattr(run, "__name__", "")
    return src == "dry_run"


# =============================================================================
# Actions
# =============================================================================
def ensure_remote_dir() -> None:
    """第一次部署时 /opt/ideaparty 可能不存在，自动 sudo 创建。"""
    # 探测目录是否存在；不存在就用 sudo mkdir 并 chown 给当前用户
    # 把多行命令包在 sh -c '...' 里执行，避免 sudo 在 ssh 非交互下的问题
    probe = ssh_cmd(f"test -d {REMOTE_DIR} && echo OK || echo MISSING", capture=True)
    if "OK" in (probe.stdout or ""):
        return
    log(f"Remote dir {REMOTE_DIR} 不存在，自动创建 ...")
    ssh_cmd(f"sudo mkdir -p {REMOTE_DIR} && sudo chown -R $USER:$USER {REMOTE_DIR}")


def action_sync() -> None:
    """rsync 同步本地项目到远程。"""
    local_dir = str(Path.cwd()) + "/"
    excludes = sum([["--exclude", e] for e in RSYNC_EXCLUDES], [])

    # -a archive  -v verbose  -z compress  --progress 显示进度
    # --delete 把远程多余的文件删掉（注意：不会删 .env.production，因为加了 --exclude）
    cmd = [
        "rsync", "-avz", "--progress", "--delete",
        *excludes,
        "-e", f"ssh -i {os.path.expanduser(SSH_KEY)}",
        local_dir, f"{SSH_TARGET}:{REMOTE_DIR}/",
    ]
    run(cmd)


def action_sync_uploads() -> None:
    """Sync server/uploads/avatars/{presets,hot-rooms} into idea-server-uploads
    named volume on the server.

    Bypasses host dhcpcd:lxd ownership trap by using a throwaway alpine
    container as root: bind-mount a remote staging path (already populated
    by Step 1 rsync) as :ro, mount the volume as /dst, cp -a into
    /dst/avatars/, chown to the container's app user uid:gid.

    Why this design (vs sudo chown / bind mount):
    - Container root writing the volume = effective root on host, so the
      dhcpcd:lxd /var/lib/docker/volumes/idea-server-uploads/_data/ drwx--x--x
      perms don't block writes.
    - No new sudo dependency on the deploy host beyond what ensure_remote_dir
      already does.
    - Idempotent: cp -a is additive (user-uploaded avatars at /dst/avatars/*.jpg
      are preserved); preset/hot-rooms files are overwritten when local changes.
    - Self-healing on Dockerfile uid drift: we read app's uid:gid at runtime
      instead of hardcoding 100:101.

    Dependency on Step 1: the local uploads tree must already be at
    {REMOTE_DIR}/server/uploads/avatars/{presets,hot-rooms}/ on the server.
    Step 1 (action_sync) does this via rsync. We use the SERVER-side path
    as the bind-mount source, not the local path — docker run executes on
    the server, so it can't see the macOS file system.
    """
    # Source path on the server (populated by Step 1 rsync).
    remote_src_root = f"{REMOTE_DIR}/server/uploads/avatars"

    # Probe what subdirs exist on the remote side (rsync may have skipped
    # some if they don't exist locally). 用 brace expansion 一次性探测
    # DEPLOY_UPLOADS_SUBDIRS 全部子目录,避免逐条 ls 多一次 ssh 调用。
    sub_brace = ",".join(DEPLOY_UPLOADS_SUBDIRS)
    probe = ssh_cmd(f"ls -d {remote_src_root}/{{{sub_brace}}} 2>/dev/null || true", capture=True)
    present = []
    for line in (probe.stdout or "").splitlines():
        line = line.strip()
        if line and any(line.endswith(f"/{sub}") for sub in DEPLOY_UPLOADS_SUBDIRS):
            present.append(line.rsplit("/", 1)[1])
    if not present:
        die(f"[uploads] no subdirs found under {remote_src_root} — refusing to wipe volume")

    # Best-effort pull: helper image may already be cached.
    log(f"[uploads] ensure helper image: {DEPLOY_UPLOADS_IMAGE}")
    ssh_cmd(f"docker pull --quiet {DEPLOY_UPLOADS_IMAGE}", check=False)

    # Build bind-mount args for each present subdir (read-only).
    mount_args: list[str] = []
    for sub in present:
        mount_args += ["-v", f"{remote_src_root}/{sub}:/src/{sub}:ro"]

    # Inner shell script — written to a local temp file and bind-mounted
    # into the helper. Sidesteps the `sh -c "..."` quoting nightmare.
    cp_steps = " && ".join(
        f"mkdir -p /dst/avatars/{sub} && cp -a /src/{sub}/. /dst/avatars/{sub}/"
        for sub in present
    )
    script_body = (
        "#!/bin/sh\n"
        "set -e\n"
        "APP_UID=$(docker exec idea-server sh -c 'id -u app' 2>/dev/null || echo 100)\n"
        "APP_GID=$(docker exec idea-server sh -c 'id -g app' 2>/dev/null || echo 101)\n"
        'echo "[uploads] using uid:gid = $APP_UID:$APP_GID"\n'
        f"{cp_steps}\n"
        "chown -R $APP_UID:$APP_GID /dst/avatars\n"
        'echo "[uploads] container-side copy OK"\n'
    )
    script_path = Path(tempfile.mkdtemp(prefix="deploy-uploads-")) / "sync.sh"
    script_path.write_text(script_body, encoding="utf-8")
    script_path.chmod(0o755)

    # Push the script to the server (it needs to live on the server FS
    # because docker run bind-mount needs server-side paths).
    remote_script_dir = f"{REMOTE_DIR}/.deploy-staging"
    ssh_cmd(f"mkdir -p {remote_script_dir}")
    remote_script = f"{remote_script_dir}/sync.sh"
    # rsync single file (no --delete, won't touch anything else).
    rsync_script_cmd = [
        "rsync", "-av",
        "-e", f"ssh -i {os.path.expanduser(SSH_KEY)}",
        str(script_path), f"{SSH_TARGET}:{remote_script}",
    ]
    run(rsync_script_cmd)

    script_mount = ["-v", f"{remote_script}:/sync.sh:ro"]
    remote_cmd = (
        f"docker run --rm "
        f"-v {DEPLOY_UPLOADS_VOLUME}:/dst "
        + " ".join(script_mount + mount_args)
        + f" {DEPLOY_UPLOADS_IMAGE} sh /sync.sh"
    )

    log(f"[uploads] sync into {DEPLOY_UPLOADS_VOLUME} ...")
    ssh_cmd(remote_cmd)

    # Clean up local + remote temp files.
    try:
        script_path.unlink()
        script_path.parent.rmdir()
    except OSError:
        pass
    ssh_cmd(f"rm -rf {remote_script_dir}", check=False)

    # Post-sync verification — count presets inside running container.
    # If idea-server isn't running (e.g. first-ever deploy), skip with a warn.
    # Dry-run mode: skip entirely (rsync 没真跑，凭空检查会假阳性失败).
    if _is_dry_run():
        log("[uploads] dry-run: skipping verification")
        return

    # check=False: server 容器还没起来时 docker exec 会返回非 0,这里不能让 deploy 炸。
    # 下面的 if/elif 三段逻辑区分"No such container"(warn) / count < threshold(die) / OK
    probe = ssh_cmd(
        "docker exec idea-server sh -c 'ls /app/uploads/avatars/presets 2>/dev/null | wc -l'",
        capture=True,
        check=False,
    )
    try:
        actual = int((probe.stdout or "0").strip())
    except ValueError:
        actual = 0
    threshold = DEPLOY_UPLOADS_MIN_PRESETS
    stderr_text = (probe.stderr or "")
    if actual == 0 and "No such container" in stderr_text:
        log("[uploads] WARN: idea-server not running — skipping count verification")
    elif actual < threshold:
        die(
            f"[uploads] verification FAILED: presets count = {actual}, "
            f"expected >= {threshold}. Check volume and bind-mount paths."
        )
    else:
        log(f"[uploads] verification OK: presets count = {actual} (>= {threshold})")


def action_deploy(*, sync_only: bool = False, skip_uploads: bool = False, force_upload: bool = False) -> None:
    """完整部署：同步 → 上传静态资源 → 远程构建 → 远程重启。

    注意:.env.production 不在 deploy 流程里同步。
    服务器的 .env.production 是手维护的（deploy.py RSYNC_EXCLUDES 排除了）。
    本地改了 .env.production 之后,scp 推过去:
      scp .env.production tenxunyun:/opt/ideaparty/.env.production
    """
    log("=== Step 0/4: ensure remote dir ===")
    ensure_remote_dir()

    log("=== Step 1/4: rsync local → remote ===")
    action_sync()

    if not skip_uploads:
        log("=== Step 1.5/4: sync uploads into idea-server-uploads volume ===")
        action_sync_uploads()
    else:
        log("[uploads] skipped via --skip-uploads")

    log("=== Step 1.6/4: upload uploads to OSS ===")
    # 默认走 manifest 增量 (--force-upload 强制全量 PUT 覆盖)。
    # 失败 warn 但不阻断 deploy。
    try:
        action_upload_uploads(force=force_upload)
    except SystemExit:
        log("⚠️  uploads 上传失败,跳过 (deploy 主流程继续)")

    if sync_only:
        log("sync-only 模式，跳过构建和重启")
        return

    log("=== Step 1.75/4: verify OSS preset count (defensive) ===")
    _check_oss_preset_count()

    log("=== Step 2/4: docker compose build ===")
    # 强制 BuildKit + plain 进度 — 默认 BuildKit 也能跑,但 -q 模式把进度压成单行
    # 让"卡 maven go-offline"这种事完全看不出在跑(见 issue: deploy 卡 go-offline)。
    # 走 BuildKit 后台 cache + plain 进度条,build 卡哪一层/哪一行立刻可见。
    build_cmd = (
        f"export DOCKER_BUILDKIT=1 BUILDKIT_PROGRESS=plain && "
        f"cd {REMOTE_DIR} && docker compose --env-file {REMOTE_ENV_FILE} build"
    )
    ssh_cmd(build_cmd)

    log("=== Step 3/4: docker compose up -d ===")
    ssh_cmd(f"cd {REMOTE_DIR} && docker compose --env-file {REMOTE_ENV_FILE} up -d")

    log("=== Step 3.5/4: wait for all services healthy ===")
    _wait_for_healthy()

    log("=== Status check ===")
    ssh_cmd(f"cd {REMOTE_DIR} && docker compose --env-file {REMOTE_ENV_FILE} ps")


def _check_oss_preset_count() -> None:
    """OSS 上 presets/ 数量低于阈值就 die,让用户跑 --migrate-oss。

    之前的 bug:本地 server/uploads/avatars/presets/ 有 600+ 张图,但 OSS 上 uploads/
    avatars/presets/ 是 0,deploy 完后客户端图片全 404(看 character library 出现字母
    头像)。原因是 .env.production 漏配 ALIYUN_* 变量,migrate-oss 没法跑。

    这个检查在 deploy 主流程里做兜底:即使 .env.production 配错了,deploy 时
    也能提醒运维"OSS 上图不全,先跑 --migrate-oss"。

    退出码:
      0  = OSS 上图数 >= 阈值,继续 deploy
      非 0 = 阻断 deploy,提示跑 --migrate-oss

    跳过方式:DEPLOY_SKIP_OSS_CHECK=1 python3 deploy.py
    """
    if os.environ.get("DEPLOY_SKIP_OSS_CHECK") == "1":
        log("[oss-check] skipped via DEPLOY_SKIP_OSS_CHECK=1")
        return

    # 阈值:本地至少有 ~600 张 presets,OSS 至少要有 500 才算 OK。
    # 留 buffer 是为了"已迁移到 OSS 但本地新加几张图"的常见场景不报警。
    threshold = int(os.environ.get("DEPLOY_OSS_MIN_PRESETS", "500"))
    bucket = os.environ.get("ALIYUN_OSS_BUCKET", "idea-party-uploads")
    # aliyun CLI 的 --region 只接受 RegionId (cn-shenzhen),不接受 oss-cn-shenzhen
    region = os.environ.get("ALIYUN_OSS_REGION", "oss-cn-shenzhen")
    if region.startswith("oss-"):
        region = region[len("oss-"):]

    # 先测 aliyun CLI 是否装了(没装就 warn+skip,不阻断 deploy)
    cli_check = ssh_cmd("command -v aliyun >/dev/null 2>&1 && echo OK || echo MISSING", capture=True, check=False)
    if "OK" not in (cli_check.stdout or ""):
        log("⚠️  服务器没装 aliyun CLI,跳过 OSS 预设图检查。装: ssh tenxunyun '... install ...'")
        return

    cmd = (
        f"export ALIYUN_PROFILE=default && "
        f"COUNT=$(aliyun oss ls oss://{bucket}/uploads/avatars/presets/ "
        f"  --region {region} 2>/dev/null | wc -l) && "
        f"echo \"oss_preset_count=$COUNT threshold=$threshold\" && "
        f"if [ \"$COUNT\" -lt \"$threshold\" ]; then "
        f"  echo \"❌ OSS 上 presets 数量 $COUNT < $threshold,部署后会全 404。\" >&2; "
        f"  echo \"   解决: python3 deploy.py --migrate-oss\" >&2; "
        f"  echo \"   跳过: DEPLOY_SKIP_OSS_CHECK=1 python3 deploy.py\" >&2; "
        f"  exit 1; "
        f"fi"
    )
    proc = ssh_cmd(cmd, check=False)
    if proc.returncode != 0:
        die(
            "OSS 上 presets 数量不足,deploy 已阻断(避免上线后客户端图片全 404)。\n"
            "  解决: python3 deploy.py --migrate-oss\n"
            "  跳过: DEPLOY_SKIP_OSS_CHECK=1 python3 deploy.py"
        )


def _scp_env_production() -> None:
    """把本地 .env.production 推到服务器(覆盖式,不带备份)。

    由 --sync-env 显式调用;deploy 主流程不再自动做这件事。

    安全考虑:
      - 本地有 .env.production 才推;本地没有就 warn 但不阻断
      - scp 失败会真 die:避免用错误 env 启动容器(server 容器连不上 MySQL 就崩)
    """
    local_env = Path.cwd() / ".env.production"
    if not local_env.exists():
        log(f"⚠️  本地 {local_env} 不存在,跳过。服务器端的 .env.production 保持不变。")
        return
    ssh_path = os.path.expanduser(SSH_KEY)
    log(f"scp .env.production → {SSH_TARGET}:{REMOTE_DIR}/.env.production")
    proc = run([
        "scp", "-i", ssh_path, "-o", "BatchMode=yes", "-o", "StrictHostKeyChecking=accept-new",
        str(local_env), f"{SSH_TARGET}:{REMOTE_DIR}/.env.production",
    ], check=False, capture=True)
    if proc.returncode != 0:
        log(f"❌ scp 失败 (exit {proc.returncode})")
        if proc.stderr:
            log(f"   stderr: {proc.stderr.strip()}")
        log(f"   stdout: {(proc.stdout or '').strip()}")
        die("env 同步失败,deploy 中断(避免用错误 env 启动容器)")


def action_sync_env() -> None:
    """显式把本地 .env.production 推到服务器(覆盖式,不带备份)。

    deploy 主流程不会自动做这件事(防止本地错配覆盖服务器正常工作状态)。
    你改完本地 .env.production 之后,显式跑这条:
      python3 deploy.py --sync-env
    然后重启 server 让 env 生效:
      python3 deploy.py --restart server
    """
    _scp_env_production()
    log("✅ 同步完成。容器还没重启,要重启请跑: python3 deploy.py --restart server")


def _wait_for_healthy(timeout: int = 180) -> None:
    """等所有容器 healthy,up -d 后立即返回 200,healthcheck 还要再等几十秒。

    用 docker compose ps --format json 解析 status,3s 轮询一次,直到全部 healthy
    或超时。如果超时,打印 unhealthy 的容器,提示用户。
    """
    log(f"等待容器 healthy (timeout={timeout}s) ...")
    deadline = time.time() + timeout
    expected = None
    while time.time() < deadline:
        # --format json 拿所有服务的 health 状态
        proc = ssh_cmd(
            f"cd {REMOTE_DIR} && docker compose --env-file {REMOTE_ENV_FILE} ps --format json",
            capture=True, check=False,
        )
        if proc.returncode != 0 or not proc.stdout:
            time.sleep(3)
            continue
        # 解析:每行一个 JSON。docker compose ps --format json 给的是 JSON Lines
        rows = []
        for line in proc.stdout.strip().splitlines():
            try:
                rows.append(json.loads(line))
            except json.JSONDecodeError:
                continue
        if expected is None:
            expected = [r["Name"] for r in rows]
        # 健康判定:Health 字段是 "healthy" / "unhealthy" / "" / "starting"
        by_name = {r["Name"]: (r.get("Health") or "").lower() for r in rows}
        all_ok = all(
            v in ("healthy", "")  # 没 healthcheck 的服务当作 healthy
            for v in by_name.values()
        )
        if all_ok and len(by_name) == len(expected):
            log(f"✅ 全部 {len(expected)} 个容器 healthy")
            return
        # 报告还在等的
        waiting = [n for n, h in by_name.items() if h not in ("healthy", "")]
        if waiting:
            log(f"   等待 healthy: {', '.join(waiting)}")
        time.sleep(3)
    log(f"⚠️  超时 {timeout}s,部分容器仍非 healthy:")
    ssh_cmd(
        f"cd {REMOTE_DIR} && docker compose --env-file {REMOTE_ENV_FILE} ps",
        check=False,
    )


def action_status() -> None:
    """查看容器状态。"""
    ssh_cmd(f"cd {REMOTE_DIR} && docker compose --env-file {REMOTE_ENV_FILE} ps")


def action_logs(service: str | None) -> None:
    """tail 日志。Ctrl+C 退出。"""
    target = service if service else ""
    # -f 持续输出，--tail=200 先看最近 200 行
    ssh_cmd(f"cd {REMOTE_DIR} && docker compose --env-file {REMOTE_ENV_FILE} logs -f --tail=200 {target}")


def action_restart(service: str) -> None:
    """重启单个服务。"""
    if not service:
        die("--restart 需要指定服务名，例如 server / client / mysql")
    log(f"Restarting {service} ...")
    ssh_cmd(f"cd {REMOTE_DIR} && docker compose --env-file {REMOTE_ENV_FILE} restart {service}")
    log(f"Done. 状态：")
    ssh_cmd(f"cd {REMOTE_DIR} && docker compose --env-file {REMOTE_ENV_FILE} ps {service}")


def action_shell(service: str) -> None:
    """进入容器 shell（交互式）。"""
    if not service:
        die("--shell 需要指定服务名")
    # 用 ssh -t 强制分配 tty
    ssh_path = os.path.expanduser(SSH_KEY)
    cmd = ["ssh", "-t", "-i", ssh_path, SSH_TARGET, f"cd {REMOTE_DIR} && docker compose --env-file {REMOTE_ENV_FILE} exec {service} sh"]
    run(cmd)


def action_migrate_oss(*, dry_run: bool = False) -> None:
    """把 server/uploads/avatars/ 推到阿里云 OSS 桶 idea-party-uploads。

    一次性迁移,跑完即可禁用迁移用的 RAM 用户的 AK。

    用法:
      python3 deploy.py --migrate-oss            # 真传
      python3 deploy.py --migrate-oss --dry-run  # 只列计划

    依赖: 服务器装了 oss2(pip3 install --user oss2),环境变量从服务器
          .env.production 读(aliyun.oss.* 和 aliyun.sts.*)。
    """
    # 先探测 pip,没有就 sudo apt install(Ubuntu 22.04 最小化没装 pip)
    # sudo 可能要密码,装失败就让用户手动装并给清晰指引
    cmd = (
        f"cd {REMOTE_DIR} && "
        f"if ! python3 -c 'import pip' 2>/dev/null; then "
        # 没 pip: 装一次。non-interactive 是为了避免 sudo 密码提示卡 deploy
        # 如果 sudo 需要密码,这里会失败,看下面错误处理
        f"  (sudo -n apt-get install -y python3-pip 2>&1 || "
        f"   {{ echo '❌ 服务器没装 pip,且 sudo 免密失败。请 SSH 服务器手动跑:'; "
        f"     echo '   sudo apt install -y python3-pip'; "
        f"     echo '   然后再跑: python3 deploy.py --migrate-oss'; exit 1; }}) "
        f"  | tail -5; "
        f"fi && "
        # 用 python3 -m pip,装到 --user 避免污染系统
        # --break-system-packages 绕过 PEP 668
        f"python3 -m pip install --user --break-system-packages --quiet oss2 2>&1 | tail -3; "
        f"python3 server/scripts/migrate_uploads_to_oss.py --src server/uploads/avatars"
        f"{' --dry-run' if dry_run else ''}"
    )
    log(f"== 迁移 server/uploads/avatars/ → 阿里云 OSS ==")
    if dry_run:
        log("[DRY-RUN] 只列计划,不会真正上传")
    # 安全: server 端自己 source .env.production,Secret 不进 SSH 命令行。
    # 把 set -a/source/set +a 拼到 cmd 前面,远程执行时所有 ALIYUN_* 自动 export。
    cmd = (
        f"cd {REMOTE_DIR} && set -a && source .env.production && set +a && "
        + cmd  # cmd 仍包含 migrate_uploads_to_oss.py 主体
    )
    ssh_cmd(cmd)


def action_upload_uploads(*, dry_run: bool = False, force: bool = False) -> None:
    """把 server/uploads/avatars/ 下所有 DEPLOY_UPLOADS_SUBDIRS 子目录推到 OSS。

    每个子目录的 key 路径: uploads/avatars/<sub>/,与 OSS 桶结构对齐。
    **Manifest 增量同步**: 本地维护 OSS_MANIFEST_PATH 记录每个 key 的
    {mtime, size, md5},deploy 时只 PUT mtime 或 size 变化的文件,
    mtime+size+md5 三项一致才 skip。RAM 用户只有 PutObject 权限(没
    HeadObject / ListObject),用本地 manifest 代替 OSS 端 diff,符合
    最小权限原则。

    首跑全量 PUT(无 manifest 视作空 dict),后续 deploy 只 PUT 变更。
    PUT 时附带 Cache-Control: public, max-age=31536000, immutable +
    Content-Type。头像/场景/品牌图是永久静态资源,immutable 缓存浏览器
    直接走磁盘缓存,不会再 304 探活。失败 warn 但不阻断 deploy。

    manifest 文件随 rsync 同步到服务器,服务器端读写都用同一份。
    文件不入 git(.gitignore 排除)。

    依赖: 服务器装了 oss2(.env.production 已配)。
    """
    subdirs = list(DEPLOY_UPLOADS_SUBDIRS)
    if force:
        log(f"== 上传 server/uploads/avatars/ 下 {len(subdirs)} 个子目录 → 阿里云 OSS (force 全量) ==")
    else:
        log(f"== 上传 server/uploads/avatars/ 下 {len(subdirs)} 个子目录 → 阿里云 OSS (manifest 增量) ==")
    if dry_run:
        log("[DRY-RUN] 只列计划,不会真正上传")
    # 安全: 不通过 SSH 转发任何 Secret,改为让 server 端 Python 自己
    # source /opt/ideaparty/.env.production。Secret 留在服务器文件系统上,
    # 不进 SSH 命令行 / 本机 log / shell history。
    # subdirs 列表用 repr() 安全注入到生成的 Python 脚本里。
    subdirs_repr = repr(subdirs)
    # manifest 路径用 OSS_MANIFEST_PATH 常量,deploy 时 rsync 同步到服务器,
    # 路径在两端完全一致。
    # force 模式: 把 manifest 看作空 dict,所有文件都重新 PUT (不走 skip 逻辑),
    # 走完后写回新 manifest (覆盖旧记录,md5 重算)。
    skip_check = "False" if force else "True"
    inline_py = textwrap.dedent("""\
        import hashlib, json, mimetypes, os, oss2, time
        from pathlib import Path

        MANIFEST_PATH = __MANIFEST__
        subdirs = __SUBDIRS__

        def md5_of(p):
            h = hashlib.md5()
            with open(p, 'rb') as f:
                for chunk in iter(lambda: f.read(1024 * 1024), b''):
                    h.update(chunk)
            return h.hexdigest()

        def load_manifest():
            try:
                with open(MANIFEST_PATH, 'r', encoding='utf-8') as f:
                    return json.load(f)
            except (FileNotFoundError, json.JSONDecodeError):
                return {}

        def save_manifest(m):
            tmp = MANIFEST_PATH + '.tmp'
            with open(tmp, 'w', encoding='utf-8') as f:
                json.dump(m, f, ensure_ascii=False, sort_keys=True, indent=2)
            os.replace(tmp, MANIFEST_PATH)

        auth = oss2.Auth(os.environ['ALIYUN_STS_ACCESS_KEY_ID'], os.environ['ALIYUN_STS_ACCESS_KEY_SECRET'])
        bucket = oss2.Bucket(auth, os.environ['ALIYUN_OSS_ENDPOINT'], os.environ['ALIYUN_OSS_BUCKET'])

        manifest = load_manifest()
        new_manifest = dict(manifest)  # 复制原 manifest,本跑增量后写回
        # 清理本地已删的 key(避免 manifest 无限膨胀 — OSS 上的旧对象由
        # 后续 _check_oss_preset_count / 手工 ossutil rm 兜底,这里只清本地账本)
        active_keys = set()

        t0 = time.time()
        total_ok = total_fail = total_skip = 0
        for sub in subdirs:
            src = Path('server/uploads/avatars') / sub
            if not src.is_dir():
                print('[upload] [%s] skip: local dir not exist' % sub)
                continue
            files = [p for p in src.rglob('*') if p.is_file()]
            print('[upload] [%s] %d files' % (sub, len(files)))
            ok = fail = skip = 0
            for p in files:
                rel = p.relative_to(src).as_posix()
                key = 'uploads/avatars/%s/%s' % (sub, rel)
                active_keys.add(key)
                st = p.stat()
                mtime = int(st.st_mtime)
                size = st.st_size
                prev = manifest.get(key)
                # mtime+size+md5 三项都一致才 skip;md5 缺失(首次或上次 PUT 失败)会
                # 视为变更,触发 PUT,PUT 成功后写新 md5 进 manifest。
                # --force-upload 时 manifest 完全跳过,所有文件强制 PUT。
                if __SKIP_CHECK__ and prev and prev.get('mtime') == mtime and prev.get('size') == size and prev.get('md5'):
                    skip += 1
                    continue
                try:
                    headers = {'Cache-Control': 'public, max-age=31536000, immutable'}
                    ct = mimetypes.guess_type(p.name)[0]
                    if ct:
                        headers['Content-Type'] = ct
                    bucket.put_object_from_file(key, str(p), headers=headers)
                    ok += 1
                    # md5 计算放 PUT 成功之后:PUT 失败就不写,下次自然重试
                    new_manifest[key] = {'mtime': mtime, 'size': size, 'md5': md5_of(p)}
                    if ok % 50 == 0:
                        print('[upload] [%s] %d/%d put' % (sub, ok, len(files)))
                except Exception as e:
                    print('  [FAIL] %s: %s: %s' % (p.relative_to(src), type(e).__name__, e))
                    fail += 1
            print('[upload] [%s] done: put=%d skip=%d fail=%d' % (sub, ok, skip, fail))
            total_ok += ok
            total_fail += fail
            total_skip += skip

        # 把本地已删的 key 从 manifest 清掉
        removed = [k for k in new_manifest if k not in active_keys]
        for k in removed:
            del new_manifest[k]

        save_manifest(new_manifest)
        if removed:
            print('[upload] manifest pruned: %d deleted keys' % len(removed))

        elapsed = time.time() - t0
        print('[upload] all done: put=%d skip=%d fail=%d in %.1fs (manifest size=%d)'
              % (total_ok, total_skip, total_fail, elapsed, len(new_manifest)))
    """).replace("__SUBDIRS__", subdirs_repr).replace("__MANIFEST__", repr(OSS_MANIFEST_PATH)).replace("__SKIP_CHECK__", skip_check)    # 写到本地临时文件,rsync 到服务器临时路径,服务器上 python3 执行。
    # 这样完全避开 bash -c / ssh escape 的复杂引用。
    local_script = Path(tempfile.mkdtemp(prefix="deploy-upload-")) / "upload_uploads.py"
    local_script.write_text(inline_py, encoding="utf-8")
    remote_script_dir = f"{REMOTE_DIR}/.deploy-staging"
    ssh_cmd(f"mkdir -p {remote_script_dir}")
    remote_script = f"{remote_script_dir}/upload_uploads.py"
    run([
        "rsync", "-av",
        "-e", f"ssh -i {os.path.expanduser(SSH_KEY)}",
        str(local_script), f"{SSH_TARGET}:{remote_script}",
    ])
    cmd = f"cd {REMOTE_DIR} && set -a && source .env.production && set +a && python3 {remote_script}"
    if dry_run:
        log(f"[DRY-RUN] 会跑: {cmd}")
        log(inline_py)
        return
    # 不传 forward_env,所有 ALIYUN_* 都在 server 端 .env.production 里
    ssh_cmd(cmd)
    # 清理服务器临时文件
    ssh_cmd(f"rm -f {remote_script}", check=False)


def action_aliyun_test(*, head_bytes: int = 300) -> None:
    """在服务器上跑一次 STS AssumeRole,输出前 N 字节。

    用于烟测 RAM 角色配置是否正确(用户绑了 AliyunSTSAssumeRoleAccess、
    角色绑了 ideaparty-oss-put-only、信任策略指向当前云账号)。
    截断输出避免意外把 STS 临时 AccessKeySecret 复制到 chat。

    退出码:
      0 = AssumeRole 拿到有效 Credentials
      非 0 = aliyun CLI 没装、AK 没配、权限链断了
    """
    role_arn = os.environ.get("ALIYUN_STS_ROLE_ARN", "").strip()
    if not role_arn:
        die(
            "ALIYUN_STS_ROLE_ARN 未设置。\n"
            "在 .env.deploy 里 export 一下,例如:\n"
            "  export ALIYUN_STS_ROLE_ARN=acs:ram::1076616112331319:role/idea-party-uploader"
        )

    # 服务器上跑 aliyun sts AssumeRole, 串 head -c 截断
    # head -c 在 aliyun CLI 没装时也能定位错误(返回 No such file)
    remote_cmd = (
        f"aliyun sts AssumeRole "
        f"--RoleArn {shlex.quote(role_arn)} "
        f"--RoleSessionName {shlex.quote('ideaparty-smoke-test')} "
        f"2>&1 | head -c {head_bytes}"
    )
    log(f"== 烟测 STS AssumeRole ==\nrole: {role_arn}\n截断输出 (前 {head_bytes} 字节):")
    try:
        result = ssh_cmd(remote_cmd, capture=True, check=False)
    except Exception as e:
        die(f"SSH 执行失败: {e}")

    print(result.stdout or "(空输出)")

    # 成功标志: 输出含 "Credentials" 子串
    if "Credentials" in (result.stdout or ""):
        log("✅ AssumeRole 返回有效 Credentials,RAM 链路通")
    elif "does not exist" in (result.stdout or ""):
        log("❌ 角色不存在或 ARN 拼错。检查 RAM 控制台角色名 / ARN 格式 (结尾应是 :role/...)")
    elif "does not have permission" in (result.stdout or ""):
        log("❌ RAM 用户没绑 AliyunSTSAssumeRoleAccess 策略,或 AK 配错")
    elif "trust policy" in (result.stdout or ""):
        log("❌ 角色信任策略没指向当前云账号。RAM 角色 → 信任策略 tab 修改")
    elif "command not found" in (result.stdout or "") or result.returncode == 127:
        log("❌ 服务器没装 aliyun CLI。SSH 进去装:\n"
            "  curl -O https://aliyuncli.alicdn.com/aliyun-cli-linux-latest-amd64.tgz\n"
            "  tar xzf aliyun-cli-linux-latest-amd64.tgz\n"
            "  sudo cp aliyun /usr/local/bin/\n"
            "  aliyun configure  # 填 AK + Region cn-shenzhen")
    else:
        log(f"❓ 未识别结果,returncode={result.returncode}。把上面输出贴给 Claude")


# =============================================================================
# CLI
# =============================================================================
def parse_args() -> argparse.Namespace:
    p = argparse.ArgumentParser(
        description="一键部署 Idea-Party 到腾讯云 CVM",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog="示例：\n  python3 deploy.py --logs server\n  python3 deploy.py --restart client",
    )
    p.add_argument("--sync-only", action="store_true", help="只同步，不构建/重启")
    p.add_argument("--skip-uploads", action="store_true", help="跳过 uploads volume 同步（仅调试 / 已知 volume 健康时使用）")
    p.add_argument("--force-upload", action="store_true", help="强制全量 PUT 所有 uploads 到 OSS,忽略 manifest (默认走增量)")
    p.add_argument("--status", action="store_true", help="查看容器状态")
    p.add_argument("--logs", nargs="?", const="", metavar="SERVICE", help="tail 日志，可指定服务名")
    p.add_argument("--restart", metavar="SERVICE", help="重启指定服务")
    p.add_argument("--shell", metavar="SERVICE", help="进入容器 shell")
    p.add_argument("--aliyun-test", action="store_true",
                   help="烟测阿里云 STS AssumeRole,服务器上跑一次,输出前 300 字节")
    p.add_argument("--migrate-oss", action="store_true",
                   help="把 server/uploads/avatars/ 推到阿里云 OSS 桶(一次性,见 server/scripts/migrate_uploads_to_oss.py)")
    p.add_argument("--sync-env", action="store_true",
                   help="显式把本地 .env.production 推到服务器(覆盖式,deploy 主流程不再自动做,改完 env 后跑这条)")
    p.add_argument("--dry-run", action="store_true", help="只打印要执行的命令，不真正执行")
    return p.parse_args()


def main() -> None:
    args = parse_args()

    # 把所有 run() 调用钩到 dry-run
    if args.dry_run:
        global run

        def dry_run(cmd, check=True, capture=False, cwd=None):
            del check, capture, cwd  # 接受同样的接口，但 dry-run 不真正用
            printable = " ".join(shlex.quote(c) for c in cmd)
            log(f"[DRY-RUN] {printable}")
            return subprocess.CompletedProcess(cmd, 0, "", "")

        run = dry_run  # type: ignore

    try:
        # 优先级：子命令 > 默认 deploy
        if args.status:
            action_status()
        elif args.logs is not None:
            action_logs(args.logs or None)
        elif args.restart:
            action_restart(args.restart)
        elif args.shell:
            action_shell(args.shell)
        elif args.aliyun_test:
            action_aliyun_test()
        elif args.migrate_oss:
            action_migrate_oss(dry_run=args.dry_run)
        elif args.sync_env:
            action_sync_env()
        else:
            action_deploy(sync_only=args.sync_only, skip_uploads=args.skip_uploads, force_upload=args.force_upload)
    except subprocess.CalledProcessError as e:
        die(f"命令执行失败 (exit {e.returncode}): {e.cmd}")


if __name__ == "__main__":
    main()
