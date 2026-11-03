#!/bin/sh
set -eu
# Only prepare the mounted volume directories, never delete/rewrite database files.
# Resolve the account from the exact same image used by the non-root service.
owner="$(id -u urlfrontier):$(id -g urlfrontier)"
chown "$owner" /crawldir
mkdir -p /crawldir/rocksdb
chown "$owner" /crawldir/rocksdb
