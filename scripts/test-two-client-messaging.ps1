# Native foreground send/edit/delete and optional offline queue/recovery. Physical-phone acceptance remains separate.
param([switch]$Edit,[switch]$Delete,[switch]$Offline,[switch]$ManualRetry)
if($ManualRetry){$Offline=$true}
$ErrorActionPreference='Stop'
$root=Split-Path $PSScriptRoot -Parent
$adb=Join-Path $root '.tools/android-sdk/platform-tools/adb.exe'
$package='xyz.eclipseofficial.vyrn.dev'
. (Join-Path $PSScriptRoot 'android-test-tools.ps1')
& (Join-Path $PSScriptRoot 'test-two-client-registration.ps1')
$rounds=@()
function Write-Fixture([string]$Serial,[hashtable]$Fixture){
    $result=Invoke-TestAdb -Arguments @('-s',$Serial,'shell','-T','run-as',$package,'sh','-c',"'cat > files/vyrn-test-fixture.json'") -InputText ($Fixture | ConvertTo-Json -Compress)
    if($result.code -ne 0){throw 'Private chat fixture transfer failed'}
}
function Start-Phase([string]$Serial,[string]$Phase){
    $stopped=Invoke-TestAdb -Arguments @('-s',$Serial,'shell','am','force-stop',$package)
    if($stopped.code -ne 0){throw 'Test client stop failed or device shell unresponsive'}
    $cleared=Invoke-TestAdb -Arguments @('-s',$Serial,'shell','run-as',$package,'rm','-f','files/vyrn-test-chat-state.json','files/vyrn-test-thread-state.txt')
    if($cleared.code -ne 0){throw 'Private chat state cleanup failed'}
    $start=[Diagnostics.ProcessStartInfo]::new($adb);$start.UseShellExecute=$false;$start.RedirectStandardOutput=$true;$start.RedirectStandardError=$true;$start.CreateNoWindow=$true
    foreach($argument in @('-s',$Serial,'shell','am','instrument','-w','-e','phase',$Phase,"$package.test/xyz.eclipseofficial.vyrn.test.LocalIntegrationRunner")){$start.ArgumentList.Add($argument)}
    $process=[Diagnostics.Process]::Start($start)
    return @{process=$process;stdout=$process.StandardOutput.ReadToEndAsync();stderr=$process.StandardError.ReadToEndAsync();serial=$Serial;phase=$Phase}
}
function Finish-Phase($Run,[int]$TimeoutMs=90000){
    if(!$Run.process.WaitForExit($TimeoutMs)){
        $trace=Invoke-TestAdb -Arguments @('-s',$Run.serial,'shell','run-as',$package,'cat','files/vyrn-test-thread-state.txt') -TimeoutMs 5000
        if($trace.code -eq 0){[IO.File]::WriteAllText((Join-Path $root ".local/chat-thread-$($Run.serial).txt"),$trace.output)}
        $null=Invoke-TestAdb -Arguments @('-s',$Run.serial,'shell','am','force-stop',$package);if(!$Run.process.WaitForExit(10000)){$Run.process.Kill()}
        [IO.File]::WriteAllText((Join-Path $root ".local/$($Run.phase)-$($Run.serial).log"),$Run.stdout.GetAwaiter().GetResult()+$Run.stderr.GetAwaiter().GetResult())
        throw "Chat instrumentation deadline exceeded: $($Run.phase)"
    }
    $result=$Run.stdout.GetAwaiter().GetResult()+$Run.stderr.GetAwaiter().GetResult()
    [IO.File]::WriteAllText((Join-Path $root ".local/$($Run.phase)-$($Run.serial).log"),$result)
    if($Run.process.ExitCode -ne 0 -or $result -notmatch 'vyrn_result=passed'){
        $state=Read-State $Run.serial
        if($state -and $null -ne $state.native_messages){$state | ConvertTo-Json -Depth 5 | Set-Content (Join-Path $root ".local/chat-diagnostic-$($Run.serial).json") -Encoding utf8}
        $stage='instrumentation_start';if($result -match 'stage=([a-zA-Z0-9_-]+)'){$stage=$matches[1]};throw "Native chat test failed: $($Run.phase) at $stage"
    }
    if($result -notmatch 'checks=(\[[^\r\n]*\])'){throw 'Chat check report missing'}
    return @{serial=$Run.serial;phase=$Run.phase;checks=($matches[1] | ConvertFrom-Json);result='passed'}
}
function Read-State([string]$Serial){
    $state=Invoke-TestAdb -Arguments @('-s',$Serial,'shell','run-as',$package,'cat','files/vyrn-test-chat-state.json') -TimeoutMs 5000
    if($state.code -ne 0){return $null}
    return ($state.output | ConvertFrom-Json)
}
$receiver=$null
$networkRun=$null
$testProxy=$null
function Restore-TestProxy {
    if(!$script:testProxy){return}
    $live=Get-Process -Id $script:testProxy.pid -ErrorAction SilentlyContinue
    if($live){if($live.Path -ne $script:testProxy.exe){throw 'Unexpected test proxy process'};return}
    $proxyInput=Join-Path $root '.local/devtls-native-input.txt';[IO.File]::WriteAllText($proxyInput,'')
    $proxy=Start-Process -FilePath $script:testProxy.exe -ArgumentList @('-upstream',$script:testProxy.upstream) -WorkingDirectory $root -WindowStyle Hidden -RedirectStandardInput $proxyInput -RedirectStandardOutput (Join-Path $root '.local/devtls-native.log') -RedirectStandardError (Join-Path $root '.local/devtls-native-error.log') -PassThru
    $script:testProxy.pid=$proxy.Id
    [IO.File]::WriteAllText((Join-Path $root '.local/devtls-native.pid'),[string]$proxy.Id)
    $deadline=[DateTimeOffset]::UtcNow.AddSeconds(10);$ready=$false
    while([DateTimeOffset]::UtcNow -lt $deadline -and !$proxy.HasExited){$socket=[Net.Sockets.TcpClient]::new();try{$connect=$socket.ConnectAsync('127.0.0.1',18443);if($connect.Wait(200) -and $socket.Connected){$ready=$true;break}}catch{}finally{$socket.Dispose()};Start-Sleep -Milliseconds 100}
    if(!$ready){throw 'Local test proxy did not become ready'}
}
function Begin-OfflineSend([string]$Phase){
    $cleared=Invoke-TestAdb -Arguments @('-s','emulator-5562','shell','run-as',$package,'rm','-f','files/vyrn-test-network-request.txt','files/vyrn-test-network-ack.txt')
    if($cleared.code -ne 0){throw 'Network handshake cleanup failed'}
    $run=Start-Phase 'emulator-5562' $Phase;$script:networkRun=$run;$deadline=[DateTimeOffset]::UtcNow.AddSeconds(60);$requested=$false
    while(!$run.process.HasExited -and [DateTimeOffset]::UtcNow -lt $deadline){$step=Invoke-TestAdb -Arguments @('-s','emulator-5562','shell','run-as',$package,'cat','files/vyrn-test-network-request.txt') -TimeoutMs 5000;if($step.timed_out){throw 'Device shell unresponsive during network handshake'};if($step.code -eq 0 -and $step.output.Trim() -eq 'offline-send'){$requested=$true;break};Start-Sleep -Milliseconds 250}
    if(!$requested){$null=Finish-Phase $run;throw 'Offline send readiness missing'}
    $proxy=Get-Process -Id $script:testProxy.pid;if($proxy.Path -ne $script:testProxy.exe){throw 'Unexpected local proxy process'};Stop-Process -Id $proxy.Id;if(!$proxy.WaitForExit(10000)){throw 'Test proxy did not stop'}
    $ack=Invoke-TestAdb -Arguments @('-s','emulator-5562','shell','run-as',$package,'sh','-c',"'printf offline-send > files/vyrn-test-network-ack.txt'")
    if($ack.code -ne 0){throw 'Offline acknowledgement transfer failed'}
    return $run
}
try{
    if($Offline){
        $exe=Join-Path $root '.local/devtls-native.exe';$proxyId=[int][IO.File]::ReadAllText((Join-Path $root '.local/devtls-native.pid'));$proxy=Get-Process -Id $proxyId
        $command=(Get-CimInstance Win32_Process -Filter "ProcessId=$proxyId").CommandLine
        if($proxy.Path -ne $exe -or $command -notmatch '-upstream\s+(http://(?:127\.[0-9.]+|172\.(?:1[6-9]|2[0-9]|3[01])\.[0-9.]+):8080)'){throw 'Offline suite requires the dedicated local test TLS proxy'}
        $testProxy=@{exe=$exe;pid=$proxyId;upstream=$matches[1]}
    }
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
    if($Edit -or $Delete -or $Offline){
        $edited='متن ویرایش‌شده '+[guid]::NewGuid().ToString('N')
        foreach($serial in @('emulator-5560','emulator-5562')){Write-Fixture $serial @{group=$group;text=$text;edited_text=$edited}}
        $receiver=Start-Phase 'emulator-5560' 'chat-watch-edit';$deadline=[DateTimeOffset]::UtcNow.AddSeconds(60);$ready=$false
        while(!$receiver.process.HasExited -and [DateTimeOffset]::UtcNow -lt $deadline){$state=Read-State 'emulator-5560';if($state -and $state.ready){$ready=$true;break};Start-Sleep -Milliseconds 250}
        if(!$ready){$rounds+=Finish-Phase $receiver;throw 'Edit receiver did not become ready'}
        $rounds+=Finish-Phase (Start-Phase 'emulator-5562' 'chat-edit');$rounds+=Finish-Phase $receiver
    }
    if($Delete -or $Offline){
        foreach($serial in @('emulator-5560','emulator-5562')){Write-Fixture $serial @{group=$group;text=$edited}}
        $receiver=Start-Phase 'emulator-5560' 'chat-watch-delete';$deadline=[DateTimeOffset]::UtcNow.AddSeconds(60);$ready=$false;$deletedId=$null
        while(!$receiver.process.HasExited -and [DateTimeOffset]::UtcNow -lt $deadline){$state=Read-State 'emulator-5560';if($state -and $state.ready -and $state.message_id){$ready=$true;$deletedId=$state.message_id;break};Start-Sleep -Milliseconds 250}
        if(!$ready){$rounds+=Finish-Phase $receiver;throw 'Delete receiver did not become ready'}
        $rounds+=Finish-Phase (Start-Phase 'emulator-5562' 'chat-delete');$rounds+=Finish-Phase $receiver
        foreach($serial in @('emulator-5560','emulator-5562')){Write-Fixture $serial @{group=$group;text=$edited;message_id=$deletedId};$rounds+=Finish-Phase (Start-Phase $serial 'chat-deleted-reopen')}
    }
    if($Offline){
        $queued='پیام آفلاین '+[guid]::NewGuid().ToString('N')
        Write-Fixture 'emulator-5562' @{group=$group;text=$queued}
        $networkRun=Begin-OfflineSend 'chat-offline-send'
        $rounds+=Finish-Phase $networkRun;$pending=Read-State 'emulator-5562'
        if(!$pending.pending -or !$pending.client_id){throw 'Durable pending message evidence missing'}
        Write-Fixture 'emulator-5562' @{group=$group;text=$queued;client_id=$pending.client_id};$rounds+=Finish-Phase (Start-Phase 'emulator-5562' 'chat-offline-reopen')
        $stopped=Invoke-TestAdb -Arguments @('-s','emulator-5562','shell','am','force-stop',$package);if($stopped.code -ne 0){throw 'Queued sender stop failed'}
        Restore-TestProxy
        Write-Fixture 'emulator-5560' @{group=$group;text=$queued};$receiver=Start-Phase 'emulator-5560' 'chat-receive';$deadline=[DateTimeOffset]::UtcNow.AddSeconds(60);$ready=$false
        while(!$receiver.process.HasExited -and [DateTimeOffset]::UtcNow -lt $deadline){$state=Read-State 'emulator-5560';if($state -and $state.ready){$ready=$true;break};Start-Sleep -Milliseconds 250}
        if(!$ready){$rounds+=Finish-Phase $receiver;throw 'Recovered-message receiver did not become ready'}
        $rounds+=Finish-Phase (Start-Phase 'emulator-5562' 'chat-recover');$rounds+=Finish-Phase $receiver
        $rounds+=Finish-Phase (Start-Phase 'emulator-5562' 'chat-recovered-reopen')
        foreach($serial in @('emulator-5560','emulator-5562')){Write-Fixture $serial @{group=$group;text=$edited;message_id=$deletedId};$rounds+=Finish-Phase (Start-Phase $serial 'chat-deleted-reopen')}
        if($ManualRetry){
            $retryText='پیام تلاش مجدد '+[guid]::NewGuid().ToString('N');Write-Fixture 'emulator-5562' @{group=$group;text=$retryText}
            $networkRun=Begin-OfflineSend 'chat-failed-send';$rounds+=Finish-Phase $networkRun 300000;$failed=Read-State 'emulator-5562'
            if(!$failed.client_id){throw 'Failed queue identity missing'}
            Write-Fixture 'emulator-5562' @{group=$group;text=$retryText;client_id=$failed.client_id;expect_failed=$true};$rounds+=Finish-Phase (Start-Phase 'emulator-5562' 'chat-failed-reopen')
            $stopped=Invoke-TestAdb -Arguments @('-s','emulator-5562','shell','am','force-stop',$package);if($stopped.code -ne 0){throw 'Failed sender stop failed'}
            Restore-TestProxy
            Write-Fixture 'emulator-5560' @{group=$group;text=$retryText};$receiver=Start-Phase 'emulator-5560' 'chat-receive';$deadline=[DateTimeOffset]::UtcNow.AddSeconds(60);$ready=$false
            while(!$receiver.process.HasExited -and [DateTimeOffset]::UtcNow -lt $deadline){$state=Read-State 'emulator-5560';if($state -and $state.ready){$ready=$true;break};Start-Sleep -Milliseconds 250}
            if(!$ready){$rounds+=Finish-Phase $receiver;throw 'Manual-retry receiver did not become ready'}
            $rounds+=Finish-Phase (Start-Phase 'emulator-5562' 'chat-manual-retry');$rounds+=Finish-Phase $receiver
            $rounds+=Finish-Phase (Start-Phase 'emulator-5562' 'chat-recovered-reopen')
        }
    }
    $registration=Get-Content (Join-Path $root 'artifacts/android-two-client-registration.json') -Raw | ConvertFrom-Json
    $report=if($ManualRetry){'artifacts/android-two-client-manual-retry.json'}elseif($Offline){'artifacts/android-two-client-offline.json'}elseif($Delete){'artifacts/android-two-client-deletion.json'}elseif($Edit){'artifacts/android-two-client-editing.json'}else{'artifacts/android-two-client-messaging.json'}
    $scope='Native button-click send and visible confirmed bubbles in both directions while both clients are foreground; REST group fixture, programmatic chat entry'
    if($Edit -or $Delete -or $Offline){$scope+='; native message menu/Edit/Done, same-process receiver edit, same server identity/ordinal with newer revision and no duplicate'}
    if($Delete -or $Offline){$scope+='; native shared deletion confirmation/cancellation, receiver removal and both cold app reopens without resurrection'}
    if($Offline){$scope+='; dedicated test proxy stopped, native queued send, offline cold pending bubble, same durable client ID, automatic delivery after reconnect, confirmed cold reopen and deleted-message absence after reconnect'}
    if($ManualRetry){$scope+='; real eight-attempt network failure, failed bubble after offline cold reopen, native Retry menu, original queue ID delivered once and confirmed cold reopen'}
    @{date=[DateTimeOffset]::UtcNow.ToString('o');result='passed';apk_sha256=$registration.devices[0].apk_sha256;devices=$registration.devices;rounds=$rounds;scope=$scope;edit_ui=[bool]($Edit -or $Delete -or $Offline);delete_ui=[bool]($Delete -or $Offline);delete_process_restart=[bool]($Delete -or $Offline);offline_automatic_retry=[bool]$Offline;manual_retry_ui=[bool]$ManualRetry;physical_devices='not tested'} | ConvertTo-Json -Depth 7 | Set-Content (Join-Path $root $report) -Encoding utf8
    Write-Output ('PASS: native composer send and foreground receipt on two independent clients.'+$(if($Edit -or $Delete -or $Offline){' Native edit and receiver update also passed.'})+$(if($Delete -or $Offline){' Shared deletion, cancellation and both cold reopens passed.'})+$(if($Offline){' Offline queue and automatic recovery passed.'}))
}finally{
    try{
    if($networkRun -and !$networkRun.process.HasExited){$null=Invoke-TestAdb -Arguments @('-s',$networkRun.serial,'shell','am','force-stop',$package);if(!$networkRun.process.WaitForExit(10000)){$networkRun.process.Kill()}}
    if($receiver -and !$receiver.process.HasExited){$null=Invoke-TestAdb -Arguments @('-s',$receiver.serial,'shell','am','force-stop',$package);if(!$receiver.process.WaitForExit(10000)){$receiver.process.Kill()}}
    foreach($serial in @('emulator-5560','emulator-5562')){
        $state=Read-State $serial
        if($state -and $null -ne $state.native_messages){$state | ConvertTo-Json -Depth 5 | Set-Content (Join-Path $root ".local/chat-diagnostic-$serial.json") -Encoding utf8}
        $null=Invoke-TestAdb -Arguments @('-s',$serial,'shell','run-as',$package,'rm','-f','files/vyrn-test-fixture.json','files/vyrn-test-chat-state.json','files/vyrn-test-network-request.txt','files/vyrn-test-network-ack.txt')
    }
    }finally{Restore-TestProxy}
}
