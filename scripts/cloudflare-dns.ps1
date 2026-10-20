param(
 [Parameter(Mandatory)][ValidatePattern('^(?=.{1,253}$)(?:[a-zA-Z0-9](?:[a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?\.)+[a-zA-Z0-9](?:[a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?$')][string]$Domain,
 [Parameter(Mandatory)][string]$IPv4,
 [switch]$Apply
)
$ErrorActionPreference='Stop'
$address=$null
if(![Net.IPAddress]::TryParse($IPv4,[ref]$address) -or $address.AddressFamily -ne [Net.Sockets.AddressFamily]::InterNetwork){throw 'A valid IPv4 address is required'}
foreach($name in @('CLOUDFLARE_API_TOKEN','CLOUDFLARE_ZONE_ID')){if(![Environment]::GetEnvironmentVariable($name)){throw "Missing environment variable: $name"}}
$headers=@{Authorization='Bearer '+$env:CLOUDFLARE_API_TOKEN}
$base='https://api.cloudflare.com/client/v4/zones/'+$env:CLOUDFLARE_ZONE_ID
$names=@('','api.','ws.','media.','push.','admin.','updates.')
$plan=@()
foreach($prefix in $names){
 $name=$prefix+$Domain
 try {$response=Invoke-RestMethod -Uri ($base+'/dns_records?name='+$name) -Headers $headers} catch {throw 'Cloudflare read failed; no credentials or response body printed'}
 if(!$response.success){throw 'Cloudflare lookup rejected'}
 if($response.result.Count -eq 0){$plan+=@{name=$name;action='create'};continue}
 $records=@($response.result)
 if($records.Count -ne 1 -or $records[0].type -ne 'A' -or $records[0].content -ne $IPv4 -or $records[0].proxied){throw "Existing DNS conflict at $name; preserved for review"}
 $plan+=@{name=$name;action='unchanged'}
}
foreach($item in $plan){
 if($Apply -and $item.action -eq 'create'){
  $body=@{type='A';name=$item.name;content=$IPv4;ttl=300;proxied=$false}|ConvertTo-Json -Compress
  try {$result=Invoke-RestMethod -Method POST -Uri ($base+'/dns_records') -Headers $headers -ContentType 'application/json' -Body $body} catch {throw 'Cloudflare write failed; no credentials or response body printed'}
  if(!$result.success){throw 'Cloudflare write rejected'}
 }
 [pscustomobject]@{Name=$item.name;Action=$item.action;Applied=[bool]$Apply}
}
