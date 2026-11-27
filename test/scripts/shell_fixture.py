"""Resolve owned fixture inputs in the shell whose contracts are under test."""
import os
from pathlib import Path
import shutil
import subprocess


def bash_executable():
    if os.name == 'nt':
        git_bash = Path(os.environ.get('ProgramFiles', 'C:/Program Files')) / 'Git/bin/bash.exe'
        if git_bash.is_file():
            return str(git_bash)
    return shutil.which('bash')


BASH = bash_executable()


def bash_directory(directory):
    return subprocess.check_output(
        [BASH, '-c', 'cd -- "$1" && pwd -P', 'fixture', str(directory)],
        text=True, timeout=5,
    ).rstrip('\r\n')
