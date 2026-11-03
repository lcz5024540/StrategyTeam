"""Explicit local-consumer handoffs. Failure only blocks archival reclamation."""
from functools import wraps
import logging


def fence_failure(control):
    try:
        control.block_release()
    except Exception:
        # If even a zero-byte durable fence cannot be created, retain an SH lease for
        # the process lifetime. Never expose a deletion window after failed handoff.
        if not getattr(control, '_failure_lease', None):
            control._failure_lease = control.lease()
            control._failure_lease.__enter__()
        logging.getLogger(__name__).error('SEALING_FENCE_IO_FAILURE: reclamation locked; offline repair required')


def paper_roots(worker, control):
    data = worker.ledger.data
    positions = data['positions']
    selected = {k for k,p in positions.items() if p.get('status') != 'CLOSED'}
    latest = {}
    for key,p in positions.items():
        if p.get('status') == 'CLOSED' and p.get('closed_at'):
            code = (p.get('account_id'),p.get('contract_code'))
            if code not in latest or str(p['closed_at']) > str(positions[latest[code]]['closed_at']):
                latest[code] = key
    selected.update(latest.values())
    roots = control.known_hashes({k:v for k,v in data.get('contexts',{}).items() if k in selected})
    for row in data['decisions']:
        if row.get('position_id') in selected:
            roots.update(control.known_hashes(row))
    roots.update(control.known_hashes([o for o in data.get('orders',{}).values()
        if o.get('status') not in {'FILLED','CANCELED','CANCELLED','REJECTED','EXPIRED'}]))
    store = worker.runtime._evidence_store
    with store._lock:
        tables = {r[0] for r in store.db.execute("SELECT name FROM sqlite_master WHERE type='table'")}
        confirmed = {r[0] for r in store.db.execute('SELECT result_hash FROM compact_confirmed_results')} if 'compact_confirmed_results' in tables else set()
    roots.update(d['payload']['evidence_ref'] for d in data['decisions'] if d['kind'] == 'EVIDENCE_RESULT'
                 and d['payload'].get('evidence_ref') and d['payload']['evidence_ref'] not in confirmed)
    return roots


def paper_tick(method):
    @wraps(method)
    def wrapped(self,*args,**kwargs):
        control = getattr(self.runtime._evidence_store,'segments',None)
        if not control:
            return method(self,*args,**kwargs)
        owner = 'paper'
        with control.lease():
            try:
                control.begin(owner)
            except Exception:
                fence_failure(control)
                return method(self,*args,**kwargs)
            result = method(self,*args,**kwargs)
            try:
                roots = paper_roots(self,control)
                safe = not self._checkpoint_failed and not self._failures.get('LEDGER_CONFIRMATION')
                control.ready(owner,roots,error=None if safe else 'PAPER_LEDGER_NOT_CONFIRMED')
            except Exception:
                fence_failure(control)
        try:
            control.seal()
        except Exception as exc:
            logging.getLogger(__name__).debug('sealing deferred: %s',type(exc).__name__)
        return result
    return wrapped


def backfill_operation(method):
    @wraps(method)
    def wrapped(self,*args,**kwargs):
        control = getattr(self.store,'segments',None)
        if not control:
            return method(self,*args,**kwargs)
        owner = 'compact-backfill'
        control.begin(owner)
        with control.lease():
            result = method(self,*args,**kwargs)
            # Windows, historical revision anchors and cursors are pinned by pin_snapshot.
            control.ready(owner,())
        return result
    return wrapped


def collector_tick(method):
    @wraps(method)
    def wrapped(self,*args,**kwargs):
        control = getattr(self.store,'segments',None)
        if not control:
            return method(self,*args,**kwargs)
        if control.config['contract'] != 'collector':
            raise ValueError('collector requires collector sealing contract')
        control.begin('continuous-collector')
        with control.lease():
            result = method(self,*args,**kwargs)
            control.ready('continuous-collector',(),error=None if result['status']=='HEALTHY' else 'COLLECTOR_INCOMPLETE')
        try:
            control.seal()
        except Exception:
            pass
        return result
    return wrapped


def realtime_capture_tick(method):
    @wraps(method)
    def wrapped(self,*args,**kwargs):
        control = getattr(self.recorder,'segments',None)
        if not control:
            return method(self,*args,**kwargs)
        with control.lease():
            try:
                control.begin('recorder')
            except Exception:
                fence_failure(control)
                return method(self,*args,**kwargs)
            result = method(self,*args,**kwargs)
            try:
                roots = capture_roots(self)
                control.ready('recorder',roots,error=None if result.status=='HEALTHY' and not self.recorder.failure else 'RUNTIME_CAPTURE_INCOMPLETE')
            except Exception:
                fence_failure(control)
        try:
            control.seal()
        except Exception:
            pass
        return result
    return wrapped


def capture_roots(worker):
    coverage = getattr(worker.management,'coverage',None)
    roots = set()
    if coverage:
        bounds = [(rows[0][1],rows[-1][1]) for rows in coverage.books.values() if rows]
        for r in worker.recorder.records:
            if (r.get('first_received_ms') is None or r.get('last_received_ms') is None or
                any(r['last_received_ms'] >= lo and r['first_received_ms'] <= hi for lo,hi in bounds)):
                roots.add(r['hash'])
    if worker.recorder.last_ref:
        roots.add(worker.recorder.last_ref)
    return roots
