from datetime import datetime, UTC, timedelta
from hashlib import sha256
from pathlib import Path
import pytest
from htx_trend.admission import seal, canonical
from htx_trend.realtime.trial_admission import validate, SCHEMA, BINDINGS
from htx_trend.realtime.trial import Limits
from htx_trend.realtime import VERSION


def artifact(tmp_path):
    (tmp_path / "confirmation.txt").write_text(
        "User confirmed <=10 slots; no first-intent pause. Management now, candidate later."
    )
    now = datetime.now(UTC)
    value = dict(
        schema=SCHEMA,
        synthetic=False,
        formal_admission="NOT_ELIGIBLE",
        permission="MANAGEMENT",
        trial_id="test",
        instance_key="15m",
        account_id=7,
        strategy_version=VERSION,
        profile="INTRADAY_15M",
        source_commit="a" * 40,
        package_sha256="b" * 64,
        config_fingerprint="c" * 64,
        parameters_hash="d" * 64,
        exit_policy_hash="e" * 64,
        contracts_hash="f" * 64,
        execution_hash="1" * 64,
        candidate=None,
        limits=Limits().payload(),
        waived_conditions=["FORMAL_PROFITABILITY", "PAPER_14_DAYS"],
        approved_by="user",
        confirmation_ref="confirmation.txt",
        confirmation_sha256=sha256(
            (tmp_path / "confirmation.txt").read_bytes()
        ).hexdigest(),
        issued_at=now.isoformat(),
        expires_at=(now + timedelta(days=1)).isoformat(),
    )
    return value


def save(tmp_path, value):
    p = tmp_path / "approval.json"
    p.write_text(canonical(seal(value)))
    return p


def test_management_cannot_authorize_entry_and_expiry_does_not_disable_management(
    tmp_path,
):
    v = artifact(tmp_path)
    p = save(tmp_path, v)
    validate(p, v)
    with pytest.raises(ValueError, match="candidate"):
        validate(p, v, entry=True)
    later = datetime.now(UTC) + timedelta(days=2)
    validate(p, v, now=later)
    with pytest.raises(ValueError, match="expired"):
        validate(p, v, now=later, entry=True)


@pytest.mark.parametrize("key", BINDINGS)
def test_identity_binding_cannot_be_replaced(tmp_path, key):
    v = artifact(tmp_path)
    p = save(tmp_path, v)
    with pytest.raises(ValueError, match="binding"):
        validate(p, {**v, key: "other"})


def test_unique_runtime_candidate_and_confirmation_required(tmp_path):
    v = artifact(tmp_path)
    v.update(permission="TRIAL", candidate="MINUTE_RESEARCH")
    with pytest.raises(ValueError, match="candidate"):
        validate(save(tmp_path, v), v, entry=True)
    v["candidate"] = "ORIGINAL_20_32_V1"
    with pytest.raises(ValueError, match="confirmation"):
        validate(save(tmp_path, v), v, entry=True)
    v.update(
        candidate_confirmation_ref=v["confirmation_ref"],
        candidate_confirmation_sha256=v["confirmation_sha256"],
    )
    validate(save(tmp_path, v), v, entry=True)
    (tmp_path / "confirmation.txt").write_text("changed")
    with pytest.raises(ValueError, match="hash"):
        validate(save(tmp_path, v), v, entry=True)


def test_no_deadline_authority_is_explicit_and_still_checks_candidate(tmp_path):
    v = artifact(tmp_path)
    v.update(permission="TRIAL", candidate="ORIGINAL_20_32_V1", expires_at=None,
             candidate_confirmation_ref=v["confirmation_ref"],
             candidate_confirmation_sha256=v["confirmation_sha256"])
    v["limits"]["duration_ms"] = None
    later = datetime.now(UTC) + timedelta(days=365)
    _, limits = validate(save(tmp_path, v), v, entry=True, now=later)
    assert limits.duration_ms is None
    with pytest.raises(ValueError, match="candidate"):
        validate(save(tmp_path, {**v, "permission": "MANAGEMENT"}), v, entry=True, now=later)
    v["issued_at"] = (later + timedelta(days=1)).isoformat()
    with pytest.raises(ValueError, match="not yet valid"):
        validate(save(tmp_path, v), v, entry=True, now=later)


@pytest.mark.parametrize("expiry", ["missing", "timed"])
def test_no_deadline_rejects_missing_or_timed_expiry(tmp_path, expiry):
    v = artifact(tmp_path)
    v["limits"]["duration_ms"] = None
    if expiry == "missing":
        del v["expires_at"]
    with pytest.raises(ValueError, match="explicit|expires_at"):
        validate(save(tmp_path, v), v, entry=True)
