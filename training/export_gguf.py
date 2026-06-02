"""
SmartAssistant LoRA → GGUF 导出脚本
=====================================
将微调后的 LoRA 权重合并回基础模型，导出为 Ollama 可加载的 GGUF 格式。

流程:
  1. 加载基础模型（原始权重）
  2. 加载 LoRA 适配器（微调得到的增量权重）
  3. 合并权重（merge_and_unload）
  4. 转换为 GGUF 格式（通过 llama.cpp）
  5. 创建 Ollama 模型

使用:
  python export_gguf.py \
      --base_model deepseek-r1:7b \
      --lora_dir ./output/checkpoint-3 \
      --output_dir ./merged_model \
      --gguf_output ./smart-travel-7b.gguf

依赖:
  需要安装 llama.cpp:
    git clone https://github.com/ggerganov/llama.cpp
    cd llama.cpp && make -j
  或 Windows 预编译包: https://github.com/ggerganov/llama.cpp/releases
"""

import os
import sys
import argparse
import subprocess
import logging
from pathlib import Path

import torch
from transformers import AutoModelForCausalLM, AutoTokenizer
from peft import PeftModel


logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s [%(levelname)s] %(message)s",
)
logger = logging.getLogger(__name__)


# HuggingFace 模型名映射
HF_MODEL_MAP = {
    "qwen2.5:7b": "Qwen/Qwen2.5-7B-Instruct",
    "deepseek-r1:7b": "deepseek-ai/DeepSeek-R1-Distill-Qwen-7B",
}


def resolve_hf_name(model_name: str) -> str:
    """将简写模型名转换为 HuggingFace 完整名称"""
    return HF_MODEL_MAP.get(model_name, model_name)


def merge_lora_weights(
    base_model_name: str,
    lora_dir: str,
    output_dir: str,
    device: str = "auto",
):
    """
    将 LoRA 权重合并回基础模型。

    参数:
        base_model_name: 基础模型名称或路径
        lora_dir: LoRA 适配器目录
        output_dir: 合并后模型输出目录
        device: 设备（auto/cpu/cuda）
    """
    hf_name = resolve_hf_name(base_model_name)

    logger.info(f"[1/4] 加载基础模型: {hf_name}")
    # 使用 float16 加载以节省显存
    base_model = AutoModelForCausalLM.from_pretrained(
        hf_name,
        torch_dtype=torch.float16,
        device_map=device,
        trust_remote_code=True,
    )

    logger.info(f"[2/4] 加载 LoRA 适配器: {lora_dir}")
    model = PeftModel.from_pretrained(base_model, lora_dir)

    logger.info("[3/4] 合并权重...")
    merged_model = model.merge_and_unload()
    
    logger.info(f"[4/4] 保存合并后模型到: {output_dir}")
    merged_model.save_pretrained(output_dir)
    
    tokenizer = AutoTokenizer.from_pretrained(hf_name, trust_remote_code=True)
    tokenizer.save_pretrained(output_dir)

    # 清理显存
    del base_model, model, merged_model
    if torch.cuda.is_available():
        torch.cuda.empty_cache()

    logger.info(f"[OK] 合并完成，模型已保存到: {output_dir}")
    return output_dir


def convert_to_gguf(
    hf_model_dir: str,
    llama_cpp_dir: str,
    gguf_output: str,
    quantize: str = "q4_k_m",
):
    """
    将 HuggingFace 格式模型转换为 GGUF 格式。

    使用 llama.cpp 的 convert-hf-to-gguf.py 脚本。

    参数:
        hf_model_dir: HuggingFace 模型目录
        llama_cpp_dir: llama.cpp 仓库根目录
        gguf_output: 输出 GGUF 文件路径
        quantize: 量化类型 (q4_k_m / q5_k_m / q8_0 / f16)
    """
    convert_script = os.path.join(llama_cpp_dir, "convert-hf-to-gguf.py")
    quantize_bin = os.path.join(llama_cpp_dir, "quantize")

    if not os.path.exists(convert_script):
        logger.error(f"找不到转换脚本: {convert_script}")
        logger.error("请确保已克隆 llama.cpp: git clone https://github.com/ggerganov/llama.cpp")
        return None

    # Step 1: 转换为 GGUF
    logger.info(f"[1/2] 转换 HF → GGUF...")
    f16_output = gguf_output.replace(".gguf", f"-f16.gguf")

    cmd = [
        sys.executable, convert_script,
        hf_model_dir,
        "--outfile", f16_output,
        "--outtype", "f16",
    ]
    logger.info(f"  执行: {' '.join(cmd)}")
    result = subprocess.run(cmd, capture_output=True, text=True)

    if result.returncode != 0:
        logger.error(f"转换失败:\n{result.stderr}")
        return None

    if not os.path.exists(f16_output):
        logger.error(f"未找到临时 GGUF 文件: {f16_output}")
        return None

    # Step 2: 量化（可选）
    if quantize and quantize.lower() != "none":
        logger.info(f"[2/2] 量化 GGUF ({quantize})...")
        quant_cmd = [quantize_bin, f16_output, gguf_output, quantize]
        logger.info(f"  执行: {' '.join(quant_cmd)}")

        if not os.path.exists(quantize_bin):
            logger.warning(f"量化工具不存在: {quantize_bin}")
            logger.info(f"将使用无量化的 F16 GGUF: {f16_output}")
            os.rename(f16_output, gguf_output)
        else:
            result = subprocess.run(quant_cmd, capture_output=True, text=True)
            if result.returncode != 0:
                logger.error(f"量化失败:\n{result.stderr}")
                return None
            # 删除临时 F16 文件
            os.remove(f16_output)
    else:
        os.rename(f16_output, gguf_output)

    logger.info(f"[OK] GGUF 导出完成: {gguf_output}")
    return gguf_output


