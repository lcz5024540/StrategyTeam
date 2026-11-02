import json
import os
from pathlib import Path
import select
import subprocess
import sys

import pytest

SCRIPT=Path(__file__).parent/'fixtures/continuity_process.py'


def start(root,bootstrap=False):
    env={**os.environ,'PYTHONPATH':str(Path(__file__).parents[1]/'src')}
    command=[sys.executable,str(SCRIPT),'--root',str(root)]
    if bootstrap:command.append('--bootstrap')
    p=subprocess.Popen(command,stdout=subprocess.PIPE,stderr=subprocess.PIPE,text=True,env=env)
    ready,_,_=select.select([p.stdout],[],[],15)
    if not ready:
        p.kill();out,err=p.communicate(timeout=5)
        raise AssertionError('drill did not become ready: '+err)
    line=p.stdout.readline()
    if not line:
        _,err=p.communicate(timeout=5)
        raise AssertionError('drill failed: '+err)
    return p,json.loads(line)


@pytest.mark.parametrize('crash',[False,True])
def test_two_process_handoff_preserves_position_and_same_bar(tmp_path,crash):
    first,initial=start(tmp_path,True)
    assert initial['quantity']==3 and initial['status']=='HEALTHY'
    try:
        # A second OS process cannot acquire the PAPER writer while it is held.
        with pytest.raises(AssertionError,match='BlockingIOError'):
            start(tmp_path)
        if crash:first.kill()
        else:first.terminate()
        first.communicate(timeout=10)
        second,resumed=start(tmp_path)
        try:
            assert resumed['quantity']==3
            assert resumed['positions']==initial['positions']
            assert resumed['fills']==initial['fills']==0
            assert resumed['decisions']==initial['decisions']
            assert resumed['previous']==('READY' if crash else 'STOPPED')
        finally:
            second.terminate();second.communicate(timeout=10)
    finally:
        if first.poll() is None:first.kill();first.communicate(timeout=5)
