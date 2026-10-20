"""Candidate artifact validation; a deliberately bounded JSON Schema subset.

Unknown schema keywords fail closed. No runtime or admission rules are changed.
"""
from hashlib import sha256
import json
from pathlib import Path

KINDS = ('strategy_card', 'feature_dict', 'experiment_ledger')


def validate_schema(value, schema, path='$'):
    supported = {'$schema', 'type', 'required', 'properties', 'additionalProperties',
                 'items', 'minItems', 'uniqueItems', 'minLength', 'minimum', 'const', 'enum'}
    if set(schema) - supported:
        raise ValueError(f'{path}: unsupported schema keywords')
    types = {'object': dict, 'array': list, 'string': str, 'boolean': bool, 'integer': int}
    if type(value) is not types.get(schema.get('type')):
        raise ValueError(f'{path}: wrong type')
    if 'const' in schema and value != schema['const']:
        raise ValueError(f'{path}: invalid constant')
    if 'enum' in schema and value not in schema['enum']:
        raise ValueError(f'{path}: invalid enum')
    if isinstance(value, dict):
        if set(schema.get('required', [])) - value.keys():
            raise ValueError(f'{path}: missing required fields')
        props = schema.get('properties', {})
        if schema.get('additionalProperties') is False and value.keys() - props.keys():
            raise ValueError(f'{path}: unknown fields')
        for key, item in value.items():
            if key in props:
                validate_schema(item, props[key], f'{path}.{key}')
    elif isinstance(value, list):
        if len(value) < schema.get('minItems', 0):
            raise ValueError(f'{path}: empty array')
        if schema.get('uniqueItems') and len({json.dumps(v, sort_keys=True) for v in value}) != len(value):
            raise ValueError(f'{path}: duplicate items')
        for index, item in enumerate(value):
            validate_schema(item, schema['items'], f'{path}[{index}]')
    elif isinstance(value, str) and len(value) < schema.get('minLength', 0):
        raise ValueError(f'{path}: empty string')
    elif type(value) is int and value < schema.get('minimum', value):
        raise ValueError(f'{path}: below minimum')


def load_artifact(root: Path, kind: str):
    if kind not in KINDS:
        raise ValueError('unknown artifact kind')
    raw = (root / f'{kind}.example.json').read_bytes()
    value = json.loads(raw)
    validate_schema(value, json.loads((root / f'{kind}.schema.json').read_bytes()))
    return value, {'id': value['id'], 'version': value['version'],
                   'path': f'release/artifacts/{kind}.example.json',
                   'sha256': sha256(raw).hexdigest(),
                   'schema_path': f'release/artifacts/{kind}.schema.json'}


def load_artifacts(root: Path):
    loaded = {kind: load_artifact(root, kind) for kind in KINDS}
    card, features, ledger = (loaded[k][0] for k in KINDS)
    keys = [f['key'] for f in features['features']]
    ids = [e['experiment_id'] for e in ledger['experiments']]
    if len(set(keys)) != len(keys) or len(set(ids)) != len(ids):
        raise ValueError('duplicate feature/experiment identity')
    if not set(card['feature_keys']) <= set(keys) or not set(card['experiment_ids']) <= set(ids):
        raise ValueError('strategy card references unknown features/experiments')
    if any(not set(e['feature_keys']) <= set(keys) for e in ledger['experiments']):
        raise ValueError('experiment references unknown features')
    refs = {kind: pair[1] for kind, pair in loaded.items()}
    refs['feature_dict']['feature_keys'] = keys
    refs['experiment_ledger']['experiment_ids'] = ids
    return card, refs
