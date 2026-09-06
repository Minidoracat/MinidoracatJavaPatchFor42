#!/usr/bin/env bash
# 本機（WSL）：列出 HL A* 子系統呼叫 reallocate_aligned@plt 的函式（mangled dynsym 名）、每個的 CALL/JMP 數。
set -u
SO=${PFG_SO:-work/native/libPZPathFind64.so.live}
raw=$(mktemp); objdump -d "$SO" > "$raw"
# 逐函式統計 reallocate_aligned@plt 的 call/jmp
awk '/^[0-9a-f]+ <.*>:$/ {fn=$2; gsub(/[<>:]/,"",fn)} /(call|jmp) .*<_Z18reallocate_alignedPvmm@plt>/ { if ($0 ~ /call/) c[fn]++; else j[fn]++ } END { for (f in c) printf "%s CALL=%d JMP=%d\n", f, c[f], j[f]+0; for (f in j) if (!(f in c)) printf "%s CALL=0 JMP=%d\n", f, j[f] }' "$raw" | sort > /tmp/callers.txt
echo "== distinct caller functions: $(wc -l < /tmp/callers.txt)"
echo "== HL* callers (mangled) and dynsym presence:"
dyn=$(readelf --dyn-syms -W "$SO")
grep -E '^_ZN[0-9]+HL' /tmp/callers.txt | while read -r f rest; do
  present=$(grep -c " $f\$" <<<"$dyn")
  printf "%-80s %s dynsym=%s\n" "$f" "$rest" "$present"
done
echo "== demangled:"
grep -E '^_ZN[0-9]+HL' /tmp/callers.txt | cut -d' ' -f1 | c++filt
echo "== non-HL callers (for reference):"
grep -vE '^_ZN[0-9]+HL' /tmp/callers.txt | cut -d' ' -f1 | c++filt | sed 's/(.*//' | sort | uniq -c | sort -rn | head -40
rm -f "$raw"
