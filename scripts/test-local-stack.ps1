# Uses only the named local smoke stack. Never targets the VPS or production DB.
$ErrorActionPreference='Stop'
$root=Split-Path $PSScriptRoot -Parent
$settings=@{}
foreach($line in [IO.File]::ReadAllLines((Join-Path $root '.local/dev.env'))){if($line -match '^([A-Z_]+)=(.*)$'){$settings[$matches[1]]=$matches[2]}}
$base='http://127.0.0.1:18080'
function Request([string]$Method,[string]$Path,$Body,$Auth){
    $args=@{Uri=$base+$Path;Method=$Method;TimeoutSec=20;Headers=@{};ErrorAction='Stop'}
    if($Auth){$args.Headers.Authorization='Bearer '+$Auth}
    if($null -ne $Body){$args.Body=ConvertTo-Json -InputObject $Body -Depth 10 -Compress;$args.ContentType='application/json'}
    try { Invoke-RestMethod @args } catch { throw "Local stack request failed: $Method $Path" }
}
$devicePath=Join-Path $root '.local/smoke-device-id'
if(!(Test-Path -LiteralPath $devicePath)){[IO.File]::WriteAllText($devicePath,[guid]::NewGuid().ToString())}
$session=Request 'POST' '/v1/auth/login' @{email=$settings.VYRN_BOOTSTRAP_EMAIL;password=$settings.VYRN_BOOTSTRAP_PASSWORD;device_id=[IO.File]::ReadAllText($devicePath);device_name='Compose restart smoke'} $null
$auth=$session.access_token
try {
    $group=Request 'POST' '/v1/conversations' @{kind='group';title='Local restart check';members=@()} $auth
    $body=@{client_id=[guid]::NewGuid().ToString();encrypted=$false;payload=@{text='Persistent restart test'}}
    $path='/v1/conversations/'+$group.id+'/messages'
    $message=Request 'POST' $path $body $auth
    Push-Location $root
    try {
        & docker compose --project-name vyrn-local --env-file .local/dev.env -f deployment/compose.yaml -f deployment/compose.local.yaml restart postgres core
        if($LASTEXITCODE -ne 0){throw 'Local container restart failed'}
    } finally { Pop-Location }
    $ready=$false
    for($attempt=0;$attempt -lt 40;$attempt++){
        try { if((Request 'GET' '/health/ready' $null $null).status -eq 'ready'){$ready=$true;break} } catch {}
        Start-Sleep -Seconds 1
    }
    if(!$ready){throw 'Local stack did not recover'}
    $messages=@(Request 'GET' $path $null $auth)
    if($messages.Count -ne 1 -or $messages[0].id -ne $message.id -or $messages[0].payload.text -ne 'Persistent restart test'){throw 'Message did not survive restart'}
    $retry=Request 'POST' $path $body $auth
    if($retry.id -ne $message.id -or @(Request 'GET' $path $null $auth).Count -ne 1){throw 'Idempotency failed across restart'}
    $sync=Request 'GET' '/v1/sync?cursor=0' $null $auth
    if(@($sync.events | Where-Object {$_.body.message_id -eq $message.id}).Count -ne 1){throw 'Durable sync event missing after restart'}
    Write-Output 'PASS: Core/PostgreSQL restart, persistent session/message/event, and idempotent retry.'
} finally {
    try { Request 'POST' '/v1/auth/logout' @{} $auth | Out-Null } catch {}
}
