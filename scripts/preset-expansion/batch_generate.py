"""
批量调 DeepSeek 生成 prompt + 描述。

输入: shortlist.json (12 个分类, 每分类已 50)
输出: generated.jsonl (每行一个 JSON: {name, categories, description, prompt, avatarSlug})

运行:python3 batch_generate.py
要求:DEEPSEEK_API_KEY 已设 (本机)

并发:4 (默认),失败重试 3 次,断点续跑 (用 generated.jsonl 记录已成功的 name)。
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
OUT = ROOT / "generated.jsonl"
LOCK = threading.Lock()

SYSTEM = """你是一个角色提示词生成器，为 AI 聊天平台根据用户提供的"角色名+分类"创建极具特色、令人难忘的角色。

# 输入格式
你会收到一个 JSON:
{"name": "角色名", "category": "分类"}

# 输出格式(严格 JSON,不要 markdown)
{
  "description": "一句话描述(中文,20-50字,通俗易懂,贴合人物)",
  "prompt": "角色提示词(中文,150-250字,按下方结构)"
}

# 必须赋予角色的要素
1. 标志性语言习惯:反问、'你懂我意思吧?'、阴阳怪气、短句、长篇论述、打断人、爱用比喻、经常'啧'、先否定再认可
2. 强烈观点(至少3条):极度讨厌浪费时间/相信努力大于天赋/不相信爱情/崇拜金钱/讨厌互联网文化/对AI极度乐观或悲观/认为大多数人活得太麻木
3. 独特世界观:把感情问题理解成'资源错配'/天然怀疑所有人/把人生理解成'不断修bug'
4. 稳定情绪基调:暴躁/疲惫/亢奋/冷幽默/疑心重/高傲/神经质/厌世/理想主义

# 禁止使用的描述(废话)
❌ '聪明且善良' / '温柔体贴' / '睿智冷静' / '喜欢帮助别人' / '拥有丰富知识' / '逻辑清晰' / '善于分析'

# 正确 vs 错误示例
错误:'他很聪明'
正确:'他能三分钟看穿别人真正想问什么,但从不直接说破'

# prompt 字段结构
1. 角色身份:职业/经历、当前状态、核心信念、最大执念、最大弱点
2. 说话风格:语气、节奏、高频词、口头禅、是否喜欢提问/嘲讽/说教/打断、是否情绪化
3. 世界观与价值观:至少3条强烈观点
4. 行为规则:如何回应用户、什么情况会生气/兴奋、如何表达关心、如何回避脆弱话题
5. 对话示例:6~10句像真实聊天记录的示例,不要像小说台词

# 风格要求
- 强聊天感、强互动感
- 避免文学化、避免AI味、避免官方感
- 避免空洞形容词,每句话都要具体、行为化
- 必须基于该角色的真实背景/性格/语录,不能瞎编不存在的事
- 输出 prompt 末尾必须包含一行:**角色语言:中文。**
- 输出 description 末尾不要加句号以外的其他符号
- 只输出 JSON,不要任何解释、不要 markdown
"""


def call(name: str, category: str) -> dict:
    body = {
        "model": MODEL,
        "temperature": 0.7,
        "messages": [
            {"role": "system", "content": SYSTEM},
            {"role": "user", "content": json.dumps({"name": name, "category": category}, ensure_ascii=False)},
        ],
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
            content = data["choices"][0]["message"]["content"].strip()
            # 截 markdown fence
            if content.startswith("```"):
                content = content.split("\n", 1)[1].rsplit("```", 1)[0].strip()
            return json.loads(content)
        except (urllib.error.HTTPError, urllib.error.URLError, json.JSONDecodeError, KeyError) as e:
            last_err = e
            time.sleep(1 + i * 2)
    raise RuntimeError(f"call failed after {RETRY}: {last_err}")


def slugify(name: str) -> str:
    import re
    s = re.sub(r"[\s　]+", "-", name.strip())
    # 简单处理: 保留 ASCII/数字,中文保留,去除标点
    s = re.sub(r"[^\w一-龥-]", "", s)
    s = re.sub(r"-+", "-", s).strip("-").lower()
    return s or "x"


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
            res = call(name, category)
            rec = {
                "name": name,
                "category": category,
                "description": res["description"],
                "prompt": res["prompt"],
                "avatarSlug": slugify(name),
            }
            with LOCK:
                with OUT.open("a", encoding="utf-8") as f:
                    f.write(json.dumps(rec, ensure_ascii=False) + "\n")
                print(f"  ✓ [{category}] {name}")
            done.add(key)
        except Exception as e:
            print(f"  ✗ [{category}] {name} - {e}", file=sys.stderr)
            q.put(item)  # 重试
        q.task_done()
        time.sleep(0.2)


def main():
    if not KEY:
        sys.exit("DEEPSEEK_API_KEY 未设置")
    short = json.loads(SHORTLIST.read_text())

    # 断点续跑:读已有
    done = set()
    if OUT.exists():
        for line in OUT.read_text(encoding="utf-8").splitlines():
            try:
                rec = json.loads(line)
                done.add(f"{rec['category']}::{rec['name']}")
            except Exception:
                pass

    # 队列
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
