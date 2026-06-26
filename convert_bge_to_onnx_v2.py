"""
将 BGE 模型转换为 ONNX 格式
用法: python convert_bge_to_onnx.py --model D:/workspace/SmartAssistant/models/bge-large-zh-v1.5 --output D:/workspace/SmartAssistant/models/bge-large-zh-v1.5.onnx
"""
import argparse
import torch
from pathlib import Path
from transformers import AutoModel, AutoTokenizer

def convert(model_path: str, output_path: str, opset: int = 17):
    print(f"[转换] 加载模型: {model_path}")
    tokenizer = AutoTokenizer.from_pretrained(model_path)
    model = AutoModel.from_pretrained(model_path, add_pooling_layer=False)
    model.eval()

    # 用 tokenizer 构造 dummy input（与 BgeEmbeddingModel.java 的 MAX_LEN=128 对齐）
    dummy_text = "这是一个测试句子，用于导出 ONNX 模型。"
    inputs = tokenizer(dummy_text, return_tensors="pt", max_length=128, padding="max_length", truncation=True)
    # 添加 token_type_ids（BGE 需要）
    seq_len = inputs["input_ids"].shape[1]
    token_type_ids = torch.zeros((1, seq_len), dtype=torch.long)
    inputs["token_type_ids"] = token_type_ids

    print(f"[转换] 输入形状: input_ids={list(inputs['input_ids'].shape)}")

    # 导出 ONNX
    Path(output_path).parent.mkdir(parents=True, exist_ok=True)
    with torch.no_grad():
        torch.onnx.export(
            model,
            (inputs["input_ids"], inputs["attention_mask"], inputs["token_type_ids"]),
            output_path,
            input_names=["input_ids", "attention_mask", "token_type_ids"],
            output_names=["last_hidden_state"],
            dynamic_axes={
                "input_ids": {0: "batch", 1: "seq"},
                "attention_mask": {0: "batch", 1: "seq"},
                "token_type_ids": {0: "batch", 1: "seq"},
                "last_hidden_state": {0: "batch", 1: "seq"},
            },
            opset_version=opset,
            do_constant_folding=True,
        )

    print(f"[转换] ✅ ONNX 模型已保存: {output_path}")
    print(f"[转换]    维度: {model.config.hidden_size}")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="BGE 模型 ONNX 转换")
    parser.add_argument("--model", required=True, help="PyTorch 模型目录")
    parser.add_argument("--output", required=True, help="输出 ONNX 文件路径")
    parser.add_argument("--opset", type=int, default=17, help="ONNX opset 版本")
    args = parser.parse_args()
    convert(args.model, args.output, args.opset)
