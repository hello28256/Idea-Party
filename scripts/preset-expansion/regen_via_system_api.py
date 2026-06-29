"""
v3: 用项目自带的 /api/characters/generate-prompt 接口重跑 shortlist.json 里的 307 个新加角色。

为什么不再直接调 DeepSeek 公网 API:
- 项目内部 CharacterService.generatePrompt 走的是 loadPromptTemplate() (prompts/character-prompt-generator.txt)
  严格的 5 段结构 + 禁词清单 + "输出第一个字符必须是【身份背景】" 等硬约束,
  比 batch_generate_v2.py 手写 system prompt 稳定得多。
- 接口已写好登录鉴权 + 错误处理, 复用即可, 不在脚本里重新发明轮子。

认证: 从环境变量 IDEAPARTY_USER / IDEAPARTY_PASS 读登录凭据,
      登录成功拿 JWT, 后续请求带 Authorization: Bearer <token>。

输入: scripts/preset-expansion/shortlist.json
输出: scripts/preset-expansion/generated_v3.jsonl
      每行 {name, category, description, prompt} 供 merge_v3_to_presets.py 写回 presets.json

断点续跑: 已写入 generated_v3.jsonl 的 (category::name) 自动跳过。
"""

import json
import os
import sys
import time
import threading
import urllib.request
import urllib.error
from pathlib import Path
from queue import Queue, Empty

# ---- 配置 ----------------------------------------------------------------
ROOT = Path("/Users/yangq/Codes/Idea-Party/scripts/preset-expansion")
SHORTLIST = ROOT / "shortlist.json"
OUT = ROOT / "generated_v3.jsonl"

BASE = os.environ.get("IDEAPARTY_BASE_URL", "http://localhost:8082").rstrip("/")
USER = os.environ.get("IDEAPARTY_USER", "").strip()
PASS = os.environ.get("IDEAPARTY_PASS", "").strip()
WORKERS = int(os.environ.get("WORKERS", "4"))
RETRY = int(os.environ.get("RETRY", "3"))

# ---- 工具 ----------------------------------------------------------------
class ApiError(RuntimeError):
    pass


def _post_json(path: str, body: dict, token: str | None = None, timeout: int = 120) -> dict:
    headers = {"Content-Type": "application/json"}
    if token:
        headers["Authorization"] = f"Bearer {token}"
    req = urllib.request.Request(
        f"{BASE}{path}",
        data=json.dumps(body).encode("utf-8"),
        headers=headers,
        method="POST",
    )
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            return json.loads(r.read())
    except urllib.error.HTTPError as e:
        body = e.read().decode("utf-8", errors="replace")[:400]
        raise ApiError(f"HTTP {e.code} {path}: {body}") from e
    except urllib.error.URLError as e:
        raise ApiError(f"URLError {path}: {e}") from e


# ---- token 自动续期 -----------------------------------------------------
class TokenRefresher:
    """线程安全: 多 worker 共享一个 token, 401 时所有 worker 一起刷新。"""
    def __init__(self):
        self._lock = threading.Lock()
        self._token: str | None = None

    def get(self) -> str:
        with self._lock:
            if not self._token:
                self._token = login()
            return self._token

    def refresh(self) -> str:
        with self._lock:
            print("\n[!] 检测到 401, 重新登录刷新 token ...", file=sys.stderr)
            self._token = login()
            return self._token


def login() -> str:
    """登录拿 JWT。失败直接抛错 (环境变量缺失 / 密码错 / 服务没起)。"""
    if not USER or not PASS:
        sys.exit(
            "需要 IDEAPARTY_USER 和 IDEAPARTY_PASS 环境变量。\n"
            "在终端执行:\n"
            '  export IDEAPARTY_USER="你的用户名"\n'
            '  export IDEAPARTY_PASS="你的密码"\n'
            "  python3 scripts/preset-expansion/regen_via_system_api.py"
        )
    print(f"[*] login as {USER} @ {BASE}")
    resp = _post_json(
        "/api/auth/login",
        {"identifier": USER, "password": PASS},
        timeout=30,
    )
    token = resp.get("token") or resp.get("accessToken") or resp.get("jwt")
    if not token:
        sys.exit(f"登录响应里没有 token 字段: {resp}")
    print(f"[+] got token ({len(token)} chars)\n")
    return token


