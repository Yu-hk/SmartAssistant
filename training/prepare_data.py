"""
SmartAssistant 微调数据准备脚本
=================================
将原始对话日志或人工标注数据转换为 JSONL 格式，
每条数据为 ChatML 格式的消息列表。

输入格式示例 (JSONL):
  {"system": "你是旅行规划助手", "user": "推荐北京景点", "assistant": "故宫、长城..."}

输出格式:
  {"messages": [
    {"role": "system", "content": "你是旅行规划助手"},
    {"role": "user", "content": "推荐北京景点"},
    {"role": "assistant", "content": "故宫、长城..."}
  ]}

支持:
  1. 从原始 JSONL 转换
  2. 使用 DeepSeek API 批量生成训练数据（需要 DEEPSEEK_API_KEY 环境变量）
  3. 数据去重和质量过滤
"""

import json
import os
import random
import argparse
from pathlib import Path


def load_jsonl(path: str) -> list:
    """加载 JSONL 文件"""
    data = []
    with open(path, "r", encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if line:
                data.append(json.loads(line))
    return data


def save_jsonl(data: list, path: str):
    """保存为 JSONL 文件"""
    with open(path, "w", encoding="utf-8") as f:
        for item in data:
            f.write(json.dumps(item, ensure_ascii=False) + "\n")
    print(f"[OK] 已保存 {len(data)} 条数据到 {path}")


def convert_to_chatml(
    raw_data: list,
    system_field: str = "system",
    user_field: str = "user",
    assistant_field: str = "assistant",
) -> list:
    """将原始格式转换为 ChatML 消息格式"""
    converted = []
    skipped = 0
    for item in raw_data:
        system = item.get(system_field, "")
        user = item.get(user_field, "")
        assistant = item.get(assistant_field, "")

        if not user or not assistant:
            skipped += 1
            continue

        messages = []
        if system:
            messages.append({"role": "system", "content": system})
        messages.append({"role": "user", "content": user})
        messages.append({"role": "assistant", "content": assistant})

        converted.append({"messages": messages})

    if skipped:
        print(f"[WARN] 跳过了 {skipped} 条不完整的数据")
    return converted


def split_dataset(
    data: list, train_ratio: float = 0.9, seed: int = 42
) -> tuple[list, list]:
    """按比例拆分训练集和验证集"""
    random.seed(seed)
    shuffled = data.copy()
    random.shuffle(shuffled)

    split_idx = int(len(shuffled) * train_ratio)
    train = shuffled[:split_idx]
    eval_ = shuffled[split_idx:]

    print(f"[OK] 拆分完成: 训练集 {len(train)} 条, 验证集 {len(eval_)} 条")
    return train, eval_


def generate_from_templates(
    templates: list[dict],
    output_path: str,
    num_variations: int = 3,
    api_key: str = None,
):
    """
    从模板批量生成训练数据。
    如果提供了 api_key，使用 DeepSeek API 扩展数据；
    否则仅使用模板本身。
    """
    if api_key:
        print("[INFO] DeepSeek API 扩展模式")
        data = _expand_with_api(templates, num_variations, api_key)
    else:
        print("[INFO] 直接使用模板（无 API 扩展）")
        data = convert_to_chatml(templates)

    save_jsonl(data, output_path)
    return data


def _expand_with_api(
    templates: list[dict], num_variations: int, api_key: str
) -> list:
    """使用 DeepSeek API 扩展模板生成更多变体"""
    import requests

    headers = {
        "Authorization": f"Bearer {api_key}",
        "Content-Type": "application/json",
    }

    all_data = convert_to_chatml(templates)
    # 拷贝模板本身
    result = list(all_data)

    for template in templates:
        prompt = f"""
请根据以下对话模板，生成 {num_variations} 个语义相似但表述不同的对话变体。
保持相同的意图和回复风格，但改变措辞和具体细节。

模板:
用户: {template.get('user', '')}
助手: {template.get('assistant', '')}
系统提示: {template.get('system', '')}

请以 JSON 数组格式返回，每个元素包含 system、user、assistant 三个字段。
"""

        payload = {
            "model": "deepseek-v4-flash",
            "messages": [{"role": "user", "content": prompt.strip()}],
            "temperature": 0.8,
            "max_tokens": 4096,
            "stream": False,
        }

        try:
            resp = requests.post(
                "https://api.deepseek.com/v1/chat/completions",
                headers=headers,
                json=payload,
                timeout=60,
            )
            if resp.status_code == 200:
                content = resp.json()["choices"][0]["message"]["content"]
                # 提取 JSON
                content = content.strip()
                if content.startswith("```json"):
                    content = content[7:]
                if content.startswith("```"):
                    content = content[3:]
                if content.endswith("```"):
                    content = content[:-3]
                content = content.strip()

                variations = json.loads(content)
                converted = convert_to_chatml(variations)
                result.extend(converted)
                print(f"[OK] 模板 '{template.get('user', '')[:20]}' → {len(converted)} 个变体")
            else:
                print(f"[WARN] API 请求失败: {resp.status_code}")
        except Exception as e:
            print(f"[ERROR] API 调用异常: {e}")

    return result


def quality_filter(data: list, min_user_len: int = 3, min_assistant_len: int = 10) -> list:
    """质量过滤：去除过短或质量明显偏低的数据"""
    filtered = []
    for item in data:
        messages = item.get("messages", [])
        user_msg = next((m for m in messages if m["role"] == "user"), None)
        assistant_msg = next((m for m in messages if m["role"] == "assistant"), None)

        if (
            user_msg
            and assistant_msg
            and len(user_msg["content"]) >= min_user_len
            and len(assistant_msg["content"]) >= min_assistant_len
        ):
            filtered.append(item)

    removed = len(data) - len(filtered)
    if removed:
        print(f"[INFO] 质量过滤移除了 {removed} 条低质量数据")
    return filtered


def deduplicate(data: list) -> list:
    """基于 user+assistant 内容去重"""
    seen = set()
    deduped = []
    for item in data:
        messages = item.get("messages", [])
        key = json.dumps(
            [(m["role"], m["content"]) for m in messages], ensure_ascii=False
        )
        if key not in seen:
            seen.add(key)
            deduped.append(item)

    removed = len(data) - len(deduped)
    if removed:
        print(f"[INFO] 去重移除了 {removed} 条重复数据")
    return deduped


def main():
    parser = argparse.ArgumentParser(description="SmartAssistant 微调数据准备")
    parser.add_argument("--input", type=str, help="输入 JSONL 文件路径")
    parser.add_argument("--output", type=str, default="./data_processed.jsonl", help="输出文件路径")
    parser.add_argument("--train-output", type=str, default="./train.jsonl", help="训练集输出")
    parser.add_argument("--eval-output", type=str, default="./eval.jsonl", help="验证集输出")
    parser.add_argument("--train-ratio", type=float, default=0.9, help="训练集比例")
    parser.add_argument("--split", action="store_true", help="是否拆分为训练/验证集")
    parser.add_argument("--generate", action="store_true", help="从模板生成数据")
    parser.add_argument("--templates", type=str, help="模板 JSONL 文件路径")
    parser.add_argument("--variations", type=int, default=3, help="每个模板生成的变体数")
    parser.add_argument("--no-filter", action="store_true", help="跳过质量过滤")
    parser.add_argument("--no-dedup", action="store_true", help="跳过去重")

    args = parser.parse_args()

    # 从模板生成
    if args.generate:
        if not args.templates:
            print("[ERROR] --generate 模式需要 --templates 参数")
            return
        templates = load_jsonl(args.templates)
        api_key = os.environ.get("DEEPSEEK_API_KEY")
        data = generate_from_templates(templates, args.output, args.variations, api_key)
    elif args.input:
        raw = load_jsonl(args.input)
        print(f"[INFO] 加载了 {len(raw)} 条原始数据")
        data = convert_to_chatml(raw)
    else:
        print("[ERROR] 请指定 --input 或 --generate 模式")
        return

    # 质量过滤
    if not args.no_filter:
        data = quality_filter(data)

    # 去重
    if not args.no_dedup:
        data = deduplicate(data)

    # 保存
    if args.split and len(data) >= 10:
        train, eval_ = split_dataset(data, args.train_ratio)
        save_jsonl(train, args.train_output)
        save_jsonl(eval_, args.eval_output)
    else:
        save_jsonl(data, args.output)

    print(f"\n[DONE] 共 {len(data)} 条就绪数据")


if __name__ == "__main__":
    main()
