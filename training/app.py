"""
SmartAssistant 微调控制面板
============================
Streamlit Web UI - 覆盖微调全流程：数据准备 → 训练 → 测试 → 导出

启动:
  cd training
  streamlit run app.py --server.port 8501

依赖:
  pip install streamlit
"""

import json
import os
import sys
import subprocess
import time
from pathlib import Path

import streamlit as st
import pandas as pd

# ============================================================
# 页面配置
# ============================================================
st.set_page_config(
    page_title="SmartAssistant 微调控制面板",
    page_icon="🔧",
    layout="wide",
    initial_sidebar_state="expanded",
)

# 工作目录
TRAINING_DIR = Path(__file__).parent
OUTPUT_DIR = TRAINING_DIR / "output"
SAMPLE_DATA_DIR = TRAINING_DIR / "sample_data"
SAMPLE_DATA_DIR.mkdir(parents=True, exist_ok=True)
OUTPUT_DIR.mkdir(parents=True, exist_ok=True)


# ============================================================
# 辅助函数
# ============================================================

def load_jsonl(path: str) -> list:
    """加载 JSONL 文件"""
    data = []
    if not os.path.exists(path):
        return data
    with open(path, "r", encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if line:
                try:
                    data.append(json.loads(line))
                except json.JSONDecodeError:
                    st.warning(f"跳过无效行: {line[:50]}")
    return data


def count_tokens(text: str) -> int:
    """粗略估算 token 数（中文约 1.5 字/token，英文 4 字符/token）"""
    if not text:
        return 0
    chinese = sum(1 for c in text if '\u4e00' <= c <= '\u9fff')
    other = len(text) - chinese
    return int(chinese * 1.5 + other / 4)


def find_checkpoints(base_dir: str = None) -> list:
    """查找已有的 checkpoint 目录"""
    if base_dir is None:
        base_dir = str(OUTPUT_DIR)
    checkpoints = []
    if os.path.exists(base_dir):
        for d in sorted(os.listdir(base_dir)):
            if d.startswith("checkpoint-"):
                cp_path = os.path.join(base_dir, d)
                if os.path.isdir(cp_path):
                    checkpoints.append(cp_path)
    return checkpoints


def find_output_dirs() -> list:
    """查找所有训练输出目录"""
    dirs = []
    if OUTPUT_DIR.exists():
        for d in sorted(OUTPUT_DIR.iterdir()):
            if d.is_dir() and not d.name.startswith("."):
                dirs.append(d)
    return dirs


def load_config(base_dir: str) -> dict:
    """加载训练配置"""
    config_path = os.path.join(base_dir, "training_config.json")
    if os.path.exists(config_path):
        with open(config_path, "r") as f:
            return json.load(f)
    return {}


def run_subprocess(cmd: list, desc: str = "") -> tuple[bool, str]:
    """运行子进程并捕获输出"""
    placeholder = st.empty()
    output_lines = []

    try:
        proc = subprocess.Popen(
            cmd,
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
            text=True,
            bufsize=1,
            cwd=str(TRAINING_DIR),
        )
        for line in proc.stdout:
            output_lines.append(line)
            if placeholder:
                placeholder.code("".join(output_lines[-20:]), language="text")
        proc.wait()
        full_output = "".join(output_lines)
        if proc.returncode == 0:
            return True, full_output
        else:
            return False, full_output
    except Exception as e:
        return False, str(e)


# ============================================================
# 侧边栏导航
# ============================================================
st.sidebar.title("🔧 SmartAssistant")
st.sidebar.caption("微调控制面板 v1.0")

page = st.sidebar.radio(
    "导航",
    ["📊 数据准备", "🛠 训练配置", "📈 训练监控", "🧪 模型测试", "📦 导出部署"],
    index=0,
)

st.sidebar.divider()
st.sidebar.subheader("快速操作")

if st.sidebar.button("📋 查看示例数据", use_container_width=True):
    page = "📊 数据准备"
    st.session_state["show_sample"] = True

if st.sidebar.button("⚡ 快速开始训练", use_container_width=True):
    page = "🛠 训练配置"
    st.session_state["quick_start"] = True

st.sidebar.divider()

# 显示当前环境状态
st.sidebar.subheader("环境检测")
try:
    import torch
    cuda_available = torch.cuda.is_available()
    if cuda_available:
        gpu_name = torch.cuda.get_device_name(0)
        vram = torch.cuda.get_device_properties(0).total_mem / 1024**3
        st.sidebar.success(f"✅ GPU: {gpu_name}\n{vram:.0f} GB VRAM")
    else:
        st.sidebar.warning("⚠️ CUDA 不可用（训练会很慢）")
except ImportError:
    st.sidebar.error("❌ torch 未安装")

# ============================================================
# 页面：数据准备
# ============================================================
if page == "📊 数据准备":
    st.title("📊 数据准备")
    st.markdown("准备微调用的对话数据（ChatML 格式）")

    tab1, tab2, tab3 = st.tabs(["上传/查看数据", "从模板生成", "数据统计"])

    # Tab 1: 上传/查看
    with tab1:
        col1, col2 = st.columns([1, 1])
        with col1:
            st.subheader("上传 JSONL 文件")
            uploaded_file = st.file_uploader(
                "选择 JSONL 文件", type=["jsonl"], key="data_upload"
            )
            if uploaded_file:
                save_path = SAMPLE_DATA_DIR / "uploaded_data.jsonl"
                with open(save_path, "wb") as f:
                    f.write(uploaded_file.getbuffer())
                st.success(f"已保存到: {save_path} ({uploaded_file.size/1024:.1f} KB)")

        with col2:
            st.subheader("或使用示例数据")
            for f in sorted(SAMPLE_DATA_DIR.glob("*.jsonl")):
                lines = sum(1 for _ in open(f, encoding="utf-8") if _.strip())
                st.write(f"- `{f.name}` ({lines} 条)")

        # 数据预览
        data_file = st.text_input(
            "数据文件路径", value=str(SAMPLE_DATA_DIR / "travel_train.jsonl"),
            key="data_path"
        )
        if data_file and os.path.exists(data_file):
            data = load_jsonl(data_file)
            if data:
                st.subheader(f"数据预览（共 {len(data)} 条）")
                for i, item in enumerate(data[:3]):
                    messages = item.get("messages", [])
                    with st.expander(f"#{i+1}: {messages[1]['content'][:40] if len(messages) > 1 else 'N/A'}..."):
                        for msg in messages:
                            role = msg.get("role", "?")
                            content = msg.get("content", "")
                            st.markdown(f"**{role}**: {content[:200]}")

    # Tab 2: 生成
    with tab2:
        st.subheader("使用 DeepSeek API 批量生成数据")
        api_key = st.text_input("DeepSeek API Key", type="password", key="gen_api_key")
        template_file = st.text_input(
            "模板文件路径", value=str(SAMPLE_DATA_DIR / "travel_train.jsonl"),
            key="template_path"
        )
        variations = st.slider("每个模板生成变体数", 1, 10, 3)

        if st.button("开始生成", type="primary", use_container_width=True):
            if not api_key:
                st.error("请输入 DeepSeek API Key")
            else:
                env = os.environ.copy()
                env["DEEPSEEK_API_KEY"] = api_key
                cmd = [
                    sys.executable, str(TRAINING_DIR / "prepare_data.py"),
                    "--generate",
                    "--templates", template_file,
                    "--output", str(SAMPLE_DATA_DIR / "generated_data.jsonl"),
                    "--variations", str(variations),
                    "--split",
                    "--train-output", str(SAMPLE_DATA_DIR / "generated_train.jsonl"),
                    "--eval-output", str(SAMPLE_DATA_DIR / "generated_eval.jsonl"),
                ]
                with st.spinner("正在生成数据..."):
                    success, output = run_subprocess(cmd, "生成中")
                    if success:
                        st.success("数据生成完成!")
                    else:
                        st.error(f"生成失败:\n{output[:500]}")

    # Tab 3: 统计
    with tab3:
        st.subheader("数据统计")
        data_file_stat = st.text_input(
            "数据文件路径", value=str(SAMPLE_DATA_DIR / "travel_train.jsonl"),
            key="stat_path"
        )
        if data_file_stat and os.path.exists(data_file_stat):
            data = load_jsonl(data_file_stat)
            if data:
                stats = []
                total_user_tokens = 0
                total_assistant_tokens = 0
                for item in data:
                    messages = item.get("messages", [])
                    user_content = next(
                        (m["content"] for m in messages if m["role"] == "user"), ""
                    )
                    assistant_content = next(
                        (m["content"] for m in messages if m["role"] == "assistant"), ""
                    )
                    user_tokens = count_tokens(user_content)
                    assistant_tokens = count_tokens(assistant_content)
                    total_user_tokens += user_tokens
                    total_assistant_tokens += assistant_tokens
                    stats.append({
                        "用户输入长度": len(user_content),
                        "助手回复长度": len(assistant_content),
                        "用户 Token 估算": user_tokens,
                        "助手 Token 估算": assistant_tokens,
                    })

                df = pd.DataFrame(stats)
                col1, col2, col3, col4 = st.columns(4)
                col1.metric("总条数", len(data))
                col2.metric("用户平均 Token", int(df["用户 Token 估算"].mean()))
                col3.metric("助手平均 Token", int(df["助手 Token 估算"].mean()))
                col4.metric("总 Token 估算", total_user_tokens + total_assistant_tokens)

                st.subheader("分布")
                st.dataframe(df.describe(), use_container_width=True)

# ============================================================
# 页面：训练配置
# ============================================================
elif page == "🛠 训练配置":
    st.title("🛠 训练配置")
    st.markdown("配置 QLoRA 微调参数并启动训练")

    with st.form("training_form"):
        st.subheader("数据配置")
        col1, col2 = st.columns(2)
        with col1:
            train_file = st.text_input(
                "训练集路径",
                value=str(SAMPLE_DATA_DIR / "generated_train.jsonl"
                          if (SAMPLE_DATA_DIR / "generated_train.jsonl").exists()
                          else str(SAMPLE_DATA_DIR / "travel_train.jsonl")),
            )
        with col2:
            eval_file = st.text_input(
                "验证集路径（可选）",
                value=str(SAMPLE_DATA_DIR / "generated_eval.jsonl"
                          if (SAMPLE_DATA_DIR / "generated_eval.jsonl").exists()
                          else str(SAMPLE_DATA_DIR / "travel_eval.jsonl")),
            )

        st.subheader("模型配置")
        model_name = st.selectbox(
            "基础模型",
            options=["deepseek-r1:7b", "qwen2.5:7b"],
            index=0,
        )
        max_seq_length = st.slider("最大序列长度", 512, 4096, 2048, step=256,
                                   help="根据显存调整，16GB 显存推荐 2048")

        st.subheader("LoRA 参数")
        col1, col2, col3 = st.columns(3)
        with col1:
            lora_r = st.selectbox("LoRA Rank (r)", [8, 16, 32, 64], index=1,
                                  help="秩越高容量越大，16 对大多数任务足够")
        with col2:
            lora_alpha = st.selectbox("LoRA Alpha", [16, 32, 64, 128], index=1,
                                      help="缩放系数，通常设为 2×r")
        with col3:
            lora_dropout = st.select_slider("Dropout", [0.0, 0.05, 0.1, 0.2],
                                            value=0.05)

        st.subheader("训练参数")
        col1, col2 = st.columns(2)
        with col1:
            learning_rate = st.select_slider(
                "学习率", options=[5e-5, 1e-4, 2e-4, 3e-4, 5e-4],
                value=2e-4, format_func=lambda x: f"{x:.0e}"
            )
            batch_size = st.selectbox("Batch Size", [1, 2, 4], index=1)
        with col2:
            num_epochs = st.slider("训练轮次", 1, 10, 3)
            grad_accum = st.selectbox("Gradient Accumulation", [1, 2, 4, 8], index=2)
            effective_bs = batch_size * grad_accum
            st.caption(f"有效 Batch Size: {effective_bs}")

        st.subheader("输出配置")
        output_dir = st.text_input("输出目录", value=str(OUTPUT_DIR / "smart-assistant-lora"))

        # Checkpoint 恢复
        checkpoints = find_checkpoints(output_dir)
        resume_checkpoint = None
        if checkpoints:
            resume_checkpoint = st.selectbox(
                "从 Checkpoint 恢复（可选）",
                options=[None] + checkpoints,
                format_func=lambda x: os.path.basename(x) if x else "不从 checkpoint 恢复",
            )

        # 提交
        submitted = st.form_submit_button("🚀 开始训练", type="primary",
                                           use_container_width=True)

        if submitted:
            if not os.path.exists(train_file):
                st.error(f"训练集不存在: {train_file}")
                st.stop()

            cmd = [
                sys.executable, str(TRAINING_DIR / "finetune_lora.py"),
                "--train_file", train_file,
                "--model_name", model_name,
                "--output_dir", output_dir,
                "--lora_r", str(lora_r),
                "--lora_alpha", str(lora_alpha),
                "--lora_dropout", str(lora_dropout),
                "--learning_rate", str(learning_rate),
                "--per_device_train_batch_size", str(batch_size),
                "--gradient_accumulation_steps", str(grad_accum),
                "--num_train_epochs", str(num_epochs),
                "--max_seq_length", str(max_seq_length),
            ]
            if eval_file and os.path.exists(eval_file):
                cmd.extend(["--eval_file", eval_file])
            if resume_checkpoint:
                cmd.extend(["--resume_from_checkpoint", resume_checkpoint])

            st.info(f"执行命令:\n```\n{' '.join(cmd)}\n```")

            with st.spinner("训练进行中..."):
                progress_bar = st.progress(0, text="训练中...")
                success, output = run_subprocess(cmd, "训练中")
                progress_bar.empty()

                if success:
                    st.success(f"✅ 训练完成! 模型已保存到: {output_dir}")
                    # 显示训练指标
                    config = load_config(output_dir)
                    if config.get("train_loss"):
                        st.metric("最终训练 Loss", f"{config['train_loss']:.4f}")
                else:
                    st.error(f"❌ 训练失败:\n{output[:1000]}")

# ============================================================
# 页面：训练监控
# ============================================================
elif page == "📈 训练监控":
    st.title("📈 训练监控")
    st.markdown("查看已有训练记录和 Loss 曲线")

    output_dirs = find_output_dirs()
    if not output_dirs:
        st.info("暂无训练记录。先去「训练配置」页面开始一次训练吧。")
        st.stop()

    selected_dir = st.selectbox(
        "选择训练输出目录",
        options=output_dirs,
        format_func=lambda x: x.name,
    )

    if selected_dir:
        config = load_config(str(selected_dir))
        if config:
            st.subheader("训练配置快照")
            col1, col2, col3, col4 = st.columns(4)
            col1.metric("基础模型", config.get("model", "N/A"))
            col2.metric("LoRA Rank", config.get("lora_r", "N/A"))
            col3.metric("学习率", f"{config.get('learning_rate', 'N/A'):.0e}" if isinstance(config.get('learning_rate'), (int, float)) else "N/A")
            col4.metric("训练 Loss", f"{config.get('train_loss', 'N/A'):.4f}" if config.get('train_loss') else "N/A")

            with st.expander("完整配置"):
                st.json(config)

        # 列出 checkpoints
        checkpoints = find_checkpoints(str(selected_dir))
        if checkpoints:
            st.subheader(f"Checkpoints（{len(checkpoints)} 个）")
            for cp in checkpoints:
                cp_name = os.path.basename(cp)
                size = sum(f.stat().st_size for f in Path(cp).rglob("*") if f.is_file())
                st.write(f"- **{cp_name}** ({size/1024/1024:.1f} MB)")

        st.subheader("TensorBoard 启动")
        st.code(f"tensorboard --logdir {selected_dir} --port 6006")
        st.info("启动后在浏览器打开 http://localhost:6006 查看 Loss 曲线")

# ============================================================
# 页面：模型测试
# ============================================================
elif page == "🧪 模型测试":
    st.title("🧪 模型测试")
    st.markdown("测试微调前后的模型输出效果")

    tab1, tab2 = st.tabs(["自动测试", "交互式对话"])

    with tab1:
        st.subheader("批量测试用例")

        # 自定义测试用例
        test_cases = st.text_area(
            "测试用例（每行一个 JSON，包含 system/user/expected 字段）",
            height=150,
            value=json.dumps([
                {"system": "你是旅行规划助手。", "user": "北京三日游怎么安排", "expected": "景点推荐"},
                {"system": "你是旅行规划助手。", "user": "上海有什么好吃的", "expected": "美食推荐"},
            ], ensure_ascii=False, indent=2),
        )

        col1, col2 = st.columns(2)
        with col1:
            test_model_path = st.text_input(
                "测试模型路径",
                value=str(OUTPUT_DIR / "smart-assistant-lora" / "checkpoint-3")
                if (OUTPUT_DIR / "smart-assistant-lora" / "checkpoint-3").exists()
                else "deepseek-r1:7b",
                key="test_model_path",
            )
        with col2:
            base_model = st.selectbox(
                "基础模型", ["deepseek-r1:7b", "qwen2.5:7b"], index=0,
                key="test_base_model"
            )

        col1, col2 = st.columns(2)
        with col1:
            if st.button("📊 测试微调后模型", use_container_width=True):
                test_file = SAMPLE_DATA_DIR / "_test_cases.jsonl"
                try:
                    cases = json.loads(test_cases)
                    with open(test_file, "w", encoding="utf-8") as f:
                        for c in cases:
                            f.write(json.dumps(c, ensure_ascii=False) + "\n")
                except json.JSONDecodeError as e:
                    st.error(f"JSON 格式错误: {e}")
                    st.stop()

                cmd = [
                    sys.executable, str(TRAINING_DIR / "test_model.py"),
                    "--model", test_model_path,
                    "--base-model", base_model,
                    "--test-file", str(test_file),
                ]
                with st.spinner("测试中..."):
                    success, output = run_subprocess(cmd, "测试中")
                    st.text(output[-2000:] if len(output) > 2000 else output)

        with col2:
            if st.button("🔄 对比微调前", use_container_width=True):
                cmd = [
                    sys.executable, str(TRAINING_DIR / "test_model.py"),
                    "--baseline",
                    "--base-model", base_model,
                ]
                with st.spinner("测试中..."):
                    success, output = run_subprocess(cmd, "测试中")
                    st.text(output[-2000:] if len(output) > 2000 else output)

    with tab2:
        st.subheader("交互式对话测试")

        model_path = st.text_input(
            "模型路径",
            value=str(OUTPUT_DIR / "smart-assistant-lora" / "checkpoint-3")
            if (OUTPUT_DIR / "smart-assistant-lora" / "checkpoint-3").exists()
            else "deepseek-r1:7b",
            key="chat_model_path",
        )
        base_model_for_chat = st.selectbox(
            "基础模型", ["deepseek-r1:7b", "qwen2.5:7b"], index=0,
            key="chat_base_model"
        )
        temperature = st.slider("Temperature", 0.0, 1.0, 0.7, 0.1)

        # 初始化聊天历史
        if "chat_history" not in st.session_state:
            st.session_state.chat_history = []

        system_prompt = st.text_input(
            "System Prompt", value="你是 SmartAssistant 的智能助手。",
            key="chat_system"
        )

        user_input = st.text_input("💬 输入消息（回车发送）", key="chat_input")
        col1, col2 = st.columns(2)
        with col1:
            if st.button("发送", use_container_width=True) and user_input:
                st.session_state.chat_history.append(("user", user_input))
                try:
                    # 使用 test_model 的 generate 函数
                    sys.path.insert(0, str(TRAINING_DIR))
                    from test_model import load_model, generate

                    model, tokenizer = load_model(model_path, base_model_for_chat)
                    reply = generate(model, tokenizer, system_prompt, user_input,
                                     temperature=temperature)

                    st.session_state.chat_history.append(("assistant", reply))
                except Exception as e:
                    st.session_state.chat_history.append(("assistant", f"[错误] {e}"))

        with col2:
            if st.button("清空历史", use_container_width=True):
                st.session_state.chat_history = []

        # 显示聊天历史
        for role, content in st.session_state.chat_history:
            if role == "user":
                st.markdown(f"**🧑 你**: {content}")
            else:
                st.markdown(f"**🤖 模型**: {content}")
                st.divider()

# ============================================================
# 页面：导出部署
# ============================================================
elif page == "📦 导出部署":
    st.title("📦 导出与部署")
    st.markdown("将微调后的 LoRA 权重导出为 GGUF 并注册到 Ollama")

    # Step 1: 选择 checkpoint
    st.subheader("Step 1: 选择 LoRA Checkpoint")
    output_dirs = find_output_dirs()

    if not output_dirs:
        st.warning("还没有训练输出。先去训练页面跑一次微调。")
        st.stop()

    selected_lora = st.selectbox(
        "选择训练输出目录",
        options=output_dirs,
        format_func=lambda x: x.name,
        key="export_output_dir",
    )

    if selected_lora:
        checkpoints = find_checkpoints(str(selected_lora))
        if not checkpoints:
            st.warning(f"目录 {selected_lora.name} 中没有找到 checkpoint")
            st.stop()

        selected_checkpoint = st.selectbox(
            "选择 Checkpoint",
            options=checkpoints,
            format_func=lambda x: os.path.basename(x),
            key="export_checkpoint",
        )

        # Step 2: 配置导出参数
        st.subheader("Step 2: 导出配置")

        col1, col2 = st.columns(2)
        with col1:
            base_model = st.selectbox(
                "基础模型",
                ["deepseek-r1:7b", "qwen2.5:7b"],
                index=0,
                key="export_base_model",
            )
        with col2:
            ollama_name = st.text_input("Ollama 模型名称", value="smart-custom",
                                        key="export_ollama_name")

        gguf_output = st.text_input(
            "GGUF 输出路径",
            value=str(TRAINING_DIR / "smart-custom-7b.gguf"),
            key="export_gguf_path",
        )
        llama_cpp_dir = st.text_input(
            "llama.cpp 目录路径（如未安装可跳过量化）",
            value="../llama.cpp",
            key="export_llama_cpp",
        )

        # Step 3: 执行导出
        st.subheader("Step 3: 执行导出")

        col1, col2 = st.columns(2)
        with col1:
            if st.button("🔗 1. 合并 LoRA 权重", use_container_width=True):
                cmd = [
                    sys.executable, str(TRAINING_DIR / "export_gguf.py"),
                    "--base-model", base_model,
                    "--lora-dir", selected_checkpoint,
                    "--output-dir", str(TRAINING_DIR / "merged_model"),
                    "--skip-gguf", "--skip-ollama",
                ]
                with st.spinner("合并中..."):
                    success, output = run_subprocess(cmd, "合并中")
                    if success:
                        st.success("合并完成!")
                    else:
                        st.error(f"合并失败:\n{output[-500:]}")

        with col2:
            if st.button("📦 2. 导出 GGUF + 注册 Ollama", use_container_width=True):
                cmd = [
                    sys.executable, str(TRAINING_DIR / "export_gguf.py"),
                    "--base-model", base_model,
                    "--lora-dir", selected_checkpoint,
                    "--output-dir", str(TRAINING_DIR / "merged_model"),
                    "--gguf-output", gguf_output,
                    "--ollama-name", ollama_name,
                    "--llama-cpp-dir", llama_cpp_dir,
                ]
                with st.spinner("导出中..."):
                    success, output = run_subprocess(cmd, "导出中")
                    if success:
                        st.success(f"导出成功! Ollama 模型 '{ollama_name}' 已就绪")
                    else:
                        st.error(f"导出失败:\n{output[-500:]}")

        st.divider()

        # 部署指南
        st.subheader("部署指南")
        st.code(f"""# 在 application.yml 中切换模型:
spring:
  ai:
    ollama:
      chat:
        options:
          model: {ollama_name}

# 测试模型是否可用:
ollama run {ollama_name} "推荐北京景点"
""", language="yaml")

        st.success("完成以上步骤后，重启 SmartAssistant 即可使用微调后的模型。")
