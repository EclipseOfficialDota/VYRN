# Contributing

Use focused branches from `develop` (the project's automation uses `codex/`). Keep changes small, describe the problem and validation in the pull request, and update the relevant guide/feature status. `main` is the public delivery branch; its name does not imply Stable acceptance.

## Checks

- Run `python3 scripts/check-repository.py` before committing.
- Use an isolated disposable PostgreSQL database for backend tests; set `DATABASE_URL`, then run `go test -race -count=1 ./...` and `go vet ./...` in `backend/`.
- For Android changes, use the relevant [native/emulator scenario](docs/ANDROID_TESTING.md), record artifact hashes and report actual failures.
- CI checks repository hygiene, administrator JavaScript, shell/PowerShell syntax, Go formatting/vet, PostgreSQL integration/race tests, reachable vulnerabilities and container compilation.

Run suites sequentially. Keep emulators off during builds, use one by default and two only when testing independent clients. Low-resource settings are in the existing scripts; never weaken assertions to reduce resource use.

Never commit credentials, real environments, keys/keystores, backups, private media, SDKs or generated upstream/build artifacts. The repository scanner detects known patterns, not every possible secret. Preserve upstream/dependency licenses and corresponding-source requirements.

Do not label Debug builds Stable. Keep calls disabled until implementation, network prerequisites and acceptance are complete. See [release gates](docs/ACCEPTANCE.md). Report vulnerabilities through the private channel described in [SECURITY](SECURITY.md), not a public issue.
