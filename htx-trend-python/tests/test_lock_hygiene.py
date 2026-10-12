"""CI guard: requirements.lock must not contain Ubuntu system packages."""

from __future__ import annotations

import sys
from pathlib import Path

import pytest

ROOT = Path(__file__).resolve().parents[1]
SCRIPTS = ROOT / "scripts"
sys.path.insert(0, str(SCRIPTS))

from check_lock_hygiene import SYSTEM_ONLY_PACKAGES, check_lock, lock_requirement_names, main

LOCK_PATH = ROOT / "requirements.lock"


def test_package_script_does_not_pip_freeze_the_host():
    text = (ROOT / "scripts" / "package.py").read_text()
    assert '"freeze"' not in text
    assert "check_lock_hygiene" in text
    assert "--no-deps" in text


def test_smoke_script_uses_hashed_lock_and_external_venv():
    text = (ROOT / "scripts" / "smoke_e2e.sh").read_text()
    assert 'pip install --require-hashes -r "$ROOT/requirements.lock"' in text
    assert "pip install --no-deps -e" not in text
    assert "python3.12 -m venv" in text
    assert "PYTHONPATH" in text


def test_requirements_lock_exists():
    assert LOCK_PATH.is_file(), "requirements.lock must be committed"


def test_requirements_lock_has_no_system_only_packages():
    leaked = check_lock(LOCK_PATH)
    assert leaked == [], f"requirements.lock contains system-only packages: {leaked}"


def test_requirements_lock_was_not_a_full_system_freeze():
    text = LOCK_PATH.read_text()
    assert "via pip freeze" not in text.lower()
    names = lock_requirement_names(text)
    assert "httpx" in names
    assert "pandas" in names
    assert "pytest" in names


def test_check_lock_hygiene_script_fails_on_leaked_system_packages(tmp_path):
    dirty = tmp_path / "dirty.lock"
    dirty.write_text("httpx==0.28.1\ndbus-python==1.3.2\npython-apt==2.7.7\n")
    assert main([str(dirty)]) == 1


def test_check_lock_hygiene_script_passes_on_clean_lock(tmp_path):
    clean = tmp_path / "clean.lock"
    clean.write_text("httpx==0.28.1\npandas==2.3.3\n")
    assert main([str(clean)]) == 0


@pytest.mark.parametrize("forbidden", sorted(SYSTEM_ONLY_PACKAGES))
def test_system_only_name_is_normalized(forbidden):
    assert forbidden == forbidden.lower()
