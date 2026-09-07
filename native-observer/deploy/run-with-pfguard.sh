#!/usr/bin/env bash
# Persistent startup gate for the PathFind observation companion.
# Installed root-owned; the pzserver account only needs read/execute permission.
#
# Gate outcome (2026-09-06 deployment decision): any doubt about the observer install — manifest
# mismatch (game update swapped libPZPathFind64.so, or an incomplete/edited observer install),
# missing files, or a tuning file that does not parse — launches the game WITHOUT the observer
# instead of refusing to start. The observer is instrumentation; the production server has no
# manual window (cron update + monitor every 10 min), so "exit 78 until a human shows up" would
# turn an unverified .so into an outage. Fail-closed means "never preload an unverified or
# misconfigured observer" — it never means "keep the game down".
set -euo pipefail

root="${PFG_ROOT:-/home/pzserver/scripts/pfguard}"
serverfiles="${PFG_SERVERFILES:-/home/pzserver/serverfiles}"
manifest="${root}/manifest.sha256"
observer="${root}/libmdcpfguard.so"
tuning="${root}/pfguard.env"
jsig="${serverfiles}/jre64/lib/libjsig.so"

GAME_ARGS=("$@")

# Inherited LD_PRELOAD (if any) minus every entry that names this observer, so a DISARMED launch
# can never carry the observer in through the environment.
inherited_preload() {
    local out="" entry
    IFS=':' read -r -a entries <<<"${LD_PRELOAD:-}"
    for entry in "${entries[@]}"; do
        [[ -z "${entry}" || "${entry}" == *libmdcpfguard* ]] && continue
        out="${out:+${out}:}${entry}"
    done
    printf '%s' "${out}"
}

disarmed() {
    local reason="$1"
    printf '[mdc-pfguard] STARTUP DISARMED: %s -- launching WITHOUT observer (vanilla)\n' "${reason}" >&2
    printf '[mdc-pfguard] STARTUP DISARMED: %s -- launching WITHOUT observer (vanilla)\n' "${reason}"
    [[ "${PFG_DRY_RUN:-0}" == 1 ]] && exit 78
    cd "${serverfiles}"
    local rest; rest="$(inherited_preload)"
    # Mirror the official launcher's intent with a real absolute path for libjsig.
    exec env LD_PRELOAD="${jsig}${rest:+:${rest}}" ./ProjectZomboid64 "${GAME_ARGS[@]}"
}

[[ -r "${manifest}" ]] || disarmed "manifest missing or unreadable: ${manifest}"
[[ -r "${observer}" ]] || disarmed "observer missing or unreadable: ${observer}"
[[ -r "${jsig}" ]] || disarmed "libjsig missing or unreadable: ${jsig}"
# The manifest pins the official PathFind library, the observer, and the tuning file: a tuning
# file that is listed but missing/edited is an incomplete install, not a "use shim defaults" case.
sha256sum --quiet --check "${manifest}" \
    || disarmed "SHA mismatch (game update or incomplete observer install)"

# Tuning: only literal `MDC_PFGUARD_<NAME>=<value>` lines are accepted (value: [A-Za-z0-9_,]);
# blank lines and `#` comments are ignored; anything else means the file is not what we wrote.
# The file is never sourced — it is data, not shell.
declare -a TUNING_ENV=()
if [[ -e "${tuning}" ]]; then
    [[ -r "${tuning}" ]] || disarmed "tuning unreadable: ${tuning}"
    while IFS= read -r line || [[ -n "${line}" ]]; do
        line="${line%$'\r'}"
        [[ -z "${line}" || "${line}" == \#* ]] && continue
        if [[ "${line}" =~ ^(MDC_PFGUARD[A-Z0-9_]*)=([A-Za-z0-9_,]*)$ ]]; then
            TUNING_ENV+=("${BASH_REMATCH[1]}=${BASH_REMATCH[2]}")
        else
            disarmed "tuning line rejected in ${tuning}: ${line:0:80}"
        fi
    done <"${tuning}"
fi

if [[ "${PFG_DRY_RUN:-0}" == 1 ]]; then
    printf '[mdc-pfguard] startup gate PASS: %s (tuning entries: %d)\n' "${manifest}" "${#TUNING_ENV[@]}"
    exit 0
fi

printf '[mdc-pfguard] startup gate PASS: preloading %s (tuning entries: %d)\n' "${observer}" "${#TUNING_ENV[@]}"
cd "${serverfiles}"
rest="$(inherited_preload)"
preload="${observer}:${jsig}${rest:+:${rest}}"
exec env "${TUNING_ENV[@]}" LD_PRELOAD="${preload}" ./ProjectZomboid64 "${GAME_ARGS[@]}"
