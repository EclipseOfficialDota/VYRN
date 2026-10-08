$ErrorActionPreference='Stop'
$adb=Join-Path $PSHOME 'pwsh.exe'
. (Join-Path $PSScriptRoot 'android-test-tools.ps1')
$result=Invoke-TestAdb -Arguments @('-NoProfile','-Command','[Console]::Write([Console]::In.ReadToEnd())') -InputText 'fixture-stdin'
if($result.code -ne 0 -or $result.output -ne 'fixture-stdin' -or $result.timed_out){throw 'Private stdin transfer failed'}
$result=Invoke-TestAdb -Arguments @('-NoProfile','-Command','exit 7')
if($result.code -ne 7 -or $result.timed_out){throw 'Exit code was not preserved'}
$timer=[Diagnostics.Stopwatch]::StartNew()
$result=Invoke-TestAdb -Arguments @('-NoProfile','-Command','Start-Sleep -Seconds 20') -TimeoutMs 100
if(!$result.timed_out -or $result.code -ne -1 -or $timer.Elapsed.TotalSeconds -gt 6){throw 'Owned helper deadline failed'}
Write-Output 'PASS: private stdin, exit code and bounded owned-process termination.'
