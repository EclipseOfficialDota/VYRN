# Installation/launch only; no claim of functional login or messaging E2E.
param([string]$Serial='emulator-5560',[switch]$LocalTest)
$ErrorActionPreference='Stop'
$root=Split-Path $PSScriptRoot -Parent
$adb=Join-Path $root '.tools/android-sdk/platform-tools/adb.exe'
$avd=(& $adb -s $Serial emu avd name) -join "`n"
if($LASTEXITCODE -ne 0 -or $avd -notmatch '(?m)^vyrn_api(30|35)\r?$'){throw 'Refusing to target a device other than the VYRN test emulator'}
$api=[int]$Matches[1]
$actualApi=(& $adb -s $Serial shell getprop ro.build.version.sdk) -join ''
if($LASTEXITCODE -ne 0 -or $actualApi.Trim() -ne [string]$api){throw 'Android API does not match the selected VYRN AVD'}
$package='xyz.eclipseofficial.vyrn.dev'
$apk=Join-Path $root ('artifacts/VyrnApp-dev-x86_64-debug'+$(if($LocalTest){'-local-tls'})+'.apk')
$install=(& $adb -s $Serial install -r -t $apk) -join "`n"
if($LASTEXITCODE -ne 0 -or $install -notmatch 'Success'){throw 'APK installation failed'}
$resolved=(& $adb -s $Serial shell cmd package resolve-activity --brief $package) -join "`n"
if($resolved -notmatch 'xyz.eclipseofficial.vyrn.dev/org.telegram.messenger.DefaultIcon'){throw 'Launcher resolution failed'}
$rounds=@()
foreach($round in 1,2){
    & $adb -s $Serial shell am force-stop $package
    $launch=(& $adb -s $Serial shell am start -W -n "$package/org.telegram.messenger.DefaultIcon") -join "`n"
    if($LASTEXITCODE -ne 0 -or $launch -notmatch 'Status: ok'){throw 'Activity launch failed'}
    $appPid=(& $adb -s $Serial shell pidof $package).Trim()
    if(!$appPid){throw 'Application process did not start'}
    Start-Sleep -Seconds 8
    $alive=(& $adb -s $Serial shell pidof $package).Trim()
    if($alive -ne $appPid){throw 'Application stopped or restarted unexpectedly'}
    $crashes=(& $adb -s $Serial logcat -d -b crash --pid=$appPid) -join "`n"
    if($crashes -match 'FATAL EXCEPTION|Fatal signal|Abort message'){throw 'VYRN crash detected'}
    $rounds+=@{round=$round;launch='passed';stable_process=$true;crash_found=$false}
}
$report=if($api -eq 35){'android-smoke.json'}else{"android-smoke-api$api.json"}
@{package=$package;apk=[IO.Path]::GetFileName($apk);sha256=(Get-FileHash $apk).Hash.ToLowerInvariant();device="vyrn_api$api";api=$api;abi='x86_64';installation='passed';rounds=$rounds;functional_e2e='not tested'} | ConvertTo-Json -Depth 5 | Set-Content (Join-Path $root "artifacts/$report") -Encoding utf8
Write-Output "PASS: Android$api x86_64 install, launcher resolution, cold launch, forced process restart and no VYRN crash in observation windows."
