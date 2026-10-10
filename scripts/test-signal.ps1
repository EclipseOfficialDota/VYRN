param([string]$Serial='emulator-5560',[switch]$Network)
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
$phases=@('signal','signal-restart')
$report='android-signal.json'
try {
if($Network){
    $phases=@('signal-network','signal-network-offline-restart','signal-network-restart');$report='android-signal-network.json'
    & $adb -s $Serial reverse tcp:18443 tcp:18443
    if($LASTEXITCODE -ne 0){throw 'Local TLS forwarding failed'}
    & $adb -s $Serial shell run-as $package mkdir -p files
    if($LASTEXITCODE -ne 0){throw 'App-private test directory unavailable'}
    $settings=@{}
    foreach($line in [IO.File]::ReadAllLines((Join-Path $root '.local/dev.env'))){if($line -match '^([A-Z_]+)=(.*)$'){$settings[$matches[1]]=$matches[2]}}
    $fixture=@{email=('signal-'+[guid]::NewGuid().ToString('N')+'@vyrn.local');password=[Convert]::ToBase64String([Security.Cryptography.RandomNumberGenerator]::GetBytes(32));invite=$settings.VYRN_BOOTSTRAP_INVITE}
    if(!$fixture.invite){throw 'Local bootstrap invite unavailable'}
    $start=[Diagnostics.ProcessStartInfo]::new($adb);$start.UseShellExecute=$false;$start.RedirectStandardInput=$true;$start.RedirectStandardOutput=$true;$start.RedirectStandardError=$true;$start.CreateNoWindow=$true
    foreach($argument in @('-s',$Serial,'shell','-T','run-as',$package,'sh','-c',"'cat > files/vyrn-signal-network-fixture.json'")){$start.ArgumentList.Add($argument)}
    $process=[Diagnostics.Process]::Start($start);$process.StandardInput.Write(($fixture | ConvertTo-Json -Compress));$process.StandardInput.Close()
    $privateOutput=$process.StandardOutput.ReadToEnd();$privateError=$process.StandardError.ReadToEnd();$process.WaitForExit()
    $fixture=$null;$settings=$null
    if($process.ExitCode -ne 0){throw 'Private Signal test fixture transfer failed'}
}
$rounds=@()
foreach($phase in $phases){
    if($Network){if($phase -eq 'signal-network-offline-restart'){& $adb -s $Serial reverse --remove tcp:18443}else{& $adb -s $Serial reverse tcp:18443 tcp:18443};if($LASTEXITCODE -ne 0){throw 'Signal test network mapping failed'}}
    & $adb -s $Serial shell am force-stop $package
    if($LASTEXITCODE -ne 0){throw 'Test process stop failed'}
    $result=(& $adb -s $Serial shell am instrument -w -e phase $phase "$package.test/xyz.eclipseofficial.vyrn.test.LocalIntegrationRunner") -join "`n"
    [IO.File]::WriteAllText((Join-Path $root ".local/android-$phase.log"),$result)
    if($LASTEXITCODE -ne 0 -or $result -notmatch 'vyrn_result=passed'){
        $stage='instrumentation_start';$failure='unknown'
        if($result -match 'stage=([a-zA-Z0-9_]+)'){$stage=$Matches[1]}
        if($result -match 'failure_type=([a-zA-Z0-9_]+)'){$failure=$Matches[1]}
        if($result -match 'failure_status=([0-9]+)'){$failure+=" HTTP $($Matches[1])"}
        throw "Signal integration failed at $stage ($failure)"
    }
    if($result -notmatch 'checks=(\[[^\r\n]*\])'){throw 'Signal report missing'}
    $checks=$Matches[1] | ConvertFrom-Json
    if($result -notmatch 'process_id=([0-9]+)'){throw 'Signal process identifier missing'}
    $processId=[int]$Matches[1]
    if($rounds.Count -gt 0 -and $rounds[0].process_id -eq $processId){throw 'Signal process restart was not exercised'}
    $rounds+=@{phase=$phase;checks=$checks;process_id=$processId;result='passed'}
    Write-Output "PASS: Signal integration phase $phase"
} } finally {
    if($Network){
        & $adb -s $Serial reverse tcp:18443 tcp:18443
        if($LASTEXITCODE -ne 0){throw 'Signal test network mapping restore failed'}
        $cleanup=(& $adb -s $Serial shell am instrument -w -e phase signal-network-cleanup "$package.test/xyz.eclipseofficial.vyrn.test.LocalIntegrationRunner") -join "`n"
        [IO.File]::WriteAllText((Join-Path $root '.local/android-signal-network-cleanup.log'),$cleanup)
        $cleanupFailed=($LASTEXITCODE -ne 0 -or $cleanup -notmatch 'vyrn_result=passed')
        & $adb -s $Serial shell run-as $package rm -f files/vyrn-signal-network-fixture.json files/vyrn-signal-network-state.json
        if($LASTEXITCODE -ne 0){throw 'Private Signal fixture cleanup failed'}
        if($cleanupFailed){throw 'Signal test session cleanup failed'}
    }
}
$fanout='not tested'
if($Network){$fanout='single and two-recipient-device ciphertext delivery; authenticated text codec, atomic encrypted outgoing/native-identity/cache records, exact pending volatile text, native persistence and cold offline recovery; component fixture'}
@{date=[DateTimeOffset]::UtcNow.ToString('o');sha256=(Get-FileHash -LiteralPath $apk).Hash.ToLowerInvariant();api=35;library='org.signal:libsignal-client:0.103.0';rounds=$rounds;private_chat_ui='foreground chat not exercised by this suite; synthetic received/pending constructor hydration, actual native pending-row storage and disconnected cold pending-row restoration; actual native composer control is covered separately by -PrivateComposer';server_fanout=$fanout;network_key_exchange=$Network.IsPresent} | ConvertTo-Json -Depth 6 | Set-Content (Join-Path $root "artifacts/$report") -Encoding utf8
