param([switch]$Apply)
$ErrorActionPreference='Stop'
foreach($name in @('CLOUDFLARE_API_TOKEN','CLOUDFLARE_ZONE_ID')){if(![Environment]::GetEnvironmentVariable($name)){throw "Missing environment variable: $name"}}
$headers=@{Authorization='Bearer '+$env:CLOUDFLARE_API_TOKEN}
$base='https://api.cloudflare.com/client/v4/zones/'+$env:CLOUDFLARE_ZONE_ID
$names=@('vyrn','api.vyrn','ws.vyrn','media.vyrn','push.vyrn','admin.vyrn','updates.vyrn','turn.vyrn')
$plan=@()
foreach($prefix in $names){
 $name=$prefix+'.eclipseofficial.xyz'
 try {$response=Invoke-RestMethod -Uri ($base+'/dns_records?name='+$name) -Headers $headers} catch {throw 'Cloudflare read failed; no credentials or response body printed'}
 if(!$response.success){throw 'Cloudflare lookup rejected'}
 if($response.result.Count -eq 0){$plan+=@{name=$name;action='create'};continue}
 $records=@($response.result)
 if($records.Count -ne 1 -or $records[0].type -ne 'A' -or $records[0].content -ne '217.18.90.114' -or $records[0].proxied){throw "Existing DNS conflict at $name; preserved for review"}
 $plan+=@{name=$name;action='unchanged'}
}
foreach($item in $plan){
 if($Apply -and $item.action -eq 'create'){
  $body=@{type='A';name=$item.name;content='217.18.90.114';ttl=300;proxied=$false}|ConvertTo-Json -Compress
  try {$result=Invoke-RestMethod -Method POST -Uri ($base+'/dns_records') -Headers $headers -ContentType 'application/json' -Body $body} catch {throw 'Cloudflare write failed; no credentials or response body printed'}
  if(!$result.success){throw 'Cloudflare write rejected'}
 }
 [pscustomobject]@{Name=$item.name;Action=$item.action;Applied=[bool]$Apply}
}
