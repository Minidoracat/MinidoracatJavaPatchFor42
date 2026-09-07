#!/usr/bin/env bash
# Re-verifies every load-bearing interposition precondition against a concrete
# libPZPathFind64.so. MUST pass after every game update; otherwise do not deploy.
#
#   bash native-observer/tests/verify-preconditions.sh /path/to/libPZPathFind64.so
set -euo pipefail

so="${1:?usage: verify-preconditions.sh /path/to/libPZPathFind64.so}"
[[ -r "${so}" ]] || { echo "cannot read ${so}"; exit 2; }

fail=0
checks=0
check() {
    local label="$1" expected="$2" actual="$3"
    checks=$((checks + 1))
    if [[ "${actual}" == "${expected}" ]]; then
        printf 'ok    %-52s %s\n' "${label}" "${actual}"
    else
        printf 'FAIL  %-52s got %s, want %s\n' "${label}" "${actual}" "${expected}"
        fail=1
    fi
}

echo "target: ${so}"
echo "sha256: $(sha256sum "${so}" | cut -d' ' -f1)"
echo

raw="$(mktemp)"
demangled="$(mktemp)"
trap 'rm -f "${raw}" "${demangled}"' EXIT
objdump -d "${so}" > "${raw}"
objdump -d -C "${so}" > "${demangled}"

# Match both ordinary calls and tail-call jumps. The shipped deallocate_aligned is a
# five-byte `jmp free@plt`; counting only `call` used to make this gate blind to the
# only deallocation path.
count_edges() {
    grep -Ec "[[:space:]](call|jmp)[[:space:]].*<$1>" "$2" || true
}

# Whole-library allocator wrappers are version-pinned too: the only extra aligned/free
# wrappers are `allocate_aligned` and Detour's dtAllocDefault/dtFreeDefault.
check "whole-library aligned_alloc@plt transfers" 2 "$(count_edges 'aligned_alloc@plt' "${demangled}")"
check "whole-library malloc_usable_size@plt transfers" 1 "$(count_edges 'malloc_usable_size@plt' "${demangled}")"
check "whole-library free@plt transfers" 4 "$(count_edges 'free@plt' "${demangled}")"
check "whole-library malloc@plt transfers" 1 "$(count_edges 'malloc@plt' "${demangled}")"

# Lock each operation independently; a single total of four cannot distinguish
# 1 aligned_alloc + 1 usable + 2 free from a different ownership/copy contract.
realloc_body="$(objdump -d -C --disassemble='reallocate_aligned(void*, unsigned long, unsigned long)' "${so}")"
dealloc_body="$(objdump -d -C --disassemble='deallocate_aligned(void*)' "${so}")"
check "reallocate_aligned calls aligned_alloc@plt" 1 \
    "$(grep -Ec '[[:space:]]call[[:space:]].*<aligned_alloc@plt>' <<<"${realloc_body}" || true)"
check "reallocate_aligned calls malloc_usable_size@plt" 1 \
    "$(grep -Ec '[[:space:]]call[[:space:]].*<malloc_usable_size@plt>' <<<"${realloc_body}" || true)"
check "reallocate_aligned calls free@plt" 2 \
    "$(grep -Ec '[[:space:]]call[[:space:]].*<free@plt>' <<<"${realloc_body}" || true)"
check "deallocate_aligned tail-jumps to free@plt" 1 \
    "$(grep -Ec '[[:space:]]jmp[[:space:]].*<free@plt>' <<<"${dealloc_body}" || true)"

# All internal helper edges must remain preemptible PLT transfers. Use the raw
# (non-demangled) disassembly consistently for mangled names; grepping mangled names
# in `objdump -C` output always returned a false zero.
check "reallocate_aligned@plt transfer sites" 136 \
    "$(count_edges '_Z18reallocate_alignedPvmm@plt' "${raw}")"
check "deallocate_aligned@plt transfer sites" 94 \
    "$(count_edges '_Z18deallocate_alignedPv@plt' "${raw}")"
check "direct reallocate_aligned transfers" 0 \
    "$(count_edges '_Z18reallocate_alignedPvmm' "${raw}")"
check "direct deallocate_aligned transfers" 0 \
    "$(count_edges '_Z18deallocate_alignedPv' "${raw}")"

