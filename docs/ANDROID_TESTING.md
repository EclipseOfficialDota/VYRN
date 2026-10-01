# Local Android integration

Use the project-local `vyrn_api35` emulator. These commands reject other devices and keep production credentials, endpoints and signing separate.

```powershell
./scripts/setup-dev-tls.ps1
./scripts/build-android.ps1 -Abis x86_64 -LocalTest -Instrumentation
./scripts/test-android-integration.ps1
```

The isolated Docker project serves HTTPS at `https://localhost:18443`. ADB reverse connects that loopback port to the emulator. Caddy generates a local CA; only its **public** certificate is copied into ignored debug resources. The network security configuration trusts this CA only for `localhost`; certificate and hostname checks stay enabled. No host or Android system trust store is changed. Default builds use production HTTPS and system roots. Release builds reject the local CA flag.

The script generates an artificial account password and reads the existing local test invite privately. It sends the fixture through stdin into app-private storage, never through command arguments. The test removes the fixture and logs only named checks. Accounts and test groups stay in the disposable local smoke database. The test APK is separate from the app APK and contains no credentials.

The runner starts the real LaunchActivity before testing the repository. A headless emulator can restrict DNS for an app running only in the background; launching the Activity resolved the observed EAI_NODATA failure. This foreground integration suite does **not** establish background sync or push behavior.

Verified checks include TLS trust rejection, disabled cleartext, invite registration, Android Keystore session persistence, group delivery, idempotent retries, Persian text round trips, concurrent access-token renewal, edit/reaction/receipt read models, ordered message IDs, history pagination and the Telegram dialog adapter. A second instrumentation process checks persisted sessions/history/UUID mappings, deletion tombstones and session revocation. Results are saved to `artifacts/android-integration.json`.

This is not complete UI E2E: login button navigation, RTL layouts, themes, media, E2EE, offline sync, notifications and physical devices still need their own checks. Instrumentation slots9001/9002 and compatibility account2 are reserved for these local fixtures; do not run against a user's populated installation.

Development compatibility: migration003 introduces server-assigned message ordinals. APKs built before this change used fetch-order IDs. Migration of Telegram's previously populated UI cache is not validated; use a fresh development installation when testing this transition. No production upgrade compatibility is claimed. The server migration preserves existing UUIDs, orders their backfill by creation time/UUID, and continues the sequence for new messages; populated migration and repeat-migration tests pass.
