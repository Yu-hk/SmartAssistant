<#
.SYNOPSIS
    SmartAssistant PDF 解析 OCR 依赖一键安装脚本（Windows）。
    安装 Tesseract OCR 引擎并拉取中文(chi_sim)训练数据，使 PdfDocumentParser 的 OCR 能力生效。

.DESCRIPTION
    幂等：已检测到的步骤会被跳过。
    1) 若 PATH 或本脚本同级 tesseract(.exe) 已可用，跳过安装；
    2) 否则尝试 winget 安装 UB-Mannheim.Tesseract（Windows 10/11 推荐）；
    3) 将 chi_sim.traineddata 下载到 tessdata 目录（兼容中文文档）；
    4) 验证 `tesseract --list-langs` 含 chi_sim。

    离线/内网：可手动下载后放到 deploy/tesseract/ 下（tesseract.exe + tessdata/chi_sim.traineddata），
    本脚本会自动识别该便携路径（sa.ocr.tesseract.bin / TESSERACT_BIN 亦可显式指定）。

.EXAMPLE
    powershell -ExecutionPolicy Bypass -File deploy/tesseract/setup-tesseract.ps1
#>

$ErrorActionPreference = "Stop"

$DeployDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$PortableExe = Join-Path $DeployDir "tesseract.exe"
$TessDataDir = Join-Path $DeployDir "tessdata"
$ChiSimUrl = "https://github.com/tesseract-ocr/tessdata_fast/raw/main/chi_sim.traineddata"

function Test-TesseractAvailable {
    # 便携路径优先
    if (Test-Path $PortableExe) { return $true }
    try {
        $null = Get-Command tesseract -ErrorAction Stop
        return $true
    } catch {
        return $false
    }
}

function Find-TessDataDir {
    # 1) 便携目录
    if (Test-Path (Join-Path $TessDataDir "chi_sim.traineddata")) { return $TessDataDir }
    # 2) 常见安装路径
    $candidates = @(
        "C:\Program Files\Tesseract-OCR\tessdata",
        "C:\Program Files (x86)\Tesseract-OCR\tessdata",
        (Join-Path $env:LOCALAPPDATA "Programs\Tesseract-OCR\tessdata")
    )
    foreach ($c in $candidates) {
        if (Test-Path $c) { return $c }
    }
    return $null
}

# ---------- 1) 引擎 ----------
if (Test-TesseractAvailable) {
    Write-Host "[setup-tesseract] Tesseract 已可用，跳过引擎安装。" -ForegroundColor Green
} else {
    Write-Host "[setup-tesseract] 未检测到 Tesseract，尝试通过 winget 安装..." -ForegroundColor Yellow
    try {
        if (Get-Command winget -ErrorAction SilentlyContinue) {
            winget install -e --id UB-Mannheim.Tesseract --accept-package-agreements --accept-source-agreements
            $env:Path = [Environment]::GetEnvironmentVariable("Path", "Machine") + ";" + [Environment]::GetEnvironmentVariable("Path", "User")
        } else {
            throw "未找到 winget。请手动安装 Tesseract: https://github.com/UB-Mannheim/tesseract/wiki"
        }
    } catch {
        Write-Warning "[setup-tesseract] 自动安装失败: $($_.Exception.Message)"
        Write-Warning "请手动安装 Tesseract 并将其目录加入 PATH，或将 tesseract.exe 放到 $DeployDir 下。"
        exit 1
    }
    if (-not (Test-TesseractAvailable)) {
        Write-Warning "安装后仍未在 PATH 检测到 tesseract，请重启终端或手动配置。"
        exit 1
    }
    Write-Host "[setup-tesseract] Tesseract 安装完成。" -ForegroundColor Green
}

# ---------- 2) 中文训练数据 ----------
$tessDir = Find-TessDataDir
if (-not $tessDir) {
    # 没有现成 tessdata，建立便携目录
    New-Item -ItemType Directory -Force -Path $TessDataDir | Out-Null
    $tessDir = $TessDataDir
}

$chiSimPath = Join-Path $tessDir "chi_sim.traineddata"
if (Test-Path $chiSimPath) {
    Write-Host "[setup-tesseract] chi_sim 训练数据已存在，跳过下载。" -ForegroundColor Green
} else {
    Write-Host "[setup-tesseract] 下载 chi_sim 训练数据 -> $chiSimPath ..." -ForegroundColor Yellow
    try {
        Invoke-WebRequest -Uri $ChiSimUrl -OutFile $chiSimPath -UseBasicParsing
        Write-Host "[setup-tesseract] chi_sim 下载完成。" -ForegroundColor Green
    } catch {
        Write-Warning "[setup-tesseract] 下载 chi_sim 失败: $($_.Exception.Message)"
        Write-Warning "可手动下载 $ChiSimUrl 并放到 $tessDir"
        exit 1
    }
}

# ---------- 3) 校验 ----------
try {
    $langs = & tesseract --list-langs 2>&1
    if ($langs -match "chi_sim") {
        Write-Host "[setup-tesseract] 校验通过：tesseract 已支持 chi_sim。" -ForegroundColor Green
    } else {
        Write-Warning "[setup-tesseract] tesseract 可用但未列出 chi_sim，请确认 tessdata 路径为: $tessDir"
    }
} catch {
    Write-Warning "[setup-tesseract] 校验失败: $($_.Exception.Message)"
}

Write-Host "[setup-tesseract] 完成。运行环境 OCR 能力已就绪。" -ForegroundColor Cyan
