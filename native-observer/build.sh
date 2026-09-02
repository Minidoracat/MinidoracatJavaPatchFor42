#!/usr/bin/env bash
# Builds libmdcpfguard.so (and the synthetic-test binaries) for Linux x86-64.
# The production server and the dev WSL image are both Ubuntu 24.04 / glibc 2.39.
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
out="${here}/out"
mkdir -p "${out}"

CC="${CC:-gcc}"
common=(-std=gnu11 -O2 -g -fno-omit-frame-pointer -Wall -Wextra -Wno-unused-parameter)

echo "== libmdcpfguard.so"
"${CC}" "${common[@]}" -fPIC -shared -fvisibility=default \
    -o "${out}/libmdcpfguard.so" "${here}/pfguard.c" -ldl -lpthread
"${CC}" "${common[@]}" -DPFG_TESTING -fPIC -shared -fvisibility=default \
    -o "${out}/libmdcpfguard-test.so" "${here}/pfguard.c" -ldl -lpthread
# The interposed symbols must be exported, or LD_PRELOAD does nothing.
# `grep -q` would SIGPIPE readelf and trip `pipefail`, so snapshot the table first.
symbols="$(readelf -Ws "${out}/libmdcpfguard.so")"
for symbol in _Z18reallocate_alignedPvmm _Z18deallocate_alignedPv mdc_pfguard_ring mdc_pfguard_counters; do
    grep -q " DEFAULT .* ${symbol}$" <<<"${symbols}" \
        || { echo "FAIL: ${symbol} is not exported"; exit 1; }
done

echo "== libPZPathFind64.so test double"
# Exact SONAME deliberately exercises pfg_resolve()'s RTLD_NOLOAD branch. The real
# production library has this SONAME; a differently named fake used to test RTLD_NEXT only.
"${CC}" "${common[@]}" -fPIC -shared -fvisibility=default \
    -Wl,-soname,libPZPathFind64.so \
    -o "${out}/libPZPathFind64.so" "${here}/tests/fakepathfind.c"
ln -sf libPZPathFind64.so "${out}/libfakepathfind.so"

echo "== cases (tests only)"
"${CC}" "${common[@]}" -pthread -o "${out}/cases" "${here}/tests/cases.c" \
    -L"${out}" -lPZPathFind64 -Wl,-rpath,"${out}"

echo "== missing-real case (tests only)"
"${CC}" "${common[@]}" -o "${out}/missing-real" "${here}/tests/missing_real.c" -ldl

echo "built:"
ls -l "${out}"
