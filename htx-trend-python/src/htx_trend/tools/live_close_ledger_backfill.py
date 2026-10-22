"""Local JSON -> dry-run repair plan. Deliberately no database/write flags."""
import argparse
from dataclasses import asdict
import json
from pathlib import Path

from htx_trend.live_close_ledger import plan_close_ledger_backfill


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('fixture', type=Path, help='JSON with position, close_order, ledger_events')
    args = parser.parse_args()
    data = json.loads(args.fixture.read_text())
    events = plan_close_ledger_backfill(data['position'], data['close_order'], data['ledger_events'])
    print(json.dumps({'dry_run': True, 'planned': len(events), 'events': [asdict(e) for e in events]},
                     default=str, indent=2))


if __name__ == '__main__':
    main()
