"""
将 BGE 模型转换为 ONNX 格式
用法：
    python convert_bge_onnx.py --input models/bge-large-zh-v1.5 --output models/bge-large-zh-v1.5.onnx
"""
import argparse
import os
import sys

def convert_to_onnx(input_dir, output_path):
    """将 BGE 模型转换为 ONNX 格式"""
    try:
        from transformers import AutoModel, AutoTokenizer
        import torch
        
        print(f"正在加载模型: {input_dir}")
        tokenizer = AutoTokenizer.from_pretrained(input_dir)
        model = AutoModel.from_pretrained(input_dir)
        model.eval()
        
        # 准备 dummy input
        dummy_text = "这是一个测试句子"
        inputs = tokenizer(dummy_text, return_tensors="pt", padding=True, truncation=True, max_length=512)
        
        # 导出为 ONNX
        print(f"正在导出 ONNX 模型到: {output_path}")
        with torch.no_grad():
            torch.onnx.export(
                model,
                (inputs['input_ids'], inputs['attention_mask']),
                output_path,
                input_names=['input_ids', 'attention_mask'],
                output_names=['last_hidden_state', 'pooler_output'],
                dynamic_axes={
                    'input_ids': {0: 'batch_size', 1: 'sequence'},
                    'attention_mask': {0: 'batch_size', 1: 'sequence'},
                    'last_hidden_state': {0: 'batch_size', 1: 'sequence'},
                    'pooler_output': {0: 'batch_size'}
                },
                opset_version=14,
                do_constant_folding=True
            )
        
        print(f"✅ ONNX 模型已保存到: {output_path}")
        return True
        
    except Exception as e:
        print(f"❌ 转换失败: {e}")
        return False

if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="转换 BGE 模型为 ONNX 格式")
    parser.add_argument("--input", required=True, help="输入模型目录")
    parser.add_argument("--output", required=True, help="输出 ONNX 文件路径")
    
    args = parser.parse_args()
    
    if not os.path.exists(args.input):
        print(f"❌ 输入目录不存在: {args.input}")
        sys.exit(1)
    
    os.makedirs(os.path.dirname(args.output) or '.', exist_ok=True)
    
    success = convert_to_onnx(args.input, args.output)
    sys.exit(0 if success else 1)
