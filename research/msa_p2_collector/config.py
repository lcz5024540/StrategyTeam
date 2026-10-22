"""研究币池。清单来自 yaml，默认读仓库内冻结研究宇宙。"""
from __future__ import annotations

import json
from pathlib import Path

REQUIRED_ANCHORS = ('BTC-USDT', 'ETH-USDT')


def _parse_yaml(text):
    root = {}
    current = None
    for raw in text.splitlines():
        stripped = raw.strip()
        if not stripped or stripped.startswith('#'):
            continue
        if raw[:1] in (' ', '\t'):
            if current is None or not stripped.startswith('- '):
                raise ValueError('币池 yaml 只支持顶层标量和字符串列表')
            root[current].append(stripped[2:].strip().strip('"').strip("'"))
            continue
        key, separator, value = stripped.partition(':')
        if not separator:
            raise ValueError('币池 yaml 缺少冒号')
        value = value.strip().strip('"').strip("'")
        if value == '':
            current = key.strip()
            root[current] = []
        else:
            current = None
            root[key.strip()] = value
    return root


def load_universe(path):
    source = Path(path)
    parsed = _parse_yaml(source.read_text(encoding='utf-8'))
    if parsed.get('contracts_file'):
        contracts_path = Path(parsed['contracts_file'])
        if not contracts_path.is_absolute():
            contracts_path = source.parent / contracts_path
        symbols = list(json.loads(contracts_path.read_text(encoding='utf-8'))['contracts'])
    else:
        symbols = list(parsed.get('contracts') or [])
    symbols.extend(parsed.get('extra_contracts') or [])
    if not symbols or len(symbols) != len(set(symbols)):
        raise ValueError('币池不能为空或重复')
    missing = [anchor for anchor in REQUIRED_ANCHORS if anchor not in symbols]
    if missing:
        raise ValueError('币池必须包含 ' + '、'.join(missing))
    return tuple(symbols)
