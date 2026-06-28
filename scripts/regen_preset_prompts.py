#!/usr/bin/env python3
"""
补齐 presets.json 中 prompt 为空的预设角色。
复刻后端 CharacterService.generatePromptByName 调用 DeepSeek 的完整路径:
  - system prompt 从 classpath prompts/character-prompt-generator.txt 加载
  - user message 模板与 Java 版一致
  - 失败兜底 "你是{name}。以深度和真实性表达自己的观点和性格,展现独特的个人魅力。"
  - temperature=0.7, model=deepseek-chat
"""
import json
import os
import sys
import time
import urllib.request
import urllib.error
from pathlib import Path

ROOT = Path('/Users/yangq/Codes/Idea-Party')
PRESETS = ROOT / 'server/src/main/resources/presets.json'
TEMPLATE = ROOT / 'server/src/main/resources/prompts/character-prompt-generator.txt'

API_KEY = os.environ.get('DEEPSEEK_API_KEY') or open('/tmp/deepseek_key').read().strip()
BASE_URL = os.environ.get('DEEPSEEK_BASE_URL', 'https://api.deepseek.com')
MODEL = 'deepseek-chat'
SYSTEM_PROMPT = TEMPLATE.read_text(encoding='utf-8')


def call_deepseek(character_name: str, description: str | None) -> str:
    """完全对齐 CharacterService.generatePromptWithAIFromNameAndDescription 的请求体。"""
    if description:
        user_msg = (
            f'请为以下角色创建一个角色提示词：\n\n角色名：{character_name}\n'
            f'用户补充描述：{description}\n\n立即生成角色提示词：'
        )
    else:
        user_msg = (
            f'请为以下角色创建一个角色提示词：{character_name}\n\n立即生成角色提示词：'
        )

    body = {
        'model': MODEL,
        'messages': [
            {'role': 'system', 'content': SYSTEM_PROMPT},
            {'role': 'user', 'content': user_msg},
        ],
        'temperature': 0.7,
    }
    req = urllib.request.Request(
        f'{BASE_URL}/chat/completions',
        data=json.dumps(body).encode('utf-8'),
        headers={
            'Content-Type': 'application/json',
            'Authorization': f'Bearer {API_KEY}',
        },
        method='POST',
    )
    with urllib.request.urlopen(req, timeout=60) as r:
        resp = json.loads(r.read().decode('utf-8'))
    return resp['choices'][0]['message']['content'].strip()


def fallback(character_name: str) -> str:
    return (
        f'你是{character_name}。以深度和真实性表达自己的观点和性格,'
        f'展现独特的个人魅力。'
    )


def main():
    data = json.loads(PRESETS.read_text(encoding='utf-8'))
    empty = [p for p in data if not (p.get('prompt') or '').strip()]
    print(f'空 prompt 角色数: {len(empty)}')

    if len(sys.argv) > 1 and sys.argv[1] == '--dry-run':
        for p in empty:
            print(f"  - {p.get('name')}")
        return

    success = 0
    failed = []
    for i, p in enumerate(empty):
        name = p.get('name')
        desc = p.get('description') or None
        print(f'[{i+1}/{len(empty)}] {name} ... ', end='', flush=True)
        try:
            new_prompt = call_deepseek(name, desc)
            # 校验: 与现有成功 prompt 长度对齐 (avg ~628)
            if len(new_prompt) < 200:
                print(f'⚠️  长度过短 ({len(new_prompt)}), 用兜底')
                new_prompt = fallback(name)
                failed.append((name, 'TOO_SHORT'))
            else:
                print(f'✅ {len(new_prompt)} chars')
            p['prompt'] = new_prompt
            success += 1
        except urllib.error.HTTPError as e:
            body = e.read().decode('utf-8', errors='replace')[:200]
            print(f'❌ HTTP {e.code}: {body}')
            failed.append((name, f'HTTP_{e.code}'))
            p['prompt'] = fallback(name)
        except Exception as e:
            print(f'❌ {type(e).__name__}: {e}')
            failed.append((name, str(e)[:50]))
            p['prompt'] = fallback(name)

        # 避免打满 DeepSeek RPM(默认 60),保守点 1.5s 间隔
        time.sleep(1.5)

    PRESETS.write_text(
        json.dumps(data, ensure_ascii=False, indent=2) + '\n',
        encoding='utf-8',
    )
    print(f'\n完成: {success}/{len(empty)} 成功')
    if failed:
        print(f'失败 {len(failed)} 个:')
        for n, why in failed:
            print(f'  - {n}: {why}')


if __name__ == '__main__':
    main()
