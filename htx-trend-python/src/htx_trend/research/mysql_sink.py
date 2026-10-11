"""Optional, explicit MySQL delivery. Never auto-create schema at startup."""
from .store import canonical


class MySqlEvidenceSink:
    def __init__(self, connection):
        self.connection = connection

    def __call__(self, key: str, value: dict):
        # Raw exchange bodies remain in file CAS; MySQL stores their immutable
        # reference. Compact manifests/traces are duplicated for queryability.
        object_uri = f"cas://sha256/{key}"
        encoded = None if value["kind"] in {"raw_kline", "runtime_raw_kline"} else canonical(value).decode()
        try:
            with self.connection.cursor() as cursor:
                cursor.execute('INSERT IGNORE INTO htx_python_evidence '
                               '(content_hash,kind,object_uri,payload_json) VALUES (%s,%s,%s,%s)',
                               (key, value['kind'], object_uri, encoded))
                cursor.execute('SELECT kind,object_uri,payload_json FROM htx_python_evidence WHERE content_hash=%s', (key,))
                row = cursor.fetchone()
                stored_kind, stored_uri, stored = ((row['kind'], row['object_uri'], row['payload_json'])
                                                   if isinstance(row, dict) else row)
                import json
                if stored_kind != value['kind'] or stored_uri != object_uri or (
                        stored is not None and canonical(json.loads(stored)) != canonical(value)):
                    raise ValueError('remote evidence collision')
            self.connection.commit()
        except Exception:
            self.connection.rollback()
            raise
