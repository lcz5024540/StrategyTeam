"""Archive view of one immutable generation; no whole-history index claims."""
from contextlib import closing
import json
from pathlib import Path
import sqlite3

from .control import Control, SealingError, digest_file, safe_file, encoded


def populate(db, job, spec, add):
    control = Control(spec['root'])
    try:
        with control.lease():
            rows = control.generation_rows(int(spec['epoch']))
            if spec['kind'] != 'sealed-' + control.config['kind']:
                raise SealingError('sealed source kind mismatch')
            for row in rows:
                p = safe_file(control.root, row['path'])
                if not row['local'] or p.stat().st_size != row['size'] or digest_file(p) != row['raw_hash']:
                    raise SealingError('sealed member bytes changed or are no longer local')
                virtual = row['path'] if control.config['kind'] == 'cas' else 'segments/' + row['path']
                meta = json.loads(row['metadata'])
                add(virtual, p, row['key'], meta.get('count'))
            scope = {'schema': 'htx-sealed-generation-v1', 'scope': 'reference_closed_generation',
                     'root': str(control.root), 'epoch': int(spec['epoch']), 'contract': control.config['contract'],
                     'global_index_included': False, 'members': len(rows)}
            target = job / 'sealed-scope.json'
            target.write_bytes(encoded(scope))
            add('sealed-scope.json', target)
            if control.config['kind'] == 'cas':
                with closing(sqlite3.connect(job / 'index.sqlite3')) as subset:
                    subset.executescript('''CREATE TABLE evidence(hash TEXT PRIMARY KEY,kind TEXT,recorded_at TEXT);
                        CREATE TABLE outbox(hash TEXT PRIMARY KEY,attempts INTEGER,delivered INTEGER,last_error TEXT);
                        CREATE TABLE checkpoints(name TEXT PRIMARY KEY,value TEXT);''')
                    with closing(sqlite3.connect((control.root / 'index.sqlite3').as_uri() + '?mode=ro', uri=True)) as source:
                        for key in sorted({r['key'] for r in rows}):
                            row = source.execute('SELECT hash,kind,recorded_at FROM evidence WHERE hash=?', (key,)).fetchone()
                            if row:
                                subset.execute('INSERT INTO evidence VALUES(?,?,?)', row)
                            outbox = source.execute('SELECT hash,attempts,delivered,last_error FROM outbox WHERE hash=?', (key,)).fetchone()
                            if outbox:
                                subset.execute('INSERT INTO outbox VALUES(?,?,?,?)', outbox)
                    subset.execute('INSERT INTO checkpoints VALUES(?,?)', ('sealed_scope', encoded(scope).decode()))
                    subset.commit()
                add('index.sqlite3', job / 'index.sqlite3')
            else:
                records = [json.loads(r['metadata']) for r in rows]
                records.sort(key=lambda r: (r['first_received_ms'] or 0, r['hash']))
                index = job / 'segments.jsonl'
                index.write_bytes(b''.join(encoded(r) + b'\n' for r in records))
                add('segments/segments.jsonl', index)
                # Public capture provenance, when present, is informational and immutable in this view.
                p = control.root.parent / 'capture.json'
                if p.exists():
                    target = job / 'capture.json'
                    target.write_bytes(p.read_bytes())
                    add('capture.json', target)
            db.execute('INSERT INTO meta VALUES(?,?)', ('sealed_scope', encoded(scope).decode()))
    finally:
        control.close()
