# Verified local COS release
2026-11-04; WIP=1; baseline support_htx_python7183ddd5; branch codex/15m-cos-verified-release.

User authorization: server-local copies may be released after upload verification, including the proposed ongoing same-root policy. COS data remain; no database, legacy evidence, trading state or age-based deletion.

- [x] Existing epoch1-9 authorized plans executed; partial1158 files/369564739B reclaimed, no interrupted intents, live PID2361464 unchanged.
- [x] Standalone Python release wrapper, explicit root/namespace/authorization binding, persistent plan retries, round-robin bounded work, unchanged sealing safety checks.
- [ ] Deploy versioned release-only systemd service/timer with exact writable root; observe real release and continued collection.
- [ ] Verification / self-check, evidence, version and rollback record.

Validation: default disabled, wrong binding/contract, remote outage/corruption, plan tampering, interrupted unlink same-plan recovery, active pins/handoff, starvation avoidance, quota refresh and append continuity. Existing core release tests also cover hash damage and crash recovery. No new dependencies, business SQL or trading logic.

Residual risk: release does not bound local control/plan metadata growth; monitor separately. Network failure or write contention can defer release and eventually trigger unchanged2GiB quota. Do not clear failed consumer guards; stop and diagnose. Source version869660ab stays pinned for both collection and maintenance dependencies.
