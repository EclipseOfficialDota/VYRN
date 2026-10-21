# Validation record

These are scoped development checks, not a production certification. Reports and APKs are generated under ignored `artifacts/`; historical records remain in [checkpoints](checkpoints/2026-10-20-release-gates.md) and Git history.

## Installer regression patch — 2026-10-21

The first CI run of this patch also found reachable standard-library advisory `GO-2026-6809` in Go1.26.9 (`crypto/x509`). The module and both Go build-image pins now require1.26.10, using the verified official multi-platform image digest. The vulnerability gate remains enabled. See the [Go advisory](https://vuln.go.dev/ID/GO-2026-6809.json) and [release history](https://go.dev/doc/devel/release#go1.26.10). Repository changes do not update already-running server binaries; those need a separate reviewed rebuild/deployment.

Three focused regression methods reproduced failures before the fix: bare documentation domains were accepted, service-menu actions bypassed remote Docker environment guards, and a saved remote context was not rejected before daemon access. The menu case covers status/start/stop with both `DOCKER_HOST` and `DOCKER_CONTEXT`.

The shared engine check now validates local context metadata before daemon requests in setup and service management. Bare `example.com/net/org` and their subdomains are rejected consistently; similar real names remain accepted. See [RFC2606](https://www.rfc-editor.org/rfc/rfc2606) for the example-domain reservation.

All22 offline installer tests passed locally on Ubuntu24.04/WSL in0.034s, including local-engine allowance, unrelated-stack preservation and the pre-existing secret/bootstrap/health tests. Shell syntax also passed. CI runs the existing real Docker bootstrap/health/restart check against the patched commit; consult the exact [Actions run](https://github.com/EclipseOfficialDota/VYRN/actions/workflows/ci.yml) for its result. This patch does not establish fresh-host package installation, live DNS changes or full Android acceptance.

## Latest critical Android run — 2026-10-20

| Suite | Recorded checks | Result |
| --- | ---: | --- |
| API30 native authentication | 12 | Passed |
| API30 integration, including populated outbox migrations | 93 | Passed |
| Signal network, offline process restart and reconnect | 238 | Passed across169/46/23-check phases |
| Native session list, retry and individual/bulk revocation | 95 | Passed |

Total:438 recorded checks; some repeat names/operations and are not independent user journeys. Local-TLS main APK SHA256: `3bb91565269cca2ca825969fd12f57625d7d564f31656a8bbee19d5a6528ac04`. Integration/Signal/session instrumentation SHA256: `c36b6f614302db73c2c4152df9c6abb9ee2e36277c651487365756035eee06ab`. Authentication used the earlier instrumentation build.

The API30 migration fixture needed a historical-table reconstruction because that SQLite version cannot DROP COLUMN. Only test setup changed. Real migration checks then passed. An invalid local test invite was replaced locally; the existing renewal helper's HTTP401 remained unresolved. See the [detailed checkpoint](checkpoints/2026-10-20-release-gates.md) for failures and scope.

Two fresh two-client attempts passed independent registration, then stopped when host emulator processes exited with Windows access violation `-1073741819`. **Current send/edit/delete/offline retesting did not pass.** An older successful report must not be presented as fresh acceptance.

## Earlier scoped evidence

- Native group creation/invitation:82 recorded checks; settings/roles/offline-title-save regression:72 on the same product artifact. See [group creation](checkpoints/2026-10-19-group-create.md) and [settings](checkpoints/2026-10-19-group-settings.md).
- Group/private draft cold persistence and clearing:77 recorded checks across three actual processes.
- Earlier targeted suites covered reactions, search, media, private composer behavior and two-client recovery; exact historical evidence is in Git history. They do not establish the whole app's current acceptance.
- Backend CI runs repository hygiene, syntax checks, Go formatting/vet, PostgreSQL integration tests with the race detector, reachable-vulnerability checks and container compilation. Consult [Actions](https://github.com/EclipseOfficialDota/VYRN/actions/workflows/ci.yml) for the result of the exact commit; a workflow file is not proof it passed.

## Remaining gates

Actual final-signed Release/R8/native-library runtime acceptance, reliable fresh two-client acceptance, full UI/lifecycle/background coverage, physical ARM compatibility and complete binary/source/license redistribution checks remain unfinished. External S3 backup transport/restore requires an operator destination and rehearsal. Calls remain unavailable.

Run only relevant suites, sequentially, using [Android testing instructions](ANDROID_TESTING.md). Do not run destructive test fixtures against a production database or physical phone without explicit authorization.
