"""Prepare only named-volume directories; do not rewrite existing Tor data/keys."""
import os
from pathlib import Path

uid, gid = (int(value) for value in os.environ['M2_RUNTIME_USER'].split(':'))
for root in (Path('/tor-state'), Path('/onion-state')):
    for directory in (root, root / 'data', root / 'hidden_service'):
        directory.mkdir(mode=0o700, exist_ok=True)
        os.chown(directory, uid, gid)
        directory.chmod(0o700)
