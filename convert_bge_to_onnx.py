"""
将 BGE 模型转换为 ONNX 格式
用法：
    python convert_bge_to_onnx.py --input models/bge-small-zh-v1.5 --output models/bge-small-zh-v1.5.onnx
"""
import argparse
import os
import sys
import torch
from transformers import AutoModel, AutoTokenizer

def convert_bge_to_onnx(input_dir, output_path):
    """将 BGE 模型转换为 ONNX 格式"""
    print(f"正在加载模型: {input_dir}")
    
    try:
        # 加载模型和分词器
        tokenizer = AutoTokenizer.from_pretrained(input_dir)
        model = AutoModel.from_pretrained(input_dir)
        model.eval()
        
        # 准备 dummy input
        dummy_text = ["这是一个测试句子"]
        inputs = tokenizer(dummy_text, return_tensors="pt", padding=True, truncation=True, max_length=512)
        
        # 创建输出目录
        os.makedirs(os.path.dirname(output_path) or '.', exist_ok=True)
        
        # 导出为 ONNX
        print(f"正在导出 ONNX 模型到: {output_path}")
        
        # 定义输入和输出名称（注意：Java BgeEmbeddingModel 会传入 3 个输入）
        input_names = ['input_ids', 'attention_mask', 'token_type_ids']
        output_names = ['last_hidden_state', 'pooler_output']
        
        # 动态轴（支持变长输入）
        dynamic_axes = {
            'input_ids': {0: 'batch_size', 1: 'sequence_length'},
            'attention_mask': {0: 'batch_size', 1: 'sequence_length'},
            'token_type_ids': {0: 'batch_size', 1: 'sequence_length'},
            'last_hidden_state': {0: 'batch_size', 1: 'sequence_length'},
            'pooler_output': {0: 'batch_size'}
        }
        
        # 导出（包含 token_type_ids，与 Java 代码兼容）
        with torch.no_grad():
            torch.onnx.export(
                model,
                (inputs['input_ids'], inputs['attention_mask'], inputs.get('token_type_ids', torch.zeros_like(inputs['input_ids']))),
                output_path,
                input_names=input_names,
                output_names=output_names,
                dynamic_axes=dynamic_axes,
                opset_version=14,
                do_constant_folding=True,
                verbose=True
            )
        
        print(f"✅ ONNX 模型已保存到: {output_path}")
        print(f"   文件大小: {os.path.getsize(output_path) / (1024*1024):.1f} MB")
        return True
        
    except Exception as e:
        print(f"❌ 转换失败: {e}")
        import traceback
        traceback.print_exc()
        return False

if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="转换 BGE 模型为 ONNX 格式")
    parser.add_argument("--input", required=True, help="输入模型目录")
    parser.add_argument("--output", required=True, help="输出 ONNX 文件路径")
    
    args = parser.parse_args()
    
    if not os.path.exists(args.input):
        print(f"❌ 输入目录不存在: {args.input}")
        sys.exit(1)
    
    success = convert_bge_to_onnx(args.input, args.output)
    sys.exit(0 if success else 1)