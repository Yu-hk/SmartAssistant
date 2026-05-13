#!/usr/bin/env python3
"""下载 BGE-small-zh 并转换为 ONNX 格式"""
import subprocess, sys, os

MODEL_DIR = r"D:\workspace\SmartAssistant\smart-assistant-router\src\main\resources\models"
ONNX_DIR = os.path.join(MODEL_DIR, "onnx")
MODEL_NAME = "BAAI/bge-small-zh-v1.5"

os.makedirs(ONNX_DIR, exist_ok=True)

# Step 1: Install dependencies
print("[1/3] Installing dependencies...")
subprocess.run([sys.executable, "-m", "pip", "install", "modelscope", "optimum", "onnxruntime", "-q"], check=True)

# Step 2: Download from ModelScope
print("[2/3] Downloading from ModelScope...")
subprocess.run([
    sys.executable, "-c",
    f"from modelscope import snapshot_download; snapshot_download('{MODEL_NAME}', cache_dir=r'{MODEL_DIR}')"
], check=True)

# Find downloaded model path
model_path = os.path.join(MODEL_DIR, MODEL_NAME.replace("/", "_"))
if not os.path.exists(model_path):
    # Try the modelscope default structure
    model_path = os.path.join(MODEL_DIR, MODEL_NAME)
if not os.path.exists(model_path):
    # Search
    for root, dirs, files in os.walk(MODEL_DIR):
        if "model.safetensors" in files:
            model_path = root
            break

print(f"Model path: {model_path}")
assert model_path and os.path.exists(model_path), f"Model not found at {model_path}"

# Step 3: Convert to ONNX
print("[3/3] Converting to ONNX...")
result = subprocess.run([
    sys.executable, "-m", "optimum.cli", "export", "onnx",
    "--model", model_path,
    "--task", "feature-extraction",
    ONNX_DIR
], capture_output=True, text=True)

print(result.stdout)
if result.returncode != 0:
    print("STDERR:", result.stderr)
    sys.exit(1)

# Verify output
onnx_files = [f for f in os.listdir(ONNX_DIR) if f.endswith(".onnx")]
print(f"\nONNX files: {onnx_files}")
for f in onnx_files:
    size = os.path.getsize(os.path.join(ONNX_DIR, f)) / 1024 / 1024
    print(f"  {f}: {size:.1f} MB")

# Rename for consistent naming
model_onnx = os.path.join(ONNX_DIR, "model.onnx")
if os.path.exists(model_onnx):
    import shutil
    shutil.copy(model_onnx, os.path.join(MODEL_DIR, "bge-small-zh-v1.5.onnx"))
    print(f"\nCopied to: {os.path.join(MODEL_DIR, 'bge-small-zh-v1.5.onnx')}")

print("\nDone!")
