# Native foreground send/edit/delete and optional offline queue/recovery. Physical-phone acceptance remains separate.
param([switch]$Edit,[switch]$Delete,[switch]$Offline,[switch]$ManualRetry,[switch]$LostAck,[switch]$MutationRestart,[switch]$PrivateKeys,[switch]$PrivateChat,[switch]$PrivateDelete,[switch]$PrivateAutomatic)
if($PrivateAutomatic){$PrivateChat=$true;if($LostAck -or $MutationRestart){throw 'Private automatic and held-response scenarios must run separately'}}
if($PrivateDelete){$PrivateChat=$true}
if($PrivateChat){$PrivateKeys=$true}
if($MutationRestart){$LostAck=$true}
if($ManualRetry -or $LostAck){$Offline=$true}
$ErrorActionPreference='Stop'
$root=Split-Path $PSScriptRoot -Parent
$adb=Join-Path $root '.tools/android-sdk/platform-tools/adb.exe'
$package='xyz.eclipseofficial.vyrn.dev'
. (Join-Path $PSScriptRoot 'android-test-tools.ps1')
if($LostAck){& go build -o (Join-Path $root '.local/lost-ack-proxy.exe') (Join-Path $PSScriptRoot 'lost-ack-proxy/main.go');if($LASTEXITCODE){throw 'Local lost-ack proxy build failed'}}
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
function Capture-PrivateChat([string]$Serial,[string]$Phase){
    $remote='/data/local/tmp/vyrn-private-chat.png'
    $captured=Invoke-TestAdb -Arguments @('-s',$Serial,'shell','screencap','-p',$remote)
    if($captured.code -ne 0){throw 'Private chat screenshot failed'}
    try{$pulled=Invoke-TestAdb -Arguments @('-s',$Serial,'pull',$remote,(Join-Path $root ".local/$Phase-$Serial.png"));if($pulled.code -ne 0){throw 'Private chat screenshot transfer failed'}}
    finally{$null=Invoke-TestAdb -Arguments @('-s',$Serial,'shell','rm','-f',$remote)}
}
$receiver=$null
$networkRun=$null
$testProxy=$null
$faultProxy=$null
$faultReverse=$false
$privateReverseRemoved=$false
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
function Interrupt-HeldRequest([string]$Phase,[string]$Group,[string]$Operation='send',[string]$Message=''){
    $faultState=Join-Path $root ('.local/lost-ack-'+[guid]::NewGuid().ToString('N')+'.json')
    $arguments=@('-group',$Group,'-operation',$Operation,'-state',('"'+$faultState+'"'));if($Message){$arguments+=@('-message',$Message)}
    $script:faultProxy=Start-Process -FilePath (Join-Path $root '.local/lost-ack-proxy.exe') -ArgumentList $arguments -WorkingDirectory $root -WindowStyle Hidden -RedirectStandardInput (Join-Path $root '.local/devtls-native-input.txt') -RedirectStandardOutput (Join-Path $root '.local/lost-ack-proxy.log') -RedirectStandardError (Join-Path $root '.local/lost-ack-proxy-error.log') -PassThru
    $deadline=[DateTimeOffset]::UtcNow.AddSeconds(15);$ready=$false
    while(!$faultProxy.HasExited -and [DateTimeOffset]::UtcNow -lt $deadline){$socket=[Net.Sockets.TcpClient]::new();try{$connect=$socket.ConnectAsync('127.0.0.1',18444);if($connect.Wait(200) -and $socket.Connected){$ready=$true;break}}catch{}finally{$socket.Dispose()};Start-Sleep -Milliseconds 100}
    if(!$ready){throw 'Lost-ack proxy not ready'}
    if(!(Get-NetTCPConnection -State Listen -LocalPort 18444 | Where-Object {$_.OwningProcess -eq $faultProxy.Id -and $_.LocalAddress -eq '127.0.0.1'})){throw 'Lost-ack listener ownership mismatch'}
    $mapped=Invoke-TestAdb -Arguments @('-s','emulator-5562','reverse','tcp:18443','tcp:18444');if($mapped.code -ne 0){throw 'Lost-ack device mapping failed'};$script:faultReverse=$true
    $script:networkRun=Start-Phase 'emulator-5562' $Phase
    $deadline=[DateTimeOffset]::UtcNow.AddSeconds(60);$held=$null;$pending=$null
    while(!$networkRun.process.HasExited -and [DateTimeOffset]::UtcNow -lt $deadline){
        $pending=Read-State 'emulator-5562'
        if(Test-Path -LiteralPath $faultState){try{$held=[IO.File]::ReadAllText($faultState) | ConvertFrom-Json}catch{}}
        if($held.server_id -and $pending.pending -and $pending.interrupted_checks){break}
        Start-Sleep -Milliseconds 100
    }
    if(!$held.server_id -or !$pending.pending -or !$pending.interrupted_checks){if($networkRun.process.HasExited){$null=Finish-Phase $networkRun};throw 'Committed held response and native pending evidence missing'}
    if($Operation -eq 'send' -and !$pending.client_id){throw 'Original pending queue identity missing'}
    if($Operation -ne 'send' -and ($held.server_id -ne $Message -or $pending.message_id -ne $Message)){throw 'Mutation response identity mismatch'}
    $interruptMs=[DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()-$held.request_started_ms
    if($held.request_started_ms -le 0 -or $interruptMs -lt 0 -or $interruptMs -gt 10000){throw 'Interruption missed the in-flight deadline before the 20s read timeout'}
    $stopped=Invoke-TestAdb -Arguments @('-s','emulator-5562','shell','am','force-stop',$package);if($stopped.code -ne 0){throw 'In-flight app interruption failed'}
    $interruptMs=[DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()-$held.request_started_ms;if($interruptMs -gt 10000){throw 'Force-stop completed after the in-flight deadline'}
    if(!$networkRun.process.WaitForExit(10000)){$networkRun.process.Kill();throw 'Interrupted instrumentation did not terminate'}
    $recordPhase=if($Operation -eq 'send'){$Phase}else{$Phase+'-interrupted'}
    [IO.File]::WriteAllText((Join-Path $root ".local/$recordPhase-emulator-5562.log"),$networkRun.stdout.GetAwaiter().GetResult()+$networkRun.stderr.GetAwaiter().GetResult())
    $round=@{serial='emulator-5562';phase=$recordPhase;operation=$Operation;checks=@($pending.interrupted_checks)+@('server_success_response_held_before_ack','app_force_stopped_before_response');interruption_ms=$interruptMs;result='interrupted_as_requested'}
    $mapped=Invoke-TestAdb -Arguments @('-s','emulator-5562','reverse','tcp:18443','tcp:18443');if($mapped.code -ne 0){throw 'Normal test mapping restore failed'};$script:faultReverse=$false
    Stop-Process -Id $faultProxy.Id;if(!$faultProxy.WaitForExit(10000)){throw 'Owned fault proxy did not stop'}
    return @{round=$round;pending=$pending;held=$held}
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
    if($PrivateKeys){
        $publications=@{}
        foreach($serial in @('emulator-5560','emulator-5562')){
            Write-Fixture $serial @{};$rounds+=Finish-Phase (Start-Phase $serial 'chat-key-readiness');$publication=Read-State $serial
            if($publication.user -ne $users[$serial] -or !$publication.key_device -or !$publication.key_address){throw 'Authenticated key publication evidence missing'}
            $publications[$serial]=$publication
        }
        if($publications['emulator-5560'].key_device -eq $publications['emulator-5562'].key_device -or $publications['emulator-5560'].key_address -eq $publications['emulator-5562'].key_address){throw 'Independent device keys required'}
        foreach($serial in @('emulator-5560','emulator-5562')){
            Write-Fixture $serial @{key_address=$publications[$serial].key_address};$rounds+=Finish-Phase (Start-Phase $serial 'chat-key-readiness');$reopened=Read-State $serial
            if($reopened.user -ne $users[$serial] -or $reopened.key_device -ne $publications[$serial].key_device){throw 'Key publication changed authenticated device on restart'}
        }
        $rounds+=@{phase='key-publication-host';result='passed';checks=@('independent_authenticated_publications','both_cold_reopens_preserve_device')}
    }
    if($PrivateChat){
        $conversation='';$liveMessage=$null
        foreach($direction in @(@{sender='emulator-5560';recipient='emulator-5562'},@{sender='emulator-5562';recipient='emulator-5560'})){
            $text='چت خصوصی بومی '+[guid]::NewGuid().ToString('N')
            Write-Fixture $direction.sender @{peer_user=$users[$direction.recipient];text=$text;conversation=$conversation}
            $rounds+=Finish-Phase (Start-Phase $direction.sender 'chat-private-pending-prepare');$pending=Read-State $direction.sender
            if(!$pending.client_id -or $pending.local_id -ge 0 -or !$pending.random_id -or !$pending.date -or !$pending.conversation){throw 'Original encrypted private pending identity missing'}
            $pendingFixture=@{peer_user=$users[$direction.recipient];text=$text;conversation=$pending.conversation;client_id=$pending.client_id;local_id=$pending.local_id;random_id=$pending.random_id;date=$pending.date}
            Write-Fixture $direction.sender $pendingFixture
            if($PrivateAutomatic){$pendingFixture.auto_schedule=$true;Write-Fixture $direction.sender $pendingFixture}
            $privateReverseRemoved=$true
            $removed=Invoke-TestAdb -Arguments @('-s',$direction.sender,'reverse','--remove','tcp:18443');if($removed.code -ne 0){throw 'Private pending offline mapping removal failed'}
            $rounds+=Finish-Phase (Start-Phase $direction.sender 'chat-private-pending-reopen');Capture-PrivateChat $direction.sender 'chat-private-pending-reopen'
            if($PrivateAutomatic){$retry=Read-State $direction.sender;if(!$retry.attempts -or $retry.error -ne 503 -or $retry.failed){throw 'Offline private worker retry evidence missing'};$pendingFixture.offline_attempts=$retry.attempts;Write-Fixture $direction.sender $pendingFixture}
            $restored=Invoke-TestAdb -Arguments @('-s',$direction.sender,'reverse','tcp:18443','tcp:18443');if($restored.code -ne 0){throw 'Private pending offline mapping restoration failed'}
            $privateReverseRemoved=$false
            if($LostAck -and $direction.sender -eq 'emulator-5562'){
                $interruptionFixture=$pendingFixture.Clone();$interruptionFixture.interrupt_send=$true;Write-Fixture $direction.sender $interruptionFixture
                $interrupted=Interrupt-HeldRequest 'chat-private-fixture-send' $pending.conversation;$rounds+=$interrupted.round
                $pendingFixture.server_id=$interrupted.held.server_id;$pendingFixture.ciphertext_sha256=$interrupted.pending.ciphertext_sha256;$pendingFixture.ratchet_sha256=$interrupted.pending.ratchet_sha256
                if(!$pendingFixture.ciphertext_sha256 -or !$pendingFixture.ratchet_sha256){throw 'Original encrypted private send proof missing'}
                Write-Fixture $direction.sender $pendingFixture
            }
            $sendPhase=if($PrivateAutomatic){'chat-private-auto-recover'}else{'chat-private-fixture-send'}
            $rounds+=Finish-Phase (Start-Phase $direction.sender $sendPhase) -TimeoutMs 120000;$sent=Read-State $direction.sender;$conversation=$sent.conversation
            if(!$conversation -or !$sent.message_id -or !$sent.ordinal){throw 'Private encrypted fixture evidence missing'}
            if(!$liveMessage){$liveMessage=@{ordinal=$sent.ordinal;text=$text}}
            foreach($serial in @('emulator-5560','emulator-5562')){
                $peer=if($serial -eq 'emulator-5560'){$users['emulator-5562']}else{$users['emulator-5560']}
                Write-Fixture $serial @{peer_user=$peer;text=$text;conversation=$conversation;message_id=$sent.message_id;ordinal=$sent.ordinal}
                $rounds+=Finish-Phase (Start-Phase $serial 'chat-private-view')
                Capture-PrivateChat $serial 'chat-private-view'
            }
            $privateReverseRemoved=$true
            foreach($serial in @('emulator-5560','emulator-5562')){
                $removed=Invoke-TestAdb -Arguments @('-s',$serial,'reverse','--remove','tcp:18443');if($removed.code -ne 0){throw 'Private offline mapping removal failed'}
                $rounds+=Finish-Phase (Start-Phase $serial 'chat-private-offline-view')
                Capture-PrivateChat $serial 'chat-private-offline-view'
            }
            foreach($serial in @('emulator-5560','emulator-5562')){$restored=Invoke-TestAdb -Arguments @('-s',$serial,'reverse','tcp:18443','tcp:18443');if($restored.code -ne 0){throw 'Private offline mapping restore failed'}}
            $privateReverseRemoved=$false
        }
        if($PrivateDelete){
            $deletedFixture=@{conversation=$conversation;message_id=$sent.message_id;ordinal=$sent.ordinal;text=$text;sender_user=$users['emulator-5562'];live_ordinal=$liveMessage.ordinal;live_text=$liveMessage.text;peer_user=$users['emulator-5560']}
            Write-Fixture 'emulator-5562' $deletedFixture;$rounds+=Finish-Phase (Start-Phase 'emulator-5562' 'chat-private-delete-fixture')
            foreach($serial in @('emulator-5560','emulator-5562')){
                $fixture=$deletedFixture.Clone();$fixture.peer_user=if($serial -eq 'emulator-5560'){$users['emulator-5562']}else{$users['emulator-5560']};Write-Fixture $serial $fixture
                $rounds+=Finish-Phase (Start-Phase $serial 'chat-private-deleted-view');Capture-PrivateChat $serial 'chat-private-deleted-view'
            }
            $privateReverseRemoved=$true
            foreach($serial in @('emulator-5560','emulator-5562')){$removed=Invoke-TestAdb -Arguments @('-s',$serial,'reverse','--remove','tcp:18443');if($removed.code -ne 0){throw 'Private deleted offline mapping removal failed'};$rounds+=Finish-Phase (Start-Phase $serial 'chat-private-deleted-offline-view');Capture-PrivateChat $serial 'chat-private-deleted-offline-view'}
            foreach($serial in @('emulator-5560','emulator-5562')){$restored=Invoke-TestAdb -Arguments @('-s',$serial,'reverse','tcp:18443','tcp:18443');if($restored.code -ne 0){throw 'Private deleted offline mapping restore failed'}};$privateReverseRemoved=$false
        }
        $registration=Get-Content (Join-Path $root 'artifacts/android-two-client-registration.json') -Raw | ConvertFrom-Json
        $report=if($PrivateDelete){'artifacts/android-two-client-private-deletion.json'}elseif($PrivateAutomatic){'artifacts/android-two-client-private-automatic.json'}elseif($LostAck){'artifacts/android-two-client-private-lost-ack.json'}else{'artifacts/android-two-client-private-chat.json'}
        $privateScope='Two independent native registrations and key publication; Programmatic app adapter preparation and delivery in both directions; actual pending private ChatActivity cells loaded from native history before and after disconnected process restart with the original encrypted identity, confirmed reconciliation to one bubble, idempotent delivery and encrypted UI journal acknowledgement; native row persistence on both clients; disconnected cold native reopen; actual composer input/saveDraft, authenticated encrypted preferences, disconnected cold composer restoration and record/account/corruption/peer UUID rejection. Input/save are programmatic; actual reply paths and legacy migration/recovery feedback remain untested. Optional REST fixture deletion, real stream-learned tombstones, stale array/new/edit/notification/SQL/dialog-top replay and cold offline absence. Unsupported private Send is disabled/dimmed; forced composer and shared native send entry preserve the draft and create no plaintext pending bubble/database row. Optional held-success response with force-stop before ACK, same server identity/ciphertext/ratchet after restart and one server/native message. Optional durable scheduling while offline and automatic original-journal recovery after cold reconnect, with one server/native identity. Native composer Send, failed/manual-retry/cancellation/trust and deletion controls remain gated/untested.'
        if($PrivateAutomatic){$privateScope=$privateScope.Replace('Programmatic app adapter preparation and delivery in both directions','Programmatic app adapter preparation and scheduling in both directions; actual offline app-worker failure persists per-record retry metadata, then automatic app-owned delivery after disconnected process restart').Replace('idempotent delivery','automatic original-journal delivery')}
        @{date=[DateTimeOffset]::Now.ToString('o');result='passed';apk_sha256=(Get-FileHash (Join-Path $root 'artifacts/VyrnApp-dev-x86_64-debug-local-tls.apk') -Algorithm SHA256).Hash.ToLowerInvariant();devices=$registration.devices;rounds=$rounds;physical_devices='not tested';private_deletion_replay=[bool]$PrivateDelete;private_lost_ack=[bool]$LostAck;private_automatic_recovery=[bool]$PrivateAutomatic;scope=$privateScope} | ConvertTo-Json -Depth 9 | Set-Content (Join-Path $root $report) -Encoding utf8
        if($PrivateAutomatic){Write-Output 'PASS: durable encrypted scheduling while offline and automatic native private recovery after cold reconnect on both clients; preparation/scheduling entry is programmatic, native composer still gated.'}
        else{Write-Output 'PASS: app adapter encrypted pending/confirmed native private bubbles, idempotent delivery and disconnected cold reopen on both clients; programmatic adapter entry, native composer still gated.'}
        return
    }
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
        if($LostAck){
            $uncertain='پیام با تأیید ازدست‌رفته '+[guid]::NewGuid().ToString('N')
            Write-Fixture 'emulator-5562' @{group=$group;text=$uncertain};$interrupted=Interrupt-HeldRequest 'chat-unknown-send' $group;$rounds+=$interrupted.round
            foreach($serial in @('emulator-5560','emulator-5562')){Write-Fixture $serial @{group=$group;text=$uncertain;client_id=$interrupted.pending.client_id;server_id=$interrupted.held.server_id}}
            $rounds+=Finish-Phase (Start-Phase 'emulator-5562' 'chat-recover')
            $rounds+=Finish-Phase (Start-Phase 'emulator-5560' 'chat-receive')
            $rounds+=Finish-Phase (Start-Phase 'emulator-5562' 'chat-recovered-reopen')
        }
        if($MutationRestart){
            $mutationText='پیام آزمون تغییر '+[guid]::NewGuid().ToString('N');$mutationEdited='ویرایش پیش از توقف '+[guid]::NewGuid().ToString('N')
            foreach($serial in @('emulator-5560','emulator-5562')){Write-Fixture $serial @{group=$group;text=$mutationText}}
            $rounds+=Finish-Phase (Start-Phase 'emulator-5562' 'chat-send');$rounds+=Finish-Phase (Start-Phase 'emulator-5560' 'chat-receive')
            $rounds+=Finish-Phase (Start-Phase 'emulator-5562' 'chat-message-identity');$identity=Read-State 'emulator-5562'
            $fixture=@{group=$group;text=$mutationText;edited_text=$mutationEdited;message_id=$identity.message_id;original_ordinal=$identity.original_ordinal;original_revision=$identity.original_revision;interrupt_mutation=$true}
            Write-Fixture 'emulator-5562' $fixture;$interrupted=Interrupt-HeldRequest 'chat-edit' $group 'edit' $identity.message_id;$rounds+=$interrupted.round
            foreach($serial in @('emulator-5560','emulator-5562')){$fixture.outgoing=$serial -eq 'emulator-5562';Write-Fixture $serial $fixture;$rounds+=Finish-Phase (Start-Phase $serial 'chat-edited-reopen')}
            Write-Fixture 'emulator-5562' @{group=$group;text=$mutationEdited};$rounds+=Finish-Phase (Start-Phase 'emulator-5562' 'chat-message-identity');$identity=Read-State 'emulator-5562'
            $fixture=@{group=$group;text=$mutationEdited;message_id=$identity.message_id;original_ordinal=$identity.original_ordinal;original_revision=$identity.original_revision;interrupt_mutation=$true}
            Write-Fixture 'emulator-5562' $fixture;$interrupted=Interrupt-HeldRequest 'chat-delete' $group 'delete' $identity.message_id;$rounds+=$interrupted.round
            foreach($serial in @('emulator-5560','emulator-5562')){Write-Fixture $serial $fixture;$rounds+=Finish-Phase (Start-Phase $serial 'chat-deleted-reopen')}
            foreach($serial in @('emulator-5560','emulator-5562')){$rounds+=Finish-Phase (Start-Phase $serial 'chat-deleted-reopen')}
        }
    }
    $registration=Get-Content (Join-Path $root 'artifacts/android-two-client-registration.json') -Raw | ConvertFrom-Json
    $report=if($PrivateKeys){'artifacts/android-two-client-private-keys.json'}elseif($MutationRestart){'artifacts/android-two-client-mutation-restart.json'}elseif($LostAck){'artifacts/android-two-client-lost-ack.json'}elseif($ManualRetry){'artifacts/android-two-client-manual-retry.json'}elseif($Offline){'artifacts/android-two-client-offline.json'}elseif($Delete){'artifacts/android-two-client-deletion.json'}elseif($Edit){'artifacts/android-two-client-editing.json'}else{'artifacts/android-two-client-messaging.json'}
    $scope='Native button-click send and visible confirmed bubbles in both directions while both clients are foreground; REST group fixture, programmatic chat entry'
    if($Edit -or $Delete -or $Offline){$scope+='; native message menu/Edit/Done, same-process receiver edit, same server identity/ordinal with newer revision and no duplicate'}
    if($Delete -or $Offline){$scope+='; native shared deletion confirmation/cancellation, receiver removal and both cold app reopens without resurrection'}
    if($Offline){$scope+='; dedicated test proxy stopped, native queued send, offline cold pending bubble, same durable client ID, automatic delivery after reconnect, confirmed cold reopen and deleted-message absence after reconnect'}
    if($ManualRetry){$scope+='; real eight-attempt network failure, failed bubble after offline cold reopen, native Retry menu, original queue ID delivered once and confirmed cold reopen'}
    if($LostAck){$scope+='; successful upstream send response held, explicit app force-stop before acknowledgement, original durable client ID reconciles to the pre-restart server ID, one native/server message and confirmed cold reopen'}
    if($MutationRestart){$scope+='; native Edit/Done and shared Delete committed upstream with responses held, explicit in-flight app interruption, edited identity/revision after both app restarts and repeated deleted-history reopens without resurrection'}
    if($PrivateKeys){$scope+='; native dialog loading prepares authenticated per-device public keys, independent device addresses and unchanged publications after both cold reopens; private messaging UI remains gated'}
    @{date=[DateTimeOffset]::UtcNow.ToString('o');result='passed';apk_sha256=$registration.devices[0].apk_sha256;devices=$registration.devices;rounds=$rounds;scope=$scope;edit_ui=[bool]($Edit -or $Delete -or $Offline);delete_ui=[bool]($Delete -or $Offline);delete_process_restart=[bool]($Delete -or $Offline);offline_automatic_retry=[bool]$Offline;manual_retry_ui=[bool]$ManualRetry;lost_ack_process_restart=[bool]$LostAck;mutation_process_restart=[bool]$MutationRestart;physical_devices='not tested'} | ConvertTo-Json -Depth 7 | Set-Content (Join-Path $root $report) -Encoding utf8
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
    }finally{
        if($privateReverseRemoved){foreach($serial in @('emulator-5560','emulator-5562')){$restored=Invoke-TestAdb -Arguments @('-s',$serial,'reverse','tcp:18443','tcp:18443');if($restored.code -ne 0){throw 'Private test mapping cleanup failed'}}}
        try{if($faultReverse){$restore=Invoke-TestAdb -Arguments @('-s','emulator-5562','reverse','tcp:18443','tcp:18443');if($restore.code -ne 0){throw 'Lost-ack mapping cleanup failed'}}}
        finally{try{if($faultProxy -and !$faultProxy.HasExited){Stop-Process -Id $faultProxy.Id;$null=$faultProxy.WaitForExit(10000)}}finally{Restore-TestProxy}}
    }
}
