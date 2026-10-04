param([string]$Serial='emulator-5560')
$ErrorActionPreference='Stop'
$root=Split-Path $PSScriptRoot -Parent
$adb=Join-Path $root '.tools/android-sdk/platform-tools/adb.exe'
$package='xyz.eclipseofficial.vyrn.dev'
$avd=(& $adb -s $Serial emu avd name) -join "`n"
if($LASTEXITCODE -ne 0 -or $avd -notmatch '(?m)^vyrn_api35\r?$'){throw 'Only the VYRN test emulator is allowed'}
$apk=Join-Path $root 'artifacts/VyrnApp-dev-x86_64-debug-local-tls.apk'
$testApk=Join-Path $root 'artifacts/VyrnApp-dev-debug-androidTest.apk'
foreach($path in @($apk,$testApk)){
    $output=(& $adb -s $Serial install -r -t $path) -join "`n"
    if($LASTEXITCODE -ne 0 -or $output -notmatch 'Success'){throw 'Signal test APK installation failed'}
}
$rounds=@()
foreach($phase in @('signal','signal-restart')){
    & $adb -s $Serial shell am force-stop $package
    if($LASTEXITCODE -ne 0){throw 'Test process stop failed'}
    $result=(& $adb -s $Serial shell am instrument -w -e phase $phase "$package.test/xyz.eclipseofficial.vyrn.test.LocalIntegrationRunner") -join "`n"
    [IO.File]::WriteAllText((Join-Path $root ".local/android-$phase.log"),$result)
    if($LASTEXITCODE -ne 0 -or $result -notmatch 'vyrn_result=passed'){
        $stage='instrumentation_start';$failure='unknown'
        if($result -match 'stage=([a-zA-Z0-9_]+)'){$stage=$Matches[1]}
        if($result -match 'failure_type=([a-zA-Z0-9_]+)'){$failure=$Matches[1]}
        throw "Signal integration failed at $stage ($failure)"
    }
    if($result -notmatch 'checks=(\[[^\r\n]*\])'){throw 'Signal report missing'}
    $checks=$Matches[1] | ConvertFrom-Json
    if($result -notmatch 'process_id=([0-9]+)'){throw 'Signal process identifier missing'}
    $processId=[int]$Matches[1]
    if($rounds.Count -gt 0 -and $rounds[0].process_id -eq $processId){throw 'Signal process restart was not exercised'}
    $rounds+=@{phase=$phase;checks=$checks;process_id=$processId;result='passed'}
    Write-Output "PASS: Signal integration phase $phase"
}
@{date=[DateTimeOffset]::UtcNow.ToString('o');sha256=(Get-FileHash -LiteralPath $apk).Hash.ToLowerInvariant();api=35;library='org.signal:libsignal-client:0.103.0';rounds=$rounds;private_chat_ui='not implemented';server_fanout='not tested'} | ConvertTo-Json -Depth 6 | Set-Content (Join-Path $root 'artifacts/android-signal.json') -Encoding utf8
