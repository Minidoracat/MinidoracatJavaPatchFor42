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
#
# Since 2026-09-28 the wrapper also gates the Java loose-class patch before anything else (see the
# "Java loose-class startup gate" block): a mismatch moves the patch aside and launches vanilla.
# Since 2026-11-10 (PZ 42.22 signs projectzomboid.jar) the Java patch ships as one agent jar instead of
# loose classes; the "Java agent startup gate" block decides whether the game JVM gets -javaagent.
set -euo pipefail

root="${PFG_ROOT:-/home/pzserver/scripts/pfguard}"
serverfiles="${PFG_SERVERFILES:-/home/pzserver/serverfiles}"
manifest="${root}/manifest.sha256"
observer="${root}/libmdcpfguard.so"
tuning="${root}/pfguard.env"
jsig="${serverfiles}/jre64/lib/libjsig.so"

GAME_ARGS=("$@")

# ---- Java loose-class startup gate (added 2026-09-28 after the 42.21.0 update) --------------------
# The loose-class patch (java/zombie + java/patch-manifest.txt, written by deploy/install.sh) is
# built against one exact projectzomboid.jar. A game update that swaps the jar must not load patched
# classes compiled against the old one, so install.sh gates 1 and 2 are re-run on every start:
#   1) every manifest entry exists under java/ with SHA256 == patched_sha;
#   2) every entry whose orig_sha is not "-" is in the jar with SHA256 == orig_sha.
# Any mismatch renames java/zombie, then patch-manifest.txt (never deletes) into
# <PFG_PATCH_DISABLED_ROOT>/patch-disabled-<UTC>-autogate/ and launches vanilla Java. Only when the
# class tree itself cannot be moved aside does the wrapper refuse to start (exit 78): launching the
# old patch against a new jar is exactly what this gate exists to prevent. PFG_GATE_STAMP only
# pins the directory stamp for tests.
java_dir="${serverfiles}/java"
patch_manifest="${java_dir}/patch-manifest.txt"
game_jar="${java_dir}/projectzomboid.jar"

javagate_say() {
    printf '[mdc-javagate] %s\n' "$1"
    printf '[mdc-javagate] %s\n' "$1" >&2
}

# Counts loose classes; never fails (a missing/unreadable tree under `set -e` must not abort startup).
javagate_loose_count() {
    { find "${java_dir}/zombie" -type f -name '*.class' 2>/dev/null || true; } | wc -l
}

# Prints the verified entry count and returns 0 when both gates pass; prints the reason otherwise.
javagate_verify() {
    local entry orig patched _rest live listing count=0
    local entry_re='^zombie/[A-Za-z0-9_$/.-]+\.class$' sha_re='^[0-9a-f]{64}$'
    local -A in_jar=()
    [[ -f "${patch_manifest}" && -r "${patch_manifest}" ]] \
        || { printf 'manifest unreadable: %s' "${patch_manifest}"; return 1; }
    [[ -r "${game_jar}" ]] || { printf 'jar missing or unreadable: %s' "${game_jar}"; return 1; }
    command -v unzip >/dev/null || { printf 'unzip not available; cannot verify the jar'; return 1; }
    listing="$(unzip -Z1 "${game_jar}" 2>/dev/null)" || { printf 'cannot list %s' "${game_jar}"; return 1; }
    while IFS= read -r entry; do in_jar["${entry}"]=1; done <<<"${listing}"
    while IFS=$'\t' read -r entry orig patched _rest || [[ -n "${entry}" ]]; do
        entry="${entry%$'\r'}"; orig="${orig%$'\r'}"; patched="${patched%$'\r'}"
        [[ -n "${entry}" ]] || continue
        # Only java/zombie is moved aside on failure, so an entry anywhere else could never be disarmed.
        if [[ ! "${entry}" =~ ${entry_re} || "${entry}" == *..* ]] \
            || [[ ! "${patched}" =~ ${sha_re} ]] || [[ "${orig}" != - && ! "${orig}" =~ ${sha_re} ]]; then
            printf 'malformed manifest line: %s' "${entry:0:120}"; return 1
        fi
        [[ -f "${java_dir}/${entry}" ]] || { printf 'payload missing: %s' "${entry}"; return 1; }
        live="$(sha256sum <"${java_dir}/${entry}")"
        [[ "${live%% *}" == "${patched}" ]] || { printf 'payload SHA mismatch: %s' "${entry}"; return 1; }
        if [[ "${orig}" != - ]]; then
            [[ -n "${in_jar["${entry}"]:-}" ]] \
                || { printf 'not in jar (game structure changed): %s' "${entry}"; return 1; }
            live="$(unzip -p "${game_jar}" "${entry}" | sha256sum)" \
                || { printf 'cannot read %s from jar' "${entry}"; return 1; }
            [[ "${live%% *}" == "${orig}" ]] \
                || { printf 'jar class differs from patch build base (game updated?): %s' "${entry}"; return 1; }
        fi
        count=$((count + 1))
    done <"${patch_manifest}"
    (( count > 0 )) || { printf 'manifest lists no entries'; return 1; }
    printf '%d' "${count}"
}

