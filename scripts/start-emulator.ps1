param([ValidateSet('emulator-5560','emulator-5562')][string]$Serial='emulator-5560',[string]$DnsServers='',[switch]$Monitor,[ValidateSet('auto','host','software')][string]$GpuMode='host')
$ErrorActionPreference='Stop'
$root=Split-Path $PSScriptRoot -Parent
$avdName=if($Serial -eq 'emulator-5560'){'vyrn_api35'}else{'vyrn_api35_peer'}
$port=if($Serial -eq 'emulator-5560'){'5560'}else{'5562'}
& (Join-Path $root 'scripts/setup-emulator.ps1') -AvdName $avdName
$sdk=Join-Path $root '.tools/android-sdk'
$adb=Join-Path $sdk 'platform-tools/adb.exe'
$emulator=Join-Path $sdk 'emulator/emulator.exe'
$env:ANDROID_USER_HOME=Join-Path $root '.local/android-home'
$env:ANDROID_AVD_HOME=Join-Path $root '.local/avd'
$existing=(& $adb devices) -join "`n"
$alreadyRunning=$false
foreach($line in $existing.Split("`n")){
    if($line -notmatch '^(emulator-[0-9]+)\s+'){continue}
    $connected=$matches[1];$expected=if($connected -eq 'emulator-5560'){'vyrn_api35'}elseif($connected -eq 'emulator-5562'){'vyrn_api35_peer'}else{throw 'An unrelated emulator is connected'}
    $name=(& $adb -s $connected emu avd name) -join "`n"
    if($LASTEXITCODE -ne 0 -or $name -notmatch "(?m)^$expected\r?$"){throw 'Connected emulator identity mismatch'}
    if($connected -eq $Serial){$alreadyRunning=$true}
}
if($alreadyRunning){Write-Output 'Verified VYRN emulator is already running.';exit 0}
$logName=if($Serial -eq 'emulator-5560'){'emulator'}else{'emulator-peer'}
$stdout=Join-Path $root ".local/$logName.log";$stderr=Join-Path $root ".local/$logName-error.log"
$arguments=@('-avd',$avdName,'-port',$port,'-no-window','-no-audio','-no-boot-anim','-gpu',$GpuMode,'-feature','-Vulkan','-no-snapshot-save')
if($Serial -eq 'emulator-5562'){$arguments+=@('-memory','1536')}
if($DnsServers){foreach($server in $DnsServers.Split(',')){$parsed=$null;if(![Net.IPAddress]::TryParse($server,[ref]$parsed)){throw 'Invalid emulator DNS server'}};$arguments+=@('-dns-server',$DnsServers)}
$process=Start-Process -FilePath $emulator -ArgumentList $arguments -WindowStyle Hidden -RedirectStandardOutput $stdout -RedirectStandardError $stderr -PassThru
$ready=$false
for($attempt=0;$attempt -lt 180;$attempt++){
    if($process.HasExited){throw 'VYRN emulator exited during startup'}
    try{
        $state=(& $adb -s $Serial get-state 2>$null) -join ''
        $boot=(& $adb -s $Serial shell getprop sys.boot_completed 2>$null) -join ''
        $name=(& $adb -s $Serial emu avd name 2>$null) -join "`n"
        if($state.Trim() -eq 'device' -and $boot.Trim() -eq '1' -and $name -match "(?m)^$avdName\r?$"){$ready=$true;break}
    }catch{}
    Start-Sleep -Seconds 1
}
if(!$ready){throw 'VYRN emulator did not finish booting'}
[IO.File]::WriteAllText((Join-Path $root ".local/$logName.pid"),[string]$process.Id)
Write-Output "VYRN Android35 emulator ready on $Serial."
if($Monitor){$process.WaitForExit();throw "VYRN emulator launcher exited with code $($process.ExitCode)"}
