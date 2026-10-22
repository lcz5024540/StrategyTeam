"""只读本地 CSV 或 Parquet。路径里出现 :// 时直接拒绝，不发起连接。"""
import csv
from pathlib import Path


class RemotePathRejected(ValueError):
    """路径是远程 URI，本模块不会去连。"""


class ParquetParserUnavailable(RuntimeError):
    """本机没有 parquet 引擎。调用方应改用 CSV，而不是现场安装。"""


def load_table(path):
    """读本地表格，返回行字典。CSV 字段保持字符串，不做类型猜测。"""
    text = path if isinstance(path, str) else str(path)
    if "://" in text:
        raise RemotePathRejected("只读本地文件，拒绝远程路径")
    file_path = Path(text)
    if file_path.is_dir():
        raise IsADirectoryError(text)
    if not file_path.is_file():
        raise FileNotFoundError(text)
    suffix = file_path.suffix.lower()
    if suffix == ".csv":
        return _read_csv(file_path)
    if suffix == ".parquet":
        return _read_parquet(file_path)
    raise ValueError("仅支持本地 CSV 或 Parquet")


def _read_csv(path):
    with path.open("r", encoding="utf-8-sig", newline="") as handle:
        reader = csv.DictReader(handle)
        if not reader.fieldnames:
            raise ValueError("CSV 没有表头")
        return [dict(row) for row in reader]


def _read_parquet(path):
    try:
        import pyarrow.parquet as pq
    except ImportError:
        pq = None
    if pq is not None:
        return pq.read_table(path).to_pylist()
    try:
        import pandas as pd
    except ImportError as exc:
        raise ParquetParserUnavailable(
            "本地没有 parquet 解析库。本脚手架不联网安装，请改用 CSV。"
        ) from exc
    try:
        frame = pd.read_parquet(path)
    except ImportError as exc:
        raise ParquetParserUnavailable(
            "本地没有 parquet 引擎。本脚手架不联网安装，请改用 CSV。"
        ) from exc
    return frame.to_dict(orient="records")
