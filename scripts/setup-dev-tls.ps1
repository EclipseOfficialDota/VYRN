$ErrorActionPreference='Stop'
$root=Split-Path $PSScriptRoot -Parent
Push-Location $root
try {
    & docker compose --project-name vyrn-local --env-file .local/dev.env -f deployment/compose.yaml -f deployment/compose.local.yaml --profile android up -d devtls
    if($LASTEXITCODE -ne 0){throw 'Local TLS proxy did not start'}
    $publicCA=Join-Path $root '.local/dev-ca.crt'
    $copied=$false
    for($attempt=0;$attempt -lt 15;$attempt++){
        & docker cp vyrn-local-devtls-1:/data/caddy/pki/authorities/local/root.crt $publicCA 2>$null
        if($LASTEXITCODE -eq 0){$copied=$true;break}
        Start-Sleep -Seconds 1
    }
    if(!$copied){throw 'Local public CA certificate not ready'}
    $res=Join-Path $root '.local/android-test-res'
    New-Item -ItemType Directory (Join-Path $res 'raw'),(Join-Path $res 'xml') -Force | Out-Null
    Copy-Item -LiteralPath $publicCA -Destination (Join-Path $res 'raw/vyrn_dev_ca.crt')
    $config=@'
<network-security-config>
  <base-config cleartextTrafficPermitted="false"><trust-anchors><certificates src="system"/></trust-anchors></base-config>
  <domain-config cleartextTrafficPermitted="false">
    <domain includeSubdomains="false">localhost</domain>
    <trust-anchors><certificates src="@raw/vyrn_dev_ca"/></trust-anchors>
  </domain-config>
</network-security-config>
'@
    [IO.File]::WriteAllText((Join-Path $res 'xml/vyrn_network_security.xml'),$config,[Text.UTF8Encoding]::new($false))
    Write-Output 'Local HTTPS is ready on loopback port18443. Public CA copied for opt-in debug builds; no system trust store changed.'
} finally { Pop-Location }
