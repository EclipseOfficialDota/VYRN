# Owner-controlled final signing

The current normal APKs are Debug builds. Do not label them as a signed production Stable release. Keep the final signing key outside this repository and off the VPS, and keep a separate offline copy.

Run the following yourself in PowerShell7. The key tool prompts for passwords without putting them in command arguments. No password or key should be sent through chat.

```powershell
$signingDirectory = Join-Path $env:USERPROFILE 'VYRN-signing'
New-Item -ItemType Directory -Path $signingDirectory -Force | Out-Null
$signingKey = Join-Path $signingDirectory 'vyrn-release.jks'
if (Test-Path -LiteralPath $signingKey) { throw 'A signing key already exists; preserve and reuse it.' }
& ./.tools/jdk21/jdk-21.0.12.1+1/bin/keytool.exe -genkeypair -storetype JKS -keystore $signingKey -alias vyrn -keyalg RSA -keysize 3072 -validity 10000
if ($LASTEXITCODE -ne 0) { throw 'Key creation failed' }
```

Prepare the four required variables in the same console. `Read-Host -MaskInput` avoids echoing passwords; these values are local to this console and are not saved in Git or user-wide environment settings.

```powershell
$env:VYRN_KEYSTORE_PATH = $signingKey
$env:VYRN_KEY_ALIAS = 'vyrn'
$env:VYRN_STORE_PASSWORD = Read-Host 'Keystore password' -MaskInput
$env:VYRN_KEY_PASSWORD = Read-Host 'Key password' -MaskInput
./scripts/build-android.ps1 -Flavor production -BuildType release -Abis 'arm64-v8a,armeabi-v7a'
./scripts/verify-apks.ps1 -Flavor production -BuildType release
Remove-Item Env:VYRN_STORE_PASSWORD,Env:VYRN_KEY_PASSWORD
```

An existing key must always be reused for later updates. Debug and production packages/signers differ; do not promise an in-place upgrade from the existing dev APK. Signing and static APK verification alone do not establish Stable: the actual Release artifacts still need runtime acceptance, including the native Signal library and optimized code. The public repository and a clearly labelled test prerelease can be delivered independently; final Release signing remains pending until the owner prepares the key.
