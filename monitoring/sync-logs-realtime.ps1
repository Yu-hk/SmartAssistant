# Real-time Log Sync Service for A2A
# This script runs in the background and syncs logs every 10 seconds
#
# To add a new service:
# 1. Add service name to $Services array below
# 2. Ensure service has logging.file.name configured in application.yml
# 3. Create logs directory: New-Item -ItemType Directory -Path "service/logs"
# 4. Restart this script

$ProjectRoot = "D:\workspace\a2a-spring-demo"
$Services = @("a2a-consumer", "router-service", "food-service", "travel-service")
# Example: Add "user-service" to the array above
$SyncInterval = 10  # seconds

Write-Host "Starting real-time log sync service..." -ForegroundColor Green
Write-Host "Sync interval: ${SyncInterval}s" -ForegroundColor Cyan
Write-Host "Press Ctrl+C to stop`n" -ForegroundColor Yellow

while ($true) {
    try {
        foreach ($Service in $Services) {
            $SourceLogDir = "$ProjectRoot\$Service\logs"
            $TargetLogDir = "$ProjectRoot\monitoring\logs"
            
            if (-not (Test-Path $SourceLogDir)) {
                continue
            }
            
            $LogFiles = Get-ChildItem -Path $SourceLogDir -Filter "*.log" -File
            
            foreach ($LogFile in $LogFiles) {
                $TargetFile = "$TargetLogDir\$($LogFile.Name)"
                
                # Only copy if file has changed
                if (-not (Test-Path $TargetFile) -or 
                    (Get-Item $LogFile).LastWriteTime -gt (Get-Item $TargetFile).LastWriteTime) {
                    Copy-Item -Path $LogFile.FullName -Destination $TargetFile -Force
                }
            }
        }
        
        Write-Host "[$(Get-Date -Format 'HH:mm:ss')] Logs synced" -ForegroundColor Gray
    }
    catch {
        Write-Host "Error: $($_.Exception.Message)" -ForegroundColor Red
    }
    
    Start-Sleep -Seconds $SyncInterval
}
