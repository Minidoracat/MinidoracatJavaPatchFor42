#!/usr/bin/env bash
# Synthetic acceptance tests for libmdcpfguard.so.
# Every case runs as its own child so the delivered signal can be asserted.
set -uo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
root="$(cd "${here}/.." && pwd)"
out="${root}/out"
shim="${out}/libmdcpfguard.so"
test_shim="${out}/libmdcpfguard-test.so"
cases="${out}/cases"

if ! bash "${root}/build.sh" >/dev/null; then
    echo "FAIL build - refusing to run stale artifacts" >&2
    exit 1
fi

pass=0
fail=0
log=$(mktemp)
trap 'rm -f "${log}"' EXIT

# expect <name> <expected-exit-or-SIGNAME> <case> [env assignments...]
expect() {
    local name="$1" want="$2" case_name="$3"; shift 3
    local status=0
    # `if ! cmd` would make $? the status of the negation, not of the child.
    env "$@" LD_PRELOAD="${shim}" "${cases}" "${case_name}" >"${log}" 2>&1 || status=$?
    local got
    if (( status > 128 )); then
        got="SIG$(kill -l $((status - 128)) 2>/dev/null || echo "?")"
    else
        got="${status}"
    fi
    if [[ "${got}" == "${want}" ]]; then
        printf 'PASS  %-24s (%s)\n' "${name}" "${got}"
        pass=$((pass + 1))
    else
        printf 'FAIL  %-24s got %s want %s\n' "${name}" "${got}" "${want}"
        sed 's/^/      | /' "${log}"
        fail=$((fail + 1))
    fi
}

# assert_log <name> <pattern> — re-checks the last captured output
assert_log() {
    local name="$1" pattern="$2"
    if grep -qE "${pattern}" "${log}"; then
        printf 'PASS  %-24s matched /%s/\n' "${name}" "${pattern}"
        pass=$((pass + 1))
    else
        printf 'FAIL  %-24s no match for /%s/\n' "${name}" "${pattern}"
        sed 's/^/      | /' "${log}"
        fail=$((fail + 1))
    fi
}

echo "=== interposition fidelity (the test double must behave like the real library)"
# Snapshot first: piping into `grep -q` SIGPIPEs objdump and `pipefail` would report
# a false failure.
fake_disasm="$(objdump -d -C "${out}/libfakepathfind.so")"
fake_raw="$(objdump -d "${out}/libfakepathfind.so")"
plt_calls=$(grep -cE 'call.+<reallocate_aligned\(void\*, unsigned long, unsigned long\)@plt>' <<<"${fake_disasm}")
direct_calls=$(grep -cE '[[:space:]](call|jmp)[[:space:]].*<_Z18reallocate_alignedPvmm>' <<<"${fake_raw}" || true)
if (( plt_calls >= 5 && direct_calls == 0 )); then
    printf 'PASS  %-24s %d PLT calls, %d direct\n' "double-uses-plt" "${plt_calls}" "${direct_calls}"
    pass=$((pass + 1))
else
    printf 'FAIL  %-24s %d PLT calls, %d direct (interposition would not be exercised)\n' \
        "double-uses-plt" "${plt_calls}" "${direct_calls}"
    fail=$((fail + 1))
fi

echo
echo "=== real-symbol resolver fails closed"
status=0
env LD_PRELOAD="${shim}" "${out}/missing-real" >"${log}" 2>&1 || status=$?
got="${status}"
(( status > 128 )) && got="SIG$(kill -l $((status - 128)) 2>/dev/null || echo "?")"
if [[ "${got}" == "SIGABRT" ]]; then
    printf 'PASS  %-24s (%s)\n' "missing-real" "${got}"
    pass=$((pass + 1))
else
    printf 'FAIL  %-24s got %s want SIGABRT\n' "missing-real" "${got}"
    fail=$((fail + 1))
fi
assert_log missing-real-armed 'armed missing real symbol'
assert_log missing-real-diagnosed 'mdc-pfguard. FATAL real-reallocate-symbol-missing'

echo
echo "=== clean workload: no false positives"

