$ErrorActionPreference='Stop'
$root=Split-Path $PSScriptRoot -Parent
foreach($line in [IO.File]::ReadAllLines((Join-Path $root '.local/dev.env'))){if($line -match '^([A-Z_]+)=(.*)$'){[Environment]::SetEnvironmentVariable($matches[1],$matches[2],'Process')}}
$process=Start-Process -FilePath (Join-Path $root 'artifacts/vyrn-core.exe') -WorkingDirectory $root -WindowStyle Hidden -RedirectStandardOutput (Join-Path $root '.local/core.log') -RedirectStandardError (Join-Path $root '.local/core-error.log') -PassThru
[IO.File]::WriteAllText((Join-Path $root '.local/core.pid'),[string]$process.Id)
Write-Output "VYRN Core started on loopback; PID $($process.Id)."
