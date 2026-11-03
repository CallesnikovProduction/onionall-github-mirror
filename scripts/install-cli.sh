#!/usr/bin/env bash
set -Eeuo pipefail

if [[ ${1:-} == --help ]]; then
    printf '%s\n' 'Install the managed CLI in XDG_DATA_HOME / XDG_BIN_HOME. Usage: bash scripts/install-cli.sh [--no-build].'
    exit 0
fi
root=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd -P)
case "$#:${1:-}" in
    1:--no-build) ;;
    0:) bash "$root/scripts/build-cli.sh" ;;
    *) echo 'Usage: install-cli.sh [--no-build]' >&2; exit 2 ;;
esac
source_dir="$root/target/onionall"
[[ -f $source_dir/.onionall-cli && ! -L $source_dir ]] || { echo 'Build the CLI distribution first.' >&2; exit 1; }
[[ -z $(find "$source_dir" -type l -print -quit) ]] || { echo 'Refusing symlinks in distribution.' >&2; exit 1; }
while IFS= read -r relative; do
    [[ $relative == bin/onionall || $relative =~ ^lib/[A-Za-z0-9][A-Za-z0-9._+-]*\.jar$ ]] || { echo 'Invalid distribution manifest.' >&2; exit 1; }
done < "$source_dir/.files"
data_home=${XDG_DATA_HOME:-$HOME/.local/share}
bin_home=${XDG_BIN_HOME:-$HOME/.local/bin}
[[ $data_home == /* && $bin_home == /* ]] || { echo 'XDG paths must be absolute.' >&2; exit 2; }
app="$data_home/onionall/cli"
launcher="$bin_home/onionall"
[[ ! -L $app ]] || { echo 'Refusing a symlink application directory.' >&2; exit 1; }
if [[ -e $app ]]; then
    [[ -f $app/.onionall-cli && $(cat "$app/.onionall-cli") == onionall-cli-v1 ]] || { echo 'Unrecognized existing application directory.' >&2; exit 1; }
    [[ -z $(find "$app" -type l -print -quit) ]] || { echo 'Refusing symlinks in installation.' >&2; exit 1; }
    for directory in bin lib; do
        [[ -d $app/$directory && ! -L $app/$directory ]] || { echo 'Unsafe installation directory.' >&2; exit 1; }
    done
    [[ $(find "$app" -mindepth 1 -type d | wc -l) == 2 ]] || { echo 'Unexpected installation subdirectories; refusing update.' >&2; exit 1; }
    # Preserve user-added files rather than silently replace them during an update.
    actual=$(cd -- "$app" && find . -type f ! -name .files ! -name .onionall-cli -printf '%P\n' | LC_ALL=C sort)
    [[ $actual == "$(LC_ALL=C sort "$app/.files")" ]] || { echo 'Application directory has unexpected files; preserve them before updating.' >&2; exit 1; }
fi
if [[ -e $launcher || -L $launcher ]]; then
    [[ -L $launcher && $(readlink -- "$launcher") == "$app/bin/onionall" ]] || { echo 'Refusing to replace unrelated onionall executable.' >&2; exit 1; }
fi
mkdir -p -- "$data_home/onionall" "$bin_home"
stage=$(mktemp -d "$data_home/onionall/.cli-install.XXXXXX")
backup=''
cleanup() {
    local status=$? cleanup_status=0
    trap - EXIT
    set +e
    rm -rf -- "$stage" || cleanup_status=$?
    if [[ -n $backup && -d $backup && ! -e $app ]]; then
        mv -- "$backup" "$app" || cleanup_status=$?
    fi
    if (( cleanup_status != 0 )); then
        echo "Installation cleanup failed (exit $cleanup_status); inspect $stage and $backup." >&2
        if (( status == 0 )); then status=$cleanup_status; fi
    fi
    exit "$status"
}
trap cleanup EXIT
cp -R -- "$source_dir/." "$stage/"
if [[ -d $app ]]; then
    backup=$(mktemp -d "$data_home/onionall/.cli-old.XXXXXX")
    rmdir -- "$backup"
    mv -- "$app" "$backup"
fi
mv -- "$stage" "$app"
if [[ ! -L $launcher ]]; then ln -s -- "$app/bin/onionall" "$launcher"; fi
if [[ -n $backup ]]; then rm -rf -- "$backup"; fi
trap - EXIT
printf 'Installed: %s\n' "$launcher"
case ":$PATH:" in
    *":$bin_home:"*) ;;
    *) printf 'Add the launcher directory to your shell PATH: %s\n' "$bin_home" ;;
esac
