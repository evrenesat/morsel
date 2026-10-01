# Morsel working rules
This is the working checkout on p100 at /root/code/morsel. The user explicitly authorized implementation by ZCode with GLM-5.3-Flash, a public repository, CI and signed APK releases. Read plans/implementation.md first and implement sequentially. Do not use AFlow.

- Keep changes here. Do not edit sibling projects, host/container configuration or global ZCode settings.
- Never send real feeder commands during unattended development, tests or CI. Use fake devices and local mock HTTP servers. No real account secrets are available.
- Exactly one implementation worker. Use bounded sessions; commit progress with conventional type/scope messages, push to main, and keep docs/implementation-status.md current with test/CI evidence and remaining work.
- No automatic feeding retries, delayed queues, invented physical success, hidden device substitution, or credentials in logs/backups/repository. Unit tests must assert HTTP request count under faults.
- Follow the plan's architecture and scope. Escalate genuine external blockers in status but complete independent work.
- Read-only review is performed by the supervising Codex chat. Implement its focused fixes without broad redesign.
- Public publishing of this app is explicitly authorized. Signing key stays outside checkout and in GitHub Actions secrets. Never print it or passwords.
- CI must test, lint, build and run emulator tests. A release must use the tested commit and contain signed APK, checksum and corresponding source. No debug signing for release.
