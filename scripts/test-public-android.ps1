# Read-only public HTTPS check. No production credentials, account creation or data reset.
param([string]$Serial='emulator-5560')
$ErrorActionPreference='Stop'
$root=Split-Path $PSScriptRoot -Parent
$env:ANDROID_USER_HOME=Join-Path $root '.local/android-user'
$adb=Join-Path $root '.tools/android-sdk/platform-tools/adb.exe'
$avd=(& $adb -s $Serial emu avd name) -join "`n"
if($LASTEXITCODE -ne 0 -or $avd -notmatch '(?m)^vyrn_api35\r?$'){throw 'Only the VYRN test emulator is allowed'}
$apk=Join-Path $root 'artifacts/VyrnApp-dev-x86_64-debug.apk'
$test=Join-Path $root 'artifacts/VyrnApp-dev-debug-androidTest.apk'
foreach($file in @($apk,$test)){
    $output=(& $adb -s $Serial install -r -t $file) -join "`n"
    if($LASTEXITCODE -ne 0 -or $output -notmatch 'Success'){throw 'Public test APK installation failed'}
}
$package='xyz.eclipseofficial.vyrn.dev'
& $adb -s $Serial shell am force-stop $package
if($LASTEXITCODE -ne 0){throw 'Test process stop failed'}
$result=(& $adb -s $Serial shell am instrument -w -e phase public-connectivity "$package.test/xyz.eclipseofficial.vyrn.test.LocalIntegrationRunner") -join "`n"
[IO.File]::WriteAllText((Join-Path $root '.local/android-public-connectivity.log'),$result)
if($LASTEXITCODE -ne 0 -or $result -notmatch 'vyrn_result=passed'){throw 'Public Android connectivity failed; inspect the sanitized instrumentation log'}
if($result -notmatch 'checks=(\[[^\r\n]*\])'){throw 'Public Android check report missing'}
$checks=$Matches[1] | ConvertFrom-Json
@{date=[DateTimeOffset]::UtcNow.ToString('o');apk=[IO.Path]::GetFileName($apk);sha256=(Get-FileHash $apk).Hash.ToLowerInvariant();device=$avd.Trim();transport='public HTTPS with Android platform trust';checks=$checks;full_ui_e2e='not tested'} | ConvertTo-Json -Depth 5 | Set-Content (Join-Path $root 'artifacts/android-public-connectivity.json') -Encoding utf8
Write-Output "PASS: $($checks.Count) public Android connectivity and access-boundary checks"
