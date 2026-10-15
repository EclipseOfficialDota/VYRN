# Owned emulator only. No instrumentation runs during process death/delivery observation.
param([ValidateSet('emulator-5560','emulator-5562','emulator-5564')][string]$Serial='emulator-5560')
$ErrorActionPreference='Stop'
$root=Split-Path $PSScriptRoot -Parent
$adb=Join-Path $root '.tools/android-sdk/platform-tools/adb.exe'
$package='xyz.eclipseofficial.vyrn.dev'
. (Join-Path $PSScriptRoot 'android-test-tools.ps1')
function Read-Adb([string[]]$Arguments){
    $result=Invoke-TestAdb -Arguments (@('-s',$Serial)+$Arguments) -TimeoutMs 15000
    if($result.code -ne 0){
        # Identify only the operation, never fixture output or credentials.
        $operation=($Arguments | Select-Object -First 2) -join ' '
        throw "Background ADB operation '$operation' failed (exit=$($result.code), timeout=$($result.timed_out)); no app data removed"
    }
    return $result.output
}
function Read-AppPid {
    $result=Invoke-TestAdb -Arguments @('-s',$Serial,'shell','pidof',$package)
    if($result.code -eq 1 -and !$result.output.Trim()){return 0}
    if($result.code -ne 0 -or $result.output.Trim() -notmatch '^[1-9][0-9]*$'){throw 'App process identity unavailable or ambiguous'}
    return [int]$result.output.Trim()
}
function Wait-For([scriptblock]$Condition,[int]$Seconds,[string]$Failure){
    $deadline=[DateTimeOffset]::UtcNow.AddSeconds($Seconds)
    do {if(& $Condition){return};Start-Sleep -Milliseconds 500}while([DateTimeOffset]::UtcNow -lt $deadline)
    throw $Failure
}
function Assert-Check([bool]$Condition,[string]$Name){if(!$Condition){throw $Name};$script:checks.Add($Name)}
function Read-Ui {
    $null=Read-Adb @('shell','uiautomator','dump','/data/local/tmp/vyrn-background.xml')
    try{return [xml](Read-Adb @('shell','cat','/data/local/tmp/vyrn-background.xml'))}catch{throw 'Background UI snapshot unavailable'}
}
function Tap-Node($Node){
    if(!$Node -or $Node.bounds -notmatch '^\[(\d+),(\d+)\]\[(\d+),(\d+)\]$'){throw 'Background notification control not visible'}
    $x=[int](([int]$Matches[1]+[int]$Matches[3])/2);$y=[int](([int]$Matches[2]+[int]$Matches[4])/2)
    $null=Read-Adb @('shell','input','tap',"$x","$y")
}
$script:checks=[Collections.Generic.List[string]]::new()
$expected=if($Serial -eq 'emulator-5560'){'vyrn_api35'}elseif($Serial -eq 'emulator-5564'){'vyrn_api30'}else{'vyrn_api35_peer'}
Assert-Check ((Read-Adb @('emu','avd','name')) -match "(?m)^$expected\r*$") 'owned_emulator_identity'
# This test models OS process termination. On this image run-as rejected the
# signal to the verified live app, even with the same Linux UID.
Assert-Check ((Read-Adb @('shell','id','-u')).Trim() -eq '0') 'owned_emulator_root_signal_capability'
$null=Read-Adb @('shell','input','keyevent','224')
$null=Read-Adb @('shell','wm','dismiss-keyguard')
& (Join-Path $PSScriptRoot 'test-android-integration.ps1') -Serial $Serial -Phases notification-process-prepare
if(!$?){throw 'Native background preparation failed'}
$privateFile='files/vyrn-test-background.json'
try{$fixture=Read-Adb @('shell','run-as',$package,'cat',$privateFile)|ConvertFrom-Json}catch{throw 'Private background fixture unavailable'}
$proxyPid=[int][IO.File]::ReadAllText((Join-Path $root '.local/devtls-native.pid'))
$proxy=Get-CimInstance Win32_Process -Filter "ProcessId=$proxyPid"
if(!$proxy -or $proxy.ExecutablePath -ne (Join-Path $root '.local/devtls-native.exe') -or $proxy.CommandLine -notmatch '-upstream\s+(http://(?:127\.[0-9.]+|172\.(?:1[6-9]|2[0-9]|3[01])\.[0-9.]+):8080)'){throw 'Dedicated local Core proxy identity unavailable'}
$api=$Matches[1]
$senderHeaders=@{Authorization='Bearer '+$fixture.sender.access_token}
$recipientHeaders=@{Authorization='Bearer '+$fixture.recipient.access_token}
$marker='Background-'+[guid]::NewGuid().ToString('N')
try {
    # Instrumentation completion may terminate its target. Establish an ordinary
    # user launch before the controlled kill; never launch again until delivery.
    $null=Read-Adb @('shell','input','keyevent','224')
    $null=Read-Adb @('shell','wm','dismiss-keyguard')
    $launch=Read-Adb @('shell','am','start','-W','-n',"$package/org.telegram.ui.LaunchActivity")
    Assert-Check ($launch -match 'Status: ok') 'ordinary_app_launch_after_preparation'
    $null=Read-Adb @('shell','input','keyevent','3')
    Wait-For { (Read-AppPid) -gt 0 -and (Read-Adb @('shell','dumpsys','activity','services',$package)) -match 'isForeground=true' } 90 'Enabled service did not start on ordinary cold app launch'
    $before=Read-AppPid
    $null=Read-Adb @('shell','input','keyevent','223')
    $null=Read-Adb @('shell','kill','-9',"$before")
    Wait-For { $current=Read-AppPid; $current -gt 0 -and $current -ne $before -and (Read-Adb @('shell','dumpsys','activity','services',$package)) -match 'isForeground=true' } 90 'Sticky service did not recover after actual process death'
    $after=Read-AppPid
    Assert-Check ($after -ne $before) 'actual_process_kill_and_automatic_foreground_service_restart'
    $processes=Read-Adb @('shell','dumpsys','activity','processes',$package)
    Assert-Check ($processes -notmatch 'mInstr=ActiveInstrumentation|instrumentationClass=') 'no_app_instrumentation_after_restart'
    Assert-Check ((Read-Adb @('shell','dumpsys','power')) -match 'mWakefulness=Asleep') 'screen_off_before_external_message'
    $body=@{client_id=[guid]::NewGuid().ToString();encrypted=$false;payload=@{text=$marker}}|ConvertTo-Json -Compress
    try{$message=Invoke-RestMethod "$api/v1/conversations/$($fixture.conversation)/messages" -Method Post -Headers $senderHeaders -ContentType 'application/json' -Body $body -TimeoutSec 15}catch{throw 'External local fixture message send failed'}
    Wait-For { (Read-Adb @('shell','dumpsys','notification','--noredact')).Contains($marker) } 45 'No system notification after process recovery'
    Assert-Check ((Read-AppPid) -eq $after) 'notification_delivered_in_restarted_process_without_app_launch'
    Assert-Check ((Read-Adb @('shell','dumpsys','power')) -match 'mWakefulness=Asleep') 'notification_arrived_while_screen_off'
    $null=Read-Adb @('shell','input','keyevent','224')
    $null=Read-Adb @('shell','wm','dismiss-keyguard')
    $null=Read-Adb @('shell','cmd','statusbar','expand-notifications')
    $script:messageNode=$null
    Wait-For { $ui=Read-Ui;$script:messageNode=@($ui.SelectNodes('//node')|Where-Object {$_.text -like "*$marker*"})|Select-Object -First 1; $null -ne $script:messageNode } 15 'Actual notification row not visible'
    Tap-Node $script:messageNode
    Wait-For {
        $ui=Read-Ui
        $deny=@($ui.SelectNodes('//node')|Where-Object {$_.package -eq 'com.android.permissioncontroller' -and $_.'resource-id' -eq 'com.android.permissioncontroller:id/permission_deny_button'})|Select-Object -First 1
        if($deny){Tap-Node $deny;if(!$script:checks.Contains('unrelated_runtime_permission_denied_without_grant')){$script:checks.Add('unrelated_runtime_permission_denied_without_grant')};return $false}
        $texts=@($ui.SelectNodes('//node')|Where-Object {$_.package -eq $package}|ForEach-Object {$_.text;$_.GetAttribute('content-desc')})
        @($texts|Where-Object {$_ -like "*$($fixture.title)*"}).Count -gt 0 -and @($texts|Where-Object {$_ -like "*$marker*"}).Count -gt 0
    } 30 'Notification did not open the matching original chat'
    Assert-Check $true 'system_notification_touch_opens_unique_matching_chat_and_message'
    Wait-For { try{$history=Invoke-RestMethod "$api/v1/conversations/$($fixture.conversation)/messages" -Headers $recipientHeaders -TimeoutSec 10;@($history|Where-Object {$_.id -eq $message.id -and $_.receipts.read_by_me}).Count -eq 1}catch{return $false} } 20 'Original chat did not record read receipt'
    Assert-Check $true 'native_chat_open_after_process_recovery_records_server_read'
    @{date=[DateTimeOffset]::Now.ToString('o');result='passed';apk_sha256=(Get-FileHash (Join-Path $root 'artifacts/VyrnApp-dev-x86_64-debug-local-tls.apk')).Hash.ToLowerInvariant();device=$expected;serial=$Serial;before_pid=$before;after_pid=$after;checks=$script:checks;scope='Native UI registration/opt-in; external actual process kill, automatic sticky restart, external REST sender, screen-off real system notification, system UI notification touch and original chat read; no app instrumentation during kill/delivery. Not Doze/reboot/physical phone/private media.'}|ConvertTo-Json -Depth 5|Set-Content (Join-Path $root "artifacts/android-background-process-$Serial.json") -Encoding utf8
    Write-Output "PASS: $($script:checks.Count) external background process-recovery checks"
} catch {
    try{$failureUi=Read-Ui;[IO.File]::WriteAllText((Join-Path $root '.local/background-process-failure.xml'),$failureUi.OuterXml)}catch{}
    throw
} finally {
    $senderHeaders=$null;$recipientHeaders=$null;$fixture=$null
    $null=Invoke-TestAdb -Arguments @('-s',$Serial,'shell','run-as',$package,'rm','-f',$privateFile)
    $null=Invoke-TestAdb -Arguments @('-s',$Serial,'shell','rm','-f','/data/local/tmp/vyrn-background.xml')
    # End only the owned emulator fixture. This is cleanup after observation,
    # never the process-death stimulus (force stop suppresses sticky restart).
    $null=Invoke-TestAdb -Arguments @('-s',$Serial,'shell','am','force-stop',$package)
    $null=Invoke-TestAdb -Arguments @('-s',$Serial,'shell','input','keyevent','224')
}
