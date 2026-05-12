# Download BGE-small-zh ONNX model for local embedding
$modelDir = "D:\workspace\SmartAssistant\smart-assistant-router\src\main\resources\models"
$modelFile = "$modelDir\bge-small-zh-v1.5.onnx"
$vocabFile = "$modelDir\tokenizer.json"

if (Test-Path $modelFile) {
    Write-Host "Model already exists: $modelFile"
    exit 0
}

# Ensure directory exists
New-Item -ItemType Directory -Force -Path $modelDir | Out-Null

Write-Host "Downloading BGE-small-zh-v1.5 ONNX model..."
Write-Host "Model: https://huggingface.co/BAAI/bge-small-zh-v1.5/resolve/main/onnx/model_optimized.onnx"
Write-Host "Tokenizer: https://huggingface.co/BAAI/bge-small-zh-v1.5/raw/main/tokenizer.json"

try {
    Invoke-WebRequest -Uri "https://huggingface.co/BAAI/bge-small-zh-v1.5/resolve/main/onnx/model_optimized.onnx" -OutFile $modelFile -TimeoutSec 120
    Write-Host "Model downloaded: $modelFile ($((Get-Item $modelFile).Length / 1MB) MB)" -ForegroundColor Green
} catch {
    Write-Host "Direct ONNX download failed. Trying alternative method..." -ForegroundColor Yellow
    # Fallback: try to convert using optimum if available
    try {
        pip install optimum onnx onnxruntime --quiet
        optimum-cli export onnx --model BAAI/bge-small-zh-v1.5 --task feature-extraction $modelDir
        Move-Item "$modelDir\model.onnx" $modelFile -Force
    } catch {
        Write-Host "Please download manually from: https://huggingface.co/BAAI/bge-small-zh-v1.5" -ForegroundColor Red
        Write-Host "Place the ONNX model at: $modelFile" -ForegroundColor Red
        exit 1
    }
}

# Download tokenizer
try {
    Invoke-WebRequest -Uri "https://huggingface.co/BAAI/bge-small-zh-v1.5/raw/main/tokenizer.json" -OutFile $vocabFile -TimeoutSec 30
    Write-Host "Tokenizer downloaded" -ForegroundColor Green
} catch {
    Write-Host "Tokenizer download failed. You may need to download manually." -ForegroundColor Yellow
}

Write-Host "Done!" -ForegroundColor Green
