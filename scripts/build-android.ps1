param([ValidateSet('dev','staging','production')][string]$Flavor='dev',[ValidateSet('debug','release')][string]$BuildType='debug',[string]$Abis='arm64-v8a,armeabi-v7a',[string]$ApiUrl='https://api.vyrn.eclipseofficial.xyz',[switch]$LocalTest,[switch]$Instrumentation)
$ErrorActionPreference='Stop'
$root=Split-Path $PSScriptRoot -Parent
if($Instrumentation -and ($Flavor -ne 'dev' -or $BuildType -ne 'debug')){throw 'Instrumentation requires dev/debug'}
if($LocalTest){
    if($Flavor -ne 'dev' -or $BuildType -ne 'debug'){throw 'Local TLS is allowed only for dev/debug'}
    if(!(Test-Path (Join-Path $root '.local/android-test-res/raw/vyrn_dev_ca.crt'))){throw 'Run setup-dev-tls.ps1 first'}
    $ApiUrl='https://localhost:18443'
}
if($ApiUrl -notmatch '^https://[a-zA-Z][a-zA-Z0-9.-]+(:[0-9]+)?$'){throw 'API must use an HTTPS hostname'}
if($Abis -notmatch '^(arm64-v8a|armeabi-v7a|x86_64)(,(arm64-v8a|armeabi-v7a|x86_64))*$'){throw 'Invalid ABI list'}
if($BuildType -eq 'release'){foreach($name in @('VYRN_KEYSTORE_PATH','VYRN_KEY_ALIAS','VYRN_STORE_PASSWORD','VYRN_KEY_PASSWORD')){if(![Environment]::GetEnvironmentVariable($name)){throw "Required environment variable missing: $name"}}}
& (Join-Path $root 'android/prepare-upstream.ps1')
$env:JAVA_HOME=Join-Path $root '.tools/jdk21/jdk-21.0.12.1+1'
if(!(Test-Path -LiteralPath (Join-Path $env:JAVA_HOME 'bin/java.exe'))){throw 'Run scripts/setup-jdk.ps1 first; libsignal requires JDK21'}
$env:ANDROID_HOME=Join-Path $root '.tools/android-sdk'
$env:ANDROID_SDK_ROOT=$env:ANDROID_HOME
$env:GRADLE_USER_HOME=Join-Path $root '.local/gradle'
New-Item -ItemType Directory -Path $env:GRADLE_USER_HOME -Force | Out-Null
$proxy=[Net.WebRequest]::GetSystemWebProxy().GetProxy([uri]'https://dl.google.com')
$javaOptions='-Djavax.net.ssl.trustStoreType=Windows-ROOT -Djavax.net.ssl.trustStore=NONE'
if($proxy.Host -ne 'dl.google.com'){$javaOptions+=" -Dhttps.proxyHost=$($proxy.Host) -Dhttps.proxyPort=$($proxy.Port) -Dhttp.proxyHost=$($proxy.Host) -Dhttp.proxyPort=$($proxy.Port)"}
$env:JAVA_TOOL_OPTIONS=$javaOptions
$sdkProperty='sdk.dir='+$env:ANDROID_HOME.Replace('\','/')
[IO.File]::WriteAllText((Join-Path $root 'android/upstream/local.properties'),$sdkProperty,[Text.UTF8Encoding]::new($false))
$task=':VyrnApp:assemble'+[char]::ToUpper($Flavor[0])+$Flavor.Substring(1)+[char]::ToUpper($BuildType[0])+$BuildType.Substring(1)
$tasks=@($task)
if($Instrumentation){$tasks+=':VyrnApp:assembleDevDebugAndroidTest'}
Push-Location (Join-Path $root 'android/upstream')
try {
    $log=Join-Path $root '.local/android-build.log'
    & (Join-Path $env:JAVA_HOME 'bin/java.exe') -classpath gradle/wrapper/gradle-wrapper.jar org.gradle.wrapper.GradleWrapperMain @tasks "-PVYRN_ABIS=$Abis" "-PVYRN_API_URL=$ApiUrl" "-PVYRN_LOCAL_TLS=$($LocalTest.IsPresent.ToString().ToLowerInvariant())" --console=plain --no-daemon --max-workers=4 --no-watch-fs *> $log
    if($LASTEXITCODE -ne 0){Get-Content -LiteralPath $log -Tail 75;throw 'Android build failed'}
    Get-Content -LiteralPath $log -Tail 5
} finally { Pop-Location }
$output=Join-Path $root "android/app/build/outputs/apk/$Flavor/$BuildType"
Get-ChildItem $output -Filter '*.apk' | ForEach-Object {
    $name=$_.Name
    if($name.Contains('universal') -and $Abis -notmatch ','){$name=$name.Replace('universal','combined-'+$Abis)}
    if($LocalTest){$name=$name.Replace('.apk','-local-tls.apk')}
    Copy-Item -LiteralPath $_.FullName -Destination (Join-Path $root "artifacts/$name")
}
if($Instrumentation){
    Get-ChildItem (Join-Path $root 'android/app/build/outputs/apk/androidTest/dev/debug') -Filter '*.apk' | ForEach-Object {
        Copy-Item -LiteralPath $_.FullName -Destination (Join-Path $root 'artifacts/VyrnApp-dev-debug-androidTest.apk')
    }
}
