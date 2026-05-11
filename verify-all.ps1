# SmartAssistant - 全量验证脚本
# 运行所有模块的编译和测试，用于提交前快速验证
# 用法: .\verify-all.ps1

$OutputEncoding = [System.Text.Encoding]::UTF8
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8

$root = "D:\workspace\SmartAssistant"
$MVN = "D:\maven\apache-maven-3.9.6\bin\mvn.cmd"
$startTime = Get-Date

$ok = 0
$fail = 0

function Run-Step {
    param($Name, $Command)
    Write-Host "[$Name] start..." -ForegroundColor Yellow
    $sw = [System.Diagnostics.Stopwatch]::StartNew()
    Invoke-Expression $Command *>$null
    if ($LASTEXITCODE -eq 0) {
        Write-Host "  PASS ($($sw.Elapsed.TotalSeconds.ToString('0.0'))s)" -ForegroundColor Green
        $script:ok++
    } else {
        Write-Host "  FAIL!" -ForegroundColor Red
        Write-Host "  Retry: $Command" -ForegroundColor Yellow
        Invoke-Expression $Command *>$null
        if ($LASTEXITCODE -eq 0) {
            Write-Host "  PASS (retry, $($sw.Elapsed.TotalSeconds.ToString('0.0'))s)" -ForegroundColor Green
            $script:ok++
        } else {
            $script:fail++
        }
    }
}

# Step 1: Install Common
Run-Step -Name "install:common" -Command "& '$MVN' install -pl smart-assistant-common -DskipTests -q"

# Step 2: Compile all
Run-Step -Name "compile:all" -Command "& '$MVN' compile -DskipTests -q"

# Step 3: Test all
Run-Step -Name "test:all" -Command "& '$MVN' test -DfailIfNoTests=false -q"

# Step 4: Frontend TS check
Run-Step -Name "frontend:tsc" -Command "cd $root\frontend; npx tsc --noEmit 2>&1"

# Step 5: Frontend build
Run-Step -Name "frontend:build" -Command "cd $root\frontend; npx vite build 2>&1"

$elapsed = [math]::Round(((Get-Date) - $startTime).TotalSeconds, 1)
Write-Host "======================================" -ForegroundColor Cyan
if ($fail -eq 0) {
    Write-Host "  ALL PASSED ($ok/$ok, ${elapsed}s)" -ForegroundColor Green
} else {
    Write-Host "  $ok passed, $fail failed (${elapsed}s)" -ForegroundColor Red
}
Write-Host "======================================" -ForegroundColor Cyan

if ($fail -gt 0) { exit 1 }
