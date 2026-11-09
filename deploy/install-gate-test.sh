#!/usr/bin/env bash
# install.sh / uninstall.sh 的閘門行為驗證（改動這兩個腳本後必跑）。
#
# 用 dist\ 的產物（mdc-javapatch-agent.jar、agent-manifest.txt）與 work\projectzomboid.jar 在暫存目錄搭一個
# 假的 serverfiles，跑七個情境：乾淨安裝、uninstall 搬走不刪、遊戲更新後拒裝、payload 損壞拒裝、重複安裝拒裝、
# 舊版 loose class 殘留拒裝（ALLOW_FOREIGN_LOOSE=1 明示放行）、42.21 以前的安裝紀錄還在時一律拒裝。
#
#   bash deploy/install-gate-test.sh
#
# 需要 sha256sum（Git Bash 與 WSL 皆有）。先跑過 build.ps1 產生 dist\。
set -u
R="$(cd "$(dirname "$(readlink -f "$0")")/.." && pwd)"
TMP="${TMPDIR:-/tmp}/pzpatch-install-gate-test"
pass=0; fail=0

check() {  # check "名稱" 期望值 實際值
    if [ "$2" = "$3" ]; then echo "PASS  $1"; pass=$((pass+1))
    else echo "FAIL  $1（期望 $2，實際 $3）"; fail=$((fail+1)); fi
}
installed() { [ -f "$SF/mdc-javapatch/mdc-javapatch-agent.jar" ] && echo 1 || echo 0; }

[ -f "$R/dist/install.sh" ] || { echo "[中止] 找不到 $R/dist——請先跑 build.ps1" >&2; exit 1; }
[ -f "$R/dist/mdc-javapatch-agent.jar" ] || { echo "[中止] 找不到 $R/dist/mdc-javapatch-agent.jar" >&2; exit 1; }
[ -f "$R/work/projectzomboid.jar" ] || { echo "[中止] 找不到 $R/work/projectzomboid.jar" >&2; exit 1; }

rm -rf "$TMP"; mkdir -p "$TMP/serverfiles/java"
SF="$TMP/serverfiles"
cp "$R/work/projectzomboid.jar" "$SF/java/projectzomboid.jar"
export PZ_SERVERFILES="$SF"
agent_sha="$(awk -F'\t' '$1 == "agent" { print $2 }' "$R/dist/agent-manifest.txt")"

echo "== A) 乾淨安裝 =="
bash "$R/dist/install.sh" >/dev/null 2>&1; check "乾淨安裝成功" 0 $?
check "agent jar 安裝後 SHA＝agent-manifest" "$agent_sha" \
      "$(sha256sum <"$SF/mdc-javapatch/mdc-javapatch-agent.jar" 2>/dev/null | cut -d' ' -f1)"
check "java/ 底下沒有放任何 loose class" 0 "$(find "$SF/java" -name '*.class' | wc -l)"

echo "== B) uninstall 整個目錄搬走、不刪 =="
bash "$R/dist/uninstall.sh" >/dev/null 2>&1; check "uninstall 成功" 0 $?
check "uninstall 後沒有安裝目錄" 0 "$(installed)"
check "agent jar 搬到 patch-disabled-*-uninstall" 1 \
      "$(find "$TMP" -path '*patch-disabled-*-uninstall*/mdc-javapatch-agent.jar' | wc -l)"

echo "== C) 遊戲更新（jar 不同）後拒裝 =="
cp "$R/work/projectzomboid.jar" "$SF/java/projectzomboid.jar"; printf 'x' >>"$SF/java/projectzomboid.jar"
bash "$R/dist/install.sh" >/dev/null 2>&1; check "jar 與建置基準不同時中止" 1 $?
check "中止後沒有安裝目錄" 0 "$(installed)"
cp "$R/work/projectzomboid.jar" "$SF/java/projectzomboid.jar"

echo "== D) payload 損壞拒裝 =="
cp -r "$R/dist" "$TMP/dist-bad"; printf 'x' >>"$TMP/dist-bad/mdc-javapatch-agent.jar"
bash "$TMP/dist-bad/install.sh" >/dev/null 2>&1; check "agent jar SHA 不符時中止" 1 $?
check "中止後沒有安裝目錄" 0 "$(installed)"

echo "== E) 已安裝時再裝一次拒裝（升級要先 uninstall）=="
bash "$R/dist/install.sh" >/dev/null 2>&1; check "第一次安裝成功" 0 $?
bash "$R/dist/install.sh" >/dev/null 2>&1; check "重複安裝中止" 1 $?
bash "$R/dist/uninstall.sh" >/dev/null 2>&1

echo "== F) 舊版 loose class 殘留（fail-closed，ALLOW_FOREIGN_LOOSE=1 明示放行）=="
mkdir -p "$SF/java/zombie/iso"
echo "stale-redirected-caller" >"$SF/java/zombie/iso/IsoGridSquare.class"
bash "$R/dist/install.sh" >/dev/null 2>&1; check "殘留 loose class 時中止" 1 $?
check "中止後沒有安裝目錄" 0 "$(installed)"
ALLOW_FOREIGN_LOOSE=1 bash "$R/dist/install.sh" >/dev/null 2>&1; check "明示放行後安裝成功" 0 $?
bash "$R/dist/uninstall.sh" >/dev/null 2>&1
rm -rf "$SF/java/zombie"

echo "== G) 42.21 以前的安裝紀錄還在時一律拒裝 =="
printf 'zombie/mdc/LogFilter.class\t-\t%064d\t0hits\n' 0 >"$SF/java/patch-manifest.txt"
ALLOW_FOREIGN_LOOSE=1 bash "$R/dist/install.sh" >/dev/null 2>&1; check "java/patch-manifest.txt 在時中止" 1 $?
check "中止後沒有安裝目錄" 0 "$(installed)"

rm -rf "$TMP"
echo "---- $pass passed, $fail failed ----"
[ "$fail" -eq 0 ]
