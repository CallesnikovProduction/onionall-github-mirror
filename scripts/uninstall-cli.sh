#!/usr/bin/env bash
set -Eeuo pipefail
(( $# == 0 )) || { echo 'Usage: uninstall-cli.sh (uses XDG_DATA_HOME / XDG_BIN_HOME)' >&2; exit 2; }
data_home=${XDG_DATA_HOME:-$HOME/.local/share}
bin_home=${XDG_BIN_HOME:-$HOME/.local/bin}
[[ $data_home == /* && $bin_home == /* ]] || { echo 'XDG paths must be absolute.' >&2; exit 2; }
app="$data_home/onionall/cli"
launcher="$bin_home/onionall"
[[ ! -L $app ]] || { echo 'Refusing symlink application directory.' >&2; exit 1; }
if [[ ! -e $app ]]; then echo 'CLI is not installed.'; exit 0; fi
[[ -f $app/.onionall-cli && $(cat "$app/.onionall-cli") == onionall-cli-v1 ]] || { echo 'Unrecognized installation; refusing removal.' >&2; exit 1; }
[[ -s $app/.files && ! -L $app/.files && ! -L $app/.onionall-cli ]] || { echo 'Unsafe install metadata.' >&2; exit 1; }
[[ -z $(find "$app" -type l -print -quit) ]] || { echo 'Refusing symlinks inside installation.' >&2; exit 1; }
for directory in bin lib; do
    [[ -d $app/$directory && ! -L $app/$directory ]] || { echo 'Unsafe installation subdirectory; refusing removal.' >&2; exit 1; }
done
while IFS= read -r relative; do
    [[ $relative == bin/onionall || $relative =~ ^lib/[A-Za-z0-9][A-Za-z0-9._+-]*\.jar$ ]] || { echo 'Invalid install manifest.' >&2; exit 1; }
done < "$app/.files"
while IFS= read -r relative; do rm -f -- "$app/$relative"; done < "$app/.files"
rm -f -- "$app/.files" "$app/.onionall-cli"
rmdir -- "$app/bin" "$app/lib" "$app" 2>/dev/null || true
if [[ -L $launcher && $(readlink -- "$launcher") == "$app/bin/onionall" ]]; then rm -- "$launcher"; fi
printf 'CLI removed; downloads and other user data retained in %s/onionall.\n' "$data_home"
