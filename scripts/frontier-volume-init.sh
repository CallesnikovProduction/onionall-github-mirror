#!/bin/sh
# Public compatibility entrypoint; Compose mounts the implementation directly.
set -eu
script_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd -P)
exec sh "$script_dir/runners/frontier-volume-init.sh" "$@"
