param()
$ErrorActionPreference='Stop'
$root=Split-Path $PSScriptRoot -Parent
$destination=Join-Path $root '.tools/jdk21'
$java=Join-Path $destination 'jdk-21.0.12.1+1/bin/java.exe'
if(Test-Path -LiteralPath $java){& $java -version;exit $LASTEXITCODE}
$archive=Join-Path $root '.local/OpenJDK21U-jdk_x64_windows_hotspot_21.0.12.1_1.zip'
New-Item -ItemType Directory -Path (Split-Path $archive),$destination -Force | Out-Null
$checksum='f9d6e191ab098c0d416e7d588a24420a8621cd2f4720dab2459b8b7b2d2d8b4e'
if(!(Test-Path -LiteralPath $archive) -or (Get-FileHash -LiteralPath $archive -Algorithm SHA256).Hash.ToLowerInvariant() -ne $checksum){
    Invoke-WebRequest -Uri 'https://github.com/adoptium/temurin21-binaries/releases/download/jdk-21.0.12.1%2B1/OpenJDK21U-jdk_x64_windows_hotspot_21.0.12.1_1.zip' -OutFile $archive -Resume
}
if((Get-FileHash -LiteralPath $archive -Algorithm SHA256).Hash.ToLowerInvariant() -ne $checksum){throw 'JDK archive checksum mismatch'}
Expand-Archive -LiteralPath $archive -DestinationPath $destination -Force
if(!(Test-Path -LiteralPath $java)){throw 'JDK installation incomplete'}
& $java -version
exit $LASTEXITCODE
