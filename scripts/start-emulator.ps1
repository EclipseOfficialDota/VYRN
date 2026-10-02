param([string]$Serial='emulator-5560')
$ErrorActionPreference='Stop'
$root=Split-Path $PSScriptRoot -Parent
& (Join-Path $root 'scripts/setup-emulator.ps1')
$sdk=Join-Path $root '.tools/android-sdk'
$adb=Join-Path $sdk 'platform-tools/adb.exe'
$emulator=Join-Path $sdk 'emulator/emulator.exe'
$env:ANDROID_USER_HOME=Join-Path $root '.local/android-home'
$env:ANDROID_AVD_HOME=Join-Path $root '.local/avd'
$existing=(& $adb devices) -join "`n"
if($existing -match "(?m)^$([regex]::Escape($Serial))\s+device$"){Write-Output 'VYRN emulator is already running.';exit 0}
if($existing -match "(?m)^emulator-"){throw 'Another emulator is connected; refusing an ambiguous test target'}
$stdout=Join-Path $root '.local/emulator.log';$stderr=Join-Path $root '.local/emulator-error.log'
$process=Start-Process -FilePath $emulator -ArgumentList @('-avd','vyrn_api35','-port','5560','-no-window','-no-audio','-no-boot-anim','-gpu','swiftshader_indirect','-no-snapshot-save') -WindowStyle Hidden -RedirectStandardOutput $stdout -RedirectStandardError $stderr -PassThru
$ready=$false
for($attempt=0;$attempt -lt 180;$attempt++){
    if($process.HasExited){throw 'VYRN emulator exited during startup'}
    try{
        $state=(& $adb -s $Serial get-state 2>$null) -join ''
        $boot=(& $adb -s $Serial shell getprop sys.boot_completed 2>$null) -join ''
        $name=(& $adb -s $Serial emu avd name 2>$null) -join "`n"
        if($state.Trim() -eq 'device' -and $boot.Trim() -eq '1' -and $name -match '(?m)^vyrn_api35\r?$'){$ready=$true;break}
    }catch{}
    Start-Sleep -Seconds 1
}
if(!$ready){throw 'VYRN emulator did not finish booting'}
[IO.File]::WriteAllText((Join-Path $root '.local/emulator.pid'),[string]$process.Id)
Write-Output "VYRN Android35 emulator ready on $Serial."
