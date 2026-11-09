#!/usr/bin/env bash
# uninstall.sh — 移除修補（42.22 起是 <serverfiles>/mdc-javapatch/ 的 agent jar）
#
# 整個目錄原子搬到 <serverfiles 的上一層>/patch-disabled-<UTC>-uninstall/（不刪檔）。下次開服啟動閘找不到
# agent，以原版啟動。執行中的 JVM 不受影響：agent jar 在開服時就由 JVM 開著，搬走後已開的檔案照常可讀，
# 沒有 loose class 時代「刪檔後冷路徑 NoClassDefFoundError」的問題，不必為此立刻重啟。
set -euo pipefail
SF="${PZ_SERVERFILES:-/home/pzserver/serverfiles}"
DEST="$SF/mdc-javapatch"
[ -d "$DEST" ] || { echo "[中止] 找不到 $DEST（未安裝？）" >&2; exit 1; }

base="$(dirname "$SF")"
# rename(2) 只在同一個檔案系統內原子；不同就留在 serverfiles 底下
[ "$(stat -c %d "$base" 2>/dev/null)" = "$(stat -c %d "$SF")" ] || base="$SF"
stamp="$(date -u +%Y%m%dT%H%M%SZ)"
park="$base/patch-disabled-$stamp-uninstall"
n=0
while [ -e "$park" ]; do
    n=$((n + 1))
    park="$base/patch-disabled-$stamp-uninstall.$n"
done
mv -T "$DEST" "$park"
echo "已移除：$DEST → $park；下次開服恢復原版"
