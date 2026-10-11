param([ValidateSet('vyrn_api35','vyrn_api35_peer','vyrn_api30')][string]$AvdName='vyrn_api35')
$ErrorActionPreference='Stop'
$ProgressPreference='SilentlyContinue'
$root=Split-Path $PSScriptRoot -Parent
$sdk=Join-Path $root '.tools/android-sdk'
$env:JAVA_HOME=Join-Path $root '.tools/jdk21/jdk-21.0.12.1+1'
if(!(Test-Path -LiteralPath (Join-Path $env:JAVA_HOME 'bin/java.exe'))){throw 'Run scripts/setup-jdk.ps1 first'}
$env:ANDROID_HOME=$sdk
$env:ANDROID_SDK_ROOT=$sdk
$env:ANDROID_USER_HOME=Join-Path $root '.local/android-home'
$env:ANDROID_AVD_HOME=Join-Path $root '.local/avd'
New-Item -ItemType Directory $env:ANDROID_USER_HOME,$env:ANDROID_AVD_HOME -Force | Out-Null
# The emulator ZIP has source.properties but modern avdmanager requires package.xml.
$emulatorMetadata=Join-Path $sdk 'emulator/package.xml'
if(!(Test-Path -LiteralPath $emulatorMetadata)){
    [xml]$metadata=Get-Content (Join-Path $sdk 'platform-tools/package.xml') -Raw
    [xml]$repository=Get-Content (Join-Path $root '.tools/repository2-3.xml') -Raw
    $remote=$repository.SelectNodes('//*[local-name()="remotePackage"]') | Where-Object {$_.path -eq 'emulator'} | Select-Object -First 1
    if(!$remote){throw 'Official emulator metadata missing'}
    $local=$metadata.SelectSingleNode('//*[local-name()="localPackage"]')
    $local.SetAttribute('path','emulator')
    $local.SelectSingleNode('revision').InnerXml=$remote.SelectSingleNode('revision').InnerXml
    $local.SelectSingleNode('display-name').InnerText=$remote.SelectSingleNode('display-name').InnerText
    $licenseRef=$remote.SelectSingleNode('uses-license').GetAttribute('ref')
    $local.SelectSingleNode('uses-license').SetAttribute('ref',$licenseRef)
    $license=$metadata.SelectSingleNode('//*[local-name()="license"]')
    $license.SetAttribute('id',$licenseRef)
    $officialLicense=$repository.SelectNodes('//*[local-name()="license"]') | Where-Object {$_.id -eq $licenseRef} | Select-Object -First 1
    $license.InnerText=$officialLicense.InnerText
    $metadata.Save($emulatorMetadata)
}
$api=if($AvdName -eq 'vyrn_api30'){30}else{35}
$imagePackage="system-images;android-$api;default;x86_64"
$target=Join-Path $sdk "system-images/android-$api/default/x86_64"
if(!(Test-Path -LiteralPath (Join-Path $target 'source.properties'))){
    $catalogPath=Join-Path $root '.tools/system-images.xml'
    Invoke-WebRequest 'https://dl.google.com/android/repository/sys-img/android/sys-img2-3.xml' -OutFile $catalogPath
    [xml]$catalog=Get-Content -LiteralPath $catalogPath -Raw
    $package=$catalog.SelectNodes('//*[local-name()="remotePackage"]') | Where-Object {$_.path -eq $imagePackage} | Select-Object -First 1
    if(!$package){throw "Official Android $api x86_64 image not found"}
    $archive=$package.archives.archive.complete
    $zip=Join-Path $root ".tools/android-$api-x86_64.zip"
    if(!(Test-Path -LiteralPath $zip)){Invoke-WebRequest ('https://dl.google.com/android/repository/sys-img/android/'+$archive.url) -OutFile $zip}
    if((Get-FileHash $zip -Algorithm SHA1).Hash -ne $archive.checksum.InnerText){throw 'System image checksum failed'}
    $unpack=Join-Path $root ('.tools/unpack-system-image-'+[guid]::NewGuid().ToString('N'))
    [IO.Compression.ZipFile]::ExtractToDirectory($zip,$unpack)
    $properties=@(Get-ChildItem $unpack -File -Filter source.properties -Recurse)
    if($properties.Count -ne 1){throw 'Ambiguous system image package root'}
    $source=[IO.Path]::GetFullPath($properties[0].DirectoryName)
    $destination=[IO.Path]::GetFullPath($target)
    $allowed=[IO.Path]::GetFullPath((Join-Path $root '.tools'))+[IO.Path]::DirectorySeparatorChar
    if(!$source.StartsWith($allowed) -or !$destination.StartsWith($allowed)){throw 'System image path escaped workspace'}
    if(Test-Path -LiteralPath $destination){throw 'Existing partial image must be inspected before retrying'}
    New-Item -ItemType Directory (Split-Path $destination -Parent) -Force | Out-Null
    Move-Item -LiteralPath $source -Destination $destination
}
$proxy=[Net.WebRequest]::GetSystemWebProxy().GetProxy([uri]'https://dl.google.com')
$env:JAVA_TOOL_OPTIONS='-Djavax.net.ssl.trustStoreType=Windows-ROOT -Djavax.net.ssl.trustStore=NONE'
if($proxy.Host -ne 'dl.google.com'){$env:JAVA_TOOL_OPTIONS+=" -Dhttps.proxyHost=$($proxy.Host) -Dhttps.proxyPort=$($proxy.Port) -Dhttp.proxyHost=$($proxy.Host) -Dhttp.proxyPort=$($proxy.Port)"}
$avd=Join-Path $env:ANDROID_AVD_HOME "$AvdName.avd"
if(!(Test-Path -LiteralPath (Join-Path $avd 'config.ini'))){
    'no' | & (Join-Path $sdk 'cmdline-tools/latest/bin/avdmanager.bat') create avd --name $AvdName --package $imagePackage --path $avd
    if($LASTEXITCODE -ne 0){throw 'AVD creation failed'}
}
Write-Output "Verified Android $api x86_64 system image and local VYRN AVD are ready."
