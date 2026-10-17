"""Account credentials loaded from the DB's existing ENC(...) records.

Secrets never enter strategy configuration, factor snapshots or logs.  The
wire format and OAEP parameters match Java ``HtxSecretCrypto`` exactly.
"""
from __future__ import annotations

import base64
from dataclasses import dataclass
from os import environ
from pathlib import Path

from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import padding

from .mysql_store import AccountCredentialRecord


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
    path = private_key_path or environ.get("APP_RSA_PRIVATE_KEY_PATH")
    if not path:
        raise RuntimeError("HTX account secret is encrypted but APP_RSA_PRIVATE_KEY_PATH is not set")
    try:
        key = serialization.load_pem_private_key(Path(path).read_bytes(), password=None)
        plaintext = key.decrypt(base64.b64decode(value[4:-1]), padding.OAEP(
            mgf=padding.MGF1(algorithm=hashes.SHA256()), algorithm=hashes.SHA256(), label=None))
        return plaintext.decode("utf-8")
    except Exception as error:
        raise RuntimeError("HTX account secret decryption failed") from error


def materialize(record: AccountCredentialRecord, *, private_key_path: str | None = None,
                require_encrypted: bool = False) -> HtxCredentials:
    if require_encrypted and not all(
        value.startswith("ENC(") and value.endswith(")")
        for value in (record.api_key_cipher, record.secret_key_cipher)
    ):
        raise RuntimeError("LIVE HTX credentials must be stored as ENC(...) values")
    return HtxCredentials(record.account_id, record.account_label,
                          decrypt_if_encrypted(record.api_key_cipher, private_key_path=private_key_path),
                          decrypt_if_encrypted(record.secret_key_cipher, private_key_path=private_key_path),
                          record.base_url)
