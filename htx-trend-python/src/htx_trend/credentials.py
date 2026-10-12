"""Account credentials loaded from the DB's existing ENC(...) records.

Secrets never enter strategy configuration, factor snapshots or logs.  The
wire format and OAEP parameters match Java ``HtxSecretCrypto`` exactly.

The 0.1.1-probe loader (``load_probe_primary_credentials``) is a separate
path: PRIMARY SQL filter, ENC-only decrypt, no env key fallback, no MIRROR.
"""
from __future__ import annotations

import base64
from dataclasses import dataclass
from os import environ as os_environ
from pathlib import Path
from typing import Mapping

from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import padding

from .mysql_store import AccountCredentialRecord, HtxTrendMySqlRepository


DEFAULT_RSA_PRIVATE_KEY_PATH = "/opt/coinstrategy/app-private.pem"
FORBIDDEN_ENV_EXACT = ("HTX_ACCESS_KEY", "HTX_SECRET_KEY", "HTX_API_KEY")
FORBIDDEN_ENV_PREFIX = ("HTX_TREND_MIRROR_",)


class ProbeCredentialError(SystemExit):
    """Hard-exit from the probe credential path. Never falls back to env keys."""

    def __init__(self, reason_code: str, message: str) -> None:
        super().__init__(f"{reason_code}: {message}")
        self.reason_code = reason_code


@dataclass(frozen=True, slots=True)
class HtxCredentials:
    account_id: int
    account_label: str
    access_key: str
    secret_key: str
    base_url: str


def decrypt_if_encrypted(value: str, *, private_key_path: str | None = None) -> str:
    if not (value.startswith("ENC(") and value.endswith(")")):
        return value
    path = private_key_path or os_environ.get("APP_RSA_PRIVATE_KEY_PATH")
    if not path:
        raise RuntimeError("HTX account secret is encrypted but APP_RSA_PRIVATE_KEY_PATH is not set")
    try:
        key = serialization.load_pem_private_key(Path(path).read_bytes(), password=None)
        plaintext = key.decrypt(base64.b64decode(value[4:-1]), padding.OAEP(
            mgf=padding.MGF1(algorithm=hashes.SHA256()), algorithm=hashes.SHA256(), label=None))
        return plaintext.decode("utf-8")
    except Exception as error:
        raise RuntimeError("HTX account secret decryption failed") from error


def materialize(record: AccountCredentialRecord, *, private_key_path: str | None = None) -> HtxCredentials:
    return HtxCredentials(record.account_id, record.account_label,
                          decrypt_if_encrypted(record.api_key_cipher, private_key_path=private_key_path),
                          decrypt_if_encrypted(record.secret_key_cipher, private_key_path=private_key_path),
                          record.base_url)


def forbid_exchange_plaintext_env(environ: Mapping[str, str] | None = None) -> None:
    env = os_environ if environ is None else environ
    hits = [name for name in FORBIDDEN_ENV_EXACT if env.get(name)]
    hits.extend(name for name in env if name.startswith(FORBIDDEN_ENV_PREFIX) and env.get(name))
    if hits:
        raise ProbeCredentialError(
            "ENV_KEY_FORBIDDEN",
            "probe forbids exchange plaintext keys and HTX_TREND_MIRROR_*: " + ",".join(sorted(hits)),
        )


def decrypt_probe_secret(value: str, *, private_key_path: str | None = None) -> str:
    if not (value.startswith("ENC(") and value.endswith(")")):
        raise ProbeCredentialError("PROBE_SECRET_NOT_ENC", "cipher must be ENC(<base64>)")
    path = private_key_path or os_environ.get("APP_RSA_PRIVATE_KEY_PATH") or DEFAULT_RSA_PRIVATE_KEY_PATH
    try:
        key = serialization.load_pem_private_key(Path(path).read_bytes(), password=None)
        plaintext = key.decrypt(
            base64.b64decode(value[4:-1]),
            padding.OAEP(
                mgf=padding.MGF1(algorithm=hashes.SHA256()),
                algorithm=hashes.SHA256(),
                label=None,
            ),
        )
        return plaintext.decode("utf-8")
    except ProbeCredentialError:
        raise
    except Exception as error:
        raise ProbeCredentialError("PROBE_DECRYPT_FAILED", "RSA-OAEP decrypt failed") from error


def load_probe_primary_credentials(
    repository: HtxTrendMySqlRepository,
    *,
    pinned_account_id: int | None = None,
    private_key_path: str | None = None,
) -> HtxCredentials:
    """Sole probe credential API: PRIMARY SQL + ENC RSA-OAEP. No env fallback."""
    forbid_exchange_plaintext_env()
    rows = repository.primary_probe_account_rows(pinned_account_id)
    if not rows:
        raise ProbeCredentialError(
            "PRIMARY_ACCOUNT_NONE",
            "no htx_trade_account row with account_role=PRIMARY AND enabled=1 AND trend_enabled=1",
        )
    if len(rows) > 1:
        raise ProbeCredentialError(
            "PRIMARY_ACCOUNT_NOT_UNIQUE",
            f"expected exactly one PRIMARY trend account, got {len(rows)}",
        )
    row = rows[0]
    role = str(row.get("account_role") or "")
    if role != "PRIMARY":
        raise ProbeCredentialError(
            "PRIMARY_ACCOUNT_ROLE",
            "refusing non-PRIMARY account; cipher not decrypted",
        )
    api_cipher = str(row.get("api_key_cipher") or "")
    secret_cipher = str(row.get("secret_key_cipher") or "")
    return HtxCredentials(
        int(row["id"]),
        str(row["account_label"]),
        decrypt_probe_secret(api_cipher, private_key_path=private_key_path),
        decrypt_probe_secret(secret_cipher, private_key_path=private_key_path),
        str(row["base_url"]),
    )
