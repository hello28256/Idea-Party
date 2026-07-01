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
import os
import shlex
import subprocess
import sys
import tempfile
import time
from pathlib import Path
from typing import Sequence

# =============================================================================
# Logger — 所有输出带 [DEBUG] 前缀，方便追溯
# =============================================================================
def log(msg: str) -> None:
    ts = time.strftime("%H:%M:%S")
    print(f"[{ts}] [DEBUG] {msg}", flush=True)


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
DEPLOY_UPLOADS_SUBDIRS = ("presets", "presets-webp", "hot-rooms", "scenarios")
# Minimum preset file count inside container for verification to pass.
DEPLOY_UPLOADS_MIN_PRESETS = int(os.environ.get("DEPLOY_UPLOADS_MIN_PRESETS", "100"))

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
]


# =============================================================================
# Subprocess helpers — 统一错误处理
# =============================================================================
def run(cmd: Sequence[str], *, check: bool = True, capture: bool = False, cwd: str | None = None) -> subprocess.CompletedProcess:
    """执行本地命令。check=True 时失败抛 CalledProcessError。"""
    printable = " ".join(shlex.quote(c) for c in cmd)
    log(f"$ {printable}")
    return subprocess.run(
        list(cmd),
        check=check,
        capture_output=capture,
        text=True,
        cwd=cwd,
    )


def ssh_cmd(remote_cmd: str, *, capture: bool = False, check: bool = True) -> subprocess.CompletedProcess:
    """在远程服务器上跑一条命令。check=False 用于 best-effort 操作
    （如 docker pull 镜像——已经缓存时仍要容忍非零退出）。"""
    ssh_path = os.path.expanduser(SSH_KEY)
    cmd = ["ssh", "-i", ssh_path, "-o", "BatchMode=yes", "-o", "StrictHostKeyChecking=accept-new", SSH_TARGET, remote_cmd]
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

    probe = ssh_cmd(
        "docker exec idea-server sh -c 'ls /app/uploads/avatars/presets 2>/dev/null | wc -l'",
        capture=True,
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


def action_deploy(*, sync_only: bool = False, skip_uploads: bool = False) -> None:
    """完整部署：同步 → 上传静态资源 → 远程构建 → 远程重启。"""
    log("=== Step 0/4: ensure remote dir ===")
    ensure_remote_dir()

    log("=== Step 1/4: rsync local → remote ===")
    action_sync()

    if not skip_uploads:
        log("=== Step 1.5/4: sync uploads into idea-server-uploads volume ===")
        action_sync_uploads()
    else:
        log("[uploads] skipped via --skip-uploads")

    if sync_only:
        log("sync-only 模式，跳过构建和重启")
        return

    log("=== Step 2/4: docker compose build ===")
    # 默认用 Docker BuildKit 缓存层(快,~30s);漏判场景下用户手动 docker compose build --no-cache 重建。
    # 不要在这里默认加 --no-cache:会拖慢日常部署(只改 .env / 文档时白白多花 1 分钟)。
    ssh_cmd(f"cd {REMOTE_DIR} && docker compose --env-file {REMOTE_ENV_FILE} build")

    log("=== Step 3/4: docker compose up -d ===")
    ssh_cmd(f"cd {REMOTE_DIR} && docker compose --env-file {REMOTE_ENV_FILE} up -d")

    log("=== Status check ===")
    ssh_cmd(f"cd {REMOTE_DIR} && docker compose --env-file {REMOTE_ENV_FILE} ps")


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
    cmd = (
        f"cd {REMOTE_DIR} && "
        # 用 python3 -m pip 而不是 pip3,避免 "pip3: command not found"
        # PEP 668 兼容:Ubuntu 22.04+ 默认禁 pip 装到系统环境,加 --break-system-packages
        # 装到 --user 不污染系统,只影响 ubuntu 用户自己
        f"python3 -m pip install --user --break-system-packages --quiet oss2 2>&1 | tail -3; "
        f"python3 server/scripts/migrate_uploads_to_oss.py"
        f"{' --dry-run' if dry_run else ''}"
    )
    log(f"== 迁移 server/uploads/avatars/ → 阿里云 OSS ==")
    if dry_run:
        log("[DRY-RUN] 只列计划,不会真正上传")
    ssh_cmd(cmd)


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
    p.add_argument("--status", action="store_true", help="查看容器状态")
    p.add_argument("--logs", nargs="?", const="", metavar="SERVICE", help="tail 日志，可指定服务名")
    p.add_argument("--restart", metavar="SERVICE", help="重启指定服务")
    p.add_argument("--shell", metavar="SERVICE", help="进入容器 shell")
    p.add_argument("--aliyun-test", action="store_true",
                   help="烟测阿里云 STS AssumeRole,服务器上跑一次,输出前 300 字节")
    p.add_argument("--migrate-oss", action="store_true",
                   help="把 server/uploads/avatars/ 推到阿里云 OSS 桶(一次性,见 server/scripts/migrate_uploads_to_oss.py)")
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
        else:
            action_deploy(sync_only=args.sync_only, skip_uploads=args.skip_uploads)
    except subprocess.CalledProcessError as e:
        die(f"命令执行失败 (exit {e.returncode}): {e.cmd}")


if __name__ == "__main__":
    main()
