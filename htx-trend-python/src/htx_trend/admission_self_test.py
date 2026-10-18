"""Offline synthetic seal checks; never issues authorization."""
from .admission import seal, canonical, read_sealed
from pathlib import Path
from tempfile import TemporaryDirectory


def run():
    with TemporaryDirectory() as root:
        path = Path(root) / 'synthetic.json'
        value = seal({'schema': 'self-test', 'synthetic': True, 'label': 'SYNTHETIC'})
        path.write_text(canonical(value))
        assert read_sealed(path, 'self-test') == value
        path.write_text(canonical(value) + '\n')
        try:
            read_sealed(path, 'self-test')
        except ValueError:
            pass
        else:
            raise AssertionError('byte mutation accepted')
    return {'status': 'PASS', 'checks': 2, 'synthetic': True}
