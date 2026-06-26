"""
bge-reranker-v2-m3 ONNX 模型下载与导出脚本。

用法：
    python scripts/download_reranker.py [--mirror]

前置依赖：
    pip install torch transformers onnx onnxruntime
"""

import argparse
import json
import os
import sys
from pathlib import Path

MODEL_NAME = "BAAI/bge-reranker-v2-m3"
OUTPUT_DIR = Path("models")
ONNX_PATH = OUTPUT_DIR / "bge-reranker-v2-m3.onnx"


def main():
    parser = argparse.ArgumentParser(description="下载并导出 bge-reranker ONNX 模型")
    parser.add_argument("--mirror", "-m", action="store_true",
                        help="使用 hf-mirror.com 国内镜像")
    args = parser.parse_args()

    if args.mirror:
        os.environ["HF_ENDPOINT"] = "https://hf-mirror.com"
        print(f"[INFO] 已切换镜像源: hf-mirror.com")

    OUTPUT_DIR.mkdir(parents=True, exist_ok=True)

    if ONNX_PATH.exists():
        file_size = ONNX_PATH.stat().st_size / (1024**3)
        print(f"[SKIP] ONNX 模型已存在: {ONNX_PATH} ({file_size:.1f} GB)")
        return

    from transformers import AutoModelForSequenceClassification, AutoTokenizer
    import torch

    print(f"[INFO] 正在加载模型: {MODEL_NAME}")
    tokenizer = AutoTokenizer.from_pretrained(MODEL_NAME)
    model = AutoModelForSequenceClassification.from_pretrained(MODEL_NAME)
    model.eval()

    # 保存 tokenizer.json（使用独立文件名，不与 BGE 嵌入模型的 tokenizer 冲突）
    tokenizer_path = OUTPUT_DIR / "bge-reranker-tokenizer.json"
    print(f"[INFO] 正在保存 tokenizer: {tokenizer_path} ...")
    vocab = tokenizer.get_vocab()
    with open(tokenizer_path, "w", encoding="utf-8") as f:
        json.dump(vocab, f, ensure_ascii=False)
    print(f"[INFO] Tokenizer 已保存: {tokenizer_path} ({len(vocab)} 词条)")

    # 导出 ONNX
    print(f"[INFO] 正在导出为 ONNX (约需 5-15 分钟)...")
    dummy = tokenizer(
        ["查询文本 [SEP] 文档内容"],
        return_tensors="pt", padding="max_length",
        max_length=512, truncation=True,
    )

    # 使用 PyTorch 2.12 的 torch.onnx.export() 新 API
    # 固定 batch=1, seq=512，无需动态轴
    torch.onnx.export(
        model,
        (dummy["input_ids"], dummy["attention_mask"]),
        str(ONNX_PATH),
        input_names=["input_ids", "attention_mask"],
        output_names=["logits"],
        opset_version=17,
    )

    file_size = ONNX_PATH.stat().st_size / (1024**3)
    print(f"[DONE] ONNX 模型已导出: {ONNX_PATH} ({file_size:.1f} GB)")
    print(f"[INFO] 配置: reranker.model.path=models/bge-reranker-v2-m3.onnx")


if __name__ == "__main__":
    main()
