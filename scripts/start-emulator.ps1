param([ValidateSet('emulator-5560','emulator-5562')][string]$Serial='emulator-5560',[string]$DnsServers='',[switch]$Monitor,[ValidateSet('auto','host','software','swangle')][string]$GpuMode='host',[switch]$ColdBoot,[string]$EmulatorPath='')
$ErrorActionPreference='Stop'
$root=Split-Path $PSScriptRoot -Parent
$avdName=if($Serial -eq 'emulator-5560'){'vyrn_api35'}else{'vyrn_api35_peer'}
$port=if($Serial -eq 'emulator-5560'){'5560'}else{'5562'}
& (Join-Path $root 'scripts/setup-emulator.ps1') -AvdName $avdName
$sdk=Join-Path $root '.tools/android-sdk'
$adb=Join-Path $sdk 'platform-tools/adb.exe'
. (Join-Path $PSScriptRoot 'android-test-tools.ps1')
$emulator=Join-Path $sdk 'emulator/emulator.exe'
if($EmulatorPath){$emulator=[IO.Path]::GetFullPath($EmulatorPath);if(!(Test-Path -LiteralPath $emulator -PathType Leaf)){throw 'Selected emulator runtime missing'}}
$env:ANDROID_SDK_ROOT=$sdk
$env:ANDROID_HOME=$sdk
$env:ANDROID_USER_HOME=Join-Path $root '.local/android-home'
$env:ANDROID_AVD_HOME=Join-Path $root '.local/avd'
$inventory=Invoke-TestAdb -Arguments @('devices') -TimeoutMs 5000
if($inventory.code -ne 0){throw 'ADB device inventory unavailable'}
$existing=$inventory.output
$alreadyRunning=$false
foreach($line in $existing.Split("`n")){
    if($line -notmatch '^(emulator-[0-9]+)\s+'){continue}
    $connected=$matches[1];$expected=if($connected -eq 'emulator-5560'){'vyrn_api35'}elseif($connected -eq 'emulator-5562'){'vyrn_api35_peer'}else{throw 'An unrelated emulator is connected'}
    $identity=Invoke-TestAdb -Arguments @('-s',$connected,'emu','avd','name') -TimeoutMs 5000
    if($identity.code -ne 0 -or $identity.output -notmatch "(?m)^$expected\r*$"){throw 'Connected emulator identity mismatch or console unavailable'}
    if($connected -eq $Serial){$alreadyRunning=$true}
}
if($alreadyRunning){Write-Output 'Verified VYRN emulator is already running.';exit 0}
$logName=if($Serial -eq 'emulator-5560'){'emulator'}else{'emulator-peer'}
$stdout=Join-Path $root ".local/$logName.log";$stderr=Join-Path $root ".local/$logName-error.log"
$arguments=@('-avd',$avdName,'-port',$port,'-no-window','-no-audio','-no-boot-anim','-gpu',$GpuMode,'-feature','-Vulkan','-no-snapshot-save')
if($ColdBoot){$arguments+='-no-snapshot-load'}
if($Serial -eq 'emulator-5562'){$arguments+=@('-memory','1536')}
if($DnsServers){foreach($server in $DnsServers.Split(',')){$parsed=$null;if(![Net.IPAddress]::TryParse($server,[ref]$parsed)){throw 'Invalid emulator DNS server'}};$arguments+=@('-dns-server',$DnsServers)}
$process=Start-Process -FilePath $emulator -ArgumentList $arguments -WindowStyle Hidden -RedirectStandardOutput $stdout -RedirectStandardError $stderr -PassThru
$ready=$false
$deadline=[DateTimeOffset]::UtcNow.AddSeconds(180)
while([DateTimeOffset]::UtcNow -lt $deadline){
    if($process.HasExited){throw 'VYRN emulator exited during startup'}
    try{
        $state=Invoke-TestAdb -Arguments @('-s',$Serial,'get-state') -TimeoutMs 5000
        if($state.code -eq 0 -and $state.output.Trim() -eq 'device'){
            $boot=Invoke-TestAdb -Arguments @('-s',$Serial,'shell','getprop','sys.boot_completed') -TimeoutMs 5000
            $identity=Invoke-TestAdb -Arguments @('-s',$Serial,'emu','avd','name') -TimeoutMs 5000
            if($boot.code -eq 0 -and $boot.output.Trim() -eq '1' -and $identity.code -eq 0 -and $identity.output -match "(?m)^$avdName\r*$"){$ready=$true;break}
        }
    }catch{}
    Start-Sleep -Seconds 1
}
if(!$ready){throw 'VYRN emulator did not finish booting'}
[IO.File]::WriteAllText((Join-Path $root ".local/$logName.pid"),[string]$process.Id)
Write-Output "VYRN Android35 emulator ready on $Serial."
if($Monitor){$process.WaitForExit();throw "VYRN emulator launcher exited with code $($process.ExitCode)"}
exit 0
