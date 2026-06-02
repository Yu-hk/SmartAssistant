"""
SmartAssistant 微调模型快速测试脚本
====================================
对微调后的 LoRA 模型进行快速的输入输出测试，
对比微调前后的回复质量差异。

使用:
  # 测试微调后的模型
  python test_model.py --model ./output/smart-assistant-lora/checkpoint-3
  
  # 对比微调前
  python test_model.py --model qwen2.5:7b --baseline
  
  # 从测试文件批量测试
  python test_model.py --model ./output/checkpoint-3 --test-file ./test_cases.jsonl
"""

import json
import sys
import argparse
import logging
from pathlib import Path

import torch
from transformers import AutoModelForCausalLM, AutoTokenizer
from peft import PeftModel

logging.basicConfig(level=logging.INFO, format="%(message)s")
logger = logging.getLogger(__name__)


HF_MODEL_MAP = {
    "qwen2.5:7b": "Qwen/Qwen2.5-7B-Instruct",
    "deepseek-r1:7b": "deepseek-ai/DeepSeek-R1-Distill-Qwen-7B",
}


def resolve_hf_name(name: str) -> str:
    return HF_MODEL_MAP.get(name, name)


# 默认测试用例
DEFAULT_TEST_CASES = [
    {
        "system": "你是 SmartAssistant 的旅行规划助手，负责推荐景点和规划行程。",
        "user": "北京三日游怎么安排",
        "expected": "包含故宫、长城等经典景点"
    },
    {
        "system": "你是 SmartAssistant 的旅行规划助手。",
        "user": "上海有什么好吃的",
        "expected": "推荐本帮菜和小吃"
    },
    {
        "system": "你是一位专业的产品推荐助手。",
        "user": "请用表格形式对比 iPhone 15 和 iPhone 16 的配置",
        "expected": "表格形式"
    },
]


