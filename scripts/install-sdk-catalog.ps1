param([string[]]$Packages=@('platforms;android-36','build-tools;36.0.0','platform-tools','ndk;27.2.12479018','cmake;3.22.1'))
$ErrorActionPreference='Stop'
$ProgressPreference='SilentlyContinue'
$root=Split-Path $PSScriptRoot -Parent
$tools=Join-Path $root '.tools'
$sdk=Join-Path $tools 'android-sdk'
[xml]$catalog=Get-Content -LiteralPath (Join-Path $tools 'repository2-3.xml') -Raw
foreach($name in $Packages){
 $package=$catalog.SelectNodes('//*[local-name()="remotePackage"]') | Where-Object {$_.path -eq $name} | Select-Object -First 1
 if($null -eq $package){throw "Package missing from official catalog: $name"}
 $archive=$package.archives.archive | Where-Object { !$_.'host-os' -or $_.'host-os' -eq 'windows' } | Select-Object -First 1
 if($null -eq $archive){throw "Windows archive missing: $name"}
 $target=Join-Path $sdk ($name.Replace(';','/'))
 if(Test-Path -LiteralPath (Join-Path $target 'source.properties')){Write-Output "Preserved installed package: $name";continue}
 $zip=Join-Path $tools ($name.Replace(';','-')+'.zip')
 if(!(Test-Path -LiteralPath $zip)){Invoke-WebRequest -Uri ('https://dl.google.com/android/repository/'+$archive.complete.url) -OutFile $zip}
 $algorithm=$archive.complete.checksum.type
 if(!$algorithm){$algorithm='SHA1'}
 if((Get-FileHash -LiteralPath $zip -Algorithm $algorithm).Hash -ne $archive.complete.checksum.InnerText){throw "Official package checksum mismatch: $name"}
$unpack=Join-Path $tools ('unpack-'+$name.Replace(';','-')+'-'+[guid]::NewGuid().ToString('N'))
 [IO.Compression.ZipFile]::ExtractToDirectory($zip,$unpack)
 $properties=@(Get-ChildItem -LiteralPath $unpack -Filter 'source.properties' -File -Recurse -Depth 2)
 if($properties.Count -ne 1){throw "Cannot locate a unique SDK package root: $name"}
 $source=$properties[0].DirectoryName
 $sourceFull=[IO.Path]::GetFullPath($source)
 $targetFull=[IO.Path]::GetFullPath($target)
 if(!$sourceFull.StartsWith($tools+[IO.Path]::DirectorySeparatorChar) -or !$targetFull.StartsWith($tools+[IO.Path]::DirectorySeparatorChar)){throw 'SDK path escaped tool directory'}
 New-Item -ItemType Directory -Path (Split-Path $target -Parent) -Force | Out-Null
 if(Test-Path -LiteralPath $targetFull){throw "Incomplete package target exists; inspect before retrying: $name"}
 Move-Item -LiteralPath $sourceFull -Destination $targetFull
 Write-Output "Installed verified package: $name"
}
