"""Bounded, atomic local restart state. Never pickle or dynamically import input.

The database/PAPER ledger remains authoritative for positions and executions.
This file carries monitoring progress, unsubmitted candidates and handoff state.
"""
from __future__ import annotations

from dataclasses import fields, is_dataclass
from datetime import datetime, UTC
from decimal import Decimal
from enum import Enum
from hashlib import sha256
import fcntl
import gzip
import json
import os
from pathlib import Path

SCHEMA = 'htx-runtime-continuity-v1'


def registry():
    from . import market_data, models, java_entry_contract, indicators, policy, strategy_context, diagnostics, live_worker
    from .research import regime
    modules = (market_data, models, java_entry_contract, indicators, policy, strategy_context, diagnostics, regime)
    result = {'htx_trend.live_worker._Scan': live_worker._Scan}
    for module in modules:
        for value in vars(module).values():
            if isinstance(value, type) and (is_dataclass(value) or issubclass(value, Enum)):
                result[value.__module__ + '.' + value.__name__] = value
    return result


def encode(value):
    if isinstance(value, Enum):
        return {'type':type(value).__module__+'.'+type(value).__name__, 'enum':value.value}
    if value is None or type(value) in (str, int, bool, float):
        return value
    if isinstance(value, Decimal):
        return {'decimal':str(value)}
    if isinstance(value, datetime):
        return {'datetime':value.isoformat()}
    if is_dataclass(value):
        name=type(value).__module__+'.'+type(value).__name__
        # Config dataclasses may be fingerprinted without being restored.
        return {'type':name, 'fields':{f.name:encode(getattr(value,f.name)) for f in fields(value) if f.init}}
    if isinstance(value, tuple):
        return {'tuple':[encode(v) for v in value]}
    if isinstance(value, list):
        return [encode(v) for v in value]
    if isinstance(value, dict):
        return {'map':[[encode(k),encode(v)] for k,v in value.items()]}
    raise ValueError('unsupported runtime state type: '+type(value).__name__)


def decode(value):
    if isinstance(value, list):
        return [decode(v) for v in value]
    if not isinstance(value, dict):
        return value
    if set(value)=={'decimal'}:
        out=Decimal(value['decimal'])
        if not out.is_finite():
            raise ValueError('non-finite runtime number')
        return out
    if set(value)=={'datetime'}:
        return datetime.fromisoformat(value['datetime'])
    if set(value)=={'tuple'}:
        return tuple(decode(v) for v in value['tuple'])
    if set(value)=={'map'}:
        return {decode(k):decode(v) for k,v in value['map']}
    cls=registry().get(value.get('type'))
    if cls is None:
        raise ValueError('unsupported runtime state class')
    if set(value)=={'type','enum'} and issubclass(cls,Enum):
        return cls(value['enum'])
    if set(value)=={'type','fields'} and is_dataclass(cls):
        if set(value['fields']) != {f.name for f in fields(cls) if f.init}:
            raise ValueError('incompatible runtime dataclass fields')
        return cls(**{k:decode(v) for k,v in value['fields'].items()})
    raise ValueError('invalid runtime state encoding')


def canonical(value):
    return json.dumps(value,sort_keys=True,separators=(',',':'),ensure_ascii=False,allow_nan=False).encode()


def fingerprint(value):
    return sha256(canonical(encode(value))).hexdigest()


