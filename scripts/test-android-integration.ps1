# Real Android networking/Keystore integration; not a claim of full UI acceptance.
param([string]$Serial='emulator-5560')
$ErrorActionPreference='Stop'
$root=Split-Path $PSScriptRoot -Parent
$adb=Join-Path $root '.tools/android-sdk/platform-tools/adb.exe'
$package='xyz.eclipseofficial.vyrn.dev'
$avd=(& $adb -s $Serial emu avd name) -join "`n"
if($LASTEXITCODE -ne 0 -or $avd -notmatch '(?m)^vyrn_api35\r?$'){throw 'Only the VYRN test emulator is allowed'}
$apk=Join-Path $root 'artifacts/VyrnApp-dev-x86_64-debug-local-tls.apk'
$testApk=Join-Path $root 'artifacts/VyrnApp-dev-debug-androidTest.apk'
foreach($path in @($apk,$testApk)){
    $output=(& $adb -s $Serial install -r -t $path) -join "`n"
    if($LASTEXITCODE -ne 0 -or $output -notmatch 'Success'){throw 'Test APK installation failed'}
}
# Each suite models a fresh install. Reused emulator state can contain identifier
# mappings from a different disposable database and would invalidate the fixture.
$cleared=(& $adb -s $Serial shell pm clear $package) -join "`n"
if($LASTEXITCODE -ne 0 -or $cleared -notmatch 'Success'){throw 'Test app data reset failed'}
& $adb -s $Serial reverse tcp:18443 tcp:18443
if($LASTEXITCODE -ne 0){throw 'Local TLS port forwarding failed'}
& $adb -s $Serial shell run-as $package mkdir -p files
if($LASTEXITCODE -ne 0){throw 'Debug private directory unavailable'}
$settings=@{}
foreach($line in [IO.File]::ReadAllLines((Join-Path $root '.local/dev.env'))){if($line -match '^([A-Z_]+)=(.*)$'){$settings[$matches[1]]=$matches[2]}}
$fixture=@{email=('android-'+[guid]::NewGuid().ToString('N')+'@vyrn.local');password=[Convert]::ToBase64String([Security.Cryptography.RandomNumberGenerator]::GetBytes(32));invite=$settings.VYRN_BOOTSTRAP_INVITE}
if(!$fixture.invite){throw 'Local bootstrap invite unavailable'}
# Feed test credentials through stdin directly into app-private storage. Never put them in argv or output.
$start=[Diagnostics.ProcessStartInfo]::new($adb)
$start.UseShellExecute=$false
$start.RedirectStandardInput=$true
$start.RedirectStandardOutput=$true
$start.RedirectStandardError=$true
$start.CreateNoWindow=$true
foreach($argument in @('-s',$Serial,'shell','-T','run-as',$package,'sh','-c',"'cat > files/vyrn-test-fixture.json'")){$start.ArgumentList.Add($argument)}
$process=[Diagnostics.Process]::Start($start)
$process.StandardInput.Write(($fixture | ConvertTo-Json -Compress))
$process.StandardInput.Close()
$privateOutput=$process.StandardOutput.ReadToEnd()
$privateError=$process.StandardError.ReadToEnd()
$process.WaitForExit()
if($process.ExitCode -ne 0){throw 'Private test fixture transfer failed'}
$fixture=$null
$rounds=@()
try {
    foreach($phase in @('first','profile','offline','restart')){
        & $adb -s $Serial shell am force-stop $package
        # The full foreground suite shares the production 300-request/minute user
        # limit with realtime reconciliation. Start recovery in a fresh window.
        if($phase -in @('profile','restart')){Write-Output "Waiting 60 seconds for the API rate-limit window before $phase tests.";Start-Sleep -Seconds 60}
        if($phase -eq 'offline'){& $adb -s $Serial reverse --remove tcp:18443}else{& $adb -s $Serial reverse tcp:18443 tcp:18443}
        if($LASTEXITCODE -ne 0){throw 'Test network transition failed'}
        $result=(& $adb -s $Serial shell am instrument -w -e phase $phase "$package.test/xyz.eclipseofficial.vyrn.test.LocalIntegrationRunner") -join "`n"
        [IO.File]::WriteAllText((Join-Path $root ".local/android-integration-$phase.log"),$result)
        if($LASTEXITCODE -ne 0 -or $result -notmatch 'vyrn_result=passed'){
            $stage='instrumentation_start';$failure='unknown'
            if($result -match 'stage=([a-zA-Z0-9_]+)'){$stage=$Matches[1]}
            if($result -match 'failure_type=([a-zA-Z0-9_]+)'){$failure=$Matches[1]}
            if($result -match 'failure_status=([0-9]+)'){$failure+=" HTTP $($Matches[1])"}
            throw "Android integration failed at $stage ($failure)"
        }
        if($result -notmatch 'checks=(\[[^\r\n]*\])'){throw 'Instrumentation report missing'}
        $checks=$Matches[1] | ConvertFrom-Json
        $rounds+=@{phase=$phase;checks=$checks;result='passed'}
        Write-Output "PASS: Android integration phase $phase"
    }
    @{date=[DateTimeOffset]::UtcNow.ToString('o');apk=[IO.Path]::GetFileName($apk);sha256=(Get-FileHash $apk).Hash.ToLowerInvariant();device='vyrn_api35';api=35;transport='HTTPS with scoped local CA';rounds=$rounds;full_ui_e2e='not tested'} | ConvertTo-Json -Depth 6 | Set-Content (Join-Path $root 'artifacts/android-integration.json') -Encoding utf8
} finally {
    & $adb -s $Serial reverse tcp:18443 tcp:18443
    & $adb -s $Serial shell run-as $package rm -f files/vyrn-test-fixture.json
}
