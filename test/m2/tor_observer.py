"""Finite, passive Tor control telemetry. Never emits addresses, auth or paths.

Executed through docker exec by the retained/cold diagnostic; SETEVENTS only,
no circuit/descriptor reset commands. Descriptor content is reduced in memory.
"""
import hashlib
import json
import os
import re
import select
import socket
import time
from datetime import datetime, timezone
from pathlib import Path


def token_hash(value):
    return hashlib.sha256(value.encode()).hexdigest()


def event_record(line):
    tokens = line.split()
    if len(tokens) < 3 or tokens[0] != '650':
        return None
    kind = tokens[1]
    event = {'kind': kind}
    if kind == 'HS_DESC':
        event['action'] = tokens[2]
        if len(tokens) > 6 and '=' not in tokens[6]:
            event['blinded_key_hash'] = token_hash(tokens[6])
    elif kind in {'STREAM', 'CIRC', 'CIRC_MINOR'}:
        if len(tokens) < 4 or not tokens[2].isdigit():
            return None
        event.update(id=tokens[2], status=tokens[3])
        if kind == 'STREAM' and len(tokens) > 4 and tokens[4].isdigit():
            event['circuit_id'] = tokens[4]
    elif kind == 'INFO':
        period = re.search(r'Setting rev counter for TP #(\d+):.*\((current|next)\)', line)
        revision = re.search(r'(current|next) descriptor of revision (\d+) initiated upload', line)
        if period:
            return dict(kind='revision_period', period=int(period[1]), which=period[2])
        if revision:
            return dict(kind='revision_upload', which=revision[1], revision_counter=int(revision[2]))
        return None
    else:
        return None
    for token in tokens:
        key, _, value = token.partition('=')
        if key in {'REASON', 'REMOTE_REASON', 'PURPOSE', 'HS_STATE'} and re.fullmatch('[A-Z0-9_]+', value):
            event[key.lower()] = value
    return event


def watch(seconds=500):
    deadline = time.monotonic() + seconds
    root = os.environ.get('TOR_OBSERVER_ROOT', '/var/lib/tor')
    client = socket.socket(socket.AF_UNIX)
    connection_deadline = time.monotonic()+8
    while True:
        try:
            client.settimeout(2)
            client.connect(root+'/control')
            break
        except OSError:
            if time.monotonic() >= connection_deadline:
                client.close()
                raise
            time.sleep(.1)
    with client:
        stream = client.makefile('rwb', buffering=0)
        stream.write(b'AUTHENTICATE ' + Path(root+'/control_auth_cookie').read_bytes().hex().encode() + b'\r\n')
        if stream.readline(4096) != b'250 OK\r\n':
            raise OSError('Control authentication failed')
        stream.write(b'SETEVENTS HS_DESC HS_DESC_CONTENT STREAM CIRC CIRC_MINOR INFO\r\n')
        if stream.readline(4096) != b'250 OK\r\n':
            raise OSError('Control subscription failed')
        periods = {}
        uploads = {}
        latest_upload = {}
        def emit(event):
            if event.get('kind') == 'revision_period':
                periods[event['which']] = event['period']
            if event.get('kind') == 'revision_upload' and event.get('which') in periods:
                event['period'] = periods[event['which']]
                latest_upload.clear()
                latest_upload.update({k:event[k] for k in ('period','revision_counter')})
            if event.get('kind') == 'HS_DESC' and event.get('blinded_key_hash'):
                key=event['blinded_key_hash']
                if event.get('action') == 'UPLOAD':
                    if latest_upload:
                        uploads[key]=dict(latest_upload)
                    latest_upload.clear()
                if key in uploads:
                    event.update(uploads[key])
            event['utc'] = datetime.now(timezone.utc).isoformat()
            print(json.dumps(event), flush=True)
        emit({'kind': 'watch_started'})
        while time.monotonic() < deadline:
            if not select.select([client], [], [], min(1, max(.01, deadline-time.monotonic())))[0]:
                continue
            line = stream.readline(32768)
            if not line:
                break
            if line.startswith(b'650+HS_DESC_CONTENT '):
                tokens = line.decode(errors='replace').split()
                event = {'kind': 'HS_DESC_CONTENT'}
                if len(tokens) > 2:
                    event['blinded_key_hash'] = token_hash(tokens[2])
                size = 0
                while True:
                    part = stream.readline(65536)
                    if not part:
                        raise OSError('Descriptor EOF')
                    if part == b'.\r\n':
                        break
                    size += len(part)
                    if size > 1048576 or time.monotonic() >= deadline:
                        raise OSError('Descriptor bound exceeded')
                    if part.startswith(b'revision-counter ') and part[17:].strip().isdigit():
                        event['revision_counter'] = int(part[17:].strip())
                emit(event)
            else:
                event = event_record(line.decode(errors='replace'))
                if event:
                    emit(event)


if __name__ == '__main__':
    try:
        watch()
    except OSError as error:
        print(json.dumps({'kind': 'observer_error', 'error_type': type(error).__name__}), flush=True)
        raise SystemExit(1)
