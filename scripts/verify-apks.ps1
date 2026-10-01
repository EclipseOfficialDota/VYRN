param([ValidateSet('dev','staging','production')][string]$Flavor='dev',[ValidateSet('debug','release')][string]$BuildType='debug')
$ErrorActionPreference='Stop'
$root=Split-Path $PSScriptRoot -Parent
$env:JAVA_HOME=(Get-ChildItem (Join-Path $root '.tools/jdk17') -Directory | Select-Object -First 1).FullName
$env:PATH=(Join-Path $env:JAVA_HOME 'bin')+';'+$env:PATH
$buildTools=Join-Path $root '.tools/android-sdk/build-tools/36.0.0'
$reports=@()
foreach($abi in @('arm64-v8a','armeabi-v7a','universal')){
    $path=Join-Path $root "artifacts/VyrnApp-$Flavor-$abi-$BuildType.apk"
    if(!(Test-Path -LiteralPath $path)){throw "Missing APK: $abi"}
    & (Join-Path $buildTools 'apksigner.bat') verify $path
    if($LASTEXITCODE -ne 0){throw "APK signature failed: $abi"}
    $badging=& (Join-Path $buildTools 'aapt2.exe') dump badging $path
    if($LASTEXITCODE -ne 0){throw 'Cannot inspect APK'}
    $manifest=(& (Join-Path $buildTools 'aapt2.exe') dump xmltree --file AndroidManifest.xml $path) -join "`n"
    $tlsPolicy=(& (Join-Path $buildTools 'aapt2.exe') dump xmltree --file res/xml/vyrn_network_security.xml $path) -join "`n"
    if($LASTEXITCODE -ne 0 -or $tlsPolicy -notmatch 'system' -or $tlsPolicy -match 'localhost|vyrn_dev_ca'){throw 'Unexpected local TLS policy in a normal APK'}
    $archive=[IO.Compression.ZipFile]::OpenRead($path)
    try {if(@($archive.Entries | Where-Object {$_.FullName -match 'vyrn_dev_ca'}).Count){throw 'Local test CA leaked into a normal APK'}}finally{$archive.Dispose()}
    foreach($entry in @('allowBackup.*=false','supportsRtl.*=true','usesCleartextTraffic.*=false')){if($manifest -notmatch $entry){throw "Manifest check failed: $entry"}}
    if($BuildType -eq 'release' -and $manifest -match 'debuggable.*=true'){throw 'Release must not be debuggable'}
    $package='xyz.eclipseofficial.vyrn'+$(if($Flavor -eq 'production'){''}else{".$Flavor"})
    if(($badging -join "`n") -notmatch [regex]::Escape("name='$package'")){throw 'Package identifier mismatch'}
    if(($badging -join "`n") -notmatch "minSdkVersion:'26'"){throw 'Minimum Android API mismatch'}
    $native=($badging | Select-String '^native-code:').Line
    $expected=if($abi -eq 'universal'){@('arm64-v8a','armeabi-v7a')}else{@($abi)}
    foreach($item in $expected){if($native -notmatch [regex]::Escape("'$item'")){throw "Missing ABI: $item"}}
    $reports+=[ordered]@{file=[IO.Path]::GetFileName($path);package=$package;bytes=(Get-Item $path).Length;sha256=(Get-FileHash $path).Hash.ToLowerInvariant();abis=@($expected);signature_valid=$true;min_api=26;cleartext=$false;local_test_ca=$false;debug=($BuildType -eq 'debug');installation_tested=$false}
}
$reports | ConvertTo-Json -Depth 4 | Set-Content (Join-Path $root 'artifacts/apk-verification.json') -Encoding utf8
Write-Output "Verified $($reports.Count) APKs: signature, package, Android API, ABI and manifest policy. Device installation not tested."
