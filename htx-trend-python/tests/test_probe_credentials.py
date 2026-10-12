"""0.1.1-probe PRIMARY credential loader — no exchange, mock DB only."""

from __future__ import annotations

import base64
from pathlib import Path

import pytest
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import padding, rsa

from htx_trend.credentials import (
    DEFAULT_RSA_PRIVATE_KEY_PATH,
    ProbeCredentialError,
    decrypt_probe_secret,
    forbid_exchange_plaintext_env,
    load_probe_primary_credentials,
)
from htx_trend.mysql_store import HtxTrendMySqlRepository


PRIMARY_ROW = {
    "id": 1,
    "account_label": "primary",
    "exchange": "HTX",
    "market_type": "USDT_SWAP",
    "base_url": "https://api.hbdm.com",
    "api_key_cipher": "ENC(dGVzdA==)",
    "secret_key_cipher": "ENC(dGVzdA==)",
    "account_role": "PRIMARY",
}


class Cursor:
    def __init__(self, connection):
        self.connection = connection

    def __enter__(self):
        return self

    def __exit__(self, *_):
        return None

    def execute(self, query, args=()):
        self.connection.queries.append((" ".join(query.split()), args))
        return 1

    def fetchone(self):
        return None

    def fetchall(self):
        query, args = self.connection.queries[-1]
        if "FROM htx_trade_account" in query and "account_role" in query:
            rows = list(self.connection.account_rows)
            if args:
                pinned = int(args[0])
                rows = [row for row in rows if int(row["id"]) == pinned]
            return rows
        return []


class Connection:
    def __init__(self, rows):
        self.account_rows = rows
        self.queries = []
        self.closed = False

    def cursor(self):
        return Cursor(self)

    def commit(self):
        return None

    def rollback(self):
        return None

    def close(self):
        self.closed = True


def _rsa_pem(tmp_path: Path) -> tuple[Path, rsa.RSAPrivateKey]:
    key = rsa.generate_private_key(public_exponent=65537, key_size=2048)
    pem = key.private_bytes(
        encoding=serialization.Encoding.PEM,
        format=serialization.PrivateFormat.PKCS8,
        encryption_algorithm=serialization.NoEncryption(),
    )
    path = tmp_path / "app-private.pem"
    path.write_bytes(pem)
    return path, key


def _enc(key: rsa.RSAPrivateKey, plaintext: str) -> str:
    cipher = key.public_key().encrypt(
        plaintext.encode("utf-8"),
        padding.OAEP(
            mgf=padding.MGF1(algorithm=hashes.SHA256()),
            algorithm=hashes.SHA256(),
            label=None,
        ),
    )
    return f"ENC({base64.b64encode(cipher).decode('ascii')})"


def test_probe_sql_filters_account_role_column_and_never_select_star():
    connection = Connection([PRIMARY_ROW])
    repo = HtxTrendMySqlRepository(connection)
    with pytest.raises(ProbeCredentialError):
        # cipher is not real ENC-OAEP; SQL must still be issued first
        load_probe_primary_credentials(repo, private_key_path="/missing.pem")
    query, args = connection.queries[-1]
    assert "SELECT *" not in query
    assert "account_role" in query
    assert "PRIMARY" in query
    assert "enabled" in query and "1" in query
    assert "trend_enabled" in query
    assert "api_key_cipher" in query
    assert "secret_key_cipher" in query
    assert "exchange" in query
    assert "market_type" in query
    assert "base_url" in query
    assert "LIMIT" not in query.upper()
    assert "mirror_enabled" not in query
    assert "HTX_TREND_MIRROR" not in query
    assert args == ()


def test_zero_primary_rows_hard_exit_without_decrypt(monkeypatch):
    monkeypatch.delenv("APP_RSA_PRIVATE_KEY_PATH", raising=False)
    repo = HtxTrendMySqlRepository(Connection([]))
    with pytest.raises(ProbeCredentialError, match="PRIMARY_ACCOUNT_NONE"):
        load_probe_primary_credentials(repo, private_key_path="/no-such.pem")


def test_two_primary_rows_hard_exit():
    rows = [dict(PRIMARY_ROW), dict(PRIMARY_ROW, id=2, account_label="primary-2")]
    repo = HtxTrendMySqlRepository(Connection(rows))
    with pytest.raises(ProbeCredentialError, match="PRIMARY_ACCOUNT_NOT_UNIQUE"):
        load_probe_primary_credentials(repo, private_key_path="/no-such.pem")


def test_non_primary_row_is_never_decrypted(monkeypatch):
    decrypted = []

    def boom(_value, *, private_key_path=None):
        decrypted.append(True)
        raise AssertionError("must not decrypt non-PRIMARY")

    monkeypatch.setattr("htx_trend.credentials.decrypt_probe_secret", boom)
    mirror = dict(PRIMARY_ROW, id=9, account_label="mirror", account_role="MIRROR")
    # WHERE should already exclude this; if a row leaks through, still reject.
    repo = HtxTrendMySqlRepository(Connection([mirror]))
    with pytest.raises(ProbeCredentialError, match="PRIMARY_ACCOUNT_"):
        load_probe_primary_credentials(repo, private_key_path="/no-such.pem")
    assert decrypted == []


