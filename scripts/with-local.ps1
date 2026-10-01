param([Parameter(Mandatory,Position=0)][string]$Command,[Parameter(ValueFromRemainingArguments)][string[]]$Arguments)
$ErrorActionPreference='Stop'
$root=Split-Path $PSScriptRoot -Parent
foreach($line in [IO.File]::ReadAllLines((Join-Path $root '.local/dev.env'))){
    if($line -match '^([A-Z_]+)=(.*)$'){[Environment]::SetEnvironmentVariable($matches[1],$matches[2],'Process')}
}
$env:GOCACHE=Join-Path $root '.local/go-cache'
$env:GOPATH=Join-Path $root '.local/go-path'
$env:GOMODCACHE=Join-Path $root '.local/go-mod'
& $Command @Arguments
exit $LASTEXITCODE