echo
echo "=== persistent startup SHA gate"
gate_root="$(mktemp -d)"
gate_server="${gate_root}/serverfiles"
gate_install="${gate_root}/pfguard"
mkdir -p "${gate_server}/linux64" "${gate_server}/jre64/lib" "${gate_install}"
printf 'pathfind-v1' >"${gate_server}/linux64/libPZPathFind64.so"
printf 'observer-v1' >"${gate_install}/libmdcpfguard.so"
printf 'jsig-v1' >"${gate_server}/jre64/lib/libjsig.so"
sha256sum "${gate_server}/linux64/libPZPathFind64.so" \
          "${gate_install}/libmdcpfguard.so" >"${gate_install}/manifest.sha256"
if PFG_ROOT="${gate_install}" PFG_SERVERFILES="${gate_server}" PFG_DRY_RUN=1 \
   "${root}/deploy/run-with-pfguard.sh" >"${log}" 2>&1 \
   && grep -q 'startup gate PASS' "${log}"; then
    echo "PASS  startup gate accepts exact hashes"
    pass=$((pass + 1))
else
    echo "FAIL  startup gate exact-hash path"
    fail=$((fail + 1))
fi
printf 'updated-game' >"${gate_server}/linux64/libPZPathFind64.so"
status=0
PFG_ROOT="${gate_install}" PFG_SERVERFILES="${gate_server}" PFG_DRY_RUN=1 \
    "${root}/deploy/run-with-pfguard.sh" >"${log}" 2>&1 || status=$?
if [[ "${status}" == 78 ]] && grep -q 'STARTUP FATAL.*SHA mismatch' "${log}"; then
    echo "PASS  startup gate rejects game updates"
    pass=$((pass + 1))
else
    echo "FAIL  startup gate mismatch path (status=${status})"
    fail=$((fail + 1))
fi
rm -rf "${gate_root}"
expect clean 0 clean
assert_log clean-guarded 'guard_alloc=[1-9]'
assert_log clean-no-canary 'canary=0 '
assert_log clean-no-shrink 'shrink=0 '
assert_log clean-live-zero 'guard_live=0 '

echo
echo "=== first-fault capture: the store itself must trap"
expect underflow 42 underflow
assert_log underflow-armed 'writing one qword below the block'
assert_log underflow-exact 'FAULT_OK sig=11 si_addr=0x[0-9a-f]+ expected=0x[0-9a-f]+ rip=0x[0-9a-f]+'
expect uaf 42 uaf
assert_log uaf-armed 'writing into the freed block'
assert_log uaf-exact 'FAULT_OK sig=11'
expect overflow-page 42 overflow-page
assert_log overflow-page-armed 'writing past the mapped data pages'
assert_log overflow-page-exact 'FAULT_OK sig=11'

echo "=== detection at the next allocator event"
expect overflow-slack SIGABRT overflow-slack
assert_log slack-message 'mdc-pfguard. FATAL canary-overflow-on-free'
expect shrink 0 shrink
assert_log shrink-counted 'shrink=[1-9]'
assert_log shrink-owned 'owned_shrink=[1-9]'
assert_log shrink-not-rounding 'foreign_rounding=0 '
assert_log shrink-safe 'OK shrink-survived'
expect foreign-rounding 0 foreign-rounding
assert_log rounding-counted 'foreign_rounding=[1-9]'
assert_log rounding-not-owned 'owned_shrink=0 '

echo
echo "=== selectivity and kill switch"
expect delegate 0 delegate
assert_log delegate-skipped 'skip_caller=[1-9]'
expect off 0 clean MDC_PFGUARD=0
assert_log off-nothing-guarded 'guard_alloc=0 '
# The kill switch must beat a stale MDC_PFGUARD_ALL=1 left in the environment.
expect off-beats-all 0 clean MDC_PFGUARD=0 MDC_PFGUARD_ALL=1
assert_log off-beats-all-nothing 'guard_alloc=0 '
assert_log off-beats-all-mode 'mode=0 '
# `delegate` case now gets a guarded block, so its deliberate underflow must fault.
expect all-mode 42 delegate MDC_PFGUARD=2
assert_log all-mode-faulted 'FAULT_OK sig=11'
expect all-mode-clean 0 clean MDC_PFGUARD=2
assert_log all-mode-guarded 'guard_alloc=[1-9]'
echo
echo "=== owned mmap failure preserves payload and ownership"
status=0
env LD_PRELOAD="${test_shim}" MDC_PFGUARD_TEST_FAIL_OWNED_MAP=1 \
    "${cases}" owned-fallback >"${log}" 2>&1 || status=$?
