"""版本和构建信息"""

from __future__ import annotations

import subprocess
from dataclasses import dataclass
from pathlib import Path

from ._version import __version__


@dataclass
class VersionInfo:
    version: str
    commit: str
    branch: str
    dirty: bool
    
    def to_dict(self) -> dict:
        return {
            "version": self.version,
            "commit": self.commit,
            "branch": self.branch,
            "dirty": self.dirty,
        }
    
    def __str__(self) -> str:
        suffix = "-dirty" if self.dirty else ""
        return f"{self.version} ({self.branch}@{self.commit[:8]}{suffix})"


def get_git_info() -> tuple[str, str, bool]:
    """获取 git 信息：(commit, branch, dirty)"""
    try:
        repo_root = Path(__file__).parent.parent.parent.parent
        
        commit = subprocess.run(
            ["git", "rev-parse", "HEAD"],
            capture_output=True,
            text=True,
            check=True,
            cwd=repo_root,
        ).stdout.strip()
        
        branch = subprocess.run(
            ["git", "rev-parse", "--abbrev-ref", "HEAD"],
            capture_output=True,
            text=True,
            check=True,
            cwd=repo_root,
        ).stdout.strip()
        
        status = subprocess.run(
            ["git", "status", "--porcelain"],
            capture_output=True,
            text=True,
            check=True,
            cwd=repo_root,
        ).stdout.strip()
        
        dirty = len(status) > 0
        
        return commit, branch, dirty
    
    except Exception:
        return "unknown", "unknown", False


def get_version_info() -> VersionInfo:
    """获取完整版本信息"""
    commit, branch, dirty = get_git_info()
    
    return VersionInfo(
        version=__version__,
        commit=commit,
        branch=branch,
        dirty=dirty,
    )