# Nothing may bind the DSO to its own definitions ahead of LD_PRELOAD.
dynamic="$(readelf -dW "${so}")"
check "DT_FLAGS/SYMBOLIC/BIND_NOW entries" 0 \
    "$(grep -cE '\(FLAGS\)|SYMBOLIC|BIND_NOW' <<<"${dynamic}" || true)"
check "DT_SONAME used by RTLD_NOLOAD resolver" "libPZPathFind64.so" \
    "$(sed -nE 's/.*\(SONAME\).*\[([^]]+)\].*/\1/p' <<<"${dynamic}")"

dynsym="$(readelf --dyn-syms -W "${so}")"
for symbol in _Z18reallocate_alignedPvmm _Z18deallocate_alignedPv; do
    binding=$(awk -v s="${symbol}" '$8==s{print $5"/"$6}' <<<"${dynsym}" | head -1)
    check "dynamic binding of ${symbol}" "GLOBAL/DEFAULT" "${binding}"
done

# dladdr(return_address) observes a dynamic symbol and needs a real CALL return address,
# not merely an ELF-local .symtab entry or a tail JMP inherited from an upstream caller.
# The list under test is what production actually preloads: MDC_PFGUARD_CALLERS from
# deploy/pfguard.env (round 2+), plus the shim's built-in defaults (used when no env is deployed).
here="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
declare -A expected_calls=(
    [_ZN13PolygonalMap221createVehicleClustersEv]=1
    [_ZN13PolygonalMap220createVehicleClusterEP11VehicleRectR9ArrayListIS1_ERS2_IP14VehicleClusterE]=7
    [_ZN14VehicleCluster5mergeEPS_]=1
    [_ZN15VisibilityGraph8trySplitEP4EdgeP11VehicleRectR9ArrayListIiE]=3
)
callers=("${!expected_calls[@]}")
env_file="${PFG_ENV:-${here}/deploy/pfguard.env}"
if [[ -r "${env_file}" ]]; then
    env_callers="$(sed -nE 's/^MDC_PFGUARD_CALLERS=([A-Za-z0-9_,]*)$/\1/p' "${env_file}" | tail -1)"
    IFS=',' read -r -a env_list <<<"${env_callers}"
    check "pfguard.env caller count within PFG_MAX_CALLERS (16)" 1 "$(( ${#env_list[@]} <= 16 ? 1 : 0 ))"
    check "pfguard.env MDC_PFGUARD_CALLERS length within g_callers_env (2048)" 1 "$(( ${#env_callers} < 2048 ? 1 : 0 ))"
    for s in "${env_list[@]}"; do
        [[ -n "${s}" && -z "${expected_calls[$s]+x}" ]] && callers+=("${s}")
    done
else
    echo "note: no ${env_file}; verifying shim defaults only"
fi
for symbol in "${callers[@]}"; do
    present=$(awk -v s="${symbol}" '$8==s{c++} END{print c+0}' <<<"${dynsym}")
    check "allowlisted dynamic symbol: ${symbol:0:40}" 1 "$((present > 0 ? 1 : 0))"
    body="$(objdump -d --disassemble="${symbol}" "${so}")"
    calls=$(grep -Ec '[[:space:]]call[[:space:]].*<_Z18reallocate_alignedPvmm@plt>' <<<"${body}" || true)
    jumps=$(grep -Ec '[[:space:]]jmp[[:space:]].*<_Z18reallocate_alignedPvmm@plt>' <<<"${body}" || true)
    if [[ -n "${expected_calls[$symbol]+x}" ]]; then
        check "allowlisted realloc CALL count: ${symbol:0:30}" "${expected_calls[$symbol]}" "${calls}"
    else
        check "allowlisted realloc CALL count >=1: ${symbol:0:30}" 1 "$((calls >= 1 ? 1 : 0))"
    fi
    check "allowlisted realloc JMP count: ${symbol:0:31}" 0 "${jumps}"
done

echo
if (( fail )); then
    echo "RESULT: FAIL (${checks} checks) - do not deploy the guard against this library"
    exit 1
fi
echo "RESULT: PASS (${checks} checks) - preconditions hold for this library"