if [[ "${status}" == 0 ]]; then
    echo "PASS  owned mmap failure transitions safely to glibc"
    pass=$((pass + 1))
else
    echo "FAIL  owned mmap fallback status=${status}"
    sed 's/^/      | /' "${log}"
    fail=$((fail + 1))
fi
assert_log owned-fallback-payload 'OK owned-fallback'
assert_log owned-fallback-mmap 'mmap_fail=[1-9]'
assert_log owned-fallback-live-zero 'guard_live=0 '
assert_log owned-fallback-real-free 'delegate_free=[1-9]'
# `mixed` calls both allowlisted and non-allowlisted callers, so skip_caller separates
# selective mode from ALL mode.
expect mixed-selective 0 mixed
assert_log mixed-skips-plain 'skip_caller=[1-9]'
assert_log mixed-guards-listed 'guarded_page_aligned=1 plain_page_aligned=0'
expect mixed-all 0 mixed MDC_PFGUARD=2
assert_log all-mode-no-filter 'skip_caller=0 '
assert_log all-mode-guards-plain 'guarded_page_aligned=1 plain_page_aligned=1'

echo
echo "=== load-bearing invariant: an owned pointer is never delegated"
expect owned-stays-guarded 0 owned-stays-guarded MDC_PFGUARD_MAXBLOCKS=1
assert_log owned-invariant 'second_guarded=0 owned_still_guarded=1'
assert_log owned-capacity-counted 'skip_cap=[1-9]'
expect zero-realloc 0 zero-realloc
assert_log zero-realloc-freed 'guard_free=[1-9]'
expect allowlist 0 allowlist
assert_log allowlist-all-matched 'matched=0xf '

echo
echo "=== multithreaded state and cache safety"
expect threads 0 threads
assert_log threads-no-conflict 'ownership_conflicts=0 '
expect double-free SIGABRT double-free
assert_log double-free-armed 'armed double-free ownership race'
assert_log double-free-diagnosed 'mdc-pfguard. FATAL ownership-conflict.* a=[23] '
expect free-realloc SIGABRT free-realloc
assert_log free-realloc-armed 'armed free/realloc ownership race'
assert_log free-realloc-diagnosed 'mdc-pfguard. FATAL ownership-conflict.* a=[23] '
expect bigalign 0 bigalign
assert_log bigalign-delegated 'skip_align=[1-9]'
expect owned-policy 0 owned-policy MDC_PFGUARD_MAXSIZE=32
assert_log owned-policy-preserved 'OK owned-policy'
expect map-overflow 0 map-overflow MDC_PFGUARD=2 MDC_PFGUARD_MAXSIZE=18446744073709551615
assert_log map-overflow-detected 'mmap_fail=[1-9]'
expect capacity-race 0 capacity-race MDC_PFGUARD_MAXBLOCKS=1
assert_log capacity-race-peak 'guard_peak=1 '
assert_log capacity-race-skipped 'skip_cap=[7-9] '
assert_log capacity-race-no-table-full 'skip_table=0 '

echo
echo "=== live reader overlaps an active wrapped writer"
env LD_PRELOAD="${shim}" MDC_PFGUARD_QUARANTINE=8 "${cases}" ringlive >"${log}" 2>&1 &
live_runner=$!
live_pid=""
for _ in $(seq 1 100); do
    live_pid=$(grep -oE 'READY [0-9]+' "${log}" 2>/dev/null | awk '{print $2}')
    [[ -n "${live_pid}" ]] && break
    sleep 0.02
done
sleep 0.2
if [[ -n "${live_pid}" ]] \
   && python3 "${root}/scripts/pfguard_ring.py" --pid "${live_pid}" --shim "${shim}" --limit 1000 \
        >"${log}.live" 2>&1 \
   && grep -qE 'GUARD_(ALLOC|FREE)' "${log}.live"; then
    echo "PASS  live reader decoded while writer was active"
    pass=$((pass + 1))
else
    echo "FAIL  live reader/writer concurrency"
    sed 's/^/      | /' "${log}.live" 2>/dev/null
    fail=$((fail + 1))
fi
wait "${live_runner}" 2>/dev/null || true
rm -f "${log}.live"

