# Verified local COS release
2026-11-04; WIP=0; baseline support_htx_python7183ddd5; branch codex/15m-cos-verified-release.

User authorization: server-local copies may be released after upload verification, including the proposed ongoing same-root policy. COS data remain; no database, legacy evidence, trading state or age-based deletion.

- [x] Existing epoch1-9 authorized plans executed; partial1158 files/369564739B reclaimed, no interrupted intents, live PID2361464 unchanged.
- [x] Standalone Python release wrapper, explicit root/namespace/authorization binding, persistent plan retries, round-robin bounded work, unchanged sealing safety checks.
- [x] Deploy versioned release-only systemd service/timer with exact writable root; observe real release and continued collection.
- [x] Verification / self-check, evidence, version and rollback record.

Validation: default disabled, wrong binding/contract, remote outage/corruption, plan tampering, interrupted unlink same-plan recovery, active pins/handoff, starvation avoidance, quota refresh and append continuity. Existing core release tests also cover hash damage and crash recovery. No new dependencies, business SQL or trading logic.

Residual risk: release does not bound local control/plan metadata growth; monitor separately. Network failure or write contention can defer release and eventually trigger unchanged2GiB quota. Do not clear failed consumer guards; stop and diagnose. Source version869660ab stays pinned for both collection and maintenance dependencies.

## Retrospective
Delivered ongoing root-bound release and post-delete COS recovery without capture restart. Real contention deferred work safely; metadata remains local and quota stays. Scope and packaging failures caught before deployment, DL084/085 recorded. Next: operators inspect latest.json and quota together; do not equate deferred with completed or delete by age. No strategy adjustment or new skill needed. Evidence: ../evidence/cos-release-20261104/report.md.
