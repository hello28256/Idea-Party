"""
v2: 用项目标准格式(5 段 **【X】** 加粗标题)重跑 307 个新加角色的 prompt。

输入: shortlist.json (12 个分类, 每分类已 50)
输出: generated_v2.jsonl
覆盖: 直接改 server/src/main/resources/presets.json 的 prompt 字段

格式参考:冯·诺依曼 / C罗 / 伏尔泰 / 亚里士多德
"""
import json
import os
import sys
import time
import urllib.request
import urllib.error
import threading
from pathlib import Path
from queue import Queue, Empty

KEY = os.environ.get("DEEPSEEK_API_KEY", "").strip()
BASE = os.environ.get("DEEPSEEK_BASE_URL", "https://api.deepseek.com").strip()
MODEL = "deepseek-chat"
WORKERS = 4
RETRY = 3

ROOT = Path("/Users/yangq/Codes/Idea-Party/scripts/preset-expansion")
SHORTLIST = ROOT / "shortlist.json"
OUT = ROOT / "generated_v2.jsonl"
LOCK = threading.Lock()

# 关键: system prompt 严格用项目 5 段 **【X】** 加粗标题格式
SYSTEM = """你是角色提示词生成器,严格按以下 Markdown 结构输出(每个标题必须用 **【X】** 包裹):

**【身份背景】**
- 角色名 + 核心标签
- 当前状态(职业/处境/最近在干嘛)
- 1 个最大执念
- 1 个最大弱点/性格短板
- 1 个小秘密或私下面

**【说话风格】**
- 语气(快/慢/带口音/冷/热)
- 节奏(短句/长句/跳跃)
- 高频词、口头禅 2-3 个
- 是否爱打断/反问/嘲讽
- 末尾一行:**角色语言:中文。**(用 ** 加粗)

**【个人观点】**
1.  **(标题)**:"原话引用风格的金句" + 解释
2.  **(标题)**:"..."
3.  **(标题)**:"..."
(3-4 条,每条必须带标题,如 "关于金钱" / "关于努力" / "关于AI" 等)

**【行为反应规则】**
*   **触发[场景]**:[具体反应]
*   **主动软化[场景]**:[具体反应]
*   **独特关心**:[具体方式]
*   **敏感话题回避**:[具体策略]

**【聊天示例】**
1.  单条对话(像微信消息,不是剧本台词)
2.  ...
(6-8 条,每条独立,不带 "用户:" 前缀)

# 风格硬性要求
- 强聊天感、强互动感
- 避免文学化、避免AI味
- 避免空洞形容词("聪明善良温柔"等)
- 必须基于该角色的真实背景/性格/语录,不能瞎编
- 短句为主,允许脏话/口语
- 5 个 **【X】** 标题必须用 ** 加粗
- 聊天示例编号必须 1./2./3. 风格,不带 "用户:" 前缀
- 末尾必须有 **角色语言:中文。**

# 输出格式
只输出纯 Markdown prompt 文本,不要任何 JSON 包装,不要任何解释。
"""


def call(name: str, category: str) -> str:
    body = {
        "model": MODEL,
        "temperature": 0.7,
        "messages": [
            {"role": "system", "content": SYSTEM},
            {"role": "user", "content": f"角色名: {name}\n分类: {category}\n\n请生成严格符合上述 Markdown 结构的角色提示词:"}
        ]
    }
    last_err = None
    for i in range(RETRY):
        try:
            req = urllib.request.Request(
                f"{BASE}/v1/chat/completions",
                data=json.dumps(body).encode("utf-8"),
                headers={"Authorization": f"Bearer {KEY}", "Content-Type": "application/json"},
                method="POST",
            )
            with urllib.request.urlopen(req, timeout=90) as r:
                data = json.loads(r.read())
            return data["choices"][0]["message"]["content"].strip()
        except (urllib.error.HTTPError, urllib.error.URLError, json.JSONDecodeError, KeyError) as e:
            last_err = e
            time.sleep(1 + i * 2)
    raise RuntimeError(f"call failed: {last_err}")


def worker(q: Queue, done: set):
    while True:
        try:
            item = q.get_nowait()
        except Empty:
            return
        name, category = item["name"], item["category"]
        key = f"{category}::{name}"
        if key in done:
            q.task_done()
            continue
        try:
            prompt = call(name, category)
            rec = {"name": name, "category": category, "prompt": prompt}
            with LOCK:
                with OUT.open("a", encoding="utf-8") as f:
                    f.write(json.dumps(rec, ensure_ascii=False) + "\n")
                print(f"  ✓ [{category}] {name}")
            done.add(key)
        except Exception as e:
            print(f"  ✗ [{category}] {name} - {e}", file=sys.stderr)
            q.put(item)
        q.task_done()
        time.sleep(0.2)


def main():
    if not KEY:
        sys.exit("DEEPSEEK_API_KEY 未设置")
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
            q.put({"name": n, "category": cat})

    total = q.qsize()
    print(f"任务总数: {total}, 已完成: {len(done)}, 剩余: {total - len(done)}, 并发: {WORKERS}\n")

    threads = [threading.Thread(target=worker, args=(q, done)) for _ in range(WORKERS)]
    for t in threads:
        t.start()
    for t in threads:
        t.join()

    print(f"\n全部完成。输出: {OUT}")


if __name__ == "__main__":
    main()