echo
echo "=== forensic ledger is readable from live memory (same path as from a core)"
env LD_PRELOAD="${shim}" MDC_PFGUARD_QUARANTINE=8 "${cases}" ringdump >"${log}" 2>&1 &
runner=$!
child=""
for _ in $(seq 1 100); do
    child=$(grep -oE 'READY [0-9]+' "${log}" 2>/dev/null | awk '{print $2}')
    [[ -n "${child}" ]] && break
    sleep 0.05
done
core_file=""
if [[ -n "${child}" ]]; then
    if python3 "${root}/scripts/pfguard_ring.py" --pid "${child}" --shim "${shim}" --limit 5 \
       > "${log}.ring" 2>&1 \
       && grep -qE 'GUARD_(ALLOC|FREE)' "${log}.ring" \
       && grep -q 'capacity=16384 head=18000' "${log}.ring" \
       && ! grep -q '<uncommitted>' "${log}.ring"; then
        echo "PASS  ledger reader decoded exact wrapped live ring"
        pass=$((pass + 1))
        sed -n '3,3p;6,8p' "${log}.ring" | sed 's/^/      | /'
    else
        echo "FAIL  ledger reader could not decode exact wrapped ring"
        sed 's/^/      | /' "${log}.ring"
        fail=$((fail + 1))
    fi
    core_file="$(mktemp -u /tmp/pfgcore.XXXXXX)"
    if gcore -o "${core_file}" "${child}" >/dev/null 2>&1 \
       && python3 "${root}/scripts/pfguard_ring.py" --core "${core_file}.${child}" \
            --shim "${shim}" --limit 5 > "${log}.core" 2>&1 \
       && grep -qE 'GUARD_(ALLOC|FREE)' "${log}.core" \
       && grep -q 'capacity=16384 head=18000' "${log}.core" \
       && ! grep -q '<uncommitted>' "${log}.core"; then
        echo "PASS  ledger reader decoded exact wrapped ELF core"
        pass=$((pass + 1))
    else
        echo "FAIL  ledger reader could not decode exact wrapped core"
        sed 's/^/      | /' "${log}.core" 2>/dev/null
        fail=$((fail + 1))
    fi
    rm -f "${core_file}."* "${log}.core"
    kill -CONT "${child}" 2>/dev/null || true
else
    echo "FAIL  ringdump case never reported READY"
    fail=$((fail + 1))
fi
wait "${runner}" 2>/dev/null || true
rm -f "${log}.ring"

echo
echo "=== overhead"
env LD_PRELOAD="${shim}" "${cases}" bench 20000 | tee "${log}" | sed 's/^/      | /'
assert_log bench-ran 'BENCH iterations=20000'
# A guarded lifecycle is syscall-bound; anything past ~200 us/cycle means something
# pathological (e.g. a table scan) crept in, which is exactly what must not ship.
bench_ns=$(grep -oE 'allowlisted=[0-9]+' "${log}" | cut -d= -f2)
if [[ -n "${bench_ns}" ]] && (( bench_ns < 200000 )); then
    printf 'PASS  %-24s %s ns/cycle < 200000\n' "bench-in-budget" "${bench_ns}"
    pass=$((pass + 1))
else
    printf 'FAIL  %-24s %s ns/cycle\n' "bench-in-budget" "${bench_ns:-<none>}"
    fail=$((fail + 1))
fi

echo
echo "=== table does not degrade under churn (no tombstone cliff)"
env LD_PRELOAD="${shim}" "${cases}" churn 80000 | tee "${log}" | sed 's/^/      | /'
assert_log churn-ran 'CHURN rounds=80000'
assert_log churn-nodes-bounded 'nodes_used=4097 '
churn_us=$(grep -oE 'per_round_us=[0-9.]+' "${log}" | cut -d= -f2)
if [[ -n "${churn_us}" ]] && (( $(printf '%.0f' "${churn_us}") < 100 )); then
    printf 'PASS  %-24s %s us/round after 80000 rounds\n' "churn-no-cliff" "${churn_us}"
    pass=$((pass + 1))
else
    printf 'FAIL  %-24s %s us/round (table likely degraded)\n' "churn-no-cliff" "${churn_us:-<none>}"
    fail=$((fail + 1))
fi

echo
echo "passed ${pass}, failed ${fail}"
(( fail == 0 ))
