"""Formal automatic LIVE deployment guards (no exchange HTTP)."""

from __future__ import annotations

import pytest

from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import padding
from cryptography.hazmat.primitives.asymmetric import rsa

from htx_trend.credentials import (
    ProbeCredentialError,
    forbid_exchange_plaintext_env,
    materialize_live_primary,
)
from htx_trend.formal_live import (
    FORMAL_LIVE_ENTRY_PERIOD,
    validate_formal_live_deployment,
)
from htx_trend.mysql_store import AccountCredentialRecord
from htx_trend.execution_service import _numeric_client_order_id
from htx_trend.research.risk.position_sizing import PositionSizer

_INT64_MAX = (1 << 63) - 1


def _enc(key: rsa.RSAPrivateKey, plaintext: str) -> str:
    import base64

    blob = key.public_key().encrypt(
        plaintext.encode("utf-8"),
        padding.OAEP(
            mgf=padding.MGF1(algorithm=hashes.SHA256()),
            algorithm=hashes.SHA256(),
            label=None,
        ),
    )
    return f"ENC({base64.b64encode(blob).decode('ascii')})"


def test_formal_entry_path_uses_numeric_client_order_id() -> None:
    cid = _numeric_client_order_id()
    assert cid.isdigit()
    assert 1 <= int(cid) <= _INT64_MAX


def test_research_position_sizer_r0001_defaults_unchanged() -> None:
    sizer = PositionSizer()
    assert sizer.max_positions == 5
    assert sizer.risk_per_trade_pct == 0.02
    assert sizer.max_total_risk_pct == 0.10


def test_validate_formal_live_accepts_first_cut_env(monkeypatch) -> None:
    monkeypatch.setenv("HTX_TREND_CONTRACTS", "BTC-USDT,ETH-USDT")
    monkeypatch.setenv("HTX_TREND_PERIOD", FORMAL_LIVE_ENTRY_PERIOD)
    monkeypatch.setenv("HTX_TREND_MAX_ACTIVE_POSITIONS", "5")
    monkeypatch.setenv("HTX_TREND_RISK_PER_TRADE_USDT", "6")
    monkeypatch.setenv("HTX_TREND_MAX_TOTAL_RISK_USDT", "30")
    monkeypatch.setenv("HTX_TREND_MAX_SAME_DIRECTION_POSITIONS", "3")
    validate_formal_live_deployment()


def test_validate_formal_live_rejects_wrong_period(monkeypatch) -> None:
    monkeypatch.setenv("HTX_TREND_CONTRACTS", "BTC-USDT")
    monkeypatch.setenv("HTX_TREND_PERIOD", "15min")
    with pytest.raises(SystemExit, match="4hour"):
        validate_formal_live_deployment()


def test_validate_formal_live_rejects_extra_symbol(monkeypatch) -> None:
    monkeypatch.setenv("HTX_TREND_CONTRACTS", "BTC-USDT,SOL-USDT")
    monkeypatch.setenv("HTX_TREND_PERIOD", FORMAL_LIVE_ENTRY_PERIOD)
    with pytest.raises(SystemExit, match="first cut"):
        validate_formal_live_deployment()


def test_materialize_live_primary_requires_enc(tmp_path, monkeypatch) -> None:
    key = rsa.generate_private_key(public_exponent=65537, key_size=2048)
    pem = tmp_path / "key.pem"
    pem.write_bytes(
        key.private_bytes(
            encoding=serialization.Encoding.PEM,
            format=serialization.PrivateFormat.PKCS8,
            encryption_algorithm=serialization.NoEncryption(),
        )
    )
    monkeypatch.setenv("APP_RSA_PRIVATE_KEY_PATH", str(pem))
    record = AccountCredentialRecord(1, "primary", "plain-key", "ENC(x)", "https://api.hbdm.com")
    with pytest.raises(ProbeCredentialError, match="PROBE_SECRET_NOT_ENC"):
        materialize_live_primary(record)


def test_materialize_live_primary_decrypts_enc(tmp_path, monkeypatch) -> None:
    key = rsa.generate_private_key(public_exponent=65537, key_size=2048)
    pem = tmp_path / "key.pem"
    pem.write_bytes(
        key.private_bytes(
            encoding=serialization.Encoding.PEM,
            format=serialization.PrivateFormat.PKCS8,
            encryption_algorithm=serialization.NoEncryption(),
        )
    )
    monkeypatch.setenv("APP_RSA_PRIVATE_KEY_PATH", str(pem))
    record = AccountCredentialRecord(
        1,
        "primary",
        _enc(key, "access"),
        _enc(key, "secret"),
        "https://api.hbdm.com",
    )
    creds = materialize_live_primary(record)
    assert creds.access_key == "access"
    assert creds.secret_key == "secret"


def test_forbid_plaintext_env_blocks_htx_access_key(monkeypatch) -> None:
    monkeypatch.setenv("HTX_ACCESS_KEY", "x")
    with pytest.raises(ProbeCredentialError, match="ENV_KEY_FORBIDDEN"):
        forbid_exchange_plaintext_env()
