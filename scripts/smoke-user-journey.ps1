<#
.SYNOPSIS
Runs the authenticated SmartAssistant user journey against a deployed or local gateway.

.DESCRIPTION
Covers authentication, identity projection, product recommendation, order-list query,
session history/detail, cancellation propagation, session close and logout. It never creates
or pays for an order. Pass an existing test account, or omit Username to create an isolated one.

.EXAMPLE
./scripts/smoke-user-journey.ps1 -BaseUrl http://localhost:8081

.EXAMPLE
./scripts/smoke-user-journey.ps1 -BaseUrl https://xiaoyuai.cloud -Username smoke_user -Password '***'
#>
[CmdletBinding()]
param(
    [string]$BaseUrl = 'http://localhost:8081',
    [string]$Username,
    [string]$Password,
    [string]$Email,
    [int]$TimeoutSec = 180
)

$ErrorActionPreference = 'Stop'
$BaseUrl = $BaseUrl.TrimEnd('/')
$createdAccount = [string]::IsNullOrWhiteSpace($Username)
$suffix = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
if ($createdAccount) {
    $Username = "smoke_$suffix"
    $Password = "Smoke!${suffix}x"
    $Email = "$Username@example.test"
}
if ([string]::IsNullOrWhiteSpace($Password)) {
    throw 'Password is required when Username is supplied.'
}

function Invoke-JsonApi {
    param(
        [Parameter(Mandatory)] [string]$Path,
        [Parameter(Mandatory)] [string]$Method,
        [hashtable]$Headers = @{},
        [object]$Body
    )
    $request = @{
        Uri = "$BaseUrl$Path"
        Method = $Method
        Headers = $Headers
        TimeoutSec = $TimeoutSec
    }
    if ($null -ne $Body) {
        $request.ContentType = 'application/json; charset=utf-8'
        $request.Body = $Body | ConvertTo-Json -Depth 12 -Compress
    }
    Invoke-RestMethod @request
}

function Assert-True {
    param([bool]$Condition, [string]$Message)
    if (-not $Condition) { throw $Message }
}

function Get-EnvelopeData {
    param([object]$Envelope, [string]$Step)
    Assert-True ($null -ne $Envelope) "$Step returned no response."
    Assert-True ([int]$Envelope.code -eq 0) "$Step failed: $($Envelope.message)"
    $Envelope.data
}

$token = $null
$refreshToken = $null
$sessionId = [guid]::NewGuid().ToString()
$steps = [System.Collections.Generic.List[object]]::new()
$startedAt = Get-Date

