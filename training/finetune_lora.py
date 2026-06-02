"""
SmartAssistant QLoRA 微调主脚本
=================================
对本地 7B 模型（deepseek-r1:7b / qwen2.5:7b）进行参数高效微调，
适配企业内部特定领域需求。

功能:
  - 4bit QLoRA 量化微调（16GB 显存可跑 7B 模型）
  - 训练/验证集拆分与 loss 监控
  - Checkpoint 自动保存与恢复
  - TensorBoard + W&B 日志（可选）

使用:
  python finetune_lora.py \
      --train_file ./sample_data/travel_train.jsonl \
      --eval_file ./sample_data/travel_eval.jsonl \
      --model_name deepseek-r1:7b \
      --output_dir ./output

依赖:
  pip install -r requirements.txt
  GPU 推荐 16GB+ 显存（RTX 4060 Ti 或更高）
"""

import json
import math
import os
import sys
import argparse
import logging
from dataclasses import dataclass, field
from typing import Optional

import torch
import transformers
from datasets import Dataset
from transformers import (
    AutoModelForCausalLM,
    AutoTokenizer,
    BitsAndBytesConfig,
    HfArgumentParser,
    Trainer,
    TrainingArguments,
    DataCollatorForSeq2Seq,
)
from peft import LoraConfig, get_peft_model, prepare_model_for_kbit_training, PeftModel


logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s [%(levelname)s] %(message)s",
)
logger = logging.getLogger(__name__)


# ============================================================
# 配置参数
# ============================================================

@dataclass
class ModelArguments:
    """模型相关参数"""
    model_name: str = field(
        default="deepseek-r1:7b",
        metadata={"help": "基础模型名称（HuggingFace 格式或本地路径）"}
    )
    load_in_4bit: bool = field(
        default=True,
        metadata={"help": "是否使用 4bit 量化（QLoRA）"}
    )
    bnb_4bit_quant_type: str = field(
        default="nf4",
        metadata={"help": "4bit 量化类型: nf4 或 fp4"}
    )
    bnb_4bit_compute_dtype: str = field(
        default="float16",
        metadata={"help": "计算精度: float16 / bfloat16 / float32"}
    )
    use_flash_attention: bool = field(
        default=False,
        metadata={"help": "是否使用 Flash Attention 2（需 GPU 支持）"}
    )


@dataclass
class LoraArguments:
    """LoRA 相关参数"""
    lora_r: int = field(
        default=16,
        metadata={"help": "LoRA 秩（rank），8-64 之间，越大容量越高"}
    )
    lora_alpha: int = field(
        default=32,
        metadata={"help": "LoRA 缩放系数，通常为 r 的 2 倍"}
    )
    lora_dropout: float = field(
        default=0.05,
        metadata={"help": "LoRA dropout 比例，防过拟合"}
    )
    lora_target_modules: str = field(
        default="q_proj,k_proj,v_proj,o_proj",
        metadata={"help": "LoRA 目标模块，逗号分隔"}
    )
    lora_bias: str = field(
        default="none",
        metadata={"help": "bias 处理方式: none / all / lora_only"}
    )


@dataclass
class DataArguments:
    """数据相关参数"""
    train_file: str = field(
        default="./sample_data/travel_train.jsonl",
        metadata={"help": "训练集 JSONL 文件路径"}
    )
    eval_file: Optional[str] = field(
        default=None,
        metadata={"help": "验证集 JSONL 文件路径（可选）"}
    )
    max_seq_length: int = field(
        default=2048,
        metadata={"help": "最大序列长度（token），根据显存调整"}
    )
    preprocessing_num_workers: int = field(
        default=4,
        metadata={"help": "预处理线程数"}
    )
    resume_from_checkpoint: Optional[str] = field(
        default=None,
        metadata={"help": "从指定 checkpoint 恢复训练，如 ./output/smart-assistant-lora/checkpoint-1"}
    )


# ============================================================
# 数据加载与预处理
# ============================================================

