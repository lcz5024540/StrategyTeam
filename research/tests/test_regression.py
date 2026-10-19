import os
from pathlib import Path
import pytest
from coinresearch.cli import regress


def test_real_ring2_reference_and_double_build(tmp_path):
    paths = {}
    for name in ('RESEARCH_RING2_DIR','RESEARCH_BGATE0_DIR'):
        value = os.environ.get(name)
        if not value or not Path(value).is_dir():
            pytest.skip(f'{name} unset or directory unavailable; real-data regression requires external data')
        paths[name] = value
    report = regress(paths['RESEARCH_RING2_DIR'], paths['RESEARCH_BGATE0_DIR'], tmp_path/'regression', target_bps=20)
    assert report['deterministic']
    for name, comparison in report['comparisons'].items():
        assert comparison['row_equal'], (name, comparison)
        assert comparison['byte_identical'], (name, comparison)
    for row in report['table']:
        assert row['passed'], row


def test_regress_unavailable_reports_no_fabricated_values(tmp_path):
    report = regress(tmp_path/'missing-data', tmp_path/'missing-reference', tmp_path/'out', target_bps=20)
    assert report['status'] == 'DATA_UNAVAILABLE'
    assert not report['passed']
    assert report['deterministic'] is None and report['comparisons'] is None
    assert 'DATA_UNAVAILABLE' in (tmp_path/'out/regression.md').read_text()
