# Native foreground composer/delivery. Full edit/delete/offline/phone acceptance remains separate.
$ErrorActionPreference='Stop'
$root=Split-Path $PSScriptRoot -Parent
$adb=Join-Path $root '.tools/android-sdk/platform-tools/adb.exe'
$package='xyz.eclipseofficial.vyrn.dev'
& (Join-Path $PSScriptRoot 'test-two-client-registration.ps1')
$rounds=@()
function Write-Fixture([string]$Serial,[hashtable]$Fixture){
    $start=[Diagnostics.ProcessStartInfo]::new($adb);$start.UseShellExecute=$false;$start.RedirectStandardInput=$true;$start.RedirectStandardOutput=$true;$start.RedirectStandardError=$true;$start.CreateNoWindow=$true
    foreach($argument in @('-s',$Serial,'shell','-T','run-as',$package,'sh','-c',"'cat > files/vyrn-test-fixture.json'")){$start.ArgumentList.Add($argument)}
    $process=[Diagnostics.Process]::Start($start);$process.StandardInput.Write(($Fixture | ConvertTo-Json -Compress));$process.StandardInput.Close()
    $null=$process.StandardOutput.ReadToEnd();$null=$process.StandardError.ReadToEnd();$process.WaitForExit()
    if($process.ExitCode -ne 0){throw 'Private chat fixture transfer failed'}
}
function Start-Phase([string]$Serial,[string]$Phase){
    & $adb -s $Serial shell am force-stop $package | Out-Null
    if($LASTEXITCODE -ne 0){throw 'Test client stop failed'}
    & $adb -s $Serial shell run-as $package rm -f files/vyrn-test-chat-state.json | Out-Null
    $start=[Diagnostics.ProcessStartInfo]::new($adb);$start.UseShellExecute=$false;$start.RedirectStandardOutput=$true;$start.RedirectStandardError=$true;$start.CreateNoWindow=$true
    foreach($argument in @('-s',$Serial,'shell','am','instrument','-w','-e','phase',$Phase,"$package.test/xyz.eclipseofficial.vyrn.test.LocalIntegrationRunner")){$start.ArgumentList.Add($argument)}
    $process=[Diagnostics.Process]::Start($start)
    return @{process=$process;stdout=$process.StandardOutput.ReadToEndAsync();stderr=$process.StandardError.ReadToEndAsync();serial=$Serial;phase=$Phase}
}
function Finish-Phase($Run){
    if(!$Run.process.WaitForExit(90000)){& $adb -s $Run.serial shell am force-stop $package | Out-Null;if(!$Run.process.WaitForExit(10000)){$Run.process.Kill()};throw 'Chat instrumentation deadline exceeded'}
    $result=$Run.stdout.GetAwaiter().GetResult()+$Run.stderr.GetAwaiter().GetResult()
    [IO.File]::WriteAllText((Join-Path $root ".local/$($Run.phase)-$($Run.serial).log"),$result)
    if($Run.process.ExitCode -ne 0 -or $result -notmatch 'vyrn_result=passed'){
        $state=Read-State $Run.serial
        if($state -and $null -ne $state.native_messages){$state | ConvertTo-Json -Depth 5 | Set-Content (Join-Path $root ".local/chat-diagnostic-$($Run.serial).json") -Encoding utf8}
        $stage='instrumentation_start';if($result -match 'stage=([a-zA-Z0-9_]+)'){$stage=$matches[1]};throw "Native chat test failed: $($Run.phase) at $stage"
    }
    if($result -notmatch 'checks=(\[[^\r\n]*\])'){throw 'Chat check report missing'}
    return @{serial=$Run.serial;phase=$Run.phase;checks=($matches[1] | ConvertFrom-Json);result='passed'}
}
function Read-State([string]$Serial){
    $raw=(& $adb -s $Serial shell run-as $package cat files/vyrn-test-chat-state.json 2>$null) -join ''
    if($LASTEXITCODE -ne 0){return $null}
    return ($raw | ConvertFrom-Json)
}
$receiver=$null
try{
    $users=@{}
    foreach($serial in @('emulator-5560','emulator-5562')){Write-Fixture $serial @{};$rounds+=Finish-Phase (Start-Phase $serial 'chat-identity');$users[$serial]=(Read-State $serial).user}
    if(!$users['emulator-5560'] -or !$users['emulator-5562'] -or $users['emulator-5560'] -eq $users['emulator-5562']){throw 'Two distinct authenticated users required'}
    $title='Native two-client '+[guid]::NewGuid().ToString('N')
    Write-Fixture 'emulator-5560' @{peer_user=$users['emulator-5562'];title=$title};$rounds+=Finish-Phase (Start-Phase 'emulator-5560' 'chat-create');$group=(Read-State 'emulator-5560').group
    if(!$group){throw 'Shared group fixture missing'}
    foreach($direction in @(@{sender='emulator-5560';recipient='emulator-5562'},@{sender='emulator-5562';recipient='emulator-5560'})){
        $text='پیام از صفحه اصلی '+[guid]::NewGuid().ToString('N')
        foreach($serial in @('emulator-5560','emulator-5562')){Write-Fixture $serial @{group=$group;text=$text}}
        $receiver=Start-Phase $direction.recipient 'chat-receive';$deadline=[DateTimeOffset]::UtcNow.AddSeconds(60);$ready=$false
        while(!$receiver.process.HasExited -and [DateTimeOffset]::UtcNow -lt $deadline){$state=Read-State $direction.recipient;if($state -and $state.ready){$ready=$true;break};Start-Sleep -Milliseconds 250}
        if(!$ready){$rounds+=Finish-Phase $receiver;throw 'Receiver chat did not become ready'}
        $rounds+=Finish-Phase (Start-Phase $direction.sender 'chat-send');$rounds+=Finish-Phase $receiver
    }
    $registration=Get-Content (Join-Path $root 'artifacts/android-two-client-registration.json') -Raw | ConvertFrom-Json
    @{date=[DateTimeOffset]::UtcNow.ToString('o');result='passed';apk_sha256=$registration.devices[0].apk_sha256;devices=$registration.devices;rounds=$rounds;scope='Native button-click send and visible confirmed bubbles in both directions while both clients are foreground; REST group fixture, programmatic chat entry';edit_delete_offline_restart='not tested';physical_devices='not tested'} | ConvertTo-Json -Depth 7 | Set-Content (Join-Path $root 'artifacts/android-two-client-messaging.json') -Encoding utf8
    Write-Output 'PASS: native composer send and foreground receipt on two independent clients.'
}finally{
    if($receiver -and !$receiver.process.HasExited){& $adb -s $receiver.serial shell am force-stop $package | Out-Null;if(!$receiver.process.WaitForExit(10000)){$receiver.process.Kill()}}
    foreach($serial in @('emulator-5560','emulator-5562')){
        $state=Read-State $serial
        if($state -and $null -ne $state.native_messages){$state | ConvertTo-Json -Depth 5 | Set-Content (Join-Path $root ".local/chat-diagnostic-$serial.json") -Encoding utf8}
        & $adb -s $serial shell run-as $package rm -f files/vyrn-test-fixture.json files/vyrn-test-chat-state.json | Out-Null
    }
}
