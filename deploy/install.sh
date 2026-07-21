#!/usr/bin/env bash
# install.sh — 在 PZ 伺服器安裝 loose-class patch（與 java/、manifest.txt 同目錄執行）
# 三道閘（全過才動正式目錄，fail-closed）：
#   1) payload preflight：manifest 所列每個檔案存在且 SHA256 與建置產物一致（含 LogFilter）
#   2) 同源驗證：伺服器 jar 內原版 class SHA256 與 manifest 記錄一致——遊戲更新過即拒裝
#   3) 衝突檢查：已安裝（有 patch-manifest.txt）或目標路徑有他人 loose 檔即拒裝
# 生效時機：下次伺服器重啟。升級＝先 uninstall.sh 再 install.sh；移除＝uninstall.sh。
set -euo pipefail
SF="${PZ_SERVERFILES:-/home/pzserver/serverfiles}"
HERE="$(dirname "$(readlink -f "$0")")"
JAR="$SF/java/projectzomboid.jar"
MF="$HERE/manifest.txt"

[ -f "$JAR" ] || { echo "[中止] 找不到 $JAR" >&2; exit 1; }
[ -f "$MF" ] || { echo "[中止] 找不到 $MF" >&2; exit 1; }

echo "== 1/3 payload preflight（產物完整性）=="
while IFS=$'\t' read -r entry orig_sha patched_sha _rest; do
    [ -n "$entry" ] || continue
    [ -f "$HERE/java/$entry" ] || { echo "[中止] 產物缺檔：$entry" >&2; exit 1; }
    live=$(sha256sum "$HERE/java/$entry" | cut -d' ' -f1)
    [ "$live" = "$patched_sha" ] || { echo "[中止] 產物 $entry SHA 與 manifest 不符（傳輸損壞／混用 build？）" >&2; exit 1; }
    echo "OK  $entry"
done < "$MF"

echo "== 2/3 同源驗證（jar 原版 class hash）=="
while IFS=$'\t' read -r entry orig_sha _rest; do
    [ -n "$entry" ] || continue
    [ "$orig_sha" = "-" ] && continue   # LogFilter 等新增檔無 jar 原版
    if ! unzip -l "$JAR" "$entry" >/dev/null 2>&1; then
        echo "[中止] jar 內不存在 $entry（遊戲版本結構已變）" >&2; exit 1
    fi
    live=$(unzip -p "$JAR" "$entry" | sha256sum | cut -d' ' -f1)
    if [ "$live" != "$orig_sha" ]; then
        echo "[中止] $entry 與 patch 建置基準不同源（遊戲已更新？）——請重新建置 patch" >&2; exit 1
    fi
    echo "OK  $entry"
done < "$MF"

echo "== 3/3 衝突檢查 =="
[ -e "$SF/java/patch-manifest.txt" ] && { echo "[中止] 已有安裝紀錄——升級請先 bash uninstall.sh" >&2; exit 1; }
while IFS=$'\t' read -r entry _rest; do
    [ -n "$entry" ] || continue
    [ -e "$SF/java/$entry" ] && { echo "[中止] $SF/java/$entry 已存在（非本 patch 產物）——請先人工處理" >&2; exit 1; }
done < "$MF"
echo "OK  無衝突"

echo "== 安裝 =="
count=0
# 相依順序：LogFilter（被改道呼叫的目標）先落地，再放 patched class
for pass in mdc rest; do
    while IFS=$'\t' read -r entry _rest; do
        [ -n "$entry" ] || continue
        case "$pass:$entry" in
            mdc:zombie/mdc/*) ;;
            rest:zombie/mdc/*) continue ;;
            mdc:*) continue ;;
        esac
        mkdir -p "$SF/java/$(dirname "$entry")"
        cp "$HERE/java/$entry" "$SF/java/$entry"
        chown pzserver:pzserver "$SF/java/$entry" "$SF/java/$(dirname "$entry")" 2>/dev/null || true
        count=$((count + 1))
    done < "$MF"
done
cp "$MF" "$SF/java/patch-manifest.txt"
chown pzserver:pzserver "$SF/java/patch-manifest.txt" 2>/dev/null || true

echo "已安裝 $count 個 class；下次伺服器重啟生效"
echo "移除：bash uninstall.sh（依 $SF/java/patch-manifest.txt 精確清除）"