def generate_one(refresher: TokenRefresher, name: str, description: str) -> str:
    """调系统接口生成 prompt。401 自动续 token + 最多 RETRY 次重试。"""
    payload = {"name": name}
    if description:
        payload["description"] = description

    last_err = None
    for i in range(RETRY):
        token = refresher.get()
        try:
            resp = _post_json(
                "/api/characters/generate-prompt",
                payload,
                token=token,
                timeout=120,
            )
            prompt = resp.get("prompt", "").strip()
            if not prompt:
                raise ApiError(f"空 prompt 响应: {resp}")
            return prompt
        except ApiError as e:
            last_err = e
            msg = str(e)
            if "HTTP 401" in msg:
                # 强制刷新 token 再重试 (本轮 i+1 用新 token)
                try:
                    refresher.refresh()
                except Exception as refresh_err:
                    raise ApiError(f"token 刷新失败: {refresh_err}") from e
                continue
            time.sleep(1 + i * 2)
    raise ApiError(f"retry exhausted: {last_err}")


# ---- 描述补全 -------------------------------------------------------------
# shortlist.json 只有 name+category, 系统接口接受 description 作上下文。
# 这里给一些常用人物类别补一个简短的英文/中文描述 hint, 帮 LLM 抓住定位。
# 没列出来的类别走空字符串, name 已经足够 LLM 检索知识。
CATEGORY_DESC_HINT = {
    "SCIENTIST": "科学家",
    "PHILOSOPHER": "哲学家",
    "ARTIST": "艺术家/画家",
    "WRITER": "作家/文学家",
    "MUSICIAN": "音乐家/作曲家",
    "ATHLETE": "运动员",
    "POLITICIAN": "政治家",
    "MILITARY_LEADER": "军事家/将领",
    "ENTREPRENEUR": "企业家/工程师",
    "HISTORICAL": "历史人物",
    "RELIGIOUS_LEADER": "宗教人物",
    "OTHER": "公众人物",
}


def build_description(category: str, name: str) -> str:
    hint = CATEGORY_DESC_HINT.get(category)
    if not hint:
        return ""
    return f"{hint}, 用于AI角色对话场景"


# ---- worker ---------------------------------------------------------------
LOCK = threading.Lock()


def worker(q: Queue, refresher: TokenRefresher, done: set):
    while True:
        try:
            item = q.get_nowait()
        except Empty:
            return
        cat, name = item["category"], item["name"]
        key = f"{cat}::{name}"
        if key in done:
            q.task_done()
            continue
        try:
            desc = build_description(cat, name)
            prompt = generate_one(refresher, name, desc)
            rec = {"name": name, "category": cat, "description": desc, "prompt": prompt}
            with LOCK:
                with OUT.open("a", encoding="utf-8") as f:
                    f.write(json.dumps(rec, ensure_ascii=False) + "\n")
                print(f"  ✓ [{cat}] {name} ({len(prompt)} chars)")
            done.add(key)
        except Exception as e:
            print(f"  ✗ [{cat}] {name} - {e}", file=sys.stderr)
            # 失败重新入队一次 (避免永久卡死), 避免重试时无限重试
            if not item.get("_retried"):
                item["_retried"] = True
                q.put(item)
        q.task_done()
        time.sleep(0.3)


# ---- main -----------------------------------------------------------------
def main():
    if not SHORTLIST.exists():
        sys.exit(f"找不到 shortlist: {SHORTLIST}")

    refresher = TokenRefresher()
    short = json.loads(SHORTLIST.read_text())

    done = set()
    if OUT.exists():
        for line in OUT.read_text(encoding="utf-8").splitlines():
            try:
                rec = json.loads(line)
                done.add(f"{rec['category']}::{rec['name']}")
            except Exception:
                pass

    q: Queue = Queue()
    for cat, names in short.items():
        for n in names:
            q.put({"category": cat, "name": n})

    total = q.qsize()
    print(f"任务总数: {total}, 已完成: {len(done)}, 剩余: {total - len(done)}, 并发: {WORKERS}\n")

    threads = [threading.Thread(target=worker, args=(q, refresher, done)) for _ in range(WORKERS)]
    for t in threads:
        t.start()
    for t in threads:
        t.join()

    print(f"\n全部完成。输出: {OUT}")


if __name__ == "__main__":
    main()