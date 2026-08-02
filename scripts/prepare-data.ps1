[CmdletBinding()]
param(
    [ValidateSet('Native', 'Docker')]
    [string]$Mode = 'Native',
    [string]$HostName = '127.0.0.1',
    [int]$Port = 5433,
    [string]$Database = 'a2a_system',
    [string]$Username = 'postgres',
    [string]$Password = $env:POSTGRES_PASSWORD,
    [string]$PsqlPath = '',
    [string]$ContainerName = 'postgres',
    [switch]$Bulk,
    [switch]$LoadTest,
    [ValidateRange(1, 100000)]
    [int]$LoadUsers = 1000,
    [ValidateRange(1, 20)]
    [int]$SessionsPerUser = 5,
    [ValidateRange(1, 10000)]
    [int]$LoadProducts = 120,
    [ValidateRange(1, 1000)]
    [int]$OrdersPerUser = 20,
    [ValidateRange(1, 5000)]
    [int]$RoutesPerUser = 30,
    [ValidateRange(0, 20)]
    [int]$FeedbackPerUser = 3,
    [ValidateRange(0, 100)]
    [int]$CouponsPerUser = 4,
    [switch]$VerifyOnly
)

$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path -Parent $PSScriptRoot
$schemaFile = Join-Path $repoRoot 'docs/database/init-entities.sql'
$seedFile = Join-Path $repoRoot 'docs/database/init-seed.sql'
$bulkFile = Join-Path $repoRoot 'docs/database/bulk_test_data.sql'
$verifyFile = Join-Path $repoRoot 'docs/database/verify.sql'
$loadTestFile = Join-Path $repoRoot 'docs/database/concurrency_test_data.sql'
$loadTestVerifyFile = Join-Path $repoRoot 'docs/database/verify_concurrency_data.sql'

if ($FeedbackPerUser -gt $SessionsPerUser) {
    throw 'FeedbackPerUser cannot be greater than SessionsPerUser'
}

$loadTestVariables = @(
    "sa_user_count=$LoadUsers"
    "sa_sessions_per_user=$SessionsPerUser"
    "sa_product_count=$LoadProducts"
    "sa_orders_per_user=$OrdersPerUser"
    "sa_routes_per_user=$RoutesPerUser"
    "sa_feedback_per_user=$FeedbackPerUser"
    "sa_coupons_per_user=$CouponsPerUser"
)

function Assert-LastExitCode([string]$Action) {
    if ($LASTEXITCODE -ne 0) {
        throw "$Action failed with exit code $LASTEXITCODE"
    }
}

function Invoke-NativeSql([string]$FilePath, [string[]]$Variables = @()) {
    if ([string]::IsNullOrWhiteSpace($script:resolvedPsql)) {
        throw 'psql executable was not resolved'
    }
    if ([string]::IsNullOrWhiteSpace($Password)) {
        throw 'POSTGRES_PASSWORD is empty; set it or pass -Password'
    }
    $previousPassword = $env:PGPASSWORD
    try {
        $env:PGPASSWORD = $Password
        $variableArgs = @()
        foreach ($variable in $Variables) {
            $variableArgs += @('-v', $variable)
        }
        & $script:resolvedPsql -h $HostName -p $Port -U $Username -d $Database `
            -v ON_ERROR_STOP=1 @variableArgs -f $FilePath
        Assert-LastExitCode "psql $FilePath"
    }
    finally {
        $env:PGPASSWORD = $previousPassword
    }
}

function Invoke-DockerSql([string]$FilePath, [string[]]$Variables = @()) {
    $remotePath = '/tmp/smartassistant-' + [IO.Path]::GetFileName($FilePath)
    & docker cp $FilePath "${ContainerName}:$remotePath"
    Assert-LastExitCode "docker cp $FilePath"
    $variableArgs = @()
    foreach ($variable in $Variables) {
        $variableArgs += @('-v', $variable)
    }
    & docker exec $ContainerName psql -U $Username -d $Database `
        -v ON_ERROR_STOP=1 @variableArgs -f $remotePath
    Assert-LastExitCode "docker exec psql $remotePath"
}

if ($Mode -eq 'Native') {
    if ([string]::IsNullOrWhiteSpace($PsqlPath)) {
        $command = Get-Command psql -ErrorAction SilentlyContinue
        if ($null -eq $command) {
            throw 'psql was not found in PATH; pass -PsqlPath or use -Mode Docker'
        }
        $script:resolvedPsql = $command.Source
    }
    else {
        $script:resolvedPsql = (Resolve-Path -LiteralPath $PsqlPath).Path
    }
    $runner = ${function:Invoke-NativeSql}
}
else {
    if ($null -eq (Get-Command docker -ErrorAction SilentlyContinue)) {
        throw 'docker was not found in PATH'
    }
    $runner = ${function:Invoke-DockerSql}
}

if (-not $VerifyOnly) {
    & $runner $schemaFile
    & $runner $seedFile
    if ($Bulk) {
        & $runner $bulkFile
    }
    if ($LoadTest) {
        & $runner $loadTestFile $loadTestVariables
    }
}
& $runner $verifyFile
if ($LoadTest) {
    & $runner $loadTestVerifyFile $loadTestVariables
}

Write-Host 'SmartAssistant data preparation completed.' -ForegroundColor Green
