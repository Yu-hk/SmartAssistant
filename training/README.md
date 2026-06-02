# SmartAssistant 微调管道

## 概述

本目录包含 SmartAssistant 的 QLoRA 微调工具集，用于对本地 7B 模型（deepseek-r1:7b / qwen2.5:7b）进行参数高效微调，适配企业内部特定领域需求。

### 适用场景

| 场景 | 说明 |
|------|------|
| 输出格式不稳定 | Agent 回复格式经常偏离预期（JSON/Markdown 表格） |
| 领域术语不熟悉 | 模型不理解公司内部缩写、产品编码 |
| 本地部署加速 | 微调后的 7B 模型可减少 30-50% 生成 token |
| 纯离线环境 | 不能访问外部 API，只能靠本地模型 |

### 不适合的场景

| 场景 | 替代方案 |
|------|---------|
| 知识频繁更新 | RAG 检索（文档变更无需重新训练） |
| 需要强推理能力 | 云端 DeepSeek V4-Flash |
| 无 GPU 环境 | RAG + Prompt 调优 |

---

## 环境准备

### 硬件要求

| 配置 | 说明 |
|------|------|
| GPU 显存 | ≥16GB（RTX 4060 Ti / 3080 或更高） |
| 内存 | ≥32GB |
| 硬盘 | ≥30GB 可用空间（存放模型权重） |

### 安装依赖

```bash
# 1. 创建虚拟环境
python -m venv .venv
# Windows:
.venv\Scripts\activate
# Linux/Mac:
source .venv/bin/activate

# 2. 安装依赖
pip install -r requirements.txt

# 3. 安装 llama.cpp（用于 GGUF 导出）
git clone https://github.com/ggerganov/llama.cpp
cd llama.cpp
# Windows: 下载预编译包 https://github.com/ggerganov/llama.cpp/releases
# Linux/Mac:
make -j

### 安装 Streamlit UI（可选）

```bash
pip install streamlit
```

首次启动:
```bash
streamlit run app.py --server.port 8501
```
```

---

## 使用流程

### Step 1: 准备数据

数据格式为 JSONL，每条包含 `messages` 字段（ChatML 格式）：

```json
{
  "messages": [
    {"role": "system", "content": "你是 SmartAssistant 的旅行规划助手。"},
    {"role": "user", "content": "推荐北京景点"},
    {"role": "assistant", "content": "故宫、长城、天坛等"}
  ]
}
```

**建议数据量**：200-500 条高质量对话（质量 > 数量）

#### 方法 A：手动标注（推荐）

收集线上 Agent 的高质量回复日志，清洗后使用。

#### 方法 B：LLM 批量生成

```bash
export DEEPSEEK_API_KEY=sk-xxxxx
python prepare_data.py \
    --generate \
    --templates ./sample_data/templates.jsonl \
    --output ./generated_data.jsonl \
    --variations 3 \
    --split \
    --train-output ./train.jsonl \
    --eval-output ./eval.jsonl
```

### Step 2: 执行微调

```bash
# 使用命令行参数
python finetune_lora.py \
    --train_file ./train.jsonl \
    --eval_file ./eval.jsonl \
    --model_name deepseek-r1:7b \
    --output_dir ./output/smart-assistant-lora \
    --num_train_epochs 3 \
    --per_device_train_batch_size 2 \
    --learning_rate 2e-4

# 或者直接修改 finetune_lora.py 底部的 train_simple() 函数后运行
python finetune_lora.py
```

### Step 3: 导出为 GGUF

```bash
python export_gguf.py \
    --base_model deepseek-r1:7b \
    --lora_dir ./output/smart-assistant-lora/checkpoint-3 \
    --output_dir ./merged_model \
    --gguf_output ./smart-travel-7b.gguf \
    --ollama_name smart-travel \
    --llama_cpp_dir ../llama.cpp
```

### Step 4: 在 SmartAssistant 中使用

修改 `application.yml`：

```yaml
spring:
  ai:
    ollama:
      chat:
        options:
          model: smart-travel    # 改为微调后的模型名
```

---

## 参数调优迭代指南

微调很少一次成功，以下是标准的迭代流程和常见问题的调参方案。

### 迭代流程

```
第1次跑 → 检查 loss 曲线和测试输出
   │
   ├── loss 不下降 → 增大 learning_rate 或 lora_r
   ├── loss 降很快但输出差 → 检查数据质量
   ├── 过拟合（eval loss 回升）→ 减少 epochs，增大 dropout
   └── 输出和基础模型没区别 → 增大 lora_r 到 32-64
         │
         ▼
  修改参数 → 从上次 checkpoint 恢复 → 继续训练
         │
         ▼
  再次测试 → 满意后导出 GGUF
```

### 从 Checkpoint 恢复训练

