# Security reporting and release status

VYRN is under development and has no supported production release yet. Private E2EE, background synchronization and deployment acceptance remain incomplete. See [the security design](docs/SECURITY.md) and [acceptance matrix](docs/ACCEPTANCE.md).

Report vulnerabilities through GitHub's private vulnerability reporting when it is enabled for this repository. Do not post credentials, private keys, message contents, backups or exploitable deployment details in public issues. Before a production release, the repository owner must configure a working private reporting channel and supported-version policy.

Repository CI has read-only permissions, uses pinned actions, runs PostgreSQL integration/race tests and scans reachable Go vulnerabilities. It never receives production deployment or signing credentials. Local test fixtures and ephemeral CI databases are separate from production data.
