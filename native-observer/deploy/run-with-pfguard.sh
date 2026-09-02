#!/usr/bin/env bash
# Persistent startup gate for the PathFind observation companion.
# Installed root-owned; the pzserver account only needs read/execute permission.
set -euo pipefail

root="${PFG_ROOT:-/home/pzserver/scripts/pfguard}"
serverfiles="${PFG_SERVERFILES:-/home/pzserver/serverfiles}"
manifest="${root}/manifest.sha256"
observer="${root}/libmdcpfguard.so"
jsig="${serverfiles}/jre64/lib/libjsig.so"

fatal() {
    printf '[mdc-pfguard] STARTUP FATAL: %s\n' "$*" >&2
    exit 78
}

[[ -r "${manifest}" ]] || fatal "manifest missing or unreadable: ${manifest}"
[[ -r "${observer}" ]] || fatal "observer missing or unreadable: ${observer}"
[[ -r "${jsig}" ]] || fatal "libjsig missing or unreadable: ${jsig}"
sha256sum --quiet --check "${manifest}" \
    || fatal "SHA mismatch (game update or incomplete observer install)"

if [[ "${PFG_DRY_RUN:-0}" == 1 ]]; then
    printf '[mdc-pfguard] startup gate PASS: %s\n' "${manifest}"
    exit 0
fi

cd "${serverfiles}"
preload="${observer}:${jsig}"
[[ -n "${LD_PRELOAD:-}" ]] && preload="${preload}:${LD_PRELOAD}"
exec env LD_PRELOAD="${preload}" ./ProjectZomboid64 "$@"
