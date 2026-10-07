# Prerequisite for two-client messaging; this does not test message delivery.
$ErrorActionPreference='Stop'
$root=Split-Path $PSScriptRoot -Parent
$adb=Join-Path $root '.tools/android-sdk/platform-tools/adb.exe'
$devices=@()
foreach($serial in @('emulator-5560','emulator-5562')){
    & (Join-Path $PSScriptRoot 'start-emulator.ps1') -Serial $serial
    if($LASTEXITCODE -ne 0){throw 'VYRN emulator startup failed'}
    $identity=((& $adb -s $serial shell settings get secure android_id) -join '').Trim()
    if($LASTEXITCODE -ne 0 -or $identity -notmatch '^[0-9a-f]{16}$'){throw 'Emulator device identity unavailable'}
    if($devices.android_id -contains $identity){throw 'Two independent Android device identities are required'}
    $started=[DateTimeOffset]::UtcNow
    & (Join-Path $PSScriptRoot 'test-android-integration.ps1') -Serial $serial -Phases registration-ui
    $suffix=if($serial -eq 'emulator-5560'){''}else{'-peer'}
    $reportPath="artifacts/android-registration-ui$suffix.json"
    $report=Get-Content (Join-Path $root $reportPath) -Raw | ConvertFrom-Json
    if([DateTimeOffset]::Parse($report.date) -lt $started -or $report.serial -ne $serial -or $report.rounds.Count -ne 1 -or $report.rounds[0].result -ne 'passed'){throw 'Fresh matching registration evidence required'}
    $devices+=@{serial=$serial;android_id=$identity;device=$report.device;report=$reportPath;checks=$report.rounds[0].checks.Count;apk_sha256=$report.sha256}
}
if($devices[0].apk_sha256 -ne $devices[1].apk_sha256){throw 'Both clients must use the same APK'}
@{date=[DateTimeOffset]::UtcNow.ToString('o');result='passed';devices=$devices;scope='Independent native invite registration and recovery acknowledgement';messaging='not tested';physical_devices='not tested'} | ConvertTo-Json -Depth 5 | Set-Content (Join-Path $root 'artifacts/android-two-client-registration.json') -Encoding utf8
Write-Output 'PASS: independent native registration on both VYRN Android clients.'
