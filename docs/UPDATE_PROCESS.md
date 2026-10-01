# Builds, signing and updates

## Debug

`scripts/build-android.ps1` supports dev, staging and production flavors and defaults to dev/debug. VYRN source lives outside the generated upstream checkout. Early builds use Android's debug identity. They must not be described as signed production releases.

## Release identity — user gate

Create the final keystore in a private local directory outside this repository. Run the following interactively in a private terminal; keytool prompts for passwords without putting them in command arguments:

```powershell
keytool -genkeypair -v -keystore "$env:VYRN_KEYSTORE_PATH" -alias "$env:VYRN_KEY_ALIAS" -keyalg RSA -keysize 4096 -validity 10000
```

Set `VYRN_KEYSTORE_PATH` and `VYRN_KEY_ALIAS` to non-secret location/name values first. Set `VYRN_STORE_PASSWORD` and `VYRN_KEY_PASSWORD` only through private secure prompts or a secret manager. Never paste them into chat. Store an encrypted offline copy of the keystore and recovery information. The final key must never be sent to the VPS or committed.

The build rejects release tasks if any required signing variable is absent. Minification and resource shrinking are configured but release R8 validation and device tests remain mandatory. Produce and verify universal, arm64-v8a and armeabi-v7a APKs; build an AAB if the verified pipeline supports it.

## Signed manifest format

`backend/internal/updates` signs the exact UTF-8 JSON payload with Ed25519 and wraps it as `payload_base64` plus `signature_base64`. Verify the signature before decoding JSON. The signed payload includes version code/name, minimum supported version, forced-update flag, APK HTTPS URL, SHA-256, size, English/Persian changelog and expiry.

`vyrn-update` reads the update-signing key from `VYRN_UPDATE_PRIVATE_KEY_FILE`; it does not create or print a key. The update key is a separate offline key, not the Android keystore. Clients must pin its public key, reject rollback/expired manifests, restrict downloads to the approved update hostname, enforce maximum size and verify the entire downloaded APK hash before handing it to the system installer. Manifest checks have Go unit tests; Android updater and signed remote-configuration integration are still incomplete.

Publish APKs, the signed envelope, changelog and corresponding complete source only after successful final tests. Keep release key material local. Caddy serves public update artifacts from `artifacts/updates`, which contains no private credentials.
