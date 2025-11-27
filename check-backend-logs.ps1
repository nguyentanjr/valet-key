# Script để kiểm tra backend logs
Write-Host "🔍 Checking backend logs for API requests..." -ForegroundColor Cyan
Write-Host ""

# Check backend 1
Write-Host "📊 BACKEND 1 (valet-key-backend-1):" -ForegroundColor Yellow
docker logs --tail=100 valet-key-backend-1 | Select-String -Pattern "GET /api" | Select-Object -Last 10

Write-Host ""
Write-Host "📊 BACKEND 2 (valet-key-backend-2):" -ForegroundColor Yellow
docker logs --tail=100 valet-key-backend-2 | Select-String -Pattern "GET /api" | Select-Object -Last 10

Write-Host ""
Write-Host "💡 Nếu không thấy logs, có thể backend chưa được rebuild với logging mới." -ForegroundColor Green
Write-Host "   Chạy: mvn clean package -DskipTests" -ForegroundColor Green
Write-Host "   Sau đó: docker-compose restart backend1 backend2" -ForegroundColor Green

