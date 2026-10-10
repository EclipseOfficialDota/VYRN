# Inspect one explicitly selected physical phone. Installation preserves app data.
param([Parameter(Mandatory)][string]$Serial,[string]$Apk,[switch]$Install)
$ErrorActionPreference='Stop'
$root=Split-Path $PSScriptRoot -Parent
$adb=Join-Path $root '.tools/android-sdk/platform-tools/adb.exe'
. (Join-Path $PSScriptRoot 'android-test-tools.ps1')
if($Serial.StartsWith('emulator-')){throw 'Select a physical phone, not a test emulator'}
if(!$Apk){$Apk=Join-Path $root 'artifacts/VyrnApp-dev-arm64-v8a-debug.apk'}
$Apk=(Resolve-Path -LiteralPath $Apk).Path
$state=Invoke-TestAdb -Arguments @('-s',$Serial,'get-state')
if($state.code -ne 0 -or $state.output.Trim() -ne 'device'){throw 'Selected phone is not connected or USB debugging is not authorized'}
function Read-PhoneProperty([string]$Name){
    $result=Invoke-TestAdb -Arguments @('-s',$Serial,'shell','getprop',$Name)
    if($result.code -ne 0){throw "Cannot inspect phone property: $Name"}
    return $result.output.Trim()
}
if((Read-PhoneProperty 'ro.kernel.qemu') -eq '1'){throw 'Select a physical phone'}
$sdk=0
if(![int]::TryParse((Read-PhoneProperty 'ro.build.version.sdk'),[ref]$sdk)){throw 'Phone Android API is unavailable'}
$abis=(Read-PhoneProperty 'ro.product.cpu.abilist').Split(',')
if(!$abis[0]){$abis=@(Read-PhoneProperty 'ro.product.cpu.abi')}
$badging=(& (Join-Path $root '.tools/android-sdk/build-tools/36.0.0/aapt2.exe') dump badging $Apk) -join "`n"
if($LASTEXITCODE -ne 0){throw 'Cannot inspect APK'}
if($badging -notmatch "(?m)^package: name='(xyz\.eclipseofficial\.vyrn(?:\.(?:dev|staging))?)'"){throw 'Select a VYRN APK'}
$package=$matches[1]
if($badging -notmatch "(?m)^minSdkVersion:'(\d+)'$"){throw 'APK minimum Android API unavailable'}
$minimum=[int]$matches[1]
if($badging -notmatch '(?m)^native-code: (.+)$'){throw 'APK native architectures unavailable'}
$apkAbis=@([regex]::Matches($matches[1],"'([^']+)'") | ForEach-Object {$_.Groups[1].Value})
$compatible=$sdk -ge $minimum -and @($apkAbis | Where-Object {$_ -in $abis}).Count -gt 0
$report=[ordered]@{date=[DateTimeOffset]::Now.ToString('o');model=(Read-PhoneProperty 'ro.product.model');android=(Read-PhoneProperty 'ro.build.version.release');sdk=$sdk;device_abis=$abis;apk=[IO.Path]::GetFileName($Apk);package=$package;sha256=(Get-FileHash -LiteralPath $Apk).Hash.ToLowerInvariant();min_api=$minimum;apk_abis=$apkAbis;sdk_and_abi_compatible=$compatible;install_attempted=$false;installation_tested=$false}
if($Install -and $compatible){
    $report.install_attempted=$true
    $result=Invoke-TestAdb -Arguments @('-s',$Serial,'install','-r','-t',$Apk) -TimeoutMs 120000
    $report.installation_tested=$result.code -eq 0 -and $result.output -match '(?m)^Success\s*$'
    $report.install_output=$result.output.Trim()
    $report.install_error=if($result.timed_out){'ADB install observation timed out; inspect installed package before retrying'}else{$result.error.Trim()}
}
$path=Join-Path $root '.local/android-phone-install.json'
$null=New-Item -ItemType Directory -Path (Split-Path $path) -Force
$report | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath $path -Encoding utf8
$report | ConvertTo-Json -Depth 4
if(!$compatible){throw 'APK Android API or architecture does not match the selected phone'}
if($Install -and !$report.installation_tested){throw 'Installation was not confirmed; see the exact Android error in .local/android-phone-install.json. App data was not removed.'}
