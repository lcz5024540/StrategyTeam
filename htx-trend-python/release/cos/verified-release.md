# Approved server-local release

The public15m capture retains its869660ab package. Independent maintenance uses
`release_verified.py`, frozen by commit and SHA, and imports that exact package's
sealing API. No COS delete operation is available.

The root-owned policy `/etc/coinstrategy/15m-cos-release.json` must contain
`enabled: true`, the exact absolute `root`, `namespace` equal to the COS adapter's
sorted JSON identity (bucket/region/prefix), and a nonempty recorded human
`authorization`. This deployment authorizes only
`/home/ubuntu/coinstrategy-data/15m-cos/capture/segments`.

Data objects are compressed before upload by the existing producer. The upload
timer remains separate. The release timer runs1minute after its last completion;
one invocation attempts at most12 verified generations,3 attempts each, with a
180-second budget checked between API calls. Network calls can overrun that soft
budget; systemd caps the process at10minutes. Contention defers work, never clears
handoff/pin checks. Root free-space guard remains5GiB.

Per-generation exact plans/SHA and durable descriptor files live under the
dedicated release workspace. Persist them before deletion. For an existing plan,
schema/root/namespace/config/epoch/manifest are checked against authorization and
the current control record. Full COS download/hash validation and latest local
dependencies are checked again by `apply_release` before deleting local payloads.
Interrupted unlink resumes the same plan. After all eligible work is reconciled,
a later tick may create a new plan for formerly pinned members. Round-robin
selection prevents a pinned old generation starving newer data.

Metadata, control DB, plans and audit history remain local; payload reclamation
does not promise constant total disk use. A capture already stopped at quota with
a failed consumer guard requires diagnosis; this service does not clear that
guard or restart trading. While healthy, the existing recorder observes layout
revision changes and reclaims quota without restarting.

## Rollback

Stop and disable `coinstrategy-15m-cos-release.timer`, then stop its service.
Keep the release workspace, policy, frozen tool and all COS objects. Existing
upload/collection may continue until their unchanged capacity protections apply.
If an older reader requires all local history, use the existing explicit
segment-restore-local path and validate full restoration before switching it.
Never claim disabling maintenance recreates already released payloads.
