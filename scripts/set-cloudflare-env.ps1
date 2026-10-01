# Run in your private PowerShell session, then launch Codex from the same session.
# Do not paste values into chat or commands. These prompts do not echo the token.
$ErrorActionPreference='Stop'
$secret=Read-Host 'CLOUDFLARE_API_TOKEN' -AsSecureString
try {$env:CLOUDFLARE_API_TOKEN=[Net.NetworkCredential]::new('', $secret).Password} finally {$secret.Dispose()}
$env:CLOUDFLARE_ZONE_ID=Read-Host 'CLOUDFLARE_ZONE_ID'
Write-Output 'Cloudflare variables set for this PowerShell process. Token was not printed.'
