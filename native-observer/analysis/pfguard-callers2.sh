#!/usr/bin/env bash
set -u
SO=${PFG_SO:-work/native/libPZPathFind64.so.live}
raw=$(mktemp); objdump -d "$SO" > "$raw"
echo "== mangled + CALL/JMP for non-HL candidates"
awk '/^[0-9a-f]+ <.*>:$/ {fn=$2; gsub(/[<>:]/,"",fn)} /(call|jmp) .*<_Z18reallocate_alignedPvmm@plt>/ { if ($0 ~ /call/) c[fn]++; else j[fn]++ } END { for (f in c) if (f ~ /VGAStar|SearchNode13getSucc|Node8addGraph|findPathHighLevel|createVehicleClustersEv/) printf "%s CALL=%d JMP=%d\n", f, c[f], j[f]+0 }' "$raw" | sort
dyn=$(readelf --dyn-syms -W "$SO")
echo "== dynsym presence"
for s in $(awk '/^[0-9a-f]+ <.*>:$/ {fn=$2; gsub(/[<>:]/,"",fn)} /call .*<_Z18reallocate_alignedPvmm@plt>/ {c[fn]++} END{for (f in c) if (f ~ /VGAStar|SearchNode13getSucc|Node8addGraph|findPathHighLevel|createVehicleClustersEv/) print f}' "$raw"); do
  printf "%-70s dynsym=%s\n" "$s" "$(grep -c " $s\$" <<<"$dyn")"
done
rm -f "$raw"