try {
    if ($createdAccount) {
        $auth = Get-EnvelopeData (Invoke-JsonApi -Path '/api/auth/register' -Method POST -Body @{
            username = $Username
            password = $Password
            email = $Email
        }) 'register'
        $steps.Add([ordered]@{ step = 'register'; passed = $true })
    } else {
        $auth = Get-EnvelopeData (Invoke-JsonApi -Path '/api/auth/login' -Method POST -Body @{
            username = $Username
            password = $Password
        }) 'login'
        $steps.Add([ordered]@{ step = 'login'; passed = $true })
    }
    $token = [string]$auth.token
    $refreshToken = [string]$auth.refreshToken
    Assert-True (-not [string]::IsNullOrWhiteSpace($token)) 'Authentication returned no access token.'
    $headers = @{ Authorization = "Bearer $token" }

    $profile = Get-EnvelopeData (Invoke-JsonApi -Path '/api/auth/me' -Method GET -Headers $headers) 'current user'
    Assert-True ([string]$profile.username -eq $Username) 'Authenticated profile does not match the login account.'
    Assert-True ([long]$profile.userId -gt 0) 'Authenticated profile has no numeric user id.'
    $steps.Add([ordered]@{ step = 'identity'; passed = $true; userId = $profile.userId })

    $productRequestId = [guid]::NewGuid().ToString('N')
    $product = Get-EnvelopeData (Invoke-JsonApi -Path '/api/math/chat' -Method POST -Headers $headers -Body @{
        message = '推荐1000元以内的热门耳机，只看有货'
        sessionId = $sessionId
        requestId = $productRequestId
    }) 'product recommendation'
    Assert-True (-not [string]::IsNullOrWhiteSpace([string]$product.reply)) 'Product recommendation is empty.'
    Assert-True ([string]$product.agentName -match '^(?i:product(?:_agent)?)$') `
        "Product recommendation was routed to unexpected agent '$($product.agentName)'."
    Assert-True (-not ([string]$product.reply -match '(?i)discover_tools|executeScript|getHotNews')) `
        'Product response leaked an unavailable internal tool name.'
    Assert-True (-not ([string]$product.reply -match '暂时无法可靠|未找到.*相关信息|NO_RELEVANT_DATA|稍后重试')) `
        "Product recommendation returned a disguised failure: $($product.reply)"
    $steps.Add([ordered]@{
        step = 'product-recommendation'
        passed = $true
        requestId = $productRequestId
        workflowStatus = $product.workflowStatus
    })

    $orderRequestId = [guid]::NewGuid().ToString('N')
    $orders = Get-EnvelopeData (Invoke-JsonApi -Path '/api/math/chat' -Method POST -Headers $headers -Body @{
        message = '查看我的订单列表'
        sessionId = $sessionId
        requestId = $orderRequestId
    }) 'order list'
    Assert-True (-not [string]::IsNullOrWhiteSpace([string]$orders.reply)) 'Order-list response is empty.'
    Assert-True ([string]$orders.agentName -match '^(?i:order(?:_agent)?)$') `
        "Order-list query was routed to unexpected agent '$($orders.agentName)'."
    Assert-True (-not ([string]$orders.reply -match `
        'INSUFFICIENT_EVIDENCE|无证据|无法识别|无法回答|暂时无法|稍后重试|处理失败')) `
        "Order-list query returned a disguised failure: $($orders.reply)"
    Assert-True ([string]$orders.reply -match `
        '当前没有查询到.*订单|订单列表|未找到.*订单|暂无.*订单') `
        "Order-list response did not contain an order result: $($orders.reply)"
    $steps.Add([ordered]@{
        step = 'order-list'
        passed = $true
        requestId = $orderRequestId
        workflowStatus = $orders.workflowStatus
    })

    $sessions = Invoke-JsonApi -Path '/api/sessions' -Method GET -Headers $headers
    $sessionList = if ($sessions -is [System.Array]) { $sessions } elseif ($null -ne $sessions.sessions) {
        $sessions.sessions
    } else { @() }
    Assert-True ($sessionList.Count -gt 0) 'Session history did not contain the completed conversation.'
    $matchedSession = $sessionList | Where-Object {
        ([string]$_.id -eq $sessionId) -or ([string]$_.sessionId -eq $sessionId) -or
        ([string]$_.session_id -eq $sessionId)
    } | Select-Object -First 1
    Assert-True ($null -ne $matchedSession) 'The current session was not returned by session history.'
    $detail = Invoke-JsonApi -Path "/api/sessions/$sessionId" -Method GET -Headers $headers
    $messages = if ($null -ne $detail.messages) { @($detail.messages) } else { @() }
    Assert-True ($messages.Count -ge 2) 'Session detail did not preserve both user and assistant messages.'
    $steps.Add([ordered]@{ step = 'session-history'; passed = $true; messages = $messages.Count })

    $cancelRequestId = [guid]::NewGuid().ToString('N')
    Invoke-JsonApi -Path '/api/math/stream/chat/cancel' -Method POST -Headers $headers -Body @{
        requestId = $cancelRequestId
    } | Out-Null
    $steps.Add([ordered]@{ step = 'cancel-propagation'; passed = $true; requestId = $cancelRequestId })

    $closed = Invoke-JsonApi -Path "/api/sessions/$sessionId/close" -Method POST -Headers $headers -Body @{}
    Assert-True ([bool]$closed.success) 'Session close did not report success.'
    $steps.Add([ordered]@{ step = 'close-session'; passed = $true })
}
finally {
    if (-not [string]::IsNullOrWhiteSpace($token)) {
        try {
            Invoke-JsonApi -Path '/api/auth/logout' -Method POST -Headers @{
                Authorization = "Bearer $token"
            } -Body @{ refreshToken = $refreshToken } | Out-Null
            $steps.Add([ordered]@{ step = 'logout'; passed = $true })
        } catch {
            $steps.Add([ordered]@{ step = 'logout'; passed = $false; error = $_.Exception.Message })
        }
    }
}

[ordered]@{
    passed = $true
    baseUrl = $BaseUrl
    username = $Username
    generatedAccount = $createdAccount
    sessionId = $sessionId
    durationMs = [long]((Get-Date) - $startedAt).TotalMilliseconds
    steps = $steps
} | ConvertTo-Json -Depth 8