javagate_disarm() {
    local reason="$1" base stamp dest n=0
    if [[ "${PFG_DRY_RUN:-0}" == 1 ]]; then
        javagate_say "DISARMED (dry run, nothing moved): ${reason}"
        exit 78
    fi
    base="${PFG_PATCH_DISABLED_ROOT:-$(dirname "${serverfiles}")}"
    # rename(2) is atomic only within one filesystem; otherwise stay inside serverfiles.
    [[ "$(stat -c %d "${base}" 2>/dev/null)" == "$(stat -c %d "${java_dir}")" ]] || base="${serverfiles}"
    stamp="${PFG_GATE_STAMP:-$(date -u +%Y%m%dT%H%M%SZ)}"
    dest="${base}/patch-disabled-${stamp}-autogate"
    until mkdir "${dest}" 2>/dev/null; do
        n=$((n + 1))
        (( n < 100 )) || { dest=""; break; }
        dest="${base}/patch-disabled-${stamp}-autogate.${n}"
    done
    # Classes first: if only the manifest move fails, the next start sees a manifest without payload
    # and disarms again, whereas a moved manifest with classes left behind would silence the gate.
    if [[ -z "${dest}" ]] || { [[ -e "${java_dir}/zombie" || -L "${java_dir}/zombie" ]] \
            && ! mv -T "${java_dir}/zombie" "${dest}/zombie"; }; then
        javagate_say "FATAL: ${reason}; could not move ${java_dir}/zombie aside -- refusing to start with unverified loose classes (exit 78)"
        exit 78
    fi
    mv -T "${patch_manifest}" "${dest}/patch-manifest.txt" \
        || javagate_say "WARNING: could not move ${patch_manifest}; the class tree is already aside"
    javagate_say "STARTUP DISARMED: ${reason} -- moved java/zombie and patch-manifest.txt to ${dest}; launching VANILLA Java (rebuild and reinstall the patch for this jar)"
}

if [[ -e "${patch_manifest}" || -L "${patch_manifest}" ]]; then
    if javagate_result="$(javagate_verify)"; then
        jar_sha="$(sha256sum <"${game_jar}")"
        loose_total="$(javagate_loose_count)"
        printf '[mdc-javagate] OK: %s loose classes verified (payload SHA + jar origin); jar sha256 %s\n' \
            "${javagate_result}" "${jar_sha:0:8}"
        (( loose_total <= javagate_result )) \
            || javagate_say "WARNING: $((loose_total - javagate_result)) loose .class files under java/zombie are not in patch-manifest.txt"
    else
        javagate_disarm "${javagate_result}"
    fi
else
    loose_total="$(javagate_loose_count)"
    (( loose_total == 0 )) \
        || javagate_say "WARNING: ${loose_total} loose .class files under ${java_dir}/zombie but no patch-manifest.txt -- not verified, not moved. The signed jar (42.22+) makes the JVM throw SecurityException when one of them loads into a game package, and the patch agent stays off (DISARMED, vanilla) while any of them shadows a class it patches or sits in zombie/mdc"
