"""
bge-reranker-v2-m3 ONNX 模型下载与导出脚本。

用法：
    python scripts/download_reranker.py

输出：
    models/bge-reranker-v2-m3.onnx    — ONNX 模型文件（~1.1 GB）
    models/bge-reranker-v2-m3/        — 原始模型文件（缓存）
    models/tokenizer.json             — 复用已有 tokenizer

前置依赖：
    pip install torch transformers optimum onnx onnxruntime
"""

import os
import sys
from pathlib import Path

MODEL_NAME = "BAAI/bge-reranker-v2-m3"
OUTPUT_DIR = Path("models")
ONNX_PATH = OUTPUT_DIR / "bge-reranker-v2-m3.onnx"


def main():
    OUTPUT_DIR.mkdir(parents=True, exist_ok=True)

    if ONNX_PATH.exists():
        file_size = ONNX_PATH.stat().st_size / (1024**3)
        print(f"[SKIP] ONNX 模型已存在: {ONNX_PATH} ({file_size:.1f} GB)")
        print(f"如需重新下载，请删除该文件后重试。")
        return

    print(f"[INFO] 正在加载模型: {MODEL_NAME}")
    print(f"[INFO] 首次下载约 1.1 GB，请耐心等待...")

    from transformers import AutoModelForSequenceClassification, AutoTokenizer
    import torch

    # 加载模型和 tokenizer
    tokenizer = AutoTokenizer.from_pretrained(MODEL_NAME)
    model = AutoModelForSequenceClassification.from_pretrained(MODEL_NAME)

    # 导出为 ONNX
    print(f"[INFO] 正在导出为 ONNX...")
    from optimum.exporters import TasksManager
    from optimum.exporters.onnx import export

    # 使用 optimum 的 ONNX 导出
    model.config.return_dict = False
    
    # 模拟输入
    dummy_input = tokenizer(
        ["查询文本 [SEP] 文档内容"],
        return_tensors="pt",
        padding="max_length",
        max_length=512,
        truncation=True,
    )

    torch.onnx.export(
        model,
        tuple(dummy_input.values()),
        str(ONNX_PATH),
        input_names=["input_ids", "attention_mask", "token_type_ids"],
        output_names=["logits"],
        dynamic_axes={
            "input_ids": {0: "batch_size", 1: "sequence_length"},
            "attention_mask": {0: "batch_size", 1: "sequence_length"},
            "token_type_ids": {0: "batch_size", 1: "sequence_length"},
        },
        opset_version=17,
    )

    file_size = ONNX_PATH.stat().st_size / (1024**3)
    print(f"[DONE] ONNX 模型已导出: {ONNX_PATH} ({file_size:.1f} GB)")
    print(f"[INFO] 使用时配置: reranker.model.path=models/bge-reranker-v2-m3.onnx")


if __name__ == "__main__":
    main()
