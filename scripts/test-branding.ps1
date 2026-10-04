# Disposable VYRN emulator only: verify branding and capture the actual fresh login.
param([string]$Serial='emulator-5560')
$ErrorActionPreference='Stop'
$root=Split-Path $PSScriptRoot -Parent
$adb=Join-Path $root '.tools/android-sdk/platform-tools/adb.exe'
$avd=(& $adb -s $Serial emu avd name) -join "`n"
if($LASTEXITCODE -ne 0 -or $avd -notmatch '(?m)^vyrn_api35\r?$'){throw 'Only the VYRN test emulator is allowed'}
$package='xyz.eclipseofficial.vyrn.dev'
$apk=Join-Path $root 'artifacts/VyrnApp-dev-x86_64-debug.apk'
foreach($path in @($apk,(Join-Path $root 'artifacts/VyrnApp-dev-debug-androidTest.apk'))){
 $output=(& $adb -s $Serial install -r -t $path) -join "`n"
 if($LASTEXITCODE -ne 0 -or $output -notmatch 'Success'){throw 'Brand test APK installation failed'}
}
$cleared=(& $adb -s $Serial shell pm clear $package) -join "`n"
if($LASTEXITCODE -ne 0 -or $cleared -notmatch 'Success'){throw 'Disposable app reset failed'}
$result=(& $adb -s $Serial shell am instrument -w -e phase branding "$package.test/xyz.eclipseofficial.vyrn.test.LocalIntegrationRunner") -join "`n"
[IO.File]::WriteAllText((Join-Path $root '.local/android-branding.log'),$result)
if($LASTEXITCODE -ne 0 -or $result -notmatch 'vyrn_result=passed'){throw 'Android branding checks failed'}
if($result -notmatch 'checks=(\[[^\r\n]*\])'){throw 'Brand check report missing'}
$checks=$Matches[1] | ConvertFrom-Json
function Export-Adb([string[]]$Arguments,[string]$Destination){
 $start=[Diagnostics.ProcessStartInfo]::new($adb);$start.UseShellExecute=$false;$start.RedirectStandardOutput=$true;$start.RedirectStandardError=$true;$start.CreateNoWindow=$true
 foreach($argument in $Arguments){$start.ArgumentList.Add($argument)}
 $process=[Diagnostics.Process]::Start($start);$file=[IO.File]::Create($Destination)
 try{$process.StandardOutput.BaseStream.CopyTo($file)}finally{$file.Dispose()}
 $stderr=$process.StandardError.ReadToEnd();$process.WaitForExit();if($process.ExitCode -ne 0){throw 'Brand artifact export failed'}
}
Export-Adb @('-s',$Serial,'exec-out','run-as',$package,'cat','files/vyrn-logo-transparent.png') (Join-Path $root 'artifacts/vyrn-logo-rendered-1024.png')
& $adb -s $Serial shell am start -n "$package/org.telegram.ui.LaunchActivity" | Out-Null
if($LASTEXITCODE -ne 0){throw 'Login launch failed'}
$ready=$false
for($attempt=0;$attempt -lt 4;$attempt++){
 & $adb -s $Serial shell rm -f /sdcard/vyrn-branding-ui.xml | Out-Null
 $dump=(& $adb -s $Serial shell uiautomator dump /sdcard/vyrn-branding-ui.xml) -join "`n"
 if($LASTEXITCODE -eq 0 -and $dump -match 'dumped to:'){
  $ui=(& $adb -s $Serial shell cat /sdcard/vyrn-branding-ui.xml) -join "`n"
  if($LASTEXITCODE -eq 0 -and $ui -match '<hierarchy' -and $ui -match 'Your email|ایمیل شما'){$ready=$true;break}
 }
 Start-Sleep -Milliseconds 750
}
if(-not $ready){throw 'Fresh login UI was not ready; screenshot verification failed'}
& $adb -s $Serial shell rm -f /sdcard/vyrn-branding-ui.xml | Out-Null
if($LASTEXITCODE -ne 0){throw 'Login UI capture cleanup failed'}
Export-Adb @('-s',$Serial,'exec-out','screencap','-p') (Join-Path $root 'artifacts/vyrn-login.png')
@{date=[DateTimeOffset]::UtcNow.ToString('o');apk=[IO.Path]::GetFileName($apk);sha256=(Get-FileHash $apk).Hash.ToLowerInvariant();checks=$checks;device='vyrn_api35';full_auth_e2e='not tested'}|ConvertTo-Json -Depth 4|Set-Content (Join-Path $root 'artifacts/android-branding.json') -Encoding utf8
Write-Output "PASS: $($checks.Count) Android branding checks; login screenshot captured"
