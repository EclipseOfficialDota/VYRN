$ErrorActionPreference='Stop'
$root=Split-Path $PSScriptRoot -Parent
foreach($line in [IO.File]::ReadAllLines((Join-Path $root '.local/dev.env'))){if($line -match '^([A-Z_]+)=(.*)$'){[Environment]::SetEnvironmentVariable($matches[1],$matches[2],'Process')}}
$executable=Join-Path $root 'artifacts/vyrn-core.exe'
$existing=@(Get-Process -Name vyrn-core -ErrorAction SilentlyContinue | Where-Object {$_.Path -eq $executable})
if($existing.Count -gt 0){throw 'Local VYRN Core already runs; inspect and stop its exact executable before restarting'}
$process=Start-Process -FilePath $executable -WorkingDirectory $root -WindowStyle Hidden -RedirectStandardOutput (Join-Path $root '.local/core.log') -RedirectStandardError (Join-Path $root '.local/core-error.log') -PassThru
$ready=$false
for($attempt=0;$attempt -lt 20;$attempt++){
    if($process.HasExited){throw 'Local Core exited during startup; inspect private logs without printing secrets'}
    try {if((Invoke-RestMethod 'http://127.0.0.1:8080/health/ready' -TimeoutSec 2).status -eq 'ready'){$ready=$true;break}}catch{}
    Start-Sleep -Milliseconds 500
}
if(!$ready -or $process.HasExited){throw 'Local Core startup readiness not confirmed'}
[IO.File]::WriteAllText((Join-Path $root '.local/core.pid'),[string]$process.Id)
Write-Output "VYRN Core started on loopback; PID $($process.Id)."