def load_test_cases(path: str = None) -> list:
    """从 JSONL 加载测试用例"""
    if path is None:
        return DEFAULT_TEST_CASES
    cases = []
    with open(path, "r", encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if line:
                cases.append(json.loads(line))
    return cases if cases else DEFAULT_TEST_CASES


def load_model(model_path: str, base_model: str = None, device: str = "auto"):
    """
    加载模型。支持：
      - 本地 LoRA 目录：自动从 base_model 加载基础权重
      - HuggingFace 模型名：直接加载
    """
    is_lora = (Path(model_path) / "adapter_config.json").exists()

    if is_lora:
        # LoRA 模式：先加载基础模型
        base = base_model or "deepseek-r1:7b"
        hf_base = resolve_hf_name(base)
        logger.info(f"[LoRA 模式] 基础模型: {hf_base}, LoRA: {model_path}")

        base_model = AutoModelForCausalLM.from_pretrained(
            hf_base,
            torch_dtype=torch.float16,
            device_map=device,
            trust_remote_code=True,
        )
        model = PeftModel.from_pretrained(base_model, model_path)
        logger.info("  LoRA 适配器加载完成")
    else:
        # 直接加载模型
        model_name = resolve_hf_name(model_path)
        logger.info(f"[直接模式] 模型: {model_name}")
        model = AutoModelForCausalLM.from_pretrained(
            model_name,
            torch_dtype=torch.float16,
            device_map=device,
            trust_remote_code=True,
        )

    tokenizer = AutoTokenizer.from_pretrained(
        resolve_hf_name(base_model or model_path), trust_remote_code=True
    )
    if tokenizer.pad_token is None:
        tokenizer.pad_token = tokenizer.eos_token

    return model, tokenizer


def generate(
    model,
    tokenizer,
    system_prompt: str,
    user_message: str,
    max_new_tokens: int = 512,
    temperature: float = 0.7,
) -> str:
    """生成回复"""
    prompt = f"<|im_start|>system\n{system_prompt}<|im_end|>\n<|im_start|>user\n{user_message}<|im_end|>\n<|im_start|>assistant\n"

    inputs = tokenizer(prompt, return_tensors="pt", truncation=True, max_length=2048)
    inputs = {k: v.to(model.device) for k, v in inputs.items()}

    with torch.no_grad():
        outputs = model.generate(
            **inputs,
            max_new_tokens=max_new_tokens,
            temperature=temperature,
            do_sample=temperature > 0,
            top_p=0.9,
            pad_token_id=tokenizer.pad_token_id,
            eos_token_id=tokenizer.eos_token_id,
        )

    full_output = tokenizer.decode(outputs[0], skip_special_tokens=False)
    # 提取 assistant 之后的内容
    assistant_marker = "<|im_start|>assistant\n"
    idx = full_output.rfind(assistant_marker)
    if idx >= 0:
        reply = full_output[idx + len(assistant_marker):]
        # 去掉结束标记
        reply = reply.split("<|im_end|>")[0].strip()
    else:
        reply = full_output[len(prompt):].strip()

    return reply


def run_tests(model, tokenizer, test_cases: list):
    """运行测试用例并输出结果"""
    passed = 0
    failed = 0

    print("\n" + "=" * 70)
    print("微调效果测试结果")
    print("=" * 70)

    for i, case in enumerate(test_cases, 1):
        system_prompt = case.get("system", "")
        user_message = case.get("user", "")
        expected = case.get("expected", "")

        print(f"\n[{i}] 用户: {user_message}")
        print(f"    期望: {expected}")

        try:
            reply = generate(model, tokenizer, system_prompt, user_message)
            print(f"    输出: {reply[:200]}...")
            # 简单的关键字检查
            if expected:
                found = any(kw in reply for kw in expected.split("、") if kw.strip())
                if found:
                    print(f"    ✅ 期望关键词已覆盖")
                    passed += 1
                else:
                    print(f"    ⚠️ 未检测到期望关键词")
                    failed += 1
        except Exception as e:
            print(f"    ❌ 生成失败: {e}")
            failed += 1

    print("\n" + "=" * 70)
    print(f"结果: {passed}/{passed + failed} 通过")
    print("=" * 70)

    return passed, failed


def interactive_mode(model, tokenizer):
    """交互式测试模式"""
    print("\n" + "=" * 70)
    print("交互式测试模式（输入 'exit' 退出）")
    print("=" * 70)

    system_prompt = input("\nSystem Prompt (直接回车使用默认): ").strip()
    if not system_prompt:
        system_prompt = "你是 SmartAssistant 的智能助手。"

    while True:
        user_input = input("\n>>> ")
        if user_input.lower() in ("exit", "quit", "q"):
            break

        reply = generate(model, tokenizer, system_prompt, user_input)
        print(f"\n{reply}")


def main():
    parser = argparse.ArgumentParser(description="SmartAssistant 微调效果测试")
    parser.add_argument("--model", type=str, default="./output/checkpoint-3",
                        help="模型路径（LoRA 目录或 HuggingFace 模型名）")
    parser.add_argument("--base-model", type=str, default="deepseek-r1:7b",
                        help="LoRA 模式下的基础模型")
    parser.add_argument("--test-file", type=str, default=None,
                        help="测试用例 JSONL 文件")
    parser.add_argument("--interactive", action="store_true",
                        help="交互式测试模式")
    parser.add_argument("--baseline", action="store_true",
                        help="对比基础模型（测试微调前的表现）")
    parser.add_argument("--temperature", type=float, default=0.7,
                        help="生成温度")
    parser.add_argument("--max-tokens", type=int, default=512,
                        help="最大生成 token 数")

    args = parser.parse_args()

    if args.baseline:
        # 测试基础模型（微调前）
        logger.info(f"加载基础模型（微调前）: {args.base_model}")
        model, tokenizer = load_model(args.base_model)
        test_cases = load_test_cases(args.test_file)
        print("\n【微调前效果】")
        run_tests(model, tokenizer, test_cases)
    else:
        # 测试微调后的模型
        logger.info(f"加载模型: {args.model}")
        model, tokenizer = load_model(args.model, args.base_model)

        test_cases = load_test_cases(args.test_file)

        if args.interactive:
            interactive_mode(model, tokenizer)
        else:
            run_tests(model, tokenizer, test_cases)

    # 清理显存
    del model
    if torch.cuda.is_available():
        torch.cuda.empty_cache()
    print("\n[DONE]")


if __name__ == "__main__":
    main()
