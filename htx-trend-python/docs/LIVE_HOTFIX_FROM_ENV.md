# LIVE hotfix — `from_env` slots descriptor crash (P0)

## Symptom

Worker fails at startup when `HTX_TREND_LEVERAGE` (and other unset `HTX_TREND_*` cooldown MS vars) are not in env:

```text
ValueError: invalid literal for int() with base 10: "<member 'leverage' of 'EntryExecutionConfig' objects>"
```

## Fix

`EntryExecutionConfig.from_env` and `ExecutionConfig.from_env` now use `defaults = cls()` instead of `cls.<field>` (slots dataclass member descriptors).

## Ops handoff (after installing this package)

1. Install tarball from `dist/` (see SHA256 below; rebuild with `scripts/package.py` on the merged commit if dist is not shipped in git).
2. In **`htx-python.env`** (or host env file loaded by `htx-trend-worker@`), set:

   ```bash
   HTX_TREND_LEVERAGE=10
   ```

   (`from_env` reads this as int; dataclass default remains 5 when unset.)

3. Reload env / redeploy `current` symlink per your standard release procedure.
4. Enable worker (PRIMARY-only gate unchanged):

   ```bash
   systemctl enable --now htx-trend-worker@htx-trend-live-primary
   ```

5. Confirm unit stays active and logs show config load without `ValueError`.

## Build (maintainers)

```bash
cd htx-trend-python
SKIP_PACKAGE_VERIFY=1 python3 scripts/package.py --version 0.1.1-live --branch <release-branch>
```

Artifact: `dist/htx-trend-python-0.1.1-live-<short-sha>.tar.gz` and matching `.sha256`.