class RuntimeCheckpoint:
    def __init__(self, root: Path, identity: dict, *, initialize=False):
        if not root.is_absolute():
            raise ValueError('runtime state directory must be absolute and outside release packages')
        root.mkdir(parents=True,exist_ok=True)
        key=sha256(str(identity['instance_key']).encode()).hexdigest()
        self.path=root/(key+'.json.gz')
        self.lock=(root/(key+'.lock')).open('a')
        self.identity=identity
        try:
            fcntl.flock(self.lock,fcntl.LOCK_EX|fcntl.LOCK_NB)
            self.data=self.read(self.path) if self.path.exists() else None
            if self.data is None:
                if not initialize:
                    raise ValueError('continuity checkpoint missing; explicit first initialization required')
                self.data=dict(schema=SCHEMA,identity=identity,status='STARTING',profiles={},generation=0,
                               updated_at=None,previous_stop=None)
            if self.data['identity'] != identity:
                raise ValueError('runtime checkpoint instance/account/mode mismatch')
            self.previous_status=self.data['status']
            self.previous_at=self.data['updated_at']
            self.save('RECOVERING')
        except BaseException:
            self.lock.close()
            raise

    @staticmethod
    def read(path: Path):
        value=json.loads(gzip.decompress(path.read_bytes()))
        digest=value.pop('hash')
        if value.get('schema')!=SCHEMA or sha256(canonical(value)).hexdigest()!=digest:
            raise ValueError('runtime checkpoint corrupted or unsupported')
        return value

    def save(self,status=None):
        if status is not None:
            self.data['status']=status
        self.data['updated_at']=datetime.now(UTC).isoformat()
        self.data['generation']+=1
        value={**self.data,'hash':sha256(canonical(self.data)).hexdigest()}
        temporary=self.path.with_suffix('.tmp')
        descriptor=os.open(temporary,os.O_WRONLY|os.O_CREAT|os.O_TRUNC,0o600)
        with os.fdopen(descriptor,'wb') as stream:
            stream.write(gzip.compress(canonical(value),mtime=0));stream.flush();os.fsync(stream.fileno())
        os.replace(temporary,self.path)
        directory=os.open(self.path.parent,os.O_RDONLY)
        try: os.fsync(directory)
        finally: os.close(directory)

    def close(self):
        self.lock.close()


class PersistentWorker:
    """Persist at action boundaries and release the continuity lock last."""
    def __init__(self, worker, checkpoint, stop_event):
        self.worker,self.checkpoint,self.stop_event=worker,checkpoint,stop_event
        self.restart_gap_seconds=((datetime.now(UTC)-datetime.fromisoformat(checkpoint.previous_at)).total_seconds()
                                  if checkpoint.previous_at else None)
        self.children=getattr(worker,'_workers',(worker,))
        for child in self.children:
            if hasattr(child,'restore_runtime'):
                key=child.runtime_key()
                saved=checkpoint.data['profiles'].get(key)
                if saved is not None:
                    child.restore_runtime(decode(saved))
                child._save_runtime=self.capture
                child._drain_requested=stop_event.is_set
        self.capture('RECOVERING')

    def capture(self,status=None):
        for child in self.children:
            if hasattr(child,'export_runtime'):
                self.checkpoint.data['profiles'][child.runtime_key()]=encode(child.export_runtime())
        self.checkpoint.save(status)

    def tick(self):
        from .strategy_platform import WorkerHealth
        if self.stop_event.is_set():
            self.capture('DRAINING')
            return WorkerHealth('STOPPED','drain requested; no new risk')
        failures=[]
        for child in self.children:
            if hasattr(child,'prepare_management'):
                try:
                    failures.extend(child.prepare_management())
                except Exception as error:
                    child._prepared_management=({},['recovery failed:'+type(error).__name__])
                    failures.append(type(error).__name__)
        for child in self.children:
            child._entry_recovery_blocked=bool(failures)
        result=self.worker.tick()
        self.capture('DRAINING' if self.stop_event.is_set() else 'READY' if result.status=='HEALTHY' else 'DEGRADED')
        gap=self.restart_gap_seconds
        return WorkerHealth(result.status,result.detail+f' continuity={self.checkpoint.data["status"]} previous={self.checkpoint.previous_status} restartGapSeconds={gap}',
                            result.candidate_count,result.active_position_count)

    def stop(self):
        self.capture('DRAINING')
        self.worker.stop()
        self.checkpoint.data['previous_stop']=datetime.now(UTC).isoformat()
        self.capture('STOPPED')
