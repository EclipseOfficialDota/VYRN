# Contributing

Use `codex/` branches from `develop`. Keep changes reviewable and update `PROJECT_MEMORY.md`, `docs/ACCEPTANCE.md` and `docs/TEST_REPORT.md` with actual evidence. Read the confirmed decisions before changing product behavior. `main` is the intended delivery branch; a branch name does not imply production readiness.

Run backend tests against an isolated local PostgreSQL database, run `go vet ./...`, and check Android changes with the appropriate emulator suite. CI checks repository hygiene, script syntax, Go formatting/tests/race detection, reachable vulnerabilities and container compilation. GitHub CI execution must be verified after the repository is created; a workflow file alone is not a passing check.

Never commit credentials, real environment files, keystores, backup data, private media, SDKs or generated upstream/build artifacts. Run `python scripts/check-repository.py` before publishing. Its pattern checks supplement manual review; they cannot prove that every arbitrary secret is absent.

Do not enable private chat before reviewed E2EE and device verification work. Calls stay disabled until inbound UDP and TURN tests pass. Do not publish a release until the complete acceptance matrix is verified, corresponding source is provided, external backup restoration passes and final signing is configured privately.
