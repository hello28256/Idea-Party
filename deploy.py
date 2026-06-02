#!/usr/bin/env python3
"""
deploy.py — 一键部署 Idea-Party 到腾讯云 CVM

功能：
  - 用 rsync 把本地项目同步到 CVM（只传差异）
  - 远程执行 docker compose build + up -d
  - 支持查看状态 / 拉日志 / 重启单个服务

用法：
  python3 deploy.py                 # 完整部署（同步 + 构建 + 重启）
  python3 deploy.py --sync-only     # 只同步，不触发部署
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


def ssh_cmd(remote_cmd: str, *, capture: bool = False) -> subprocess.CompletedProcess:
    """在远程服务器上跑一条命令。"""
    ssh_path = os.path.expanduser(SSH_KEY)
    cmd = ["ssh", "-i", ssh_path, "-o", "BatchMode=yes", "-o", "StrictHostKeyChecking=accept-new", SSH_TARGET, remote_cmd]
    return run(cmd, capture=capture)


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


def action_deploy(*, sync_only: bool = False) -> None:
    """完整部署：同步 → 远程构建 → 远程重启。"""
    log("=== Step 0/3: ensure remote dir ===")
    ensure_remote_dir()

    log("=== Step 1/3: rsync local → remote ===")
    action_sync()

    if sync_only:
        log("sync-only 模式，跳过构建和重启")
        return

    log("=== Step 2/3: docker compose build ===")
    ssh_cmd(f"cd {REMOTE_DIR} && docker compose --env-file {REMOTE_ENV_FILE} build")

    log("=== Step 3/3: docker compose up -d ===")
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
    p.add_argument("--status", action="store_true", help="查看容器状态")
    p.add_argument("--logs", nargs="?", const="", metavar="SERVICE", help="tail 日志，可指定服务名")
    p.add_argument("--restart", metavar="SERVICE", help="重启指定服务")
    p.add_argument("--shell", metavar="SERVICE", help="进入容器 shell")
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
        else:
            action_deploy(sync_only=args.sync_only)
    except subprocess.CalledProcessError as e:
        die(f"命令执行失败 (exit {e.returncode}): {e.cmd}")


if __name__ == "__main__":
    main()
