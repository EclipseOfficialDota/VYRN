$ErrorActionPreference='Stop'
$descriptor=Get-Content -LiteralPath (Join-Path $PSScriptRoot 'upstream.lock.json') -Raw | ConvertFrom-Json
if($descriptor.repository -ne 'https://github.com/DrKLO/Telegram.git' -or $descriptor.commit -notmatch '^[0-9a-f]{40}$'){throw 'Invalid upstream descriptor'}
$target=Join-Path $PSScriptRoot 'upstream'
$gitPath=(Get-Command git -ErrorAction Stop).Source
$gitRoot=Split-Path (Split-Path $gitPath -Parent) -Parent
$savedPath=$env:PATH
$unixTools=Join-Path $gitRoot 'usr/bin'
if(Test-Path -LiteralPath $unixTools){$env:PATH=$unixTools+[IO.Path]::PathSeparator+$env:PATH}
# Scoped to this generated checkout; do not change the user's global Git trust settings.
$gitOptions=@('-c',"safe.directory=$($target.Replace('\','/'))",'-c',"safe.directory=$($target.Replace('\','/'))/*",'-c','core.longpaths=true')
try {
if(Test-Path -LiteralPath $target){
    if(!(Test-Path -LiteralPath (Join-Path $target '.git'))){throw 'Existing upstream directory is not a Git checkout; preserved without modification'}
    $head=(& git @gitOptions -C $target rev-parse HEAD) -join ''
    if($LASTEXITCODE -ne 0 -or $head -ne $descriptor.commit){throw 'Existing upstream revision differs; preserved without modification'}
}else{
    & git @gitOptions init $target
    if($LASTEXITCODE -ne 0){throw 'Upstream initialization failed'}
    & git @gitOptions -C $target remote add origin $descriptor.repository
    if($LASTEXITCODE -ne 0){throw 'Upstream remote setup failed'}
    & git @gitOptions -C $target fetch --depth 1 origin $descriptor.commit
    if($LASTEXITCODE -ne 0){throw 'Pinned upstream fetch failed'}
    & git @gitOptions -C $target checkout --detach $descriptor.commit
    if($LASTEXITCODE -ne 0){throw 'Pinned upstream checkout failed'}
}
& git @gitOptions -C $target submodule update --init --recursive --depth 1
if($LASTEXITCODE -ne 0){throw 'Pinned submodule initialization failed'}
$status=@(& git @gitOptions -C $target submodule status --recursive)
if($LASTEXITCODE -ne 0 -or @($status | Where-Object {$_ -match '^[+\-U]'}).Count){throw 'Submodule revision verification failed'}
Write-Output 'Exact Telegram revision and recursive submodule pins verified.'
} finally {$env:PATH=$savedPath}
