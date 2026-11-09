#!/usr/bin/env bash
# install.sh — 在 PZ 伺服器安裝修補（與 mdc-javapatch-agent.jar、agent-manifest.txt 同目錄執行）
#
# 42.22 起 projectzomboid.jar 簽章，修補不再是 java/ 底下的 loose class（同一個 package 的 class 簽章者不同，
# JVM 丟 SecurityException）。整包修補是一個 Java agent jar，裝到 <serverfiles>/mdc-javapatch/；開服時啟動閘
# （run-with-pfguard.sh）驗過 SHA 才以 -javaagent 帶進遊戲 JVM，agent 在官方 class 載入時換成手術後的 bytes。
#
# 三道閘（全過才動正式目錄，fail-closed）：
#   1) payload：agent jar 的 SHA256 與 agent-manifest.txt 一致
#   2) 同源：伺服器 jar 的 SHA256 等於建置基準——遊戲更新過即拒裝
#   3) 衝突：已有安裝（升級請先 uninstall.sh）、舊版 loose class 安裝還在、或 java/ 底下有不明 loose class 即拒裝
# 生效時機：下次開服（console 印 [mdc-javagate] AGENT ARMED 與 [mdc-agent] ARMED）。移除：uninstall.sh。
set -euo pipefail
SF="${PZ_SERVERFILES:-/home/pzserver/serverfiles}"
HERE="$(dirname "$(readlink -f "$0")")"
JAR="$SF/java/projectzomboid.jar"
DEST="$SF/mdc-javapatch"
AGENT="$HERE/mdc-javapatch-agent.jar"
AMF="$HERE/agent-manifest.txt"

abort() { echo "[中止] $*" >&2; exit 1; }
field() { awk -F'\t' -v k="$1" '$1 == k { sub(/\r$/, "", $2); print $2; exit }' "$AMF"; }

[ -f "$JAR" ] || abort "找不到 $JAR"
[ -f "$AGENT" ] || abort "找不到 $AGENT"
[ -f "$AMF" ] || abort "找不到 $AMF"
agent_sha="$(field agent)"
base_sha="$(field jar)"
[[ "$agent_sha" =~ ^[0-9a-f]{64}$ && "$base_sha" =~ ^[0-9a-f]{64}$ ]] || abort "$AMF 格式不符"

echo "== 1/3 payload preflight（產物完整性）=="
live="$(sha256sum <"$AGENT" | cut -d' ' -f1)"
[ "$live" = "$agent_sha" ] || abort "agent jar SHA 與 agent-manifest.txt 不符（傳輸損壞／混用 build？）"
echo "OK  mdc-javapatch-agent.jar ${agent_sha:0:8}"

echo "== 2/3 同源驗證（伺服器 jar 等於建置基準）=="
live="$(sha256sum <"$JAR" | cut -d' ' -f1)"
[ "$live" = "$base_sha" ] || abort "伺服器 jar ${live:0:8} 與修補建置基準 ${base_sha:0:8} 不同（遊戲已更新？）——請重新建置修補"
echo "OK  projectzomboid.jar ${base_sha:0:8}"

echo "== 3/3 衝突檢查 =="
{ [ -e "$DEST" ] || [ -L "$DEST" ]; } && abort "已有安裝 $DEST——升級請先 bash uninstall.sh"
[ -e "$SF/java/patch-manifest.txt" ] && abort "42.21 以前的 loose class 安裝還在（java/patch-manifest.txt）——先用那一版的 uninstall.sh 移除"
# 不明 loose class（fail-closed）：簽章 jar 之下，遊戲 package 裡的 loose class 會讓 JVM 丟 SecurityException；
# 確認來源無害時以 ALLOW_FOREIGN_LOOSE=1 明示放行。
foreign="$(find "$SF/java" -name '*.class' 2>/dev/null | sed "s|^$SF/java/||" || true)"
if [ -n "$foreign" ]; then
    echo "[中止] java/ 底下有 loose class：" >&2
    echo "$foreign" | head -20 | sed 's/^/    /' >&2
    if [ "${ALLOW_FOREIGN_LOOSE:-0}" != "1" ]; then
        echo "       我方舊版殘留 → 用該版的 uninstall.sh 移除後重試；確認無害 → ALLOW_FOREIGN_LOOSE=1 bash install.sh" >&2
        exit 1
    fi
    echo "[警告] ALLOW_FOREIGN_LOOSE=1 已明示放行" >&2
fi
echo "OK  無衝突"

echo "== 安裝 =="
# 先寫到同一個檔案系統的暫存目錄，再一次改名成 $DEST：啟動閘不會看到裝到一半的目錄
tmp="$SF/.mdc-javapatch.tmp.$$"
rm -rf "$tmp"
mkdir "$tmp"
cp "$AGENT" "$AMF" "$tmp/"
[ ! -f "$HERE/manifest.txt" ] || cp "$HERE/manifest.txt" "$tmp/"
chmod 755 "$tmp"
chmod 644 "$tmp"/*
chown -R pzserver:pzserver "$tmp" 2>/dev/null || true
mv -T "$tmp" "$DEST"

echo "已安裝：$(field patched) 個修補 class 的 agent（版本 $(field version)）；下次開服生效"
echo "移除：bash uninstall.sh（$DEST 整個搬走）"