def load_jsonl_dataset(file_path: str) -> Dataset:
    """加载 JSONL 格式数据集"""
    data = []
    with open(file_path, "r", encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if line:
                data.append(json.loads(line))
    logger.info(f"加载了 {len(data)} 条数据: {file_path}")
    return Dataset.from_list(data)


def format_chatml(example: dict) -> str:
    """
    将 ChatML 格式转换为模型输入文本。
    格式:
      <|im_start|>system
      {system_content}<|im_end|>
      <|im_start|>user
      {user_content}<|im_end|>
      <|im_start|>assistant
      {assistant_content}<|im_end|>
    """
    messages = example.get("messages", [])
    if not messages:
        return ""

    parts = []
    for msg in messages:
        role = msg.get("role", "user")
        content = msg.get("content", "")
        parts.append(f"<|im_start|>{role}\n{content}<|im_end|>")

    # 最后追加 assistant 的起始标记，用于训练时在此处生成损失
    if messages[-1]["role"] == "assistant":
        pass  # 最后一条已经是 assistant，不需要额外标记

    return "\n".join(parts)


def preprocess_dataset(
    dataset: Dataset,
    tokenizer: AutoTokenizer,
    max_seq_length: int,
    num_workers: int = 4,
) -> Dataset:
    """预处理数据集：文本格式化 + Tokenize"""

    def tokenize_function(examples):
        texts = []
        for messages in examples["messages"]:
            # 构建完整的对话文本
            parts = []
            for msg in messages:
                role = msg.get("role", "user")
                content = msg.get("content", "")
                parts.append(f"<|im_start|>{role}\n{content}<|im_end|>")
            parts.append("<|im_start|>assistant\n")  # 让模型学习续写
            texts.append("\n".join(parts))

        tokenized = tokenizer(
            texts,
            truncation=True,
            max_length=max_seq_length,
            padding=False,
            return_tensors=None,
        )
        return tokenized

    return dataset.map(
        tokenize_function,
        batched=True,
        num_proc=num_workers,
        remove_columns=dataset.column_names,
        desc="Tokenizing",
    )


# ============================================================
# 模型加载
# ============================================================

def create_quantized_model(model_args: ModelArguments):
    """创建 4bit 量化模型"""
    compute_dtype = (
        torch.bfloat16
        if model_args.bnb_4bit_compute_dtype == "bfloat16"
        else torch.float16
    )

    bnb_config = BitsAndBytesConfig(
        load_in_4bit=model_args.load_in_4bit,
        bnb_4bit_quant_type=model_args.bnb_4bit_quant_type,
        bnb_4bit_compute_dtype=compute_dtype,
        bnb_4bit_use_double_quant=True,
    )

    # 从 HuggingFace 或本地加载
    model_name = model_args.model_name
    # 如果是 ollama 格式如 "deepseek-r1:7b"，转换为 HuggingFace 模型名
    hf_name = {
        "qwen2.5:7b": "Qwen/Qwen2.5-7B-Instruct",
        "deepseek-r1:7b": "deepseek-ai/DeepSeek-R1-Distill-Qwen-7B",
    }.get(model_name, model_name)

    logger.info(f"正在加载模型: {hf_name}")

    model = AutoModelForCausalLM.from_pretrained(
        hf_name,
        quantization_config=bnb_config,
        device_map="auto",
        trust_remote_code=True,
        use_flash_attention_2=model_args.use_flash_attention,
        torch_dtype=compute_dtype,
    )

    model = prepare_model_for_kbit_training(model)
    model.config.use_cache = False  # 训练时禁用 KV 缓存

    logger.info(f"模型加载完成，参数量: {model.num_parameters():,}")
    return model


def create_lora_model(model, lora_args: LoraArguments):
    """在模型上应用 LoRA"""
    target_modules = [
        m.strip() for m in lora_args.lora_target_modules.split(",")
    ]

    lora_config = LoraConfig(
        r=lora_args.lora_r,
        lora_alpha=lora_args.lora_alpha,
        target_modules=target_modules,
        lora_dropout=lora_args.lora_dropout,
        bias=lora_args.lora_bias,
        task_type="CAUSAL_LM",
    )

    model = get_peft_model(model, lora_config)

    trainable_params = model.num_parameters(only_trainable=True)
    total_params = model.num_parameters()
    logger.info(
        f"LoRA 配置完成: 可训练参数 {trainable_params:,} "
        f"({100 * trainable_params / total_params:.4f}%)"
    )
    logger.info(f"LoRA 目标模块: {target_modules}")

    return model


# ============================================================
# 主流程
# ============================================================

def main():
    parser = HfArgumentParser(
        (ModelArguments, LoraArguments, DataArguments, TrainingArguments)
    )

    # 如果命令行参数通过 JSON 文件传入
    if len(sys.argv) > 1 and sys.argv[1].endswith(".json"):
        model_args, lora_args, data_args, training_args = parser.parse_json_file(
            json_file=sys.argv[1]
        )
    else:
        model_args, lora_args, data_args, training_args = parser.parse_args_into_dataclasses()

    # ---------- 1. 加载 Tokenizer ----------
    hf_name = {
        "qwen2.5:7b": "Qwen/Qwen2.5-7B-Instruct",
        "deepseek-r1:7b": "deepseek-ai/DeepSeek-R1-Distill-Qwen-7B",
    }.get(model_args.model_name, model_args.model_name)

    tokenizer = AutoTokenizer.from_pretrained(hf_name, trust_remote_code=True)
    if tokenizer.pad_token is None:
        tokenizer.pad_token = tokenizer.eos_token

    # ---------- 2. 加载数据 ----------
    logger.info(f"加载训练数据: {data_args.train_file}")
    train_dataset = load_jsonl_dataset(data_args.train_file)
    train_dataset = preprocess_dataset(
        train_dataset, tokenizer, data_args.max_seq_length,
        data_args.preprocessing_num_workers,
    )

    eval_dataset = None
    if data_args.eval_file:
        logger.info(f"加载验证数据: {data_args.eval_file}")
        eval_dataset = load_jsonl_dataset(data_args.eval_file)
        eval_dataset = preprocess_dataset(
            eval_dataset, tokenizer, data_args.max_seq_length,
            data_args.preprocessing_num_workers,
        )

    # ---------- 3. 加载模型 ----------
    model = create_quantized_model(model_args)
    model = create_lora_model(model, lora_args)

    # ---------- 4. 配置训练器 ----------
    data_collator = DataCollatorForSeq2Seq(
        tokenizer=tokenizer,
        padding=True,
        return_tensors="pt",
    )

    # 设置默认的训练参数
    training_args.remove_unused_columns = False
    training_args.dataloader_num_workers = 0  # Windows 兼容
    training_args.save_strategy = training_args.save_strategy or "epoch"
    training_args.logging_strategy = training_args.logging_strategy or "steps"
    training_args.logging_steps = training_args.logging_steps or 10
    training_args.eval_strategy = training_args.eval_strategy or "epoch"
    training_args.save_total_limit = training_args.save_total_limit or 2
    training_args.learning_rate = training_args.learning_rate or 2e-4
    training_args.per_device_train_batch_size = training_args.per_device_train_batch_size or 2
    training_args.gradient_accumulation_steps = training_args.gradient_accumulation_steps or 4
    training_args.num_train_epochs = training_args.num_train_epochs or 3
    training_args.warmup_ratio = training_args.warmup_ratio or 0.03
    training_args.lr_scheduler_type = training_args.lr_scheduler_type or "cosine"
    training_args.bf16 = training_args.bf16 or torch.cuda.is_bf16_supported()
    training_args.fp16 = not training_args.bf16
    training_args.optim = training_args.optim or "paged_adamw_8bit"
    training_args.report_to = training_args.report_to or ["tensorboard"]
    training_args.dataloader_pin_memory = False  # Windows 兼容

    # 支持从 checkpoint 恢复训练
    resume_checkpoint = data_args.resume_from_checkpoint
    if resume_checkpoint:
        if not os.path.exists(os.path.join(resume_checkpoint, "trainer_state.json")):
            logger.warning(f"Checkpoint 目录不存在或无效: {resume_checkpoint}，将从零开始训练")
            resume_checkpoint = None
        else:
            logger.info(f"从 checkpoint 恢复训练: {resume_checkpoint}")
            # 从 checkpoint 加载 LoRA 权重
            model = PeftModel.from_pretrained(model, resume_checkpoint)

    trainer = Trainer(
        model=model,
        args=training_args,
        data_collator=data_collator,
        train_dataset=train_dataset,
        eval_dataset=eval_dataset,
        tokenizer=tokenizer,
    )

    # ---------- 5. 开始训练 ----------
    logger.info("=" * 60)
    logger.info("开始 QLoRA 微调")
    logger.info(f"  模型: {model_args.model_name}")
    logger.info(f"  训练数据: {len(train_dataset)} 条")
    logger.info(f"  LoRA rank: {lora_args.lora_r}")
    logger.info(f"  训练轮次: {training_args.num_train_epochs}")
    logger.info(f"  学习率: {training_args.learning_rate}")
    logger.info(f"  Batch size: {training_args.per_device_train_batch_size}")
    logger.info(f"  有效 batch size: {training_args.per_device_train_batch_size * training_args.gradient_accumulation_steps}")
    logger.info(f"  设备: {model.device}")
    logger.info("=" * 60)

    train_result = trainer.train(resume_from_checkpoint=resume_checkpoint)

    # ---------- 6. 保存模型 ----------
    trainer.save_model(training_args.output_dir)
    tokenizer.save_pretrained(training_args.output_dir)

    metrics = train_result.metrics
    trainer.log_metrics("train", metrics)
    trainer.save_metrics("train", metrics)

    if eval_dataset:
        eval_metrics = trainer.evaluate()
        trainer.log_metrics("eval", eval_metrics)
        trainer.save_metrics("eval", eval_metrics)

    logger.info(f"\n[DONE] 微调完成，模型已保存到: {training_args.output_dir}")
    logger.info(f"  可训练参数: {sum(p.numel() for p in model.parameters() if p.requires_grad):,}")
    logger.info(f"  训练 loss: {metrics.get('train_loss', 'N/A')}")
    logger.info("\n下一步: python export_gguf.py --lora_dir ./output/checkpoint-final")

    # 保存本次训练配置，方便复现
    config_path = os.path.join(training_args.output_dir, "training_config.json")
    config = {
        "model": model_args.model_name,
        "lora_r": lora_args.lora_r,
        "lora_alpha": lora_args.lora_alpha,
        "lora_dropout": lora_args.lora_dropout,
        "target_modules": lora_args.lora_target_modules,
        "max_seq_length": data_args.max_seq_length,
        "learning_rate": training_args.learning_rate,
        "batch_size": training_args.per_device_train_batch_size,
        "gradient_accumulation_steps": training_args.gradient_accumulation_steps,
        "num_epochs": training_args.num_train_epochs,
        "warmup_ratio": training_args.warmup_ratio,
        "lr_scheduler": training_args.lr_scheduler_type,
        "train_file": data_args.train_file,
        "eval_file": data_args.eval_file,
        "train_loss": metrics.get("train_loss", None),
    }
    with open(config_path, "w", encoding="utf-8") as f:
        json.dump(config, f, ensure_ascii=False, indent=2)
    logger.info(f"训练配置已保存到: {config_path}")


def train_simple():
    """
    简化的训练入口（无参数解析，适合快速启动）。
    修改下方参数后直接运行: python finetune_lora.py
    """
    # ====== 修改这里 ======
    MODEL_NAME = "deepseek-r1:7b"       # 或 "qwen2.5:7b"
    TRAIN_FILE = "./sample_data/travel_train.jsonl"
    EVAL_FILE = "./sample_data/travel_eval.jsonl"
    OUTPUT_DIR = "./output/smart-assistant-lora"
    NUM_EPOCHS = 3
    LORA_R = 16
    # =====================

    model_args = ModelArguments(model_name=MODEL_NAME)
    lora_args = LoraArguments(lora_r=LORA_R)
    data_args = DataArguments(train_file=TRAIN_FILE, eval_file=EVAL_FILE)

    training_args = TrainingArguments(
        output_dir=OUTPUT_DIR,
        num_train_epochs=NUM_EPOCHS,
        per_device_train_batch_size=2,
        gradient_accumulation_steps=4,
        learning_rate=2e-4,
        warmup_ratio=0.03,
        lr_scheduler_type="cosine",
        logging_steps=10,
        save_strategy="epoch",
        evaluation_strategy="epoch",
        save_total_limit=2,
        report_to=["tensorboard"],
        fp16=not torch.cuda.is_bf16_supported(),
        bf16=torch.cuda.is_bf16_supported(),
        optim="paged_adamw_8bit",
        dataloader_num_workers=0,
        remove_unused_columns=False,
        dataloader_pin_memory=False,
    )

    # 复用主流程
    sys.argv = [sys.argv[0]]  # 清空参数，使用默认值
    # 直接手动执行核心流程
    tokenizer = AutoTokenizer.from_pretrained(
        "deepseek-ai/DeepSeek-R1-Distill-Qwen-7B", trust_remote_code=True
    )
    if tokenizer.pad_token is None:
        tokenizer.pad_token = tokenizer.eos_token

    train_dataset = preprocess_dataset(
        load_jsonl_dataset(TRAIN_FILE), tokenizer, 2048
    )
    eval_dataset = None
    if EVAL_FILE:
        eval_dataset = preprocess_dataset(
            load_jsonl_dataset(EVAL_FILE), tokenizer, 2048
        )

    model = create_quantized_model(model_args)
    model = create_lora_model(model, lora_args)

    trainer = Trainer(
        model=model,
        args=training_args,
        data_collator=DataCollatorForSeq2Seq(tokenizer=tokenizer, padding=True, return_tensors="pt"),
        train_dataset=train_dataset,
        eval_dataset=eval_dataset,
        tokenizer=tokenizer,
    )

    trainer.train()
    trainer.save_model(OUTPUT_DIR)
    tokenizer.save_pretrained(OUTPUT_DIR)
    logger.info(f"[DONE] 微调完成，模型已保存到: {OUTPUT_DIR}")


if __name__ == "__main__":
    main()