def create_ollama_model(gguf_path: str, model_name: str, modelfile_content: str = None):
    """
    通过 Modelfile 创建 Ollama 模型。

    参数:
        gguf_path: GGUF 文件路径
        model_name: Ollama 模型名称（如 smart-travel）
        modelfile_content: 自定义 Modelfile 内容（可选）
    """
    logger.info(f"创建 Ollama 模型: {model_name}")

    # 生成 Modelfile
    if modelfile_content is None:
        modelfile_content = f"""FROM {gguf_path}

TEMPLATE \"\"\"<|im_start|>system
{{{{ .System }}}}<|im_end|>
<|im_start|>user
{{{{ .Prompt }}}}<|im_end|>
<|im_start|>assistant
{{{{ .Response }}}}<|im_end|>
\"\"\"

PARAMETER temperature 0.7
PARAMETER top_p 0.9
PARAMETER stop "<|im_end|>"
"""

    modelfile_path = os.path.join(os.path.dirname(gguf_path), "Modelfile")
    with open(modelfile_path, "w", encoding="utf-8") as f:
        f.write(modelfile_content)

    logger.info(f"Modelfile 已保存: {modelfile_path}")

    # 调用 ollama create
    cmd = ["ollama", "create", model_name, "-f", modelfile_path]
    logger.info(f"  执行: {' '.join(cmd)}")

    result = subprocess.run(cmd, capture_output=True, text=True)
    if result.returncode != 0:
        logger.error(f"Ollama 创建失败:\n{result.stderr}")
        return False

    logger.info(f"[OK] Ollama 模型 '{model_name}' 创建成功")
    logger.info(f"现在可以在 application.yml 中设置: spring.ai.ollama.chat.options.model: {model_name}")
    return True


def main():
    parser = argparse.ArgumentParser(description="SmartAssistant LoRA → GGUF 导出")
    parser.add_argument("--base-model", type=str, default="deepseek-r1:7b",
                        help="基础模型名称")
    parser.add_argument("--lora-dir", type=str, default="./output/checkpoint-3",
                        help="LoRA 适配器目录")
    parser.add_argument("--output-dir", type=str, default="./merged_model",
                        help="合并后模型输出目录")
    parser.add_argument("--gguf-output", type=str, default="./smart-custom-7b.gguf",
                        help="GGUF 输出文件路径")
    parser.add_argument("--ollama-name", type=str, default="smart-custom",
                        help="Ollama 模型名称")
    parser.add_argument("--llama-cpp-dir", type=str, default="../llama.cpp",
                        help="llama.cpp 目录路径")
    parser.add_argument("--quantize", type=str, default="q4_k_m",
                        help="量化类型 (q4_k_m / q5_k_m / q8_0 / f16 / none)")
    parser.add_argument("--skip-merge", action="store_true",
                        help="跳过合并（直接使用已合并的目录）")
    parser.add_argument("--skip-ollama", action="store_true",
                        help="跳过 Ollama 创建")

    args = parser.parse_args()

    if not args.skip_merge:
        # 合并 LoRA 权重
        merge_lora_weights(
            base_model_name=args.base_model,
            lora_dir=args.lora_dir,
            output_dir=args.output_dir,
        )
    else:
        logger.info("跳过合并步骤，直接使用: %s", args.output_dir)

    # 转换为 GGUF
    gguf_path = convert_to_gguf(
        hf_model_dir=args.output_dir,
        llama_cpp_dir=args.llama_cpp_dir,
        gguf_output=args.gguf_output,
        quantize=args.quantize,
    )

    if gguf_path and not args.skip_ollama:
        # 创建 Ollama 模型
        create_ollama_model(gguf_path, args.ollama_name)

    logger.info("\n[DONE] 全部完成!")
    if gguf_path:
        logger.info(f"  GGUF: {gguf_path}")
    if not args.skip_ollama:
        logger.info(f"  Ollama 模型: {args.ollama_name}")
    logger.info("  修改 application.yml:")
    logger.info(f"    spring.ai.ollama.chat.options.model: {args.ollama_name}")


if __name__ == "__main__":
    main()
