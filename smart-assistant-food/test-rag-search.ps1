# =====================================================
# 美食服务 RAG 功能快速测试脚本
# 用途：验证语义搜索功能是否正常工作
# 使用：在 PowerShell 中运行 .\test-rag-search.ps1
# =====================================================

Write-Host "========================================" -ForegroundColor Cyan
Write-Host "  美食服务 RAG 功能测试" -ForegroundColor Cyan
Write-Host "========================================" -ForegroundColor Cyan
Write-Host ""

$baseUrl = "http://localhost:8084"

# 检查服务是否运行
Write-Host "[1/5] 检查服务状态..." -ForegroundColor Yellow
try {
    $response = Invoke-WebRequest -Uri "$baseUrl/actuator/health" -Method Get -TimeoutSec 5
    if ($response.StatusCode -eq 200) {
        Write-Host "✅ 服务运行正常" -ForegroundColor Green
    } else {
        Write-Host "❌ 服务返回异常状态码: $($response.StatusCode)" -ForegroundColor Red
        exit 1
    }
} catch {
    Write-Host "❌ 无法连接到服务，请确认服务已启动" -ForegroundColor Red
    Write-Host "   运行命令: mvn spring-boot:run" -ForegroundColor Gray
    exit 1
}

Write-Host ""

# 测试 1: 语义搜索 - 环境好的餐厅
Write-Host "[2/5] 测试语义搜索：环境好的西餐厅..." -ForegroundColor Yellow
try {
    $body = @{
        query = "环境好的西餐厅"
        city = "北京"
        maxPrice = 300
        minRating = 4.0
    } | ConvertTo-Json

    $response = Invoke-RestMethod -Uri "$baseUrl/api/food/recommend" `
                                  -Method Post `
                                  -Body $body `
                                  -ContentType "application/json"

    Write-Host "✅ 搜索成功" -ForegroundColor Green
    Write-Host "返回结果:" -ForegroundColor Gray
    Write-Host $response -ForegroundColor White
} catch {
    Write-Host "⚠️  搜索失败: $($_.Exception.Message)" -ForegroundColor Red
}

Write-Host ""
Write-Host "按任意键继续下一个测试..." -ForegroundColor Gray
$null = $Host.UI.RawUI.ReadKey("NoEcho,IncludeKeyDown")

# 测试 2: 热门餐厅
Write-Host "[3/5] 测试热门餐厅：成都川菜馆..." -ForegroundColor Yellow
try {
    $body = @{
        city = "成都"
        cuisineType = "川菜"
        limit = 3
    } | ConvertTo-Json

    $response = Invoke-RestMethod -Uri "$baseUrl/api/food/popular" `
                                  -Method Post `
                                  -Body $body `
                                  -ContentType "application/json"

    Write-Host "✅ 获取成功" -ForegroundColor Green
    Write-Host "返回结果:" -ForegroundColor Gray
    Write-Host $response -ForegroundColor White
} catch {
    Write-Host "⚠️  获取失败: $($_.Exception.Message)" -ForegroundColor Red
}

Write-Host ""
Write-Host "按任意键继续下一个测试..." -ForegroundColor Gray
$null = $Host.UI.RawUI.ReadKey("NoEcho,IncludeKeyDown")

# 测试 3: 数据库检查
Write-Host "[4/5] 检查数据库向量化状态..." -ForegroundColor Yellow
try {
    # 这里需要直接连接数据库检查
    Write-Host "ℹ️  请手动执行以下 SQL 检查：" -ForegroundColor Gray
    Write-Host "   SELECT COUNT(*) as total," -ForegroundColor Gray
    Write-Host "          COUNT(embedding) as vectorized" -ForegroundColor Gray
    Write-Host "   FROM restaurant_reviews_vector;" -ForegroundColor Gray
    Write-Host ""
    Write-Host "   如果 vectorized = total，说明所有评论已向量化 ✓" -ForegroundColor Gray
} catch {
    Write-Host "⚠️  检查失败" -ForegroundColor Red
}

Write-Host ""
Write-Host "按任意键查看测试总结..." -ForegroundColor Gray
$null = $Host.UI.RawUI.ReadKey("NoEcho,IncludeKeyDown")

# 测试总结
Write-Host ""
Write-Host "========================================" -ForegroundColor Cyan
Write-Host "  测试完成" -ForegroundColor Cyan
Write-Host "========================================" -ForegroundColor Cyan
Write-Host ""
Write-Host "下一步操作:" -ForegroundColor Yellow
Write-Host "1. 查看完整日志: logs/food-service.log" -ForegroundColor Gray
Write-Host "2. 运行单元测试: mvn test -Dtest=RestaurantReviewSearchServiceTest" -ForegroundColor Gray
Write-Host "3. 阅读部署指南: RAG_DEPLOYMENT_GUIDE.md" -ForegroundColor Gray
Write-Host ""
Write-Host "常见问题:" -ForegroundColor Yellow
Write-Host "- 如果搜索结果为空，检查数据是否已向量化" -ForegroundColor Gray
Write-Host "- 如果响应慢，检查 Embedding API 是否正常" -ForegroundColor Gray
Write-Host "- 查看详细文档: RAG_MVP_SUMMARY.md" -ForegroundColor Gray
Write-Host ""
