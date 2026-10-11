-- Python-only immutable evidence index. Apply explicitly in a non-production
-- database before integration acceptance. Large raw bodies remain in file CAS.
CREATE TABLE IF NOT EXISTS htx_python_evidence (
  content_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
  kind VARCHAR(64) NOT NULL,
  object_uri VARCHAR(255) NOT NULL,
  payload_json LONGTEXT NULL,
  created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  KEY idx_python_evidence_kind (kind, created_at)
);