def test_probe_rejects_plaintext_non_enc(tmp_path):
    path, _key = _rsa_pem(tmp_path)
    row = dict(PRIMARY_ROW, api_key_cipher="plaintext-key", secret_key_cipher="ENC(dGVzdA==)")
    with pytest.raises(ProbeCredentialError, match="PROBE_SECRET_NOT_ENC"):
        decrypt_probe_secret(row["api_key_cipher"], private_key_path=str(path))


def test_probe_decrypt_oaep_roundtrip(tmp_path):
    path, key = _rsa_pem(tmp_path)
    secret = decrypt_probe_secret(_enc(key, "htx-access"), private_key_path=str(path))
    assert secret == "htx-access"


def test_probe_decrypt_failure_hard_exits(tmp_path):
    path, _key = _rsa_pem(tmp_path)
    with pytest.raises(ProbeCredentialError, match="PROBE_DECRYPT_FAILED"):
        decrypt_probe_secret("ENC(bm90LW9hZXA=)", private_key_path=str(path))


def test_load_success_requires_enc_and_primary_filter(tmp_path, monkeypatch):
    path, key = _rsa_pem(tmp_path)
    monkeypatch.setenv("APP_RSA_PRIVATE_KEY_PATH", str(path))
    row = dict(
        PRIMARY_ROW,
        api_key_cipher=_enc(key, "ak"),
        secret_key_cipher=_enc(key, "sk"),
    )
    creds = load_probe_primary_credentials(HtxTrendMySqlRepository(Connection([row])))
    assert creds.account_id == 1
    assert creds.account_label == "primary"
    assert creds.access_key == "ak"
    assert creds.secret_key == "sk"
    assert creds.base_url == "https://api.hbdm.com"


def test_pinned_id_still_requires_primary_where(tmp_path, monkeypatch):
    path, key = _rsa_pem(tmp_path)
    monkeypatch.setenv("APP_RSA_PRIVATE_KEY_PATH", str(path))
    row = dict(PRIMARY_ROW, api_key_cipher=_enc(key, "ak"), secret_key_cipher=_enc(key, "sk"))
    connection = Connection([row])
    load_probe_primary_credentials(
        HtxTrendMySqlRepository(connection), pinned_account_id=1
    )
    query, args = connection.queries[-1]
    assert "account_role" in query and "PRIMARY" in query
    assert args == (1,)


def test_forbid_exchange_plaintext_and_mirror_env(monkeypatch):
    monkeypatch.setenv("HTX_ACCESS_KEY", "x")
    with pytest.raises(ProbeCredentialError, match="ENV_KEY_FORBIDDEN"):
        forbid_exchange_plaintext_env(environ=None)
    monkeypatch.delenv("HTX_ACCESS_KEY")
    monkeypatch.setenv("HTX_SECRET_KEY", "y")
    with pytest.raises(ProbeCredentialError, match="ENV_KEY_FORBIDDEN"):
        forbid_exchange_plaintext_env()
    monkeypatch.delenv("HTX_SECRET_KEY")
    monkeypatch.setenv("HTX_TREND_MIRROR_ACCOUNT_ID", "2")
    with pytest.raises(ProbeCredentialError, match="ENV_KEY_FORBIDDEN"):
        forbid_exchange_plaintext_env()


def test_default_rsa_path_is_ops_pem():
    assert DEFAULT_RSA_PRIVATE_KEY_PATH == "/opt/coinstrategy/app-private.pem"


def test_formal_decrypt_if_encrypted_still_allows_plaintext():
    from htx_trend.credentials import decrypt_if_encrypted

    assert decrypt_if_encrypted("plain") == "plain"


def test_locked_primary_apikey_spec_hash():
    from hashlib import sha256
    from pathlib import Path

    path = Path(__file__).resolve().parents[1] / "docs" / "primary-apikey-read-method.md"
    digest = sha256(path.read_bytes()).hexdigest()
    assert digest == "220ab16670c4c75a0bea14df78ab468caa0cdb0e7ccf36acd81d89edfe022480"


def test_probe_loader_never_calls_enabled_trend_accounts(tmp_path, monkeypatch):
    path, key = _rsa_pem(tmp_path)
    monkeypatch.setenv("APP_RSA_PRIVATE_KEY_PATH", str(path))
    row = dict(PRIMARY_ROW, api_key_cipher=_enc(key, "ak"), secret_key_cipher=_enc(key, "sk"))

    class BoomRepo(HtxTrendMySqlRepository):
        def enabled_trend_accounts(self):
            raise AssertionError("probe must not scan enabled_trend_accounts / MIRROR")

        def account_credentials(self, account_id: int):
            raise AssertionError("probe must not use unfiltered account_credentials")

    BoomRepo.primary_probe_account_rows = HtxTrendMySqlRepository.primary_probe_account_rows
    creds = load_probe_primary_credentials(BoomRepo(Connection([row])))
    assert creds.access_key == "ak"
