# SmartAssistant 图片功能测试脚本
# 解决中文乱码：使用 [System.Text.Encoding]::UTF8.GetBytes() 强制 UTF-8 编码
#
# 用法:
#   powershell -ExecutionPolicy Bypass -File test-image.ps1
#
# 先在 start-all.ps1 启动全部服务后运行

$TOKEN = "eyJhbGciOiJIUzI1NiJ9.eyJyb2xlIjoiUk9MRV9VU0VSIiwidXNlcklkIjozMDc1LCJqdGkiOiIyZWMxYThjNC00Y2E0LTQzMWMtOTU2My0xZDM5MTc3YjA5YmIiLCJ1c2VybmFtZSI6InRlc3RfdXNlciIsInN1YiI6InRlc3RfdXNlciIsImlhdCI6MTc3ODI4NzI5NywiZXhwIjoxNzc4MjkwODk3fQ.Mws_Sx_kKJ3C4_D0igsL_N3MxNPglTbXuUyNc6RDE5Q"
$GATEWAY = "http://localhost:8081/assistant/api/math/chat"
$HEADERS = @{ 'Authorization' = "Bearer $TOKEN"; 'X-User-Id' = '3075'; 'X-User-Role' = 'ROLE_USER' }

function Send-Chat {
    param([string]$Message)
    $body = @{ message = $Message } | ConvertTo-Json
    $utf8Bytes = [System.Text.Encoding]::UTF8.GetBytes($body)
    try {
        $result = Invoke-RestMethod -Uri $GATEWAY -Method Post -Body $utf8Bytes `
            -ContentType 'application/json; charset=utf-8' -Headers $HEADERS -TimeoutSec 120
        return $result.reply
    } catch {
        return "ERROR: $($_.Exception.Message)"
    }
}

Write-Host "========================================" -ForegroundColor Cyan
Write-Host "  SmartAssistant 图片功能测试"           -ForegroundColor Cyan
Write-Host "========================================" -ForegroundColor Cyan
Write-Host ""

# 测试 1: 文生图
Write-Host ">>> 测试1: 文生图（水墨西湖）" -ForegroundColor Yellow
$reply1 = Send-Chat -Message "作为通用助手，帮我画一张水墨风格的西湖风景图"
Write-Host $reply1 -ForegroundColor Green
Write-Host ""

# 测试 2: 文生图（其他主题）
Write-Host ">>> 测试2: 文生图（熊猫竹林）" -ForegroundColor Yellow
$reply2 = Send-Chat -Message "作为通用助手，帮我画一只熊猫在竹林里吃竹子"
Write-Host $reply2 -ForegroundColor Green
Write-Host ""

# 测试 3: 联网搜索
Write-Host ">>> 测试3: 联网搜索（中文）" -ForegroundColor Yellow
$reply3 = Send-Chat -Message "帮我搜索一下今天的人工智能最新新闻"
Write-Host $reply3 -ForegroundColor Green
Write-Host ""

# 测试 4: 通用闲聊
Write-Host ">>> 测试4: 通用闲聊" -ForegroundColor Yellow
$reply4 = Send-Chat -Message "你好，请介绍一下你自己"
Write-Host $reply4 -ForegroundColor Green
Write-Host ""

Write-Host "========================================" -ForegroundColor Cyan
Write-Host "  测试完成！"                             -ForegroundColor Cyan
Write-Host "========================================" -ForegroundColor Cyan
