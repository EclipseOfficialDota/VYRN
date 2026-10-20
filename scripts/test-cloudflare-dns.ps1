# Offline contract check: no DNS requests or real credentials.
$ErrorActionPreference='Stop'
$savedToken=$env:CLOUDFLARE_API_TOKEN
$savedZone=$env:CLOUDFLARE_ZONE_ID
$dnsTestState=@{writes=0;conflict=$false}
function Invoke-RestMethod {
 param($Uri,$Headers,$Method,$ContentType,$Body)
 if($Uri -notlike 'https://api.cloudflare.com/client/v4/zones/test-zone/*'){throw 'Unexpected DNS endpoint'}
 if($Method -eq 'POST'){
  $record=$Body|ConvertFrom-Json
  if($record.content -ne '192.0.2.10' -or $record.proxied -or $record.name -notmatch '(^|\.)chat\.example\.com$'){throw 'Invalid planned DNS record'}
  $dnsTestState.writes++
  return @{success=$true}
 }
 if($dnsTestState.conflict){return @{success=$true;result=@(@{type='A';content='192.0.2.11';proxied=$false})}}
 return @{success=$true;result=@()}
}
try {
 $env:CLOUDFLARE_API_TOKEN='offline-test-only'
 $env:CLOUDFLARE_ZONE_ID='test-zone'
 $scriptPath=Join-Path $PSScriptRoot 'cloudflare-dns.ps1'
 $plan=@(& $scriptPath -Domain chat.example.com -IPv4 192.0.2.10)
 if($plan.Count -ne 7 -or $dnsTestState.writes -ne 0 -or @($plan|Where-Object Applied).Count){throw 'Read-only planning contract failed'}
 $applied=@(& $scriptPath -Domain chat.example.com -IPv4 192.0.2.10 -Apply)
 if($applied.Count -ne 7 -or $dnsTestState.writes -ne 7){throw 'Create contract failed'}
 $dnsTestState.conflict=$true
 $rejected=$false
 try {& $scriptPath -Domain chat.example.com -IPv4 192.0.2.10 -Apply|Out-Null}catch {$rejected=$_.Exception.Message -like 'Existing DNS conflict*'}
 if(!$rejected -or $dnsTestState.writes -ne 7){throw 'Existing records were not preserved'}
 $rejected=$false
 try {& $scriptPath -Domain chat.example.com -IPv4 '::1'|Out-Null}catch {$rejected=$_.Exception.Message -eq 'A valid IPv4 address is required'}
 if(!$rejected){throw 'IPv6 must not be used for an A record'}
 Write-Output 'PASS: DNS plan, custom domain/IP, create, conflict preservation and IPv4 validation (offline).'
} finally {
 $env:CLOUDFLARE_API_TOKEN=$savedToken
 $env:CLOUDFLARE_ZONE_ID=$savedZone
}
