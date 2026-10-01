$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
$local = Join-Path $root '.local'
New-Item -ItemType Directory -Path $local -Force | Out-Null
$path = Join-Path $local 'dev.env'
if (Test-Path -LiteralPath $path) { Write-Output 'Local environment already exists; preserved.'; exit 0 }
function New-Token { [Convert]::ToHexString([Security.Cryptography.RandomNumberGenerator]::GetBytes(32)).ToLowerInvariant() }
$db = New-Token
$alphabet='ABCDEFGHIJKLMNOPQRSTUVWXYZ234567'
$totp=-join ([Security.Cryptography.RandomNumberGenerator]::GetBytes(32) | ForEach-Object {$alphabet[$_ -band 31]})
$lines = @(
    "POSTGRES_PASSWORD=$db",
    'POSTGRES_USER=vyrn',
    'POSTGRES_DB=vyrn',
    "DATABASE_URL=postgres://vyrn:${db}@127.0.0.1:55432/vyrn?sslmode=disable",
    'VYRN_BOOTSTRAP_EMAIL=admin@vyrn.local',
    "VYRN_BOOTSTRAP_PASSWORD=$(New-Token)",
    "VYRN_BOOTSTRAP_INVITE=$(New-Token)",
    'VYRN_LISTEN=127.0.0.1:8080',
    "VYRN_MEDIA_DIR=$root/.local/media",
    'VYRN_ADMIN_ORIGIN=http://127.0.0.1:8080',
    "VYRN_ADMIN_DIR=$root/admin",
    "VYRN_ADMIN_TOTP_SECRET=$totp"
)
[IO.File]::WriteAllLines($path,$lines,[Text.UTF8Encoding]::new($false))
Write-Output 'Generated local development configuration in .local/dev.env. Values were not printed.'