fi

# ---- Java agent startup gate (added 2026-11-10 for PZ 42.22's signed jar) ---------------------------
# 42.22 signs projectzomboid.jar: a class loaded from java/ next to classes of the same package from the
# signed jar throws SecurityException, so the patch can no longer be loose classes. It ships as one agent
# jar (deploy/install.sh puts it in <serverfiles>/mdc-javapatch/) that swaps class bytes while the official
# jar loads them. The game JVM only gets -javaagent (through JAVA_TOOL_OPTIONS) when
#   1) the agent jar's SHA256 equals agent-manifest.txt (complete, unedited payload), and
#   2) java/projectzomboid.jar's SHA256 equals the jar the agent was built against.
# Any mismatch launches vanilla Java and leaves the files where they are: without the flag they are inert.
# The agent re-checks the jar itself and stays vanilla on a mismatch. An inherited JAVA_TOOL_OPTIONS keeps
# its other options but loses any -javaagent naming this agent, so a DISARMED launch cannot carry it in.
# The JVM splits JAVA_TOOL_OPTIONS on whitespace and honours quotes; this script only splits on whitespace.
# So an inherited value that names this agent inside quotes is dropped whole (it cannot be cut out safely),
# and an agent path with whitespace or quotes is never passed (DISARMED).
agent_dir="${PFG_AGENT_DIR:-${serverfiles}/mdc-javapatch}"
agent_jar="${agent_dir}/mdc-javapatch-agent.jar"
agent_manifest="${agent_dir}/agent-manifest.txt"
java_tool_options=""
inherited_jto_raw="${JAVA_TOOL_OPTIONS:-}"
if [[ "${inherited_jto_raw}" == *mdc-javapatch-agent* && "${inherited_jto_raw}" == *[\"\']* ]]; then
    javagate_say "WARNING: inherited JAVA_TOOL_OPTIONS names mdc-javapatch-agent inside quotes -- dropping the whole inherited value"
    inherited_jto_raw=""
fi
read -r -a inherited_jto <<<"${inherited_jto_raw}"   # whitespace split, no globbing
for opt in "${inherited_jto[@]}"; do
    [[ "${opt}" == -javaagent:*mdc-javapatch-agent* ]] && continue
    java_tool_options="${java_tool_options:+${java_tool_options} }${opt}"
done

# Prints a short status and returns 0 when both gates pass; prints the reason otherwise.
agentgate_verify() {
    local key value agent_sha="" base_sha="" live
    [[ ! "${agent_jar}" =~ [[:space:]\"\'] ]] \
        || { printf 'agent jar path has whitespace or quotes (cannot be passed in JAVA_TOOL_OPTIONS): %s' "${agent_jar}"; return 1; }
    [[ -f "${agent_manifest}" && -r "${agent_manifest}" ]] \
        || { printf 'agent manifest unreadable: %s' "${agent_manifest}"; return 1; }
    while IFS=$'\t' read -r key value || [[ -n "${key}" ]]; do
        value="${value%$'\r'}"
        case "${key}" in
            agent) agent_sha="${value}" ;;
            jar) base_sha="${value}" ;;
        esac
    done <"${agent_manifest}"
    [[ "${agent_sha}" =~ ^[0-9a-f]{64}$ && "${base_sha}" =~ ^[0-9a-f]{64}$ ]] \
        || { printf 'malformed agent manifest: %s' "${agent_manifest}"; return 1; }
    [[ -f "${agent_jar}" && -r "${agent_jar}" ]] || { printf 'agent jar missing: %s' "${agent_jar}"; return 1; }
    live="$(sha256sum <"${agent_jar}")"
    [[ "${live%% *}" == "${agent_sha}" ]] || { printf 'agent jar SHA mismatch: %s' "${agent_jar}"; return 1; }
    [[ -r "${game_jar}" ]] || { printf 'jar missing or unreadable: %s' "${game_jar}"; return 1; }
    live="$(sha256sum <"${game_jar}")"
    [[ "${live%% *}" == "${base_sha}" ]] \
        || { printf 'game jar sha256 %s differs from agent build base %s (game updated?)' "${live:0:8}" "${base_sha:0:8}"; return 1; }
    printf 'jar sha256 %s' "${base_sha:0:8}"
}

if [[ -e "${agent_manifest}" || -e "${agent_jar}" ]]; then
    if agentgate_result="$(agentgate_verify)"; then
        java_tool_options="-javaagent:${agent_jar}${java_tool_options:+ ${java_tool_options}}"
        printf '[mdc-javagate] AGENT ARMED: %s (%s)\n' "${agent_jar}" "${agentgate_result}"
    else
        javagate_say "AGENT DISARMED: ${agentgate_result} -- launching VANILLA Java (rebuild and reinstall the patch for this jar)"
        [[ "${PFG_DRY_RUN:-0}" != 1 ]] || exit 78
    fi
fi
# env(1) arguments that set JAVA_TOOL_OPTIONS for the game, or unset it when nothing is left.
if [[ -n "${java_tool_options}" ]]; then
    JTO_ENV=("JAVA_TOOL_OPTIONS=${java_tool_options}")
else
    JTO_ENV=(-u JAVA_TOOL_OPTIONS)
fi

# Steam repair is independent of the PathFind observer. Remove an inherited copy
# before evaluating the gate, so an invalid/off repair can never leak through.
audit=""
steam_enabled=0
IFS=':' read -r -a audit_entries <<<"${LD_AUDIT:-}"
for entry in "${audit_entries[@]}"; do
    [[ -z "${entry}" || "${entry}" == *libmdcsteamfix* ]] && continue
    audit="${audit:+${audit}:}${entry}"
done
steamfix="${root}/libmdcsteamfix.so"
steam_manifest="${root}/steamfix.manifest.sha256"
steam_manifest_complete() {
    local digest file count=0 steam=0 helper=0
    while read -r digest file; do
        [[ "${digest}" =~ ^[[:xdigit:]]{64}$ ]] || return 1
        file="${file#\*}"
        if [[ "${file}" == "${serverfiles}/linux64/steamclient.so" ]]; then
            steam=$((steam+1))
        elif [[ "${file}" == "${steamfix}" ]]; then
            helper=$((helper+1))
        else
            return 1
        fi
        count=$((count+1))
    done <"${steam_manifest}"
    [[ "${count}" == 2 && "${steam}" == 1 && "${helper}" == 1 ]]
}
if [[ -e "${steam_manifest}" || -e "${steamfix}" ]]; then
    mode=""
    [[ ! -r "${root}/steamfix.mode" ]] || mode="$(<"${root}/steamfix.mode")"
    if [[ "${mode}" == 0 || "${mode}" == off ]]; then
        printf '[mdc-steamfix] OFF: vanilla Steam selected\n' >&2
    elif [[ "${mode}" != 1 || ! -r "${steamfix}" || ! -r "${steam_manifest}" ]] \
        || ! steam_manifest_complete \
        || ! sha256sum --quiet --strict --check "${steam_manifest}"; then
        printf '[mdc-steamfix] DISARMED: mode/file/SHA gate failed; vanilla Steam selected\n' >&2
        [[ "${PFG_DRY_RUN:-0}" != 1 ]] || exit 78
    else
        audit="${steamfix}${audit:+:${audit}}"
        steam_enabled=1
    fi
fi

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
    exec env "${JTO_ENV[@]}" LD_PRELOAD="${jsig}${rest:+:${rest}}" LD_AUDIT="${audit}" MDC_STEAMFIX="${steam_enabled}" \
        ./ProjectZomboid64 "${GAME_ARGS[@]}"
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
exec env "${JTO_ENV[@]}" "${TUNING_ENV[@]}" LD_PRELOAD="${preload}" LD_AUDIT="${audit}" MDC_STEAMFIX="${steam_enabled}" \
    ./ProjectZomboid64 "${GAME_ARGS[@]}"
