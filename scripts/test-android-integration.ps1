# Real Android networking/Keystore integration; not a claim of full UI acceptance.
param([ValidateSet('emulator-5560','emulator-5562')][string]$Serial='emulator-5560',[ValidateSet('first','mutations','profile','offline','restart','media-ui','media-photo-ui','media-voice-ui','media-voice-send-ui','media-voice-send-member-ui','media-voice-send-offline-ui','media-voice-cancel-chat-ui','media-voice-chat-cold-prepare','media-voice-chat-cold-recover','media-send-ui','media-photo-send-ui','media-photo-send-part-ack-ui','media-photo-send-mid-offline-ui','media-photo-send-member-ui','media-photo-send-offline-ui','media-photo-cancel-chat-ui','media-send-member-ui','media-send-offline-ui','media-send-part-ack-ui','media-cancel-prepare','media-cancel-recover','media-cancel-chat-ui','media-upload','media-voice-codec','media-voice-upload','media-photo-upload','media-photo-upload-cold-prepare','media-photo-upload-cold-recover','media-photo-chat-cold-prepare','media-photo-chat-cold-recover','media-upload-big','media-upload-cancel','auth-ui','registration-ui','sessions-ui','sessions-network','logout-prepare','logout-offline','logout-ui','onboarding-prepare','onboarding-restart','onboarding-gap','onboarding-recover','onboarding-complete')][string[]]$Phases=@('first','mutations','profile','offline','restart'))
$ErrorActionPreference='Stop'
if(@($Phases|Where-Object {$_ -in @('media-voice-send-ui','media-voice-send-member-ui','media-voice-send-offline-ui','media-voice-cancel-chat-ui','media-voice-chat-cold-prepare','media-voice-chat-cold-recover')}).Count){
    $owned=Get-CimInstance Win32_Process|Where-Object {$_.Name -match '^qemu-system' -and $_.CommandLine -match '-avd vyrn_api35' -and $_.CommandLine -match '-port 5560' -and $_.CommandLine -match '-no-audio'}
    if($Serial -ne 'emulator-5560' -or @($owned).Count -ne 1){throw 'Voice recording acceptance requires the owned no-host-audio emulator'}
}
if(@($Phases|Where-Object {$_ -like 'media-*-cold-*'}).Count -and ($Phases -join ',') -notin @('media-photo-upload-cold-prepare,media-photo-upload-cold-recover','media-photo-chat-cold-prepare,media-photo-chat-cold-recover','media-voice-chat-cold-prepare,media-voice-chat-cold-recover')){throw 'Cold media requires an exact ordered prepare/recover pair'}
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
# Finish the old chat/permission task before clearing app data. A late task removal
# can otherwise kill the next instrumentation process before it attaches.
$vyrnLauncherResult=(& $adb -s $Serial shell am start -W -a android.intent.action.MAIN -c android.intent.category.HOME) -join "`n"
if($LASTEXITCODE -ne 0 -or $vyrnLauncherResult -notmatch 'Status: ok'){throw 'Test launcher did not become ready'}
& $adb -s $Serial shell am force-stop $package
if($LASTEXITCODE -ne 0){throw 'Old test app could not stop'}
$taskDeadline=[DateTimeOffset]::UtcNow.AddSeconds(20)
$removedPermissionTasks=@{}
do {
    $oldActivities=(& $adb -s $Serial shell dumpsys activity activities) -join "`n"
    if($LASTEXITCODE -ne 0){throw 'Cannot inspect old test tasks'}
    $taskGone=$oldActivities -notmatch ('(?m)^\s*(?:\* Task\{|[^\r\n]*Hist #)[^\r\n]*'+[regex]::Escape($package))
    if($taskGone){break}
    # A system permission activity can outlive its stopped caller. Remove only
    # its verified single-task root, never the permission-controller process.
    $vyrnAppPid=(& $adb -s $Serial shell pidof $package) -join ''
    if(!$vyrnAppPid.Trim()){
        $rootTasks=(& $adb -s $Serial shell am stack list) -join "`n"
        if($LASTEXITCODE -ne 0){throw 'Cannot inspect orphaned permission task'}
        foreach($block in [regex]::Split($rootTasks,'(?m)(?=^RootTask id=)')){
            if($block -notmatch '^RootTask id=([0-9]+)\b'){continue}
            $permissionRoot=$Matches[1]
            if($removedPermissionTasks.ContainsKey($permissionRoot)){continue}
            if([regex]::Matches($block,'(?m)^\s*taskId=').Count -ne 1 -or $block -notmatch ('(?m)^\s*taskId='+$permissionRoot+': '+[regex]::Escape($package)+'/org\.telegram\.ui\.LaunchActivity\b') -or $block -notmatch 'topActivity=ComponentInfo\{com\.android\.permissioncontroller/com\.android\.permissioncontroller\.permission\.ui\.GrantPermissionsActivity\}'){continue}
            & $adb -s $Serial shell am stack remove $permissionRoot
            if($LASTEXITCODE -ne 0){throw 'Owned orphan permission task could not finish'}
            $removedPermissionTasks[$permissionRoot]=$true
        }
    }
    Start-Sleep -Milliseconds 200
} while([DateTimeOffset]::UtcNow -lt $taskDeadline)
if(!$taskGone){throw 'Old test task still exists; fresh instrumentation refused'}
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
$partFault=$null
$partState=$null
if(@($Phases | Where-Object {$_ -in @('media-send-part-ack-ui','media-photo-send-part-ack-ui','media-photo-send-mid-offline-ui','media-photo-upload-cold-prepare','media-photo-chat-cold-prepare','media-voice-chat-cold-prepare')}).Count){
    if($Phases.Count -ne 1 -and ($Phases -join ',') -notin @('media-photo-upload-cold-prepare,media-photo-upload-cold-recover','media-photo-chat-cold-prepare,media-photo-chat-cold-recover','media-voice-chat-cold-prepare,media-voice-chat-cold-recover')){throw 'Upload acknowledgement fault must be an isolated phase or exact cold pair'}
    $env:GOTOOLCHAIN='local';$env:GOCACHE=Join-Path $root '.local/go-build'
    $taskGo=Join-Path $root '.local/go-mod/golang.org/toolchain@v0.0.1-go1.26.8.windows-amd64/bin/go.exe'
    & $taskGo build -o (Join-Path $root '.local/upload-ack-proxy.exe') (Join-Path $root 'scripts/lost-ack-proxy/main.go')
    if($LASTEXITCODE -ne 0){throw 'Upload fault helper build failed'}
}
function Restore-TestProxy {
    if(!$script:liveTestProxy -or (Get-Process -Id $script:liveTestProxy.pid -ErrorAction SilentlyContinue)){return}
    $proxyInput=Join-Path $root '.local/devtls-stdin-empty.txt';[IO.File]::WriteAllText($proxyInput,'')
    $proxy=Start-Process -FilePath $script:liveTestProxy.exe -ArgumentList @('-upstream',$script:liveTestProxy.upstream) -WorkingDirectory $root -WindowStyle Hidden -RedirectStandardInput $proxyInput -RedirectStandardOutput (Join-Path $root '.local/devtls-native.log') -RedirectStandardError (Join-Path $root '.local/devtls-native-error.log') -PassThru
    $script:liveTestProxy.pid=$proxy.Id
    [IO.File]::WriteAllText((Join-Path $root '.local/devtls-native.pid'),[string]$proxy.Id)
}
function Run-Instrumentation([string]$Phase){
    if($Phase -notin @('media-voice-send-offline-ui','media-voice-cancel-chat-ui','media-photo-send-mid-offline-ui','sessions-network','media-send-offline-ui','media-photo-send-offline-ui','media-photo-cancel-chat-ui','media-cancel-prepare','media-cancel-recover','media-cancel-chat-ui')){return ((& $adb -s $Serial shell am instrument -w -e phase $Phase "$package.test/xyz.eclipseofficial.vyrn.test.LocalIntegrationRunner") -join "`n")}
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
        if([DateTimeOffset]::UtcNow -gt $deadline){& $adb -s $Serial shell am force-stop $package; if(!$runner.WaitForExit(10000)){$runner.Kill()};throw 'Network instrumentation deadline exceeded'}
        $step=((& $adb -s $Serial shell run-as $package cat files/vyrn-test-network-request.txt 2>$null) -join '').Trim()
        if(!$step -or $step -eq $handled){continue}
        if($step -notin @('offline-load','online-retry','offline-revoke','online-confirm')){throw 'Unexpected test network step'}
        if($step.StartsWith('offline-')){
            $proxy=Get-Process -Id $script:liveTestProxy.pid
            if($proxy.Path -ne $script:liveTestProxy.exe){throw 'Unexpected local TLS proxy process'}
            Stop-Process -Id $proxy.Id;$proxy.WaitForExit()
            if($Phase -eq 'media-photo-send-mid-offline-ui'){
                if(!$partFault -or $partFault.HasExited -or $partFault.Path -ne (Join-Path $root '.local/upload-ack-proxy.exe')){throw 'Unexpected owned mid-transfer proxy'}
                Stop-Process -Id $partFault.Id;$partFault.WaitForExit()
            }
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
        if($phase -in @('media-send-part-ack-ui','media-photo-send-part-ack-ui','media-photo-send-mid-offline-ui','media-photo-upload-cold-prepare','media-photo-chat-cold-prepare','media-voice-chat-cold-prepare')){
            if(Get-NetTCPConnection -State Listen -LocalPort 18444 -ErrorAction SilentlyContinue){throw 'Local fault port already occupied'}
            $partState=Join-Path $root ('.local/upload-ack-'+[guid]::NewGuid().ToString('N')+'.json')
            $partInput=Join-Path $root '.local/upload-ack-input.txt';[IO.File]::WriteAllText($partInput,'')
            $partOperation=if($phase -eq 'media-photo-send-mid-offline-ui'){'upload-part-mid-hold'}elseif($phase -in @('media-photo-upload-cold-prepare','media-photo-chat-cold-prepare','media-voice-chat-cold-prepare')){'upload-part-hold'}else{'upload-part'}
            $partFault=Start-Process -FilePath (Join-Path $root '.local/upload-ack-proxy.exe') -ArgumentList @('-operation',$partOperation,'-group',[guid]::NewGuid().ToString(),'-state',('"'+$partState+'"')) -WorkingDirectory $root -WindowStyle Hidden -RedirectStandardInput $partInput -RedirectStandardOutput (Join-Path $root '.local/upload-ack-proxy.log') -RedirectStandardError (Join-Path $root '.local/upload-ack-proxy-error.log') -PassThru
            $deadline=[DateTimeOffset]::UtcNow.AddSeconds(10);$ready=$false
            while(!$partFault.HasExited -and [DateTimeOffset]::UtcNow -lt $deadline){if(Get-NetTCPConnection -State Listen -LocalPort 18444 -ErrorAction SilentlyContinue|Where-Object {$_.OwningProcess -eq $partFault.Id -and $_.LocalAddress -eq '127.0.0.1'}){$ready=$true;break};Start-Sleep -Milliseconds 100}
            if(!$ready){throw 'Owned upload fault helper unavailable'}
            & $adb -s $Serial reverse tcp:18443 tcp:18444
            if($LASTEXITCODE -ne 0){throw 'Upload fault mapping failed'}
        }
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
        if($phase -in @('media-send-part-ack-ui','media-photo-send-part-ack-ui','media-photo-send-mid-offline-ui','media-photo-upload-cold-prepare','media-photo-chat-cold-prepare','media-voice-chat-cold-prepare')){
            $proof=[IO.File]::ReadAllText($partState)|ConvertFrom-Json
            $upload=((& $adb -s $Serial shell run-as $package cat files/vyrn-test-media-upload-id.txt)-join '').Trim()
            if($LASTEXITCODE -ne 0 -or $upload -notmatch '^[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}$' -or $proof.server_id -ne $upload -or $proof.status -ne 204 -or ($phase -notin @('media-photo-upload-cold-prepare','media-photo-chat-cold-prepare','media-voice-chat-cold-prepare','media-photo-send-mid-offline-ui') -and $proof.replays -lt 1)){throw 'Missing exact native upload commit/drop/replay evidence'}
            $checks+=if($phase -eq 'media-photo-send-mid-offline-ui'){@('mid_upload_second_committed_part_held_then_network_interrupted')}elseif($phase -in @('media-photo-upload-cold-prepare','media-photo-chat-cold-prepare','media-voice-chat-cold-prepare')){@('cold_upload_committed_part_held_for_process_death')}else{@('file_ui_committed_part_ack_discarded_and_same_upload_replayed')}
        }
        $rounds+=@{phase=$phase;checks=$checks;result='passed';startup_attach_retry=$attachRetried}
        Write-Output "PASS: Android integration phase $phase"
    }
    $report=if(($Phases -join ',') -eq 'media-voice-chat-cold-prepare,media-voice-chat-cold-recover'){'artifacts/android-media-voice-chat-cold.json'}elseif(($Phases -join ',') -eq 'media-photo-chat-cold-prepare,media-photo-chat-cold-recover'){'artifacts/android-media-photo-chat-cold.json'}elseif(($Phases -join ',') -eq 'media-photo-upload-cold-prepare,media-photo-upload-cold-recover'){'artifacts/android-media-photo-upload-cold.json'}elseif($Phases.Count -eq 1 -and $Phases[0] -eq 'media-cancel-chat-ui'){'artifacts/android-media-cancel-chat-ui.json'}elseif(@($Phases | Where-Object {$_ -notlike 'media-cancel-*'}).Count -eq 0){'artifacts/android-media-cancel.json'}elseif(@($Phases | Where-Object {$_ -notlike 'onboarding-*'}).Count -eq 0){'artifacts/android-onboarding-ui.json'}elseif(@($Phases | Where-Object {$_ -notlike 'logout-*'}).Count -eq 0){'artifacts/android-logout-ui.json'}elseif($Phases.Count -eq 1 -and $Phases[0] -in @('media-ui','media-photo-ui','media-voice-ui','media-voice-send-ui','media-voice-send-member-ui','media-voice-send-offline-ui','media-voice-cancel-chat-ui','media-voice-chat-cold-prepare','media-voice-chat-cold-recover','media-send-ui','media-photo-send-ui','media-photo-send-part-ack-ui','media-photo-send-mid-offline-ui','media-photo-send-member-ui','media-photo-send-offline-ui','media-photo-cancel-chat-ui','media-send-member-ui','media-send-offline-ui','media-send-part-ack-ui','media-cancel-prepare','media-cancel-recover','media-cancel-chat-ui','media-upload','media-voice-codec','media-voice-upload','media-photo-upload','media-photo-upload-cold-prepare','media-photo-upload-cold-recover','media-photo-chat-cold-prepare','media-photo-chat-cold-recover','media-upload-big','media-upload-cancel','auth-ui','registration-ui','sessions-ui','sessions-network')){"artifacts/android-$($Phases[0]).json"}else{'artifacts/android-integration.json'}
    if($deviceSuffix){$report=$report.Replace('.json',"$deviceSuffix.json")}
    @{date=[DateTimeOffset]::UtcNow.ToString('o');apk=[IO.Path]::GetFileName($apk);sha256=(Get-FileHash $apk).Hash.ToLowerInvariant();device=$avdName;serial=$Serial;api=35;transport='HTTPS with scoped local CA';rounds=$rounds;full_ui_e2e='not tested'} | ConvertTo-Json -Depth 6 | Set-Content (Join-Path $root $report) -Encoding utf8
} finally {
    try{if($partFault -and !$partFault.HasExited){if($partFault.Path -ne (Join-Path $root '.local/upload-ack-proxy.exe')){throw 'Fault helper identity changed'};Stop-Process -Id $partFault.Id;if(!$partFault.WaitForExit(10000)){throw 'Owned fault helper did not terminate'}}}finally{
    Restore-TestProxy
    & $adb -s $Serial reverse tcp:18443 tcp:18443
    & $adb -s $Serial shell run-as $package rm -f files/vyrn-test-fixture.json files/vyrn-test-onboarding.json files/vyrn-test-network-request.txt files/vyrn-test-network-ack.txt files/vyrn-test-media-upload-id.txt
    }
}
