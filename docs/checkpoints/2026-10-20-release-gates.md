# Critical checks and final Release gate — 2026-10-20

The source repository is public at https://github.com/EclipseOfficialDota/VYRN. The owner explicitly selected final Release only on 2026-10-20: do not publish another Debug prerelease in place of the final artifact. The existing `debug-20261019` prerelease remains an older development snapshot. No new Stable release is claimed.

## Fresh Android checks

Sequential API30 runs used the existing local-TLS product APK, SHA256 `3bb91565269cca2ca825969fd12f57625d7d564f31656a8bbee19d5a6528ac04`:

| Report under ignored `artifacts/` | Recorded checks | Distinct names | Passed timestamp (+03:30) |
| --- | ---: | ---: | --- |
| `android-auth-ui-api30.json` | 12 | 12 | 21:45:56 |
| `android-integration-api30.json` | 93 | 93 | 21:52:06 |
| `android-signal-network-api30.json` | 238 | 230 | 21:53:04 |
| `android-sessions-network-api30.json` | 95 | 78 | 21:55:42 |

These are 438 recorded checks, not 438 independent end-to-end user journeys. Signal's 169/46/23-check phases exercise real TLS key exchange, native encryption, interrupted connectivity and new app processes; they do not exercise the private composer UI. Sessions checks exercise the native list, failures/retry and server rejection after individual/bulk revocation. The integration suite includes populated historical outbox migrations. Phone/ARM runtime acceptance and production Release optimization/signing are not established by these x86_64 Debug runs.

The rebuilt instrumentation APK has SHA256 `c36b6f614302db73c2c4152df9c6abb9ee2e36277c651487365756035eee06ab`; integration, Signal and sessions use it. The earlier auth pass used the prior test APK. Product code and the existing normal ARM Debug APKs were unchanged.

## Failures and fixture repair

Local Core/TLS services were restored without resetting PostgreSQL or rotating the test CA. Registration initially returned HTTP400 for its local invite. The existing API invite-renewal helper returned HTTP401; its admin-auth cause is unresolved. A fresh hashed invite was inserted only into the local test database and the ignored local fixture was updated. No production account or credential was changed. The subsequent native auth run passed.

The migration fixture originally used SQLite `DROP COLUMN`, which API30 does not support. Only instrumentation code changed: it now reconstructs the historical schema in a transaction with explicit columns, existing data, defaults and constraints preserved. The real product migration then passed all 93 integration checks. This fixes the test setup, not a demonstrated production migration defect. The test-only build completed in 2m57s.

An initial two-client repeat using emulator37.2.12/software passed both native registrations, then both host emulator processes exited with Windows access-violation code `-1073741819`. Messaging stopped at `chat-send` instrumentation start; that run is not accepted. A repeat using installed37.3.2/swangle again passed both native registrations (16 checks per client), but the peer emulator exited with the same host access-violation code and `chat-receive` failed at instrumentation start. The surviving owned emulator was shut down afterward. Neither run establishes current send/edit/delete/offline acceptance; the older offline report is not fresh evidence. Resolve the host emulator crash or use another authorized test environment before accepting this gate. Physical-phone work remains excluded by owner instruction.

## Delivery gates

The previous source CI run39314802895 passed repository checks, backend race/integration tests, vulnerability checking and the production container build. A full-history Gitleaks scan on 2026-10-20 covered177 commits and found no leaks; the tracked-file checker passed204 files before this checkpoint was added.

Final signing variables were absent when checked. Follow [owner-controlled signing](../RELEASE_SIGNING.md), keep the keystore outside Git/VPS and do not send passwords through chat. After signing, verify the actual Release APKs and run Release-specific runtime acceptance before assigning a Stable tag. Existing Debug evidence must not be relabelled as that acceptance. Calls remain disabled pending inbound UDP confirmation; external backups remain pending a destination. The full project acceptance matrix is still incomplete.
