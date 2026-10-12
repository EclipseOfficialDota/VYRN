# Real Android networking/Keystore integration; not a claim of full UI acceptance.
param([ValidateSet('emulator-5560','emulator-5562')][string]$Serial='emulator-5560',[ValidateSet('first','mutations','profile','offline','restart','media-ui','media-send-ui','media-send-member-ui','media-upload','media-upload-big','media-upload-cancel','auth-ui','registration-ui','sessions-ui','sessions-network','logout-prepare','logout-offline','logout-ui','onboarding-prepare','onboarding-restart','onboarding-gap','onboarding-recover','onboarding-complete')][string[]]$Phases=@('first','mutations','profile','offline','restart'))
$ErrorActionPreference='Stop'
$root=Split-Path $PSScriptRoot -Parent
$adb=Join-Path $root '.tools/android-sdk/platform-tools/adb.exe'
$package='xyz.eclipseofficial.vyrn.dev'
$avdName=if($Serial -eq 'emulator-5560'){'vyrn_api35'}else{'vyrn_api35_peer'}
$deviceSuffix=if($Serial -eq 'emulator-5560'){''}else{'-peer'}
$avd=(& $adb -s $Serial emu avd name) -join "`n"
if($LASTEXITCODE -ne 0 -or $avd -notmatch "(?m)^$avdName\r?$"){throw 'Only the matching VYRN test emulator is allowed'}
$apk=Join-Path $root 'artifacts/VyrnApp-dev-x86_64-debug-local-tls.apk'
$testApk=Join-Path $root 'artifacts/VyrnApp-dev-debug-androidTest.apk'
$archive=[IO.Compression.ZipFile]::OpenRead($apk)
try{
    $certificate=$archive.GetEntry('res/raw/vyrn_dev_ca.crt')
    if(!$certificate){throw 'Local test CA missing from APK'}
    $stream=$certificate.Open()
    try{$embeddedCa=[Convert]::ToHexString([Security.Cryptography.SHA256]::HashData($stream))}finally{$stream.Dispose()}
    if($embeddedCa -ne (Get-FileHash (Join-Path $root '.local/dev-ca.crt')).Hash){throw 'Local CA changed: rebuild with -LocalTest -Instrumentation after setup-dev-tls.ps1'}
}finally{$archive.Dispose()}
foreach($path in @($apk,$testApk)){
    $output=(& $adb -s $Serial install -r -t $path) -join "`n"
    if($LASTEXITCODE -ne 0 -or $output -notmatch 'Success'){throw 'Test APK installation failed'}
}
# Each suite models a fresh install. Reused emulator state can contain identifier
# mappings from a different disposable database and would invalidate the fixture.
$cleared=(& $adb -s $Serial shell pm clear $package) -join "`n"
if($LASTEXITCODE -ne 0 -or $cleared -notmatch 'Success'){throw 'Test app data reset failed'}
& $adb -s $Serial reverse tcp:18443 tcp:18443
if($LASTEXITCODE -ne 0){throw 'Local TLS port forwarding failed'}
& $adb -s $Serial shell run-as $package mkdir -p files
if($LASTEXITCODE -ne 0){throw 'Debug private directory unavailable'}
$settings=@{}
foreach($line in [IO.File]::ReadAllLines((Join-Path $root '.local/dev.env'))){if($line -match '^([A-Z_]+)=(.*)$'){$settings[$matches[1]]=$matches[2]}}
$fixture=@{email=('android-'+[guid]::NewGuid().ToString('N')+'@vyrn.local');password=[Convert]::ToBase64String([Security.Cryptography.RandomNumberGenerator]::GetBytes(32));invite=$settings.VYRN_BOOTSTRAP_INVITE}
if(!$fixture.invite){throw 'Local bootstrap invite unavailable'}
# Feed test credentials through stdin directly into app-private storage. Never put them in argv or output.
$start=[Diagnostics.ProcessStartInfo]::new($adb)
$start.UseShellExecute=$false
$start.RedirectStandardInput=$true
$start.RedirectStandardOutput=$true
$start.RedirectStandardError=$true
$start.CreateNoWindow=$true
foreach($argument in @('-s',$Serial,'shell','-T','run-as',$package,'sh','-c',"'cat > files/vyrn-test-fixture.json'")){$start.ArgumentList.Add($argument)}
$process=[Diagnostics.Process]::Start($start)
$process.StandardInput.Write(($fixture | ConvertTo-Json -Compress))
$process.StandardInput.Close()
$privateOutput=$process.StandardOutput.ReadToEnd()
$privateError=$process.StandardError.ReadToEnd()
$process.WaitForExit()
if($process.ExitCode -ne 0){throw 'Private test fixture transfer failed'}
$fixture=$null
$rounds=@()
$script:liveTestProxy=$null
function Restore-TestProxy {
    if(!$script:liveTestProxy -or (Get-Process -Id $script:liveTestProxy.pid -ErrorAction SilentlyContinue)){return}
    $proxy=Start-Process -FilePath $script:liveTestProxy.exe -ArgumentList @('-upstream',$script:liveTestProxy.upstream) -WorkingDirectory $root -WindowStyle Hidden -RedirectStandardOutput (Join-Path $root '.local/devtls-native.log') -RedirectStandardError (Join-Path $root '.local/devtls-native-error.log') -PassThru
    $script:liveTestProxy.pid=$proxy.Id
    [IO.File]::WriteAllText((Join-Path $root '.local/devtls-native.pid'),[string]$proxy.Id)
}
function Run-Instrumentation([string]$Phase){
    if($Phase -ne 'sessions-network'){return ((& $adb -s $Serial shell am instrument -w -e phase $Phase "$package.test/xyz.eclipseofficial.vyrn.test.LocalIntegrationRunner") -join "`n")}
    if(!$script:liveTestProxy){
        $exe=Join-Path $root '.local/devtls-native.exe';$proxyId=[int][IO.File]::ReadAllText((Join-Path $root '.local/devtls-native.pid'));$proxy=Get-Process -Id $proxyId
        $command=(Get-CimInstance Win32_Process -Filter "ProcessId=$proxyId").CommandLine
        if($proxy.Path -ne $exe -or $command -notmatch '-upstream\s+(http://(?:127\.[0-9.]+|172\.(?:1[6-9]|2[0-9]|3[01])\.[0-9.]+):8080)'){throw 'Live network suite requires the existing dedicated native local TLS proxy'}
        $script:liveTestProxy=@{exe=$exe;pid=$proxyId;upstream=$matches[1]}
    }
    & $adb -s $Serial shell run-as $package rm -f files/vyrn-test-network-request.txt files/vyrn-test-network-ack.txt
    $launch=[Diagnostics.ProcessStartInfo]::new($adb);$launch.UseShellExecute=$false;$launch.RedirectStandardOutput=$true;$launch.RedirectStandardError=$true;$launch.CreateNoWindow=$true
    foreach($argument in @('-s',$Serial,'shell','am','instrument','-w','-e','phase',$Phase,"$package.test/xyz.eclipseofficial.vyrn.test.LocalIntegrationRunner")){$launch.ArgumentList.Add($argument)}
    $runner=[Diagnostics.Process]::Start($launch);$stdout=$runner.StandardOutput.ReadToEndAsync();$stderr=$runner.StandardError.ReadToEndAsync();$handled='';$deadline=[DateTimeOffset]::UtcNow.AddMinutes(4)
    while(!$runner.WaitForExit(500)){
        if([DateTimeOffset]::UtcNow -gt $deadline){& $adb -s $Serial shell am force-stop $package; if(!$runner.WaitForExit(10000)){$runner.Kill()};throw 'Session network instrumentation deadline exceeded'}
        $step=((& $adb -s $Serial shell run-as $package cat files/vyrn-test-network-request.txt 2>$null) -join '').Trim()
        if(!$step -or $step -eq $handled){continue}
        if($step -notin @('offline-load','online-retry','offline-revoke','online-confirm')){throw 'Unexpected test network step'}
        if($step.StartsWith('offline-')){
            $proxy=Get-Process -Id $script:liveTestProxy.pid
            if($proxy.Path -ne $script:liveTestProxy.exe){throw 'Unexpected local TLS proxy process'}
            Stop-Process -Id $proxy.Id;$proxy.WaitForExit()
            & $adb -s $Serial reverse --remove tcp:18443 | Out-Null
        }else{Restore-TestProxy; & $adb -s $Serial reverse tcp:18443 tcp:18443 | Out-Null}
        if($LASTEXITCODE -ne 0){throw 'Live test network transition failed'}
        & $adb -s $Serial shell run-as $package sh -c "'printf $step > files/vyrn-test-network-ack.txt'"
        if($LASTEXITCODE -ne 0){throw 'Test network acknowledgement failed'}
        $handled=$step
    }
    return ($stdout.GetAwaiter().GetResult()+$stderr.GetAwaiter().GetResult())
}
try {
    foreach($phase in $Phases){
        & $adb -s $Serial shell am force-stop $package
        # The full foreground suite shares the production 300-request/minute user
        # limit with realtime reconciliation. Start recovery in a fresh window.
        if($phase -in @('mutations','profile','restart')){Write-Output "Waiting 60 seconds for the API rate-limit window before $phase tests.";Start-Sleep -Seconds 60}
        if($phase -in @('offline','logout-offline','onboarding-restart')){& $adb -s $Serial reverse --remove tcp:18443}else{& $adb -s $Serial reverse tcp:18443 tcp:18443}
        if($LASTEXITCODE -ne 0){throw 'Test network transition failed'}
        $result=Run-Instrumentation $phase
        $attachRetried=$false
        # Android can finish an old task removal after pm clear and kill the new
        # process before attach. Retry only this confirmed pre-runner failure.
        if($result -match '(?m)^INSTRUMENTATION_RESULT: shortMsg=failed to attach\r?$'){
            [IO.File]::WriteAllText((Join-Path $root ".local/android-integration-$phase$deviceSuffix-attach-failure.log"),$result)
            & $adb -s $Serial shell am force-stop $package
            if($LASTEXITCODE -ne 0){throw 'Failed-attach cleanup failed'}
            Write-Output "Retrying pre-runner Android attach once for $phase."
            $attachRetried=$true
            $result=Run-Instrumentation $phase
        }
        [IO.File]::WriteAllText((Join-Path $root ".local/android-integration-$phase$deviceSuffix.log"),$result)
        if($LASTEXITCODE -ne 0 -or $result -notmatch 'vyrn_result=passed'){
            $stage='instrumentation_start';$failure='unknown'
            if($result -match 'stage=([a-zA-Z0-9_]+)'){$stage=$Matches[1]}
            if($result -match 'failure_type=([a-zA-Z0-9_]+)'){$failure=$Matches[1]}
            if($result -match 'failure_location=([^\r\n]*)'){Write-Output ('Test failure frames: '+$Matches[1])}
            if($result -match 'failure_status=([0-9]+)'){$failure+=" HTTP $($Matches[1])"}
            throw "Android integration failed at $stage ($failure)"
        }
        if($result -notmatch 'checks=(\[[^\r\n]*\])'){throw 'Instrumentation report missing'}
        $checks=$Matches[1] | ConvertFrom-Json
        $rounds+=@{phase=$phase;checks=$checks;result='passed';startup_attach_retry=$attachRetried}
        Write-Output "PASS: Android integration phase $phase"
    }
    $report=if(@($Phases | Where-Object {$_ -notlike 'onboarding-*'}).Count -eq 0){'artifacts/android-onboarding-ui.json'}elseif(@($Phases | Where-Object {$_ -notlike 'logout-*'}).Count -eq 0){'artifacts/android-logout-ui.json'}elseif($Phases.Count -eq 1 -and $Phases[0] -in @('media-ui','media-send-ui','media-send-member-ui','media-upload','media-upload-big','media-upload-cancel','auth-ui','registration-ui','sessions-ui','sessions-network')){"artifacts/android-$($Phases[0]).json"}else{'artifacts/android-integration.json'}
    if($deviceSuffix){$report=$report.Replace('.json',"$deviceSuffix.json")}
    @{date=[DateTimeOffset]::UtcNow.ToString('o');apk=[IO.Path]::GetFileName($apk);sha256=(Get-FileHash $apk).Hash.ToLowerInvariant();device=$avdName;serial=$Serial;api=35;transport='HTTPS with scoped local CA';rounds=$rounds;full_ui_e2e='not tested'} | ConvertTo-Json -Depth 6 | Set-Content (Join-Path $root $report) -Encoding utf8
} finally {
    Restore-TestProxy
    & $adb -s $Serial reverse tcp:18443 tcp:18443
    & $adb -s $Serial shell run-as $package rm -f files/vyrn-test-fixture.json files/vyrn-test-onboarding.json files/vyrn-test-network-request.txt files/vyrn-test-network-ack.txt
}
