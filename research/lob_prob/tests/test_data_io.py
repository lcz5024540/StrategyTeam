"""只读本地 CSV / Parquet。远程路径在打开文件之前拒绝。"""
import pytest

from data_io import ParquetParserUnavailable, RemotePathRejected, load_table


def test_loads_local_csv(tmp_path):
    path = tmp_path / "book.csv"
    path.write_text("ts,mid,qb,qa\n11,100,3,1\n", encoding="utf-8")
    assert load_table(path) == [{"ts": "11", "mid": "100", "qb": "3", "qa": "1"}]


def test_rejects_remote_uri_before_read():
    with pytest.raises(RemotePathRejected):
        load_table("https://example.com/book.csv")
    with pytest.raises(RemotePathRejected):
        load_table("s3://bucket/book.parquet")


def test_missing_local_file_raises(tmp_path):
    with pytest.raises(FileNotFoundError):
        load_table(tmp_path / "missing.csv")


def test_parquet_stays_on_local_disk(tmp_path):
    path = tmp_path / "book.parquet"
    path.write_bytes(b"not-a-parquet")
    with pytest.raises((ParquetParserUnavailable, Exception)) as caught:
        load_table(path)
    assert not isinstance(caught.value, RemotePathRejected)
    assert "http" not in str(caught.value).lower()