```bash
# 查看已有的 checkpoint
ls ./output/smart-assistant-lora/

# 从 checkpoint-2 恢复，调整学习率和轮次
python finetune_lora.py \
    --train_file ./train.jsonl \
    --eval_file ./eval.jsonl \
    --model_name deepseek-r1:7b \
    --output_dir ./output/smart-assistant-lora \
    --resume_from_checkpoint ./output/smart-assistant-lora/checkpoint-2 \
    --num_train_epochs 5 \
    --learning_rate 1e-4
```

### 常见问题诊断

#### Loss 曲线解读

```
loss
 ↑
2.5┤  ╱╲
2.0┤ ╱  ╲╱╲__      ← 理想: 快速下降后趋于平稳
1.5┤╱          ╲__
1.0┤              ╲__   ← 继续下降但变慢
0.5┤
   └─────────────────→ epoch
   0    1     2     3

loss
 ↑
2.5┤  ╱╲
2.0┤ ╱  ╲    ╱╲     ← 抖动: 学习率太高，降低 2-5 倍
1.5┤╱    ╲╱╱  ╲╱╲
   └─────────────────→ epoch

loss
 ↑                    ← 过拟合: 训练 loss 降但验证 loss 升
2.0┤╲__
1.5┤ ╲__  train
   └─────────────────→
2.0┤   ╱╲  eval (回升!)
1.5┤  ╱  ╲╱╲
   └─────────────────→ epoch
```

#### 快速调参对照表

| 现象 | 首选项 | 备选 | 原理 |
|------|--------|------|------|
| Loss 不降 | ↑ learning_rate (3e-4) | ↑ lora_r (32) | 学得太慢，增加更新步长 |
| Loss 震荡 | ↓ learning_rate (1e-4) | ↑ batch_size (accum=8) | 步长太大，跨过最优点 |
| 过拟合（eval loss 回升）| ↓ num_epochs (2) | ↑ lora_dropout (0.1) | 学太多训练集噪音 |
| 输出格式不对 | 检查数据 > 调整参数 | ↑ lora_r (32) | 数据中缺乏格式示例 |
| 知识没学到 | 检查数据 > 增加数据量 | ↑ lora_r (64) | 容量不够 |
| 灾难性遗忘 | ↓ learning_rate (1e-4) | ↓ num_epochs (1) | 学得太猛，覆盖了原能力 |

### 快速对比测试

```bash
# 测试微调前的基础模型表现
python test_model.py --baseline

# 测试微调后效果
python test_model.py --model ./output/smart-assistant-lora/checkpoint-3

# 交互式测试
python test_model.py --model ./output/smart-assistant-lora/checkpoint-3 --interactive

# 使用自定义测试用例
python test_model.py --model ./output/checkpoint-3 --test-file ./my_tests.jsonl
```

### LoRA 参数

| 参数 | 默认值 | 范围 | 说明 |
|------|--------|------|------|
| lora_r | 16 | 8-64 | 秩越高容量越大，16 对大多数任务足够 |
| lora_alpha | 32 | 16-64 | 缩放系数，通常设为 2×r |
| lora_dropout | 0.05 | 0-0.1 | 数据量小时可提高到 0.1 |
| target_modules | q_proj,k_proj,v_proj,o_proj | — | 注意力层，MLP 层可选 |

### 训练参数

| 参数 | 默认值 | 说明 |
|------|--------|------|
| num_train_epochs | 3 | 1-3 epoch 足够，过多会过拟合 |
| learning_rate | 2e-4 | LoRA 用较大学习率（比全量微调大 10 倍）|
| per_device_train_batch_size | 2 | 16GB 显存可跑 2 |
| gradient_accumulation_steps | 4 | 有效 batch size = 2×4=8 |
| warmup_ratio | 0.03 | 前 3% 步数预热学习率 |
| lr_scheduler_type | cosine | 余弦退火调度 |

---

## 目录结构

```
training/
├── requirements.txt           # Python 依赖
├── prepare_data.py            # 数据准备与 LLM 生成
├── finetune_lora.py           # QLoRA 微调主脚本
├── export_gguf.py             # GGUF 导出 + Ollama 注册
├── sample_data/
│   ├── travel_train.jsonl     # 示例训练数据（4 条）
│   └── travel_eval.jsonl      # 示例验证数据（2 条）
└── README.md                  # 本文档
```

---

## 常见问题

### Q: 显存不够怎么办？
- 降低 `per_device_train_batch_size` 到 1
- 增加 `gradient_accumulation_steps` 到 8
- 开启 `gradient_checkpointing`
- 使用 `load_in_4bit=True`（QLoRA 标配）

### Q: 没有 GPU 能跑吗？
- 可以，但非常慢。7B 模型在 CPU 上微调预计需要数天
- 建议使用 Google Colab（免费 T4 16GB 可用）

### Q: 数据量不够怎么办？
- 使用 `prepare_data.py` 的 `--generate` 模式，用 DeepSeek API 扩展
- 50 条高质量数据 + 模板扩展 = 200 条可用数据

### Q: 如何监控训练过程？
```bash
tensorboard --logdir ./output/smart-assistant-lora
```
然后在浏览器打开 http://localhost:6006
