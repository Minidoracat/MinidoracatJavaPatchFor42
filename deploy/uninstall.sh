#!/usr/bin/env bash
# uninstall.sh — 移除本 patch 佈署的 loose class（依伺服器上的 patch-manifest.txt）
set -euo pipefail
SF="${PZ_SERVERFILES:-/home/pzserver/serverfiles}"
MF="$SF/java/patch-manifest.txt"
[ -f "$MF" ] || { echo "[中止] 找不到 $MF（未安裝？）" >&2; exit 1; }

while IFS=$'\t' read -r entry _; do
    [ -n "$entry" ] || continue
    rm -f "$SF/java/$entry"
    echo "removed $entry"
done < "$MF"
rm -f "$SF/java/zombie/mdc/LogFilter.class"
rmdir "$SF/java/zombie/mdc" 2>/dev/null || true
rm -f "$MF"
find "$SF/java/zombie" -type d -empty -delete 2>/dev/null || true
echo "已移除全部 patch；下次伺服器重啟恢復原版行為"
