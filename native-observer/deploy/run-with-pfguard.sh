#!/usr/bin/env bash
# Persistent startup gate for the PathFind observation companion.
# Installed root-owned; the pzserver account only needs read/execute permission.
#
# Gate outcome (2026-09-06 deployment decision): a manifest mismatch (game update swapped
# libPZPathFind64.so, or an incomplete observer install) launches the game WITHOUT the
# observer instead of refusing to start. The observer is instrumentation; the production
# server has no manual window (cron update + monitor every 10 min), so "exit 78 until a
# human shows up" would turn an unverified .so into an outage. Fail-closed still means
# "never preload an unverified observer" — it just never means "keep the game down".
set -euo pipefail

root="${PFG_ROOT:-/home/pzserver/scripts/pfguard}"
serverfiles="${PFG_SERVERFILES:-/home/pzserver/serverfiles}"
manifest="${root}/manifest.sha256"
observer="${root}/libmdcpfguard.so"
jsig="${serverfiles}/jre64/lib/libjsig.so"

disarmed() {
    local reason="$1"
    printf '[mdc-pfguard] STARTUP DISARMED: %s -- launching WITHOUT observer (vanilla)\n' "${reason}" >&2
    printf '[mdc-pfguard] STARTUP DISARMED: %s -- launching WITHOUT observer (vanilla)\n' "${reason}"
    [[ "${PFG_DRY_RUN:-0}" == 1 ]] && exit 78
    cd "${serverfiles}"
    # Mirror the official launcher's intent with a real absolute path for libjsig.
    exec env LD_PRELOAD="${jsig}${LD_PRELOAD:+:${LD_PRELOAD}}" ./ProjectZomboid64 "${GAME_ARGS[@]}"
}

GAME_ARGS=("$@")
[[ -r "${manifest}" ]] || disarmed "manifest missing or unreadable: ${manifest}"
[[ -r "${observer}" ]] || disarmed "observer missing or unreadable: ${observer}"
[[ -r "${jsig}" ]] || disarmed "libjsig missing or unreadable: ${jsig}"
sha256sum --quiet --check "${manifest}" \
    || disarmed "SHA mismatch (game update or incomplete observer install)"

if [[ "${PFG_DRY_RUN:-0}" == 1 ]]; then
    printf '[mdc-pfguard] startup gate PASS: %s\n' "${manifest}"
    exit 0
fi

printf '[mdc-pfguard] startup gate PASS: preloading %s\n' "${observer}"
cd "${serverfiles}"
preload="${observer}:${jsig}"
[[ -n "${LD_PRELOAD:-}" ]] && preload="${preload}:${LD_PRELOAD}"
exec env LD_PRELOAD="${preload}" ./ProjectZomboid64 "$@"
